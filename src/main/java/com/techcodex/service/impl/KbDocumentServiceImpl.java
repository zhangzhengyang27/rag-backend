package com.techcodex.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import com.techcodex.config.KbConfig;
import com.techcodex.dto.KbDocumentView;
import com.techcodex.dto.KbImportTaskView;
import com.techcodex.dto.KbStatsView;
import com.techcodex.entity.KbDocumentStatus;
import com.techcodex.entity.KnowledgeDocumentEntity;
import com.techcodex.parse.DocumentParser;
import com.techcodex.parse.impl.UrlFetcher;
import com.techcodex.repository.KnowledgeChunkRepository;
import com.techcodex.repository.KnowledgeDocumentRepository;
import com.techcodex.service.KbDocumentService;
import com.techcodex.service.KbIndexer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 知识库文档管理服务实现：负责文件导入（单文件上传 + 目录批量导入）、查询、删除、
 * 索引重建与统计。目录导入与全量重建均为异步后台任务，通过任务ID轮询进度。
 */
@Slf4j
@Service
public class KbDocumentServiceImpl implements KbDocumentService {

    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^\\s*#{1,6}\\s+(.+?)\\s*$");

    /** 导入允许的扩展名（忽略大小写）；纯文本直接 UTF-8 读取，其余走 Tika 转 Markdown。 */
    private static final Set<String> ALLOWED_EXTENSIONS = new HashSet<>(Arrays.asList(
            "md", "markdown", "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "html", "htm"));

    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeChunkRepository chunkRepository;
    private final KbIndexer kbIndexer;
    private final EmbeddingStore<TextSegment> kbEmbeddingStore;
    private final Executor kbImportExecutor;
    private final com.techcodex.quota.QuotaService quotaService;
    private final DocumentParser documentParser;
    private final UrlFetcher urlFetcher;
    private final com.techcodex.service.AuditLogService auditLogService;
    private final com.techcodex.service.KbAlertService kbAlertService;
    private final com.techcodex.service.DocumentInfoCache docCache;
    private final redis.clients.jedis.JedisPooled kbJedis;
    private final String redisPrefix;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final int maxFileSize;

    /** 文档内容/索引变化后使语义缓存版本失效；缓存故障不影响文档操作主链路。 */
    @Autowired
    private com.techcodex.service.SemanticCacheService semanticCacheService;

    /**
     * 目录导入/同步的路径白名单（逗号分隔；默认跟随 docs 同步根目录，见 application.properties）。
     * 管理员传入的服务器路径必须等于或位于某个白名单根之下，防止（管理员账号失陷时）
     * 把服务器任意目录的文件索引进知识库造成敏感文件泄露。
     */
    @Value("${app.kb.import.allowed-roots:}")
    private String importAllowedRoots;

    /** 来源类型：与 KnowledgeDocumentEntity.sourceType 对应。 */
    private static final String SOURCE_UPLOAD = "UPLOAD";
    private static final String SOURCE_URL = "URL";
    private static final String SOURCE_DIRECTORY = "DIRECTORY";


    /** 内存任务表：taskId -> 任务条目（视图 + 归属空间 + 创建时间）。 */
    private final ConcurrentHashMap<String, TaskEntry> tasks = new ConcurrentHashMap<>();

    /** 任务查重/启动的互斥锁：check-then-act 原子化，避免并发触发绕过"空间内单任务"约束。 */
    private final Object taskLock = new Object();

    /** 任务条目：进度视图 + 归属空间（按空间限流）+ 创建时间（过期清扫）。 */
    private record TaskEntry(KbImportTaskView view, String libraryId, long createdAt) {
    }

    /** 已结束任务保留时长（供前端轮询最终状态），超时后由定时清扫回收。 */
    private static final long FINISHED_TASK_RETENTION_MILLIS = 60 * 60 * 1000L;

    public KbDocumentServiceImpl(KnowledgeDocumentRepository documentRepository,
                                 KnowledgeChunkRepository chunkRepository,
                                 KbIndexer kbIndexer,
                                 EmbeddingStore<TextSegment> kbEmbeddingStore,
                                 @Qualifier("kbImportExecutor") Executor kbImportExecutor,
                                 KbConfig kbConfig,
                                 com.techcodex.quota.QuotaService quotaService,
                             DocumentParser documentParser,
                             UrlFetcher urlFetcher,
                             com.techcodex.service.AuditLogService auditLogService,
                             com.techcodex.service.KbAlertService kbAlertService,
                             com.techcodex.service.DocumentInfoCache docCache,
                             redis.clients.jedis.JedisPooled kbJedis,
                             @Value("${app.kb.redis.prefix:lostsystem:kbdoc:}") String redisPrefix,
                             org.springframework.transaction.PlatformTransactionManager transactionManager,
                             @Value("${app.kb.import.max-file-size:5242880}") int maxFileSize) {
        this.documentRepository = documentRepository;
        this.chunkRepository = chunkRepository;
        this.kbIndexer = kbIndexer;
        this.kbEmbeddingStore = kbEmbeddingStore;
        this.kbImportExecutor = kbImportExecutor;
        this.quotaService = quotaService;
        this.documentParser = documentParser;
        this.urlFetcher = urlFetcher;
        this.auditLogService = auditLogService;
        this.kbAlertService = kbAlertService;
        this.docCache = docCache;
        this.kbJedis = kbJedis;
        this.redisPrefix = redisPrefix;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.maxFileSize = maxFileSize;
    }

    @Override
    public Page<KbDocumentView> list(String keyword, String status, String libraryId, Pageable page) {
        KbDocumentStatus statusEnum = parseStatus(status);
        Page<KnowledgeDocumentEntity> result;
        if (statusEnum == null && (keyword == null || keyword.isBlank())) {
            result = documentRepository.findByLibraryId(libraryId, page);
        } else {
            org.springframework.data.jpa.domain.Specification<KnowledgeDocumentEntity> spec =
                    (root, query, cb) -> {
                        java.util.List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
                        ps.add(cb.equal(root.get("libraryId"), libraryId));
                        if (statusEnum != null) {
                            ps.add(cb.equal(root.get("status"), statusEnum));
                        }
                        if (keyword != null && !keyword.isBlank()) {
                            String like = "%" + keyword.trim() + "%";
                            ps.add(cb.or(cb.like(root.get("title"), like), cb.like(root.get("fileName"), like)));
                        }
                        return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
                    };
            result = documentRepository.findAll(spec, page);
        }
        return result.map(e -> KbDocumentView.fromEntity(e, false));
    }

    /** 状态参数解析：空/非法值视为不过滤。 */
    private KbDocumentStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return KbDocumentStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public void reindex(Long id, String libraryId) {
        KnowledgeDocumentEntity doc = documentRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        doc.setStatus(KbDocumentStatus.PENDING);
        // save 对游离实体是 merge：必须改用返回的受管副本继续操作，
        // 否则原实例版本号过期，indexDocument 事务内再次保存会乐观锁冲突
        KnowledgeDocumentEntity managed = documentRepository.save(doc);
        try {
            kbIndexer.indexDocument(managed, managed.getContent());
            log.info("单文档索引重建完成 - id: {}, title: {}", managed.getId(), managed.getTitle());
            invalidateSemanticCache(libraryId);
            auditLogService.record(libraryId, com.techcodex.security.SecurityUtil.currentUsername(),
                    "REINDEX", "DOCUMENT", id, "重建索引：" + managed.getTitle());
        } catch (Exception e) {
            managed.setStatus(KbDocumentStatus.FAILED);
            documentRepository.save(managed);
            throw new IllegalArgumentException("重建索引失败: " + e.getMessage());
        }
    }

    @Override
    public KbDocumentView updateContent(Long id, String libraryId, String title, String content, String operator) {
        KnowledgeDocumentEntity doc = documentRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("文档内容不能为空");
        }
        String newHash = sha256(content);
        boolean contentChanged = !newHash.equals(doc.getContentHash());
        boolean titleChanged = title != null && !title.isBlank() && !title.trim().equals(doc.getTitle());
        if (!contentChanged && !titleChanged) {
            return KbDocumentView.fromEntity(doc, true);
        }
        if (contentChanged) {
            doc.setContent(content);
            doc.setContentHash(newHash);
            doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        }
        if (titleChanged) {
            doc.setTitle(title.trim());
        }
        doc.setStatus(KbDocumentStatus.PENDING);
        // 同 reindex：merge 返回受管副本，避免游离实体乐观锁冲突
        KnowledgeDocumentEntity managed = documentRepository.save(doc);
        try {
            kbIndexer.indexDocument(managed, managed.getContent());
            log.info("文档编辑完成并重建索引 - id: {}, title: {}", managed.getId(), managed.getTitle());
            invalidateSemanticCache(libraryId);
        } catch (Exception e) {
            managed.setStatus(KbDocumentStatus.FAILED);
            documentRepository.save(managed);
            throw new IllegalArgumentException("编辑后索引重建失败: " + e.getMessage());
        } finally {
            // 内容/标题已变更（无论索引成败），检索溯源缓存必须失效，否则读到旧标题
            docCache.evict(managed.getId());
        }
        auditLogService.record(libraryId, operator, "EDIT", "DOCUMENT", id, "编辑文档：" + managed.getTitle());
        return KbDocumentView.fromEntity(managed, true);
    }

    @Override
    public KbDocumentView get(Long id, String libraryId, boolean withContent) {
        KnowledgeDocumentEntity doc = documentRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        return KbDocumentView.fromEntity(doc, withContent);
    }

    @Override
    public KbDocumentView upload(MultipartFile file, String title, String userId, String libraryId) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        if (file.getSize() > maxFileSize) {
            throw new IllegalArgumentException("文件超过大小限制");
        }
        // M3：文档数配额校验
        quotaService.assertDocumentQuota(libraryId);
        String fileName = file.getOriginalFilename();
        if (!isSupported(fileName)) {
            throw new IllegalArgumentException("不支持的文件类型: " + fileName);
        }
        String content;
        try {
            content = isPlainText(fileName)
                    ? new String(file.getBytes(), StandardCharsets.UTF_8)
                    : documentParser.parse(file.getBytes(), fileName);
        } catch (IOException e) {
            throw new IllegalArgumentException("文件读取失败");
        }
        String hash = sha256(content);
        if (documentRepository.existsByContentHashAndLibraryId(hash, libraryId)) {
            throw new IllegalArgumentException("该文档已存在，无需重复导入");
        }
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setTitle(resolveTitle(content, title, fileName));
        doc.setSiteSlug(resolveSiteSlug(content));
        doc.setCategory(resolveCategory(content));
        doc.setFileName(fileName);
        // 上传文档也要有层级路径：裸文件名会让 deriveSpace 取不到目录、
        // 断链检查解析相对链接时直接落到根目录。统一为 upload/{上传人}/{uuid}-{原名}：
        // uuid 前缀避免同名覆盖，后半段保留原名便于辨认。
        doc.setFilePath(buildUploadPath(resolveUploader(userId, libraryId), fileName));
        // 上传文档没有分类目录，显式归默认空间；若交给 deriveSpace，"upload" 会变成一个伪分类标签
        doc.setSpace("default");
        doc.setSourceType(SOURCE_UPLOAD);
        doc.setFileSize(file.getSize());
        doc.setContentHash(hash);
        doc.setContent(content);
        doc.setImportedBy(userId);
        doc.setLibraryId(libraryId);
        doc.setStatus(KbDocumentStatus.PENDING);
        documentRepository.save(doc);
        kbIndexer.indexDocument(doc, content);
        invalidateSemanticCache(libraryId);
        auditLogService.record(libraryId, userId, "UPLOAD", "DOCUMENT", doc.getId(), "上传文档：" + doc.getTitle());
        return KbDocumentView.fromEntity(doc, false);
    }

