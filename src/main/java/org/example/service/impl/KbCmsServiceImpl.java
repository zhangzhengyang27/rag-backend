package org.example.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.example.dto.CmsDocumentDetailView;
import org.example.dto.CmsDocumentSaveRequest;
import org.example.dto.CmsImportRequest;
import org.example.dto.CmsImportResult;
import org.example.dto.CmsVersionView;
import org.example.dto.KbDocumentView;
import org.example.entity.KbDocumentStatus;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.entity.KnowledgeDocumentVersionEntity;
import org.example.repository.KnowledgeChunkRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.repository.KnowledgeDocumentVersionRepository;
import org.example.repository.KnowledgeSectionRepository;
import org.example.service.AuditLogService;
import org.example.service.DocumentInfoCache;
import org.example.service.KbCmsService;
import org.example.service.KbIndexer;
import org.example.util.CmsMarkdown;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * CMS 内容服务实现。
 *
 * <p>一致性策略：数据库是内容真相。mirror-enabled=true 时 docs/ 是可重建的物化镜像，
 * 发布顺序为「写文件 → 事务写库+版本 → 重建索引」，库写失败时回滚文件；
 * mirror-enabled=false（DB-only 模式）时完全不触碰文件系统，只写库并重建索引，
 * 索引失败只标记 FAILED，不回滚已发布内容（避免把一次可重试的索引故障放大成内容丢失）。</p>
 *
 * <p>安全边界：启用镜像时，所有文件路径都必须解析在 docs 根内；分类目录与 slug
 * 拒绝 .. 和绝对路径。删除不物理销毁镜像，而是移入 docs/.cms-trash。</p>
 */
@Slf4j
@Service
public class KbCmsServiceImpl implements KbCmsService {

    private static final String SOURCE_CMS = "CMS";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String TRASH_DIR = ".cms-trash";

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeDocumentVersionRepository versionRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KnowledgeSectionRepository sectionRepository;
    private final KbIndexer kbIndexer;
    private final EmbeddingStore<TextSegment> kbEmbeddingStore;
    private final DocumentInfoCache docCache;
    private final AuditLogService auditLogService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    @Value("${app.kb.cms.docs-root:${KB_DOCS_ROOT:../tech-codex/docs}}")
    private String docsRoot;

    /** false=DB-only：发布/删除/回滚不再读写 docs 目录，数据库是唯一内容源。 */
    @Value("${app.kb.cms.mirror-enabled:${KB_CMS_MIRROR_ENABLED:true}}")
    private boolean mirrorEnabled;

    @Value("${app.kb.import.max-file-size:20971520}")
    private long maxFileSize;

    /** 语义缓存是旁路能力；单测/裁剪部署可能没有该 Bean，缺失时不影响内容主链路。 */
    @Autowired(required = false)
    private org.example.service.SemanticCacheService semanticCacheService;

    public KbCmsServiceImpl(KnowledgeDocumentRepository documentRepository,
                            KnowledgeDocumentVersionRepository versionRepository,
                            KnowledgeChunkRepository chunkRepository,
                            KnowledgeSectionRepository sectionRepository,
                            KbIndexer kbIndexer,
                            EmbeddingStore<TextSegment> kbEmbeddingStore,
                            DocumentInfoCache docCache,
                            AuditLogService auditLogService,
                            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.documentRepository = documentRepository;
        this.versionRepository = versionRepository;
        this.chunkRepository = chunkRepository;
        this.sectionRepository = sectionRepository;
        this.kbIndexer = kbIndexer;
        this.kbEmbeddingStore = kbEmbeddingStore;
        this.docCache = docCache;
        this.auditLogService = auditLogService;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
    }

    @Override
    public Page<KbDocumentView> list(String keyword, String categoryDir, String libraryId, Pageable page) {
        Page<KnowledgeDocumentEntity> result = documentRepository.searchCmsDocuments(
                libraryId, SOURCE_CMS, blankToNull(keyword), blankToNull(categoryDir), page);
        return result.map(e -> KbDocumentView.fromEntity(e, false));
    }

