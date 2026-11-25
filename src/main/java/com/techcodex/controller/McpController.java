package com.techcodex.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techcodex.dto.KbChunkHit;
import com.techcodex.dto.KbDocumentView;
import com.techcodex.entity.KnowledgeChunkEntity;
import com.techcodex.entity.KnowledgeDocumentEntity;
import com.techcodex.repository.KnowledgeChunkRepository;
import com.techcodex.repository.KnowledgeDocumentRepository;
import com.techcodex.security.SecurityUtil;
import com.techcodex.service.KbDocumentService;
import com.techcodex.service.KbSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 最小 MCP（Model Context Protocol）服务端，走 JSON-RPC 2.0 over HTTP。
 *
 * <p>安全边界：
 * <ul>
 *   <li>复用现有 Spring Security + JWT；/mcp 不在 permitAll 列表，未登录直接 401。</li>
 *   <li>工具只查公共空间 {@code default}，不接受调用方传入 libraryId/scope，避免越权读个人空间。</li>
 *   <li>fetch_doc 只返回公共文档正文；正文截断到 50k 字符，list_sections 只返回结构信息。</li>
 * </ul>
 * 当前是 P1 的无状态最小实现：不签发 MCP session，不实现 resources/prompts；后续可扩展。
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private static final String PUBLIC_LIBRARY = "default";
    private static final int MAX_FETCH_CHARS = 50_000;

    @Autowired
    private KbSearchService kbSearchService;

    @Autowired
    private KbDocumentService kbDocumentService;

    @Autowired
    private KnowledgeDocumentRepository documentRepository;

    @Autowired
    private KnowledgeChunkRepository chunkRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> rpc(@RequestBody Map<String, Object> request) {
        String method = asString(request.get("method"));
        Object id = request.get("id");
        // JSON-RPC notification：没有 id，不需要 result/error
        if (method != null && method.startsWith("notifications/")) {
            return ResponseEntity.noContent().build();
        }
        if (method == null || method.isBlank()) {
            return ResponseEntity.ok(error(id, -32600, "Invalid Request"));
        }

        try {
            return switch (method) {
                case "initialize" -> ResponseEntity.ok(result(id, initializeResult()));
                case "ping" -> ResponseEntity.ok(result(id, Map.of()));
                case "tools/list" -> ResponseEntity.ok(result(id, Map.of("tools", tools())));
                case "tools/call" -> {
                    Map<String, Object> params = asMap(request.get("params"));
                    Map<String, Object> args = asMap(params.get("arguments"));
                    String name = asString(params.get("name"));
                    yield ResponseEntity.ok(callTool(id, name, args));
                }
                default -> ResponseEntity.ok(error(id, -32601, "Method not found: " + method));
            };
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(error(id, -32602, e.getMessage()));
        } catch (Exception e) {
            // 对外只回固定错误码 + 短 traceId：异常 message 可能带 SQL 片段、磁盘路径与内部类名，
            // 原样回传等于给探测者送内部结构。traceId 用于把调用方看到的错误与服务器日志对齐。
            String traceId = UUID.randomUUID().toString().substring(0, 8);
            log.error("MCP 调用失败 traceId={} method={}", traceId, method, e);
            return ResponseEntity.ok(error(id, -32603, "Internal error (traceId: " + traceId + ")"));
        }
    }

    private Map<String, Object> initializeResult() {
        return Map.of(
                "protocolVersion", "2025-06-18",
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", "tech-codex-kb", "version", "1.0.0")
        );
    }

    private List<Map<String, Object>> tools() {
        return List.of(
                tool("search_docs",
                        "在 Tech Codex 公共知识库中做向量 + BM25 + RRF 混合检索，返回文档片段与引用元信息。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "query", Map.of("type", "string", "description", "检索问题或关键词"),
                                        "topK", Map.of("type", "integer", "minimum", 1, "maximum", 10, "default", 5)
                                ),
                                "required", List.of("query")
                        )),
                tool("fetch_doc",
                        "按 docId 或 filePath 获取公共文档全文（最多 50k 字符）。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "docId", Map.of("type", "integer", "description", "文档 ID"),
                                        "filePath", Map.of("type", "string", "description", "文档相对路径，如 04-前端/02-HTML5/00-HTML5概览.md")
                                )
                        )),
                tool("list_sections",
                        "列出某篇公共文档的分块结构（标题路径、锚点、类型、代码语言），不返回正文。",
                        Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "docId", Map.of("type", "integer"),
                                        "filePath", Map.of("type", "string")
                                )
                        ))
        );
    }

    private Map<String, Object> tool(String name, String description, Map<String, Object> inputSchema) {
        return Map.of("name", name, "description", description, "inputSchema", inputSchema);
    }

    private Map<String, Object> callTool(Object id, String name, Map<String, Object> args) {
        if (name == null || name.isBlank()) {
            return error(id, -32602, "Missing tool name");
        }
        return switch (name) {
            case "search_docs" -> {
                String query = asString(args.get("query"));
                if (query == null || query.isBlank()) throw new IllegalArgumentException("query 不能为空");
                int topK = Math.max(1, Math.min(10, asInt(args.get("topK"), 5)));
                List<KbChunkHit> hits = kbSearchService.searchAs(
                        List.of(PUBLIC_LIBRARY), SecurityUtil.currentUsername(), query, topK, null, null, null, null);
                for (int i = 0; i < hits.size(); i++) hits.get(i).setCitationId(String.valueOf(i + 1));
                yield toolResult(id, hits.stream().map(this::searchItem).toList());
            }
            case "fetch_doc" -> {
                KnowledgeDocumentEntity doc = resolvePublicDoc(args);
                if (doc == null) throw new IllegalArgumentException("公共文档不存在");
                KbDocumentView view = kbDocumentService.get(doc.getId(), PUBLIC_LIBRARY, true);
                String content = view.getContent() == null ? "" : view.getContent();
                boolean truncated = content.length() > MAX_FETCH_CHARS;
                String body = truncated ? content.substring(0, MAX_FETCH_CHARS) : content;
                yield toolResult(id, Map.of(
                        "docId", view.getId(),
                        "title", nullToEmpty(view.getTitle()),
                        "filePath", nullToEmpty(view.getFilePath()),
                        "truncated", truncated,
                        "content", body
                ));
            }
            case "list_sections" -> {
                KnowledgeDocumentEntity doc = resolvePublicDoc(args);
                if (doc == null) throw new IllegalArgumentException("公共文档不存在");
                List<Map<String, Object>> sections = new ArrayList<>();
                for (KnowledgeChunkEntity c : chunkRepository.findByDocIdOrderByChunkIndexAsc(doc.getId())) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("chunkIndex", c.getChunkIndex());
                    item.put("headingPath", nullToEmpty(c.getHeadingPath()));
                    item.put("heading", lastHeading(c.getHeadingPath()));
                    item.put("anchor", nullToEmpty(c.getAnchor()));
                    item.put("chunkType", nullToEmpty(c.getChunkType()));
                    item.put("codeLang", nullToEmpty(c.getCodeLang()));
                    item.put("tokenCount", c.getTokenCount());
                    sections.add(item);
                }
                yield toolResult(id, Map.of(
                        "docId", doc.getId(),
                        "title", nullToEmpty(doc.getTitle()),
                        "filePath", nullToEmpty(doc.getFilePath()),
                        "sections", sections
                ));
            }
            default -> error(id, -32601, "Unknown tool: " + name);
        };
    }

    private Map<String, Object> searchItem(KbChunkHit h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("citationId", h.getCitationId());
        m.put("docId", h.getDocId());
        m.put("title", nullToEmpty(h.getTitle()));
        m.put("filePath", nullToEmpty(h.getFilePath()));
        m.put("headingPath", nullToEmpty(h.getHeadingPath()));
        m.put("anchor", nullToEmpty(h.getAnchor()));
        m.put("chunkType", nullToEmpty(h.getChunkType()));
        m.put("codeLang", nullToEmpty(h.getCodeLang()));
        m.put("score", h.getScore());
        String text = nullToEmpty(h.getText());
        m.put("snippet", text.length() > 600 ? text.substring(0, 600) : text);
        return m;
    }

    /** 只允许默认公共空间；filePath 兼容带/不带 docs/ 前缀和 .md 后缀。 */
    private KnowledgeDocumentEntity resolvePublicDoc(Map<String, Object> args) {
        String rawPath = asString(args.get("filePath"));
        if (rawPath != null && !rawPath.isBlank()) {
            String path = rawPath.replace('\\', '/').replaceFirst("^/", "");
            if (path.startsWith("docs/")) path = path.substring(5);
            Optional<KnowledgeDocumentEntity> doc = documentRepository.findByLibraryIdAndFilePath(PUBLIC_LIBRARY, path);
            if (doc.isEmpty() && !path.endsWith(".md")) {
                doc = documentRepository.findByLibraryIdAndFilePath(PUBLIC_LIBRARY, path + ".md");
            }
            return doc.orElse(null);
        }
        Integer docId = asInteger(args.get("docId"));
        if (docId == null) throw new IllegalArgumentException("docId 或 filePath 至少提供一个");
        return documentRepository.findByIdAndLibraryId((long) docId, PUBLIC_LIBRARY).orElse(null);
    }

    private static String lastHeading(String headingPath) {
        if (headingPath == null || headingPath.isBlank()) return "";
        int idx = headingPath.lastIndexOf(" > ");
        return idx >= 0 ? headingPath.substring(idx + 3) : headingPath;
    }

    private Map<String, Object> toolResult(Object id, Object payload) {
        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        try {
            textContent.put("text", objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            textContent.put("text", String.valueOf(payload));
        }
        return result(id, Map.of("content", List.of(textContent), "isError", false));
    }

    private Map<String, Object> result(Object id, Object result) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("result", result);
        return m;
    }

    private Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jsonrpc", "2.0");
        m.put("id", id);
        m.put("error", Map.of("code", code, "message", message == null ? "" : message));
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object v) {
        return v instanceof Map<?, ?> ? (Map<String, Object>) v : Map.of();
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static Integer asInteger(Object v) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? null : Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int asInt(Object v, int fallback) {
        Integer n = asInteger(v);
        return n == null ? fallback : n;
    }

    private static String nullToEmpty(String v) {
        return v == null ? "" : v;
    }
}