    @Override
    public KbDocumentView importUrl(String url, String userId, String libraryId) {
        // M3：文档数配额校验（先于抓取，避免对非法 URL 产生无谓请求）
        quotaService.assertDocumentQuota(libraryId);
        UrlFetcher.FetchedPage page = urlFetcher.fetch(url);
        String content = page.markdown();
        String hash = sha256(content);
        if (documentRepository.existsByContentHashAndLibraryId(hash, libraryId)) {
            throw new IllegalArgumentException("该文档已存在，无需重复导入");
        }
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setTitle(resolveTitle(content, page.title(), null));
        doc.setSiteSlug(resolveSiteSlug(content));
        doc.setCategory(resolveCategory(content));
        doc.setFileName(url);
        doc.setFilePath(url);
        doc.setSpace("web");
        doc.setSourceType(SOURCE_URL);
        doc.setFileSize((long) content.getBytes(StandardCharsets.UTF_8).length);
        doc.setContentHash(hash);
        doc.setContent(content);
        doc.setImportedBy(userId);
        doc.setLibraryId(libraryId);
        doc.setStatus(KbDocumentStatus.PENDING);
        documentRepository.save(doc);
        kbIndexer.indexDocument(doc, content);
        invalidateSemanticCache(libraryId);
        auditLogService.record(libraryId, userId, "IMPORT_URL", "DOCUMENT", doc.getId(), "URL 导入：" + url);
        return KbDocumentView.fromEntity(doc, false);
    }

