package org.example.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.service.KbDeepAnnotationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
public class KbDeepAnnotationServiceImpl implements KbDeepAnnotationService {

    private static final Pattern MD_LINK = Pattern.compile("\\]\\(([^)]+\\.md(?:#[^)]*)?)\\)");
    private static final String NOTE = "标题+章节+Markdown 出链的文档级实体关系，链接目标可解析；非开放域 NER";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public KbDeepAnnotationServiceImpl(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public Map<String, Object> seedLinkGraph(int limit) {
        int target = Math.max(1, Math.min(limit, 200));
        List<DocRow> docs = jdbcTemplate.query(
                "SELECT id, title, file_path, content FROM kb_document " +
                        "WHERE library_id='default' AND status='INDEXED' AND content IS NOT NULL " +
                        "ORDER BY chunk_count DESC, id ASC LIMIT 600",
                (rs, i) -> new DocRow(rs.getLong("id"), rs.getString("title"),
                        rs.getString("file_path"), rs.getString("content")));
        // 出链目标可能在候选 120 篇之外，必须用全量路径建索引，否则会把真实关系误判成断链。
        List<DocRow> allDocs = jdbcTemplate.query(
                "SELECT id, title, file_path FROM kb_document WHERE library_id='default' AND status='INDEXED'",
                (rs, i) -> new DocRow(rs.getLong("id"), rs.getString("title"),
                        rs.getString("file_path"), ""));
        Map<String, DocRow> byPath = new LinkedHashMap<>();
        for (DocRow d : allDocs) {
            if (d.path != null) {
                byPath.put(normalizePath(d.path), d);
            }
        }
        int annotated = 0;
        int entities = 0;
        int relations = 0;
        for (DocRow doc : docs) {
            if (annotated >= target) {
                break;
            }
            Set<String> entityNames = new LinkedHashSet<>();
            List<Map<String, String>> relationList = new ArrayList<>();
            if (doc.title != null && !doc.title.isBlank()) {
                entityNames.add(doc.title.trim());
            }
            Matcher matcher = MD_LINK.matcher(doc.content == null ? "" : doc.content);
            Set<String> seenTargets = new LinkedHashSet<>();
            while (matcher.find()) {
                String raw = matcher.group(1).trim();
                String targetPath = resolveLink(doc.path, raw);
                DocRow linked = targetPath == null ? null : byPath.get(targetPath);
                if (linked == null || linked.id == doc.id || linked.title == null || linked.title.isBlank()) {
                    continue;
                }
                if (!seenTargets.add(linked.id + "")) {
                    continue;
                }
                entityNames.add(linked.title.trim());
                Map<String, String> rel = new LinkedHashMap<>();
                rel.put("from", doc.title == null ? "" : doc.title.trim());
                rel.put("to", linked.title.trim());
                rel.put("type", "LINKS_TO");
                relationList.add(rel);
            }
            if (relationList.isEmpty()) {
                continue;
            }
            List<Map<String, String>> entityList = new ArrayList<>();
            String docTitle = doc.title == null ? "" : doc.title.trim();
            for (String name : entityNames) {
                Map<String, String> entity = new LinkedHashMap<>();
                entity.put("name", name);
                entity.put("type", name.equals(docTitle) ? "DOCUMENT" : "DOCUMENT_REF");
                entityList.add(entity);
            }
            String entitiesJson = toJson(entityList);
            String relationsJson = toJson(relationList);
            jdbcTemplate.update(
                    "INSERT INTO kb_deep_annotation " +
                            "(doc_id, entity_count, relation_count, entities_json, relations_json, status, annotator, note, created_date, updated_date, version) " +
                            "VALUES (?, ?, ?, ?, ?, 'VERIFIED', 'rule:link-graph', ?, NOW(6), NOW(6), 0) " +
                            "ON DUPLICATE KEY UPDATE entity_count=VALUES(entity_count), relation_count=VALUES(relation_count), " +
                            "entities_json=VALUES(entities_json), relations_json=VALUES(relations_json), status='VERIFIED', " +
                            "annotator=VALUES(annotator), note=VALUES(note), updated_date=NOW(6)",
                    doc.id, entityList.size(), relationList.size(), entitiesJson, relationsJson, NOTE);
            annotated++;
            entities += entityList.size();
            relations += relationList.size();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("annotated", annotated);
        result.put("target", target);
        result.put("candidateDocuments", docs.size());
        result.put("entities", entities);
        result.put("relations", relations);
        result.put("status", "VERIFIED");
        result.put("note", NOTE);
        log.info("GraphRAG 文档级标注完成 - annotated: {}, entities: {}, relations: {}", annotated, entities, relations);
        return result;
    }

    @Override
    public Map<String, Object> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("documents", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM kb_deep_annotation WHERE status='VERIFIED'", Integer.class));
        result.put("entities", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(entity_count),0) FROM kb_deep_annotation WHERE status='VERIFIED'", Integer.class));
        result.put("relations", jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(relation_count),0) FROM kb_deep_annotation WHERE status='VERIFIED'", Integer.class));
        result.put("status", "VERIFIED");
        return result;
    }

    private String resolveLink(String docPath, String raw) {
        if (docPath == null) {
            return null;
        }
        String link = raw;
        int hash = link.indexOf('#');
        if (hash >= 0) {
            link = link.substring(0, hash);
        }
        if (link.isBlank() || link.startsWith("http://") || link.startsWith("https://") || link.startsWith("/")) {
            return null;
        }
        try {
            Path parent = Paths.get(docPath).getParent();
            return normalizePath(parent == null ? link : parent.resolve(link).toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String normalizePath(String value) {
        return value == null ? null : Paths.get(value).normalize().toString().replace('\\', '/');
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("标注 JSON 序列化失败", e);
        }
    }

    private record DocRow(long id, String title, String path, String content) {
    }
}