    @Override
    public CmsDocumentDetailView detail(Long id, String libraryId) {
        KnowledgeDocumentEntity doc = requireCms(id, libraryId);
        CmsMarkdown.Parts parts = CmsMarkdown.split(doc.getContent());
        String title = firstNonBlank(doc.getTitle(), CmsMarkdown.getScalar(parts.frontmatter(), "title"));
        String description = firstNonBlank(doc.getDescription(), CmsMarkdown.getScalar(parts.frontmatter(), "description"));
        return CmsDocumentDetailView.builder()
                .id(doc.getId())
                .title(title)
                .description(description)
                .categorySlug(doc.getCategorySlug())
                .categoryDir(doc.getCategoryDir())
                .docSlug(doc.getDocSlug())
                .filePath(doc.getFilePath())
                .publishStatus(doc.getPublishStatus())
                .revision(doc.getRevision())
                .publishedAt(doc.getPublishedAt())
                .publishedBy(doc.getPublishedBy())
                .updatedDate(doc.getUpdatedDate())
                .body(parts.body())
                .frontmatter(parts.frontmatter())
                .build();
    }

    @Override
    public CmsDocumentDetailView create(CmsDocumentSaveRequest request, String libraryId, String operator) {
        Norm norm = normalize(request, true);
        String raw = mergeFrontmatter(request.getFrontmatter(), norm);
        assertSize(raw);
        String relPath = norm.relPath();
        // DB-only 模式下不需要也不允许用文件存在性做冲突判断，唯一约束回到数据库
        Path target = mirrorEnabled ? resolveSafe(relPath) : null;
        if (mirrorEnabled && Files.exists(target)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "目标文件已存在: " + relPath);
        }
        if (documentRepository.findByLibraryIdAndFilePath(libraryId, relPath).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "同路径文档已存在: " + relPath);
        }

        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        applyMetadata(doc, norm, raw, libraryId, operator, 1);

        try {
            if (mirrorEnabled) {
                writeAtomic(target, raw);
            }
            KnowledgeDocumentEntity saved = transactionTemplate.execute(status -> {
                KnowledgeDocumentEntity persisted = documentRepository.save(doc);
                saveVersion(persisted, raw, "新建并发布", operator);
                return persisted;
            });
            indexQuietly(saved, raw, libraryId);
            audit(libraryId, operator, "CMS_CREATE", saved.getId(), "新建内容：" + saved.getTitle());
            return detail(saved.getId(), libraryId);
        } catch (RuntimeException e) {
            // 库写失败：删掉刚写的镜像，保持「库没有、文件也没有」的干净状态
            if (mirrorEnabled) {
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupError) {
                    log.warn("新建失败后清理镜像失败 - path: {}, err: {}", relPath, cleanupError.getMessage());
                }
            }
            throw e;
        }
    }

    @Override
    public CmsDocumentDetailView update(Long id, CmsDocumentSaveRequest request, String libraryId, String operator) {
        KnowledgeDocumentEntity doc = requireCms(id, libraryId);
        int currentRevision = doc.getRevision() == null ? 1 : doc.getRevision();
        if (request.getExpectedRevision() != null && request.getExpectedRevision() != currentRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "内容已在别处更新（当前版本 r" + currentRevision + "），请刷新后重试");
        }

        Norm norm = normalize(request, false);
        String raw = mergeFrontmatter(request.getFrontmatter(), norm);
        assertSize(raw);
        String oldPath = doc.getFilePath();
        String oldRaw = doc.getContent();
        String newPath = norm.relPath();
        // 路径校验/文件冲突只在镜像模式下需要；DB-only 下路径冲突由数据库唯一查询负责
        Path newTarget = mirrorEnabled ? resolveSafe(newPath) : null;
        Path oldTarget = mirrorEnabled && oldPath != null && !oldPath.isBlank()
                ? resolveSafe(oldPath) : null;

        if (!newPath.equals(oldPath)) {
            if (mirrorEnabled && Files.exists(newTarget)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "目标文件已存在: " + newPath);
            }
            var samePath = documentRepository.findByLibraryIdAndFilePath(libraryId, newPath);
            if (samePath.isPresent() && !samePath.get().getId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "同路径文档已存在: " + newPath);
            }
        }

        try {
            if (mirrorEnabled) {
                writeAtomic(newTarget, raw);
            }
            int nextRevision = currentRevision + 1;
            KnowledgeDocumentEntity saved = transactionTemplate.execute(status -> {
                applyMetadata(doc, norm, raw, libraryId, operator, nextRevision);
                KnowledgeDocumentEntity persisted = documentRepository.save(doc);
                saveVersion(persisted, raw, request.getChangeNote(), operator);
                return persisted;
            });
            // 改名/换分类：新文件已落盘后再把旧文件移入回收站，避免写失败丢旧稿
            if (mirrorEnabled && !newPath.equals(oldPath) && oldTarget != null) {
                moveToTrash(oldTarget);
            }
            indexQuietly(saved, raw, libraryId);
            audit(libraryId, operator, "CMS_UPDATE", saved.getId(), "发布内容：" + saved.getTitle() + " (r" + nextRevision + ")");
            return detail(saved.getId(), libraryId);
        } catch (RuntimeException e) {
            if (mirrorEnabled) {
                restoreMirror(oldTarget, newTarget, oldRaw, oldPath, newPath);
            }
            throw e;
        }
    }

    @Override
    public void delete(Long id, String libraryId, String operator) {
        KnowledgeDocumentEntity doc = requireCms(id, libraryId);
        String relPath = doc.getFilePath();
        List<String> vectorIds = chunkRepository.findVectorIdsByDocId(id);
        long chunks = chunkRepository.countByDocId(id);
        // 先删库、后移镜像：库删失败时文件还在，站点最多多展示一篇；反过来会造成「库里有记录、文件没了」的硬 404。
        transactionTemplate.executeWithoutResult(status -> {
            versionRepository.deleteByDocId(id);
            chunkRepository.deleteByDocId(id);
            sectionRepository.deleteByDocId(id);
            documentRepository.delete(doc);
        });
        if (mirrorEnabled && relPath != null && !relPath.isBlank()) {
            try {
                moveToTrash(resolveSafe(relPath));
            } catch (RuntimeException e) {
                // 镜像清理失败不回滚已删除的库记录；文件留在原处可人工清理或重新导入，不影响库的一致性。
                log.warn("CMS 删除后移入回收站失败 - docId: {}, path: {}, err: {}", id, relPath, e.getMessage());
            }
        }
        if (vectorIds != null && !vectorIds.isEmpty()) {
            try {
                kbEmbeddingStore.removeAll(vectorIds);
            } catch (Exception e) {
                log.warn("CMS 删除文档向量失败 - docId: {}, err: {}", id, e.getMessage());
            }
        }
        docCache.evict(id);
        invalidateSemanticCache(libraryId);
        audit(libraryId, operator, "CMS_DELETE", id, "删除内容：" + doc.getTitle());
        log.info("CMS 文档已删除 - id: {}, title: {}, chunks: {}, 镜像: {}", id, doc.getTitle(), chunks,
                mirrorEnabled ? relPath : "DB-only");
    }

    @Override
    public List<CmsVersionView> versions(Long id, String libraryId) {
        requireCms(id, libraryId);
        return versionRepository.findByDocIdOrderByRevisionDesc(id).stream()
                .map(v -> CmsVersionView.builder()
                        .id(v.getId())
                        .revision(v.getRevision())
                        .title(v.getTitle())
                        .description(v.getDescription())
                        .changeNote(v.getChangeNote())
                        .createdBy(v.getCreatedBy())
                        .createdDate(v.getCreatedDate())
                        .build())
                .toList();
    }

    @Override
    public CmsDocumentDetailView restore(Long id, Long versionId, String libraryId, String operator) {
        KnowledgeDocumentEntity doc = requireCms(id, libraryId);
        KnowledgeDocumentVersionEntity version = versionRepository.findByIdAndDocId(versionId, id)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在"));
        CmsMarkdown.Parts parts = CmsMarkdown.split(version.getContent());
        CmsDocumentSaveRequest request = new CmsDocumentSaveRequest();
        request.setTitle(firstNonBlank(version.getTitle(), doc.getTitle()));
        request.setDescription(firstNonBlank(version.getDescription(), doc.getDescription()));
        request.setCategoryDir(doc.getCategoryDir());
        request.setCategorySlug(doc.getCategorySlug());
        request.setDocSlug(doc.getDocSlug());
        request.setBody(parts.body());
        request.setFrontmatter(parts.frontmatter());
        request.setExpectedRevision(doc.getRevision());
        request.setChangeNote("回滚到 r" + version.getRevision());
        CmsDocumentDetailView restored = update(id, request, libraryId, operator);
        audit(libraryId, operator, "CMS_RESTORE", id,
                "回滚内容：" + doc.getTitle() + " → r" + version.getRevision());
        return restored;
    }

    @Override
    public CmsImportResult importDocs(CmsImportRequest request, String libraryId, String operator) {
        Path root = docsRoot();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("docs 根目录不存在: " + root);
        }
        List<CmsImportRequest.Category> categories = normalizeCategories(request.getCategories());
        categories.sort(Comparator.comparingInt((CmsImportRequest.Category c) -> c.getDir().length()).reversed());

        Map<String, KnowledgeDocumentEntity> byPath = new HashMap<>();
        for (KnowledgeDocumentEntity d : documentRepository.findAllByLibraryId(libraryId)) {
            if (d.getFilePath() != null) {
                byPath.put(d.getFilePath(), d);
            }
        }

        CmsImportResult result = CmsImportResult.builder().build();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isCmsSyncable(root, p))
                    .sorted(Comparator.comparing(Path::toString))
                    .forEach(file -> {
                        result.setScanned(result.getScanned() + 1);
                        try {
                            String rel = root.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
                            CmsImportRequest.Category owner = findOwner(rel, categories);
                            if (owner == null) {
                                result.setSkipped(result.getSkipped() + 1);
                                return;
                            }
                            String docSlug = rel.substring(owner.getDir().length() + 1).replaceFirst("(?i)\\.md$", "");
                            if (docSlug.isBlank()) {
                                result.setSkipped(result.getSkipped() + 1);
                                return;
                            }
                            String content = Files.readString(file, StandardCharsets.UTF_8);
                            String hash = sha256(content);
                            CmsMarkdown.Parts parts = CmsMarkdown.split(content);
                            String title = truncate(CmsMarkdown.resolveTitle(content, file.getFileName().toString()), 500);
                            String description = truncate(CmsMarkdown.getScalar(parts.frontmatter(), "description"), 2000);

                            KnowledgeDocumentEntity existing = byPath.get(rel);
                            if (existing != null) {
                                if (SOURCE_CMS.equals(existing.getSourceType()) && !request.isForce()) {
                                    result.setSkipped(result.getSkipped() + 1);
                                    return;
                                }
                                adoptExisting(existing, owner, docSlug, rel, content, hash, title, description,
                                        libraryId, operator, request.isIndexNew());
                                result.setAdopted(result.getAdopted() + 1);
                            } else {
                                KnowledgeDocumentEntity created = newEntityFromImport(owner, docSlug, rel, content,
                                        hash, title, description, libraryId, operator);
                                KnowledgeDocumentEntity saved = documentRepository.save(created);
                                saveVersion(saved, content, "从 docs 导入", operator);
                                if (request.isIndexNew()) {
                                    indexQuietly(saved, content, libraryId);
                                }
                                result.setCreated(result.getCreated() + 1);
                            }
                        } catch (Exception e) {
                            result.setFailed(result.getFailed() + 1);
                            if (result.getErrors().size() < 100) {
                                result.getErrors().add(file + ": " + e.getMessage());
                            }
                            log.warn("CMS 导入单文件失败 - file: {}, err: {}", file, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            throw new IllegalStateException("扫描 docs 目录失败: " + e.getMessage(), e);
        }

        invalidateSemanticCache(libraryId);
        audit(libraryId, operator, "CMS_IMPORT", null,
                "docs 收编：扫描 " + result.getScanned() + "，收编 " + result.getAdopted()
                        + "，新建 " + result.getCreated() + "，跳过 " + result.getSkipped()
                        + "，失败 " + result.getFailed());
        log.info("CMS docs 收编完成 - library: {}, scanned: {}, adopted: {}, created: {}, skipped: {}, failed: {}",
                libraryId, result.getScanned(), result.getAdopted(), result.getCreated(),
                result.getSkipped(), result.getFailed());
        return result;
    }

    // ================================ 元数据 / 路径 ================================

    private record Norm(String title, String description, String categoryDir,
                        String categorySlug, String docSlug, String body) {
        String relPath() {
            return categoryDir + "/" + docSlug + ".md";
        }
    }

    private Norm normalize(CmsDocumentSaveRequest request, boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("请求不能为空");
        }
        String title = truncate(trimToNull(request.getTitle()), 500);
        if (title == null) {
            throw new IllegalArgumentException("标题不能为空");
        }
        String categoryDir = normalizeCategoryDir(request.getCategoryDir());
        String categorySlug = trimToNull(request.getCategorySlug());
        if (categorySlug == null || !categorySlug.matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
            throw new IllegalArgumentException("分类 slug 非法（只允许小写字母、数字、连字符）");
        }
        String docSlug = normalizeDocSlug(request.getDocSlug());
        String body = CmsMarkdown.normalizeNewlines(request.getBody());
        if (body == null || body.isBlank()) {
            throw new IllegalArgumentException("正文不能为空");
        }
        return new Norm(title, truncate(trimToNull(request.getDescription()), 2000),
                categoryDir, categorySlug, docSlug, body);
    }

    private String mergeFrontmatter(String requestedFrontmatter, Norm norm) {
        String fm = requestedFrontmatter;
        if (fm == null || fm.isBlank() || !fm.trim().startsWith("---")) {
            fm = CmsMarkdown.buildFrontmatter(norm.title(), norm.description());
        } else {
            fm = CmsMarkdown.setScalar(fm, "title", norm.title());
        }
        // 摘要为空时删除该行，避免留下空描述污染 SEO/列表
        fm = CmsMarkdown.setScalar(fm, "description", norm.description());
        return CmsMarkdown.merge(fm, norm.body());
    }

    private void applyMetadata(KnowledgeDocumentEntity doc, Norm norm, String raw,
                               String libraryId, String operator, int revision) {
        String hash = sha256(raw);
        doc.setTitle(norm.title());
        doc.setDescription(norm.description());
        doc.setCategorySlug(norm.categorySlug());
        doc.setCategoryDir(norm.categoryDir());
        doc.setDocSlug(norm.docSlug());
        doc.setFilePath(norm.relPath());
        doc.setFileName(Paths.get(norm.relPath()).getFileName().toString());
        doc.setFileSize((long) raw.getBytes(StandardCharsets.UTF_8).length);
        doc.setContentHash(hash);
        doc.setContent(raw);
        doc.setSpace(deriveSpace(norm.relPath()));
        doc.setSiteSlug(norm.categorySlug() + "/" + norm.docSlug());
        doc.setCategory(truncate(CmsMarkdown.getScalar(CmsMarkdown.split(raw).frontmatter(), "category"), 128));
        doc.setSourceType(SOURCE_CMS);
        doc.setLibraryId(libraryId);
        doc.setImportedBy(operator);
        doc.setPublishStatus(PUBLISHED);
        doc.setPublishedAt(new Date());
        doc.setPublishedBy(operator);
        doc.setRevision(revision);
        doc.setStatus(KbDocumentStatus.PENDING);
        docCache.evict(doc.getId());
    }

    private KnowledgeDocumentEntity newEntityFromImport(CmsImportRequest.Category owner, String docSlug,
                                                        String rel, String content, String hash, String title,
                                                        String description, String libraryId, String operator) {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setTitle(title);
        doc.setDescription(description);
        doc.setCategorySlug(owner.getSlug());
        doc.setCategoryDir(owner.getDir());
        doc.setDocSlug(docSlug);
        doc.setFilePath(rel);
        doc.setFileName(Paths.get(rel).getFileName().toString());
        doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        doc.setContentHash(hash);
        doc.setContent(content);
        doc.setSpace(deriveSpace(rel));
        doc.setSiteSlug(owner.getSlug() + "/" + docSlug);
        doc.setCategory(truncate(CmsMarkdown.getScalar(CmsMarkdown.split(content).frontmatter(), "category"), 128));
        doc.setSourceType(SOURCE_CMS);
        doc.setLibraryId(libraryId);
        doc.setImportedBy(operator);
        doc.setPublishStatus(PUBLISHED);
        doc.setPublishedAt(new Date());
        doc.setPublishedBy(operator);
        doc.setRevision(1);
        doc.setStatus(KbDocumentStatus.PENDING);
        return doc;
    }

    private void adoptExisting(KnowledgeDocumentEntity doc, CmsImportRequest.Category owner, String docSlug,
                               String rel, String content, String hash, String title, String description,
                               String libraryId, String operator, boolean indexNew) {
        boolean contentChanged = !hash.equals(doc.getContentHash());
        // 已经被 CMS 接管的文档再次 force 覆盖时，必须递增 revision 并留下版本快照；
        // 否则内容变了但历史版本对不上，回滚会回到一个「从未存在过」的中间态。
        boolean wasCms = SOURCE_CMS.equals(doc.getSourceType());
        doc.setTitle(title);
        doc.setDescription(description);
        doc.setCategorySlug(owner.getSlug());
        doc.setCategoryDir(owner.getDir());
        doc.setDocSlug(docSlug);
        doc.setFilePath(rel);
        doc.setFileName(Paths.get(rel).getFileName().toString());
        doc.setSiteSlug(owner.getSlug() + "/" + docSlug);
        doc.setSourceType(SOURCE_CMS);
        doc.setPublishStatus(PUBLISHED);
        doc.setPublishedAt(doc.getPublishedAt() == null ? new Date() : doc.getPublishedAt());
        doc.setPublishedBy(operator);
        int currentRevision = doc.getRevision() == null ? 1 : Math.max(1, doc.getRevision());
        doc.setRevision(wasCms && contentChanged ? currentRevision + 1 : currentRevision);
        if (contentChanged) {
            doc.setContent(content);
            doc.setContentHash(hash);
            doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
            doc.setStatus(KbDocumentStatus.PENDING);
        }
        KnowledgeDocumentEntity saved = documentRepository.save(doc);
        // 首次收编补 r1 快照；已接管文档只在内容真的变化时补新版本，避免每次 force 都刷一条空版本。
        if (versionRepository.countByDocId(saved.getId()) == 0 || (wasCms && contentChanged)) {
            saveVersion(saved, doc.getContent(), wasCms ? "从 docs 覆盖" : "从 docs 收编", operator);
        }
        if (contentChanged && indexNew) {
            indexQuietly(saved, content, libraryId);
        }
        docCache.evict(saved.getId());
    }

    private List<CmsImportRequest.Category> normalizeCategories(List<CmsImportRequest.Category> input) {
        List<CmsImportRequest.Category> result = new ArrayList<>();
        if (input == null) {
            return result;
        }
        Map<String, CmsImportRequest.Category> dedupe = new LinkedHashMap<>();
        for (CmsImportRequest.Category c : input) {
            if (c == null || c.getSlug() == null || c.getDir() == null) {
                continue;
            }
            String slug = c.getSlug().trim();
            String dir = normalizeCategoryDir(c.getDir());
            if (!slug.matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
                continue;
            }
            CmsImportRequest.Category norm = new CmsImportRequest.Category();
            norm.setSlug(slug);
            norm.setDir(dir);
            dedupe.put(dir, norm);
        }
        result.addAll(dedupe.values());
        return result;
    }

    private CmsImportRequest.Category findOwner(String rel, List<CmsImportRequest.Category> categories) {
        for (CmsImportRequest.Category c : categories) {
            if (rel.startsWith(c.getDir() + "/")) {
                return c;
            }
        }
        return null;
    }

    /** 站点可渲染口径：只收 .md，跳过隐藏目录、images、index.md 与 _ 工作区元文件。 */
    private boolean isCmsSyncable(Path root, Path file) {
        String name = file.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".md")) {
            return false;
        }
        if ("index.md".equalsIgnoreCase(name) || name.startsWith("_")) {
            return false;
        }
        for (Path seg : root.relativize(file)) {
            String s = seg.toString();
            if (s.startsWith(".") || "images".equals(s)) {
                return false;
            }
        }
        return true;
    }

    private String normalizeCategoryDir(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new IllegalArgumentException("分类目录不能为空");
        }
        value = value.replace('\\', '/').replaceAll("/+", "/");
        value = value.replaceAll("^/+", "").replaceAll("/+$", "");
        if (value.isBlank() || value.startsWith(".") || value.contains("../") || value.contains("/..")) {
            throw new IllegalArgumentException("分类目录非法: " + raw);
        }
        // 解析后必须仍在 docs 根内，防止 ../ 穿越
        resolveSafe(value + "/_probe.md");
        return value;
    }

    private String normalizeDocSlug(String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new IllegalArgumentException("文档 slug 不能为空");
        }
        value = value.replace('\\', '/').replaceAll("/+", "/");
        value = value.replaceAll("^/+", "").replaceFirst("(?i)\\.md$", "");
        if (value.isBlank() || value.startsWith(".") || value.contains("../")
                || value.contains("/..") || value.contains("\u0000")) {
            throw new IllegalArgumentException("文档 slug 非法: " + raw);
        }
        // 只允许安全的路径片段；中文/空格/点号/下划线/连字符都保留
        for (String seg : value.split("/")) {
            if (seg.isBlank() || ".".equals(seg) || "..".equals(seg)) {
                throw new IllegalArgumentException("文档 slug 非法: " + raw);
            }
        }
        return value;
    }

    private Path docsRoot() {
        return Paths.get(docsRoot).toAbsolutePath().normalize();
    }

    private Path resolveSafe(String relPath) {
        String rel = relPath == null ? "" : relPath.replace('\\', '/').replaceAll("/+", "/");
        while (rel.startsWith("/")) {
            rel = rel.substring(1);
        }
        Path root = docsRoot();
        Path target = root.resolve(rel).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IllegalArgumentException("路径越界: " + relPath);
        }
        return target;
    }

    private void writeAtomic(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("写入镜像失败: " + e.getMessage(), e);
        }
    }

    private void moveToTrash(Path target) {
        if (target == null || !Files.exists(target)) {
            return;
        }
        try {
            Path root = docsRoot();
            Path rel = root.relativize(target);
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path trash = root.resolve(TRASH_DIR).resolve(stamp + "-" + rel.toString().replace('/', '_'));
            Files.createDirectories(trash.getParent());
            Files.move(target, trash, StandardCopyOption.REPLACE_EXISTING);
            log.info("CMS 镜像已移入回收站 - {} → {}", target, trash);
        } catch (IOException e) {
            log.warn("CMS 镜像移入回收站失败 - path: {}, err: {}", target, e.getMessage());
        }
    }

    private void restoreMirror(Path oldTarget, Path newTarget, String oldRaw, String oldPath, String newPath) {
        try {
            if (!newPath.equals(oldPath) && Files.exists(newTarget)) {
                Files.deleteIfExists(newTarget);
            }
            if (oldTarget != null && oldRaw != null) {
                writeAtomic(oldTarget, oldRaw);
            }
        } catch (Exception e) {
            log.error("CMS 更新失败后恢复镜像失败 - old: {}, new: {}, err: {}", oldPath, newPath, e.getMessage());
        }
    }

    private void saveVersion(KnowledgeDocumentEntity doc, String raw, String note, String operator) {
        KnowledgeDocumentVersionEntity version = new KnowledgeDocumentVersionEntity();
        version.setDocId(doc.getId());
        version.setRevision(doc.getRevision());
        version.setTitle(doc.getTitle());
        version.setDescription(doc.getDescription());
        version.setContent(raw);
        version.setContentHash(doc.getContentHash());
        version.setChangeNote(truncate(note, 500));
        version.setCreatedBy(operator);
        versionRepository.save(version);
    }

    private void indexQuietly(KnowledgeDocumentEntity doc, String raw, String libraryId) {
        try {
            kbIndexer.indexDocument(doc, raw);
            docCache.evict(doc.getId());
            invalidateSemanticCache(libraryId);
        } catch (Exception e) {
            doc.setStatus(KbDocumentStatus.FAILED);
            documentRepository.save(doc);
            log.warn("CMS 内容已发布但索引失败 - id: {}, title: {}, err: {}",
                    doc.getId(), doc.getTitle(), e.getMessage());
        }
    }

    private KnowledgeDocumentEntity requireCms(Long id, String libraryId) {
        KnowledgeDocumentEntity doc = documentRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (!SOURCE_CMS.equals(doc.getSourceType())) {
            throw new IllegalArgumentException("该文档不是 CMS 内容，请在 CMS 导入后编辑");
        }
        return doc;
    }

    private void assertSize(String raw) {
        long size = raw.getBytes(StandardCharsets.UTF_8).length;
        if (size > maxFileSize) {
            throw new IllegalArgumentException("内容超过上限 " + maxFileSize + " 字节");
        }
    }

    /** 与 KbDocumentServiceImpl 同口径：顶级目录去掉排序编号作为空间标签。 */
    private String deriveSpace(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "default";
        }
        String normalized = filePath.replace('\\', '/');
        int idx = normalized.indexOf('/');
        if (idx > 0) {
            return normalized.substring(0, idx).replaceFirst("^\\d+-", "");
        }
        return "default";
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算内容哈希失败", e);
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String blankToNull(String value) {
        return trimToNull(value);
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 审计是旁路：内容已经发布后再因审计失败回滚镜像，会把「库里有、文件没了」变成更坏的状态。 */
    private void audit(String libraryId, String operator, String action, Long targetId, String detail) {
        try {
            auditLogService.record(libraryId, operator, action, "DOCUMENT", targetId, detail);
        } catch (RuntimeException e) {
            log.warn("CMS 审计写入失败 - action: {}, target: {}, err: {}", action, targetId, e.getMessage());
        }
    }

    private void invalidateSemanticCache(String libraryId) {
        if (semanticCacheService == null) {
            return;
        }
        try {
            semanticCacheService.invalidate(libraryId);
        } catch (Exception e) {
            log.warn("CMS 语义缓存失效失败 - library: {}, err: {}", libraryId, e.getMessage());
        }
    }
}