    /** 个人库 id 形如 u-{username}，取后缀作为上传人；管理员在公共库上传时退回 userId。 */
    private String resolveUploader(String userId, String libraryId) {
        if (libraryId != null && libraryId.startsWith("u-") && libraryId.length() > 2) {
            return libraryId.substring(2);
        }
        return (userId == null || userId.isBlank()) ? "anonymous" : userId;
    }

    /**
     * 生成上传文档的存储路径：upload/{上传人}/{8 位 uuid}-{原文件名}。
     *
     * <p>文件名只取 basename 并剥掉 ".."：浏览器可能传带 C:\fakepath 前缀的名字，
     * 而这条路径会写入库并参与后续解析，不能让路径片段漏进去。</p>
     */
    private String buildUploadPath(String uploader, String rawFileName) {
        String user = uploader.replaceAll("[^\\p{L}\\p{N}_-]", "_");
        String base = rawFileName == null ? "document" : rawFileName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replace("..", "").replaceAll("^[./]+", "");
        if (base.isBlank()) {
            base = "document";
        }
        return "upload/" + user + "/" + UUID.randomUUID().toString().substring(0, 8) + "-" + base;
    }

    /** 从相对路径的顶级目录派生 space；无目录层级时用 "default"。顶级目录的排序编号前缀（如 "04-前端"）不进入标签。 */
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

