package org.example.service.impl;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.example.config.KbConfig;
import org.example.dto.KbDocumentView;
import org.example.dto.KbImportTaskView;
import org.example.dto.KbStatsView;
import org.example.entity.KbDocumentStatus;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.parse.DocumentParser;
import org.example.parse.impl.UrlFetcher;
import org.example.repository.KnowledgeChunkRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.service.KbDocumentService;
import org.example.service.KbIndexer;
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
import java.util.LinkedHashMap;
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
    private final org.example.quota.QuotaService quotaService;
    private final DocumentParser documentParser;
    private final UrlFetcher urlFetcher;
    private final org.example.service.AuditLogService auditLogService;
    private final org.example.service.KbAlertService kbAlertService;
    private final org.example.service.DocumentInfoCache docCache;
    private final redis.clients.jedis.JedisPooled kbJedis;
    private final String redisPrefix;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final int maxFileSize;

    // ---- docs 目录自动同步配置（默认指向旁侧 tech-codex 前端的 docs 根，可用 KB_DOCS_ROOT 覆盖；
    //      触发调度见 KbDocsSyncScheduler：启动对账 + 发布钩子/手动触发，轮询默认关闭） ----
    @Value("${app.kb.docs-sync.root:${KB_DOCS_ROOT:../tech-codex/docs}}")
    private String docsSyncRoot;

    @Value("${app.kb.docs-sync.library-code:default}")
    private String docsSyncLibraryCode;

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
    private static final String SOURCE_SYNC = "SYNC";

    /** docs 同步仅处理的扩展名（docs 目录为纯 Markdown 知识库）。 */
    private static final Set<String> SYNC_EXTENSIONS = new HashSet<>(Arrays.asList("md", "markdown"));

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
                                 org.example.quota.QuotaService quotaService,
                             DocumentParser documentParser,
                             UrlFetcher urlFetcher,
                             org.example.service.AuditLogService auditLogService,
                             org.example.service.KbAlertService kbAlertService,
                             org.example.service.DocumentInfoCache docCache,
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
            auditLogService.record(libraryId, org.example.security.SecurityUtil.currentUsername(),
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
        if (SOURCE_SYNC.equals(doc.getSourceType())) {
            throw new IllegalArgumentException(
                    "docs 同步受管文档不支持在线编辑，请在源仓库修改后执行「同步 docs 内容」");
        }
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
        doc.setFilePath(fileName);
        doc.setSpace(deriveSpace(fileName));
        doc.setSourceType(SOURCE_UPLOAD);
        doc.setFileSize(file.getSize());
        doc.setContentHash(hash);
        doc.setContent(content);
        doc.setImportedBy(userId);
        doc.setLibraryId(libraryId);
        doc.setStatus(KbDocumentStatus.PENDING);
        documentRepository.save(doc);
        kbIndexer.indexDocument(doc, content);
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
        auditLogService.record(libraryId, userId, "IMPORT_URL", "DOCUMENT", doc.getId(), "URL 导入：" + url);
        return KbDocumentView.fromEntity(doc, false);
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
        docCache.evict(id);
        log.info("知识库文档已删除（按向量ID增量清理） - id: {}, title: {}, 分块数: {}", id, doc.getTitle(), chunkCount);
        auditLogService.record(libraryId, org.example.security.SecurityUtil.currentUsername(),
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
                } catch (org.example.exception.QuotaExceededException e) {
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
        auditLogService.record(libraryId, org.example.security.SecurityUtil.currentUsername(),
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

    /** 受管同步文档（sourceType=SYNC）。 */
    @Override
    public String syncDocs(String path, String userId, String libraryId) {
        Path root = Paths.get(path != null && !path.isBlank() ? path : docsSyncRoot).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("同步目录不存在: " + root);
        }
        assertImportPathAllowed(root);
        auditLogService.record(libraryId, userId, "DOCS_SYNC", "DIR", null, "docs 目录同步：" + root);
        return startTask(libraryId, view -> runSync(view, root, userId, libraryId));
    }

    /** docs 同步扫描的单文件快照。 */
    private record SyncFile(String relPath, String content, String hash, long size, String fileName) {
    }

    /**
     * docs 目录对账（与目录导入同语义并扩展失效清理）：
     * <ul>
     *   <li>快照中的新路径 → 新建并索引（sourceType=SYNC）；</li>
     *   <li>路径未变内容变化 → 复用 docId 重建分块与向量；内容未变 → 幂等跳过；</li>
     *   <li>受管文档路径消失但内容 hash 命中快照 → 判定为改名/移动，原地更新路径元数据并重建索引；</li>
     *   <li>受管文档路径与内容 hash 均消失 → 删除分块与 Redis 向量（文档已不存在于 docs 目录）；</li>
     *   <li>历史目录导入（sourceType 空/DIRECTORY）的同路径文档被收编为受管，后续随目录对账。</li>
     * </ul>
     * 与 runImport 的差异：不做跨路径内容去重（docs 树中同内容多路径应各自存在），
     * 仅处理 Markdown，跳过隐藏目录与 images 资源目录。
     */
    private void runSync(KbImportTaskView view, Path root, String actor, String libraryId) {
        LinkedHashMap<String, SyncFile> current = new LinkedHashMap<>();
        HashMap<String, String> hashToPath = new HashMap<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                  .filter(p -> isSyncable(root, p))
                  .sorted(Comparator.comparing(Path::toString))
                  .forEach(file -> {
                      try {
                          byte[] bytes = Files.readAllBytes(file);
                          if (bytes.length > maxFileSize) {
                              view.getErrors().add(file + ": 文件过大已跳过");
                              view.setFailed(view.getFailed() + 1);
                              return;
                          }
                          String content = new String(bytes, StandardCharsets.UTF_8);
                          String hash = sha256(content);
                          String relPath = root.relativize(file.toAbsolutePath()).toString().replace('\\', '/');
                          if (current.put(relPath, new SyncFile(relPath, content, hash, bytes.length,
                                  file.getFileName().toString())) == null) {
                              hashToPath.putIfAbsent(hash, relPath);
                          }
                      } catch (Exception e) {
                          view.getErrors().add(file + ": 读取失败 " + e.getMessage());
                          view.setFailed(view.getFailed() + 1);
                      }
                  });
        } catch (IOException e) {
            log.error("docs 同步扫描失败", e);
            view.setStatus("FAILED");
            view.getErrors().add("扫描目录失败: " + e.getMessage());
            return;
        }
        view.setTotal(current.size());

        List<KnowledgeDocumentEntity> managed = documentRepository.findAllByLibraryIdAndSourceType(libraryId, SOURCE_SYNC);
        Map<String, KnowledgeDocumentEntity> byPath = new HashMap<>();
        for (KnowledgeDocumentEntity d : managed) {
            byPath.put(d.getFilePath(), d);
        }
        Set<Long> handled = new HashSet<>();

        // 1) 改名/移动：路径消失但内容 hash 命中快照 → 原地更新路径元数据并重建索引
        int moved = 0;
        for (KnowledgeDocumentEntity doc : managed) {
            if (current.containsKey(doc.getFilePath())) {
                continue;
            }
            String target = hashToPath.get(doc.getContentHash());
            if (target == null || byPath.containsKey(target)) {
                continue;
            }
            try {
                SyncFile sf = current.get(target);
                doc.setFilePath(target);
                doc.setFileName(sf.fileName());
                doc.setSpace(deriveSpace(target));
                doc.setTitle(resolveTitle(sf.content(), null, sf.fileName()));
                doc.setSiteSlug(resolveSiteSlug(sf.content()));
                doc.setCategory(resolveCategory(sf.content()));
                doc.setContent(sf.content());
                doc.setContentHash(sf.hash());
                doc.setFileSize(sf.size());
                docCache.evict(doc.getId());
                kbIndexer.indexDocument(doc, sf.content());
                handled.add(doc.getId());
                moved++;
                view.setSucceeded(view.getSucceeded() + 1);
                view.setProcessed(view.getProcessed() + 1);
            } catch (Exception e) {
                view.setFailed(view.getFailed() + 1);
                if (view.getErrors().size() < 100) {
                    view.getErrors().add(doc.getFilePath() + " → " + target + ": " + e.getMessage());
                }
                log.warn("docs 同步改名失败 - old: {}, new: {}, err: {}", doc.getFilePath(), target, e.getMessage());
            }
        }

        // 2) 逐文件对账：新增 / 内容更新 / 幂等跳过（并收编存量目录导入文档）
        int created = 0, updated = 0, skipped = 0;
        for (SyncFile sf : current.values()) {
            KnowledgeDocumentEntity doc = byPath.get(sf.relPath());
            if (doc == null) {
                KnowledgeDocumentEntity legacy = documentRepository
                        .findByLibraryIdAndFilePath(libraryId, sf.relPath()).orElse(null);
                if (legacy != null) {
                    if (handled.contains(legacy.getId())) {
                        // 该路径是第 1 步改名后的新路径：docId 已原地落库并计数（moved/succeeded/processed），
                        // 这里再按"新路径新建"或"冲突"处理都会重复计数/误报失败，直接幂等跳过。
                        continue;
                    }
                    if (legacy.getSourceType() == null || SOURCE_DIRECTORY.equals(legacy.getSourceType())) {
                        doc = legacy; // 收编为同步受管
                    } else {
                        view.getErrors().add(sf.relPath() + ": 与既有 " + legacy.getSourceType() + " 文档路径冲突，已跳过");
                        view.setFailed(view.getFailed() + 1);
                        view.setProcessed(view.getProcessed() + 1);
                        continue;
                    }
                }
            }
            try {
                if (doc != null) {
                    if (sf.hash().equals(doc.getContentHash())) {
                        // 内容未变也做轻量元数据回填：category 等新增字段靠对账补齐存量数据（免重建索引）
                        String fmCategory = resolveCategory(sf.content());
                        if (!SOURCE_SYNC.equals(doc.getSourceType())
                                || !java.util.Objects.equals(fmCategory, doc.getCategory())) {
                            doc.setSourceType(SOURCE_SYNC);
                            doc.setCategory(fmCategory);
                            documentRepository.save(doc);
                        }
                        skipped++;
                    } else {
                        doc.setContent(sf.content());
                        doc.setContentHash(sf.hash());
                        doc.setFileSize(sf.size());
                        doc.setTitle(resolveTitle(sf.content(), null, sf.fileName()));
                        doc.setSiteSlug(resolveSiteSlug(sf.content()));
                        doc.setCategory(resolveCategory(sf.content()));
                        doc.setSourceType(SOURCE_SYNC);
                        docCache.evict(doc.getId());
                        kbIndexer.indexDocument(doc, sf.content());
                        updated++;
                    }
                    view.setSucceeded(view.getSucceeded() + 1);
                } else {
                    try {
                        quotaService.assertDocumentQuota(libraryId);
                    } catch (org.example.exception.QuotaExceededException e) {
                        view.getErrors().add(sf.relPath() + ": " + e.getMessage());
                        view.setFailed(view.getFailed() + 1);
                        view.setProcessed(view.getProcessed() + 1);
                        continue;
                    }
                    KnowledgeDocumentEntity created2 = new KnowledgeDocumentEntity();
                    created2.setTitle(resolveTitle(sf.content(), null, sf.fileName()));
                    created2.setSiteSlug(resolveSiteSlug(sf.content()));
                    created2.setCategory(resolveCategory(sf.content()));
                    created2.setFileName(sf.fileName());
                    created2.setFilePath(sf.relPath());
                    created2.setSpace(deriveSpace(sf.relPath()));
                    created2.setFileSize(sf.size());
                    created2.setContentHash(sf.hash());
                    created2.setContent(sf.content());
                    created2.setImportedBy(actor);
                    created2.setLibraryId(libraryId);
                    created2.setStatus(KbDocumentStatus.PENDING);
                    created2.setSourceType(SOURCE_SYNC);
                    kbIndexer.indexDocument(created2, sf.content());
                    created++;
                    view.setSucceeded(view.getSucceeded() + 1);
                }
            } catch (Exception e) {
                view.setFailed(view.getFailed() + 1);
                if (view.getErrors().size() < 100) {
                    view.getErrors().add(sf.relPath() + ": " + e.getMessage());
                }
                log.warn("docs 同步单文件失败 - rel: {}, err: {}", sf.relPath(), e.getMessage());
            } finally {
                view.setProcessed(view.getProcessed() + 1);
            }
        }

        // 3) 删除：受管文档路径与内容 hash 均已从快照消失（文档已不在 docs 目录）
        int deleted = 0;
        for (KnowledgeDocumentEntity doc : managed) {
            if (handled.contains(doc.getId()) || current.containsKey(doc.getFilePath())) {
                continue;
            }
            try {
                java.util.List<String> vectorIds = chunkRepository.findVectorIdsByDocId(doc.getId());
                transactionTemplate.executeWithoutResult(tx -> {
                    chunkRepository.deleteByDocId(doc.getId());
                    documentRepository.delete(doc);
                });
                if (vectorIds != null && !vectorIds.isEmpty()) {
                    try {
                        kbEmbeddingStore.removeAll(vectorIds);
                    } catch (Exception e) {
                        log.warn("docs 同步删除向量失败 - docId: {}, err: {}", doc.getId(), e.getMessage());
                    }
                }
                docCache.evict(doc.getId());
                deleted++;
                view.setProcessed(view.getProcessed() + 1);
            } catch (Exception e) {
                view.setFailed(view.getFailed() + 1);
                log.warn("docs 同步删除失败 - id: {}, err: {}", doc.getId(), e.getMessage());
            }
        }
        // 4) 收尾：历史目录导入（sourceType 为空）且路径已不在快照的失效条目一并清理
        //    （上传/URL 文档不受影响：其 filePath 为裸文件名或 URL，不含路径分隔符）
        int legacyDeleted = 0;
        for (KnowledgeDocumentEntity doc : documentRepository.findAllByLibraryIdAndSourceTypeIsNull(libraryId)) {
            if (doc.getFilePath() == null || !doc.getFilePath().contains("/")
                    || current.containsKey(doc.getFilePath())) {
                continue;
            }
            try {
                java.util.List<String> vectorIds = chunkRepository.findVectorIdsByDocId(doc.getId());
                transactionTemplate.executeWithoutResult(tx -> {
                    chunkRepository.deleteByDocId(doc.getId());
                    documentRepository.delete(doc);
                });
                if (vectorIds != null && !vectorIds.isEmpty()) {
                    try {
                        kbEmbeddingStore.removeAll(vectorIds);
                    } catch (Exception e) {
                        log.warn("docs 同步清理存量向量失败 - docId: {}, err: {}", doc.getId(), e.getMessage());
                    }
                }
                docCache.evict(doc.getId());
                legacyDeleted++;
                view.setProcessed(view.getProcessed() + 1);
            } catch (Exception e) {
                view.setFailed(view.getFailed() + 1);
                log.warn("docs 同步清理存量条目失败 - id: {}, err: {}", doc.getId(), e.getMessage());
            }
        }
        deleted += legacyDeleted;
        view.setStatus("DONE");
        log.info("docs 目录同步完成 - root: {}, library: {}, 快照: {}, 新增: {}, 更新: {}, 改名: {}, 删除: {} (含存量失效 {}), 跳过: {}, 失败: {}",
                root, libraryId, current.size(), created, updated, moved, deleted, legacyDeleted, skipped, view.getFailed());
        auditLogService.record(libraryId, actor, "DOCS_SYNC", "DIR", null,
                "docs 同步完成：新增 " + created + "，更新 " + updated + "，改名 " + moved + "，删除 " + deleted + "，跳过 " + skipped);
        if (view.getFailed() > 0) {
            kbAlertService.raise(libraryId, "DOCS_SYNC_FAILED", "WARN",
                    "docs 同步存在 " + view.getFailed() + " 条失败，请检查同步目录",
                    String.join("\n", view.getErrors().stream().limit(10).toList()));
        }
    }

    /** docs 同步仅认 Markdown；跳过隐藏目录/文件与 images 资源目录。 */
    private boolean isSyncable(Path root, Path file) {
        if (!SYNC_EXTENSIONS.contains(extensionOf(file.getFileName().toString()))) {
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
        long indexed = documentRepository.countByStatusAndLibraryId(KbDocumentStatus.INDEXED, libraryId);
        long pending = documentRepository.countByStatusAndLibraryId(KbDocumentStatus.PENDING, libraryId);
        long failed = documentRepository.countByStatusAndLibraryId(KbDocumentStatus.FAILED, libraryId);
        return KbStatsView.builder()
                .totalDocuments(documentRepository.countByLibraryId(libraryId))
                .totalChunks(chunkRepository.countByLibraryId(libraryId))
                .indexedDocuments(indexed)
                .pendingDocuments(pending)
                .failedDocuments(failed)
                .totalVectorCount(chunkRepository.countByLibraryId(libraryId))
                .build();
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
}