    @Override
    @Transactional
    public void delete(Long id, String libraryId) {
        KnowledgeDocumentEntity doc = documentRepository.findByIdAndLibraryId(id, libraryId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        long chunkCount = chunkRepository.countByDocId(id);
        // 先取旧向量 ID，再删除 chunk 与 Redis 向量（按 ID 精准删除，无需整库重建）
        java.util.List<String> vectorIds = chunkRepository.findVectorIdsByDocId(id);
        chunkRepository.deleteByDocId(id);
        if (vectorIds != null && !vectorIds.isEmpty()) {
            try {
                kbEmbeddingStore.removeAll(vectorIds);
            } catch (Exception e) {
                log.warn("删除文档向量失败 - docId: {}, err: {}", id, e.getMessage());
            }
        }
        documentRepository.delete(doc);
        invalidateSemanticCache(libraryId);
        docCache.evict(id);
        log.info("知识库文档已删除（按向量ID增量清理） - id: {}, title: {}, 分块数: {}", id, doc.getTitle(), chunkCount);
        auditLogService.record(libraryId, com.techcodex.security.SecurityUtil.currentUsername(),
                "DELETE", "DOCUMENT", id, "删除文档：" + doc.getTitle());
    }

    @Override
    public String importDirectory(String path, String userId, String libraryId) {
        Path dir = Paths.get(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("目录不存在: " + path);
        }
        assertImportPathAllowed(dir);
        auditLogService.record(libraryId, userId, "IMPORT_DIR", "DIR", null, "目录导入：" + path);
        return startTask(libraryId, view -> runImport(view, dir, userId, libraryId));
    }

    /** 校验目标路径落在导入白名单根之内（归一化后前缀匹配），白名单为空时放行（兼容手工构造的单测）。 */
    private void assertImportPathAllowed(Path dir) {
        if (importAllowedRoots == null || importAllowedRoots.isBlank()) {
            return;
        }
        Path target = dir.toAbsolutePath().normalize();
        for (String root : importAllowedRoots.split(",")) {
            if (root.isBlank()) {
                continue;
            }
            Path allowed = Paths.get(root.trim()).toAbsolutePath().normalize();
            // 白名单根不允许是文件系统根，防止配置失误等于关闭白名单
            if (allowed.getParent() == null) {
                throw new IllegalArgumentException("导入白名单根目录不允许是文件系统根: " + root.trim());
            }
            if (target.equals(allowed) || target.startsWith(allowed)) {
                return;
            }
        }
        throw new IllegalArgumentException(
                "目录不在允许导入的白名单内: " + target + "（可用 app.kb.import.allowed-roots / KB_IMPORT_ALLOWED_ROOTS 配置）");
    }

    private void runImport(KbImportTaskView view, Path dir, String userId, String libraryId) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> isSupported(p.getFileName().toString()))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(files::add);
        } catch (IOException e) {
            log.error("扫描目录失败", e);
            view.setStatus("FAILED");
            view.getErrors().add("扫描目录失败: " + e.getMessage());
            return;
        }
        view.setTotal(files.size());
        Path base = dir.toAbsolutePath();
        for (Path file : files) {
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (bytes.length > maxFileSize) {
                    view.getErrors().add(file.getFileName() + ": 文件过大已跳过");
                    view.setFailed(view.getFailed() + 1);
                    continue;
                }
                String fileName = file.getFileName().toString();
                String content = isPlainText(fileName)
                        ? new String(bytes, StandardCharsets.UTF_8)
                        : documentParser.parse(bytes, fileName);
                String hash = sha256(content);
                String relPath = base.relativize(file.toAbsolutePath()).toString();
                // 1) 按相对路径识别文档：若已存在则视为"更新"，内容变更时复用 docId 重建
                //    （indexDocument 内部会按 docId 删除旧 chunk 与 Redis 向量，实现增量更新）
                KnowledgeDocumentEntity existing = documentRepository.findByLibraryIdAndFilePath(libraryId, relPath).orElse(null);
                if (existing != null) {
                    if (hash.equals(existing.getContentHash())) {
                        // 内容未变，幂等跳过
                        view.setSucceeded(view.getSucceeded() + 1);
                        continue;
                    }
                    existing.setContent(content);
                    existing.setContentHash(hash);
                    existing.setFileSize((long) bytes.length);
                    existing.setTitle(resolveTitle(content, null, fileName));
                    existing.setSiteSlug(resolveSiteSlug(content));
                    existing.setCategory(resolveCategory(content));
                    existing.setLibraryId(libraryId);
                    docCache.evict(existing.getId());
                    kbIndexer.indexDocument(existing, content);
                    view.setSucceeded(view.getSucceeded() + 1);
                    continue;
                }
                // 2) 不同路径但内容 hash 相同：空间内去重，视为成功（幂等）
                if (documentRepository.existsByContentHashAndLibraryId(hash, libraryId)) {
                    view.setSucceeded(view.getSucceeded() + 1);
                    continue;
                }
                // 3) 全新文档：先校验文档数配额，超限记录错误并跳过，不中断整批
                try {
                    quotaService.assertDocumentQuota(libraryId);
                } catch (com.techcodex.exception.QuotaExceededException e) {
                    view.getErrors().add(file.getFileName() + ": " + e.getMessage());
                    view.setFailed(view.getFailed() + 1);
                    continue;
                }
                KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
                doc.setTitle(resolveTitle(content, null, fileName));
                doc.setSiteSlug(resolveSiteSlug(content));
                doc.setCategory(resolveCategory(content));
                doc.setFileName(fileName);
                doc.setFilePath(relPath);
                doc.setSpace(deriveSpace(relPath));
                doc.setSourceType(SOURCE_DIRECTORY);
                doc.setFileSize((long) bytes.length);
                doc.setContentHash(hash);
                doc.setContent(content);
                doc.setImportedBy(userId);
                doc.setLibraryId(libraryId);
                doc.setStatus(KbDocumentStatus.PENDING);
                kbIndexer.indexDocument(doc, content);
                view.setSucceeded(view.getSucceeded() + 1);
            } catch (Exception e) {
                view.setFailed(view.getFailed() + 1);
                if (view.getErrors().size() < 100) {
                    view.getErrors().add(file.getFileName() + ": " + e.getMessage());
                }
                log.warn("目录导入单文件失败 - file: {}", file.getFileName(), e);
            } finally {
                view.setProcessed(view.getProcessed() + 1);
            }
        }
        view.setStatus("DONE");
        invalidateSemanticCache(libraryId);
        log.info("知识库目录导入完成 - files: {}, succeeded: {}, failed: {}", files.size(), view.getSucceeded(), view.getFailed());
    }

    @Override
    public KbImportTaskView getImportTask(String taskId) {
        TaskEntry entry = tasks.get(taskId);
        if (entry == null) {
            throw new IllegalArgumentException("任务不存在");
        }
        return entry.view();
    }

    @Override
    public String rebuildIndex(String libraryId) {
        auditLogService.record(libraryId, com.techcodex.security.SecurityUtil.currentUsername(),
                "REBUILD_INDEX", "LIBRARY", null, "重建索引：" + libraryId);
        return startTask(libraryId, view -> runRebuild(view, libraryId));
    }

    /**
     * 启动一个后台任务（目录导入或全量重建）。
     * 同一空间同时只允许一个任务（导入与重建互斥，避免并发写同一批 docId 的分块/向量）；
     * 不同空间互不影响。有任务冲突时抛业务异常。
     */
    private String startTask(String libraryId, java.util.function.Consumer<KbImportTaskView> work) {
        synchronized (taskLock) {
            boolean busy = tasks.values().stream()
                    .anyMatch(e -> libraryId.equals(e.libraryId()) && "RUNNING".equals(e.view().getStatus()));
            if (busy) {
                throw new IllegalArgumentException("该空间已有导入/重建任务进行中，请等待完成后再试");
            }
            String taskId = UUID.randomUUID().toString().replace("-", "");
            KbImportTaskView view = KbImportTaskView.builder()
                    .taskId(taskId).status("RUNNING").total(0).processed(0).succeeded(0).failed(0).build();
            tasks.put(taskId, new TaskEntry(view, libraryId, System.currentTimeMillis()));
            kbImportExecutor.execute(() -> {
                try {
                    work.accept(view);
                } catch (Exception e) {
                    // 兜底：任何未捕获异常都要终结任务状态，否则该空间被永久锁死
                    log.error("后台任务异常终止 - library: {}", libraryId, e);
                    view.setStatus("FAILED");
                    if (view.getErrors().size() < 100) {
                        view.getErrors().add("任务异常终止: " + e.getMessage());
                    }
                }
            });
            return taskId;
        }
    }

    /** 定期清扫已结束超过保留期的任务条目，防止任务表无上限增长。 */
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    public void sweepFinishedTasks() {
        long expireBefore = System.currentTimeMillis() - FINISHED_TASK_RETENTION_MILLIS;
        tasks.values().removeIf(e -> !"RUNNING".equals(e.view().getStatus()) && e.createdAt() < expireBefore);
    }

    private void runRebuild(KbImportTaskView view, String libraryId) {
        java.util.List<String> vectorIds = chunkRepository.findVectorIdsByLibraryId(libraryId);
        if (vectorIds != null && !vectorIds.isEmpty()) {
            kbEmbeddingStore.removeAll(vectorIds);
        }
        List<KnowledgeDocumentEntity> docs = documentRepository.findByLibraryId(libraryId, org.springframework.data.domain.Pageable.unpaged()).getContent();
        view.setTotal(docs.size());
        for (KnowledgeDocumentEntity doc : docs) {
            try {
                kbIndexer.indexDocument(doc, doc.getContent());
                view.setSucceeded(view.getSucceeded() + 1);
            } catch (Exception e) {
                view.setFailed(view.getFailed() + 1);
                if (view.getErrors().size() < 100) {
                    view.getErrors().add(doc.getTitle() + ": " + e.getMessage());
                }
                log.warn("索引重建单文档失败 - id: {}", doc.getId(), e);
            } finally {
                view.setProcessed(view.getProcessed() + 1);
            }
        }
        // 对账清理：删除 Redis 中不再被任何分块引用的孤儿向量。
        // 增量更新的"删旧向量→重建分块"存在失败残留窗口（Redis 非事务存储，
        // 历史上失败的批量操作会留下永久孤儿并污染检索与统计），
        // 重建是全量修复入口，故在此做一次前缀空间与 kb_chunk 引用集的严格对账。
        int swept = sweepOrphanVectors();
        view.setStatus("DONE");
        invalidateSemanticCache(libraryId);
        log.info("知识库索引重建完成 - library: {}, docs: {}, ok: {}, fail: {}, 孤儿向量清理: {}",
                libraryId, docs.size(), view.getSucceeded(), view.getFailed(), swept);
    }

    /**
     * 扫描 Redis 向量前缀空间，删除 id 不在 kb_chunk 引用集内的孤儿向量。
     * 引用集取全库（findAllVectorIds）：前缀空间是全局的，跨空间向量也必须保留。
     *
     * @return 清理的孤儿向量数
     */
    private int sweepOrphanVectors() {
        java.util.Set<String> validIds = new HashSet<>(chunkRepository.findAllVectorIds());
        List<String> staleBatch = new ArrayList<>(500);
        int swept = 0;
        String cursor = "0";
        redis.clients.jedis.params.ScanParams params = new redis.clients.jedis.params.ScanParams()
                .match(redisPrefix + "*").count(2000);
        do {
            redis.clients.jedis.resps.ScanResult<String> result = kbJedis.scan(cursor, params);
            cursor = result.getCursor();
            for (String key : result.getResult()) {
                if (!validIds.contains(key.substring(redisPrefix.length()))) {
                    staleBatch.add(key);
                    if (staleBatch.size() >= 500) {
                        swept += kbJedis.del(staleBatch.toArray(new String[0]));
                        staleBatch.clear();
                    }
                }
            }
        } while (!"0".equals(cursor));
        if (!staleBatch.isEmpty()) {
            swept += kbJedis.del(staleBatch.toArray(new String[0]));
        }
        return swept;
    }

    @Override
    public KbStatsView stats(String libraryId) {
        // 状态分布用一次 group by 取回，替代 INDEXED / PENDING / FAILED 三次独立 count。
        // 只关心这三种对外展示的状态；其他状态（若有）不计入，避免总览出现无法归类的数字。
        var byStatus = new HashMap<KbDocumentStatus, Long>();
        documentRepository.countGroupByStatus(libraryId).forEach(row -> {
            // 用 valueOf 转而不是强转 Object[]：JPQL 返回的枚举列在不同 Hibernate 版本里
            // 可能是枚举实例也可能是 name 字符串，走字符串解析两边都成立。
            try {
                byStatus.merge(
                        KbDocumentStatus.valueOf(String.valueOf(row[0])),
                        ((Number) row[1]).longValue(),
                        Long::sum);
            } catch (IllegalArgumentException ignored) {
                // 未知状态不计入对外统计，避免总览出现无法归类的分类
            }
        });

        // 分块数与向量数同源：当前一张分块表同时决定两者，多查一次没有信息增益，
        // 但 chunk 表是最大的表（几十万行），重复 count 代价明确。
        long totalChunks = chunkRepository.countByLibraryId(libraryId);

        return KbStatsView.builder()
                .totalDocuments(documentRepository.countByLibraryId(libraryId))
                .totalChunks(totalChunks)
                .indexedDocuments(byStatus.getOrDefault(KbDocumentStatus.INDEXED, 0L))
                .pendingDocuments(byStatus.getOrDefault(KbDocumentStatus.PENDING, 0L))
                .failedDocuments(byStatus.getOrDefault(KbDocumentStatus.FAILED, 0L))
                .totalVectorCount(totalChunks)
                .build();
    }

    @Override
    public java.util.List<String> categories(String libraryId) {
        return documentRepository.findDistinctCategories(libraryId);
    }

    @Override
    public java.util.List<com.techcodex.vo.CategoryGroupVo> categoryGroups(String libraryId) {
        // 一次分组查询拿到每个原分类的文档数，再按归并表汇总成顶级分类
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        for (KnowledgeDocumentRepository.CategoryCountRow row : documentRepository.countByCategory(libraryId)) {
            counts.put(row.getCategory(), row.getCnt());
        }
        java.util.List<com.techcodex.vo.CategoryGroupVo> result = new java.util.ArrayList<>();
        for (com.techcodex.library.CategoryGroups.Group group : com.techcodex.library.CategoryGroups.groups()) {
            java.util.List<com.techcodex.vo.CategoryGroupVo.CategoryVo> children = new java.util.ArrayList<>();
            long total = 0;
            for (String child : group.children()) {
                long count = counts.getOrDefault(child, 0L);
                if (count > 0) {
                    children.add(new com.techcodex.vo.CategoryGroupVo.CategoryVo(child, count));
                    total += count;
                }
            }
            if (total > 0) {
                result.add(new com.techcodex.vo.CategoryGroupVo(group.name(), total, children));
            }
        }
        // 归并表之外的新分类统一落到「其他」，避免新增文档的分类从下拉里静默消失
        java.util.List<com.techcodex.vo.CategoryGroupVo.CategoryVo> otherChildren = new java.util.ArrayList<>();
        long otherCount = 0;
        for (java.util.Map.Entry<String, Long> entry : counts.entrySet()) {
            if (!com.techcodex.library.CategoryGroups.isKnownChild(entry.getKey())) {
                otherChildren.add(new com.techcodex.vo.CategoryGroupVo.CategoryVo(entry.getKey(), entry.getValue()));
                otherCount += entry.getValue();
            }
        }
        if (otherCount > 0) {
            result.add(new com.techcodex.vo.CategoryGroupVo(
                    com.techcodex.library.CategoryGroups.OTHER, otherCount, otherChildren));
        }
        return result;
    }

    /** 从 Markdown frontmatter 解析单行标量键值（slug/category 等），无则返回 null */
    private String resolveFrontmatterScalar(String content, String key) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String[] lines = content.split("\\r?\\n");
        int i = 0;
        while (i < lines.length && lines[i].trim().isEmpty()) i++;
        if (i >= lines.length || !lines[i].trim().startsWith("---")) return null;
        String prefix = key + ":";
        for (i = i + 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.startsWith("---")) break;
            if (line.startsWith(prefix)) {
                String v = line.substring(prefix.length()).trim().replaceAll("^['\"]|['\"]$", "");
                return v.isEmpty() ? null : v;
            }
        }
        return null;
    }

    /** 从 Markdown frontmatter 解析站点 slug（tech-codex 文档约定 frontmatter 里 slug: 相对 /docs/ 的路径），无则返回 null */
    private String resolveSiteSlug(String content) {
        return resolveFrontmatterScalar(content, "slug");
    }

    /** 从 Markdown frontmatter 解析 category（课程级标签，进向量 metadata 供分类范围检索），无则返回 null */
    private String resolveCategory(String content) {
        String v = resolveFrontmatterScalar(content, "category");
        return (v == null || v.length() > 128) ? null : v;
    }

    private String resolveTitle(String content, String title, String fileName) {
        if (title != null && !title.isBlank()) {
            return title;
        }
        Matcher matcher = FIRST_HEADING.matcher(content);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        if (fileName != null && !fileName.isBlank()) {
            return fileName.replaceFirst("(?i)\\.[a-z0-9]+$", "");
        }
        return "未命名文档";
    }

    /** 是否属于导入白名单类型（忽略大小写）。 */
    private boolean isSupported(String fileName) {
        return ALLOWED_EXTENSIONS.contains(extensionOf(fileName));
    }

    /** 纯文本类型直接 UTF-8 读取，无需 Tika 解析。 */
    private boolean isPlainText(String fileName) {
        String ext = extensionOf(fileName);
        return "md".equals(ext) || "markdown".equals(ext) || "txt".equals(ext);
    }

    private String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
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
            throw new RuntimeException("计算内容哈希失败", e);
        }
    }

    /** 缓存失效是旁路能力：单测手工构造/缓存未启用时允许为空，避免拖垮文档主链路。 */
    private void invalidateSemanticCache(String libraryId) {
        if (semanticCacheService == null) {
            return;
        }
        try {
            semanticCacheService.invalidate(libraryId);
        } catch (Exception e) {
            log.warn("语义缓存失效失败，忽略 - libraryId={}, err={}", libraryId, e.getMessage());
        }
    }
}
