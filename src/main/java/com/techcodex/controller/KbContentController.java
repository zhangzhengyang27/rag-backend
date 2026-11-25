package com.techcodex.controller;

import lombok.extern.slf4j.Slf4j;
import com.techcodex.dto.ContentDocView;
import com.techcodex.dto.ContentIndexItem;
import com.techcodex.dto.ContentIndexView;
import com.techcodex.entity.KnowledgeDocumentEntity;
import com.techcodex.library.KbLibrary;
import com.techcodex.repository.KnowledgeDocumentRepository;
import com.techcodex.util.CmsMarkdown;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 站点公开内容 API：供 Next 前端在“无 Markdown 文件”模式下读取全站内容。
 *
 * <p>与 /admin/kb/cms 的边界：这里只读、免鉴权、只暴露“已发布”内容；
 * 管理面（草稿、版本、导入、删除）仍留在 /admin/**。路径参数使用 docs 相对路径，
 * 因为它是 CMS 自导入起就稳定存在的业务主键，前端 URL 由 categorySlug + docSlug 另行生成。</p>
 *
 * <p>来源白名单：CMS 是现行发布通道，SYNC 是启用 CMS 前由 docs 目录同步落库的历史内容。
 * CMS 启用后同步任务停用，但 CMS 尚未发布任何文档时 SYNC 就是站点唯一内容，
 * 因此读取层同时接受两者；两者都必须 publishStatus=PUBLISHED，草稿不会因来源放宽而泄露。</p>
 */
@Slf4j
@RestController
@RequestMapping("/content")
public class KbContentController {

    // 只发布 CMS 来源：docs 目录同步链路已于 2026-11-25 下线，存量文档整体转为 CMS，
    // 仍保留 SYNC 只会让"未清理干净的历史行"重新出现在站点上。
    private static final List<String> PUBLISHED_SOURCES = List.of("CMS");
    private static final String PUBLISHED = "PUBLISHED";
    /**
     * 索引体积告警阈值（3MB）。
     *
     * 为什么监控而不是分页：一次下发 4.5k 条元数据约 2MB，是前端构建导航树与
     * llms-full/sitemap 的前提；加分页会打断这条单次拉取链路。真正需要防的是
     * "投影退化成实体"或元数据异常膨胀，所以只对体积做趋势监控与阈值告警。
     */
    private static final long INDEX_SIZE_WARN_BYTES = 3L * 1024 * 1024;

    private final KnowledgeDocumentRepository documentRepository;

    public KbContentController(KnowledgeDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /** 全站导航索引（约 4.5k 条，单次请求约 2MB；前端与 Next 数据缓存共同兜底）。 */
    @GetMapping("/index")
    public ResponseEntity<ContentIndexView> index() {
        List<ContentIndexItem> docs = documentRepository.findPublishedContentIndex(
                KbLibrary.DEFAULT_LIBRARY, PUBLISHED_SOURCES, PUBLISHED);
        long approxBytes = estimateIndexBytes(docs);
        if (approxBytes > INDEX_SIZE_WARN_BYTES) {
            log.warn("内容索引体积超阈值 - docs: {}, approxBytes: {}, threshold: {}",
                    docs.size(), approxBytes, INDEX_SIZE_WARN_BYTES);
        } else {
            log.info("内容索引下发 - docs: {}, approxBytes: {}", docs.size(), approxBytes);
        }
        ContentIndexView view = ContentIndexView.builder()
                .total(docs.size())
                .generatedAt(new Date())
                .docs(docs)
                .build();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(view);
    }

    /** 单分类全量正文；供 /llms-full/{category}.txt 一次性生成，避免每篇一次 HTTP。 */
    @GetMapping("/category")
    public ResponseEntity<List<ContentDocView>> category(@RequestParam("slug") String slug) {
        if (slug == null || !slug.matches("^[a-z0-9][a-z0-9-]{0,63}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "slug 非法");
        }
        List<ContentDocView> docs = documentRepository
                .findPublishedByCategory(KbLibrary.DEFAULT_LIBRARY, PUBLISHED_SOURCES, PUBLISHED, slug)
                .stream()
                .map(this::toView)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(docs);
    }

    /** 单篇正文；path 必须是 docs 相对路径（如 01-Java/03-核心框架/02-JDBC/00-JDBC.md）。 */
    @GetMapping("/doc")
    public ResponseEntity<ContentDocView> doc(@RequestParam("path") String path) {
        String rel = normalizePath(path);
        KnowledgeDocumentEntity doc = documentRepository
                .findByLibraryIdAndFilePath(KbLibrary.DEFAULT_LIBRARY, rel)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在"));

        // 双保险：即使误传了草稿/站外来源的路径，也不能从公开接口泄露未发布内容
        if (!PUBLISHED_SOURCES.contains(doc.getSourceType()) || !PUBLISHED.equals(doc.getPublishStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(toView(doc));
    }

    /**
     * 索引体积估算。
     *
     * 为什么不用 ObjectMapper 实测：序列化一遍等于把响应体再拼一次，成本比监控本身还高。
     * 这里按元数据字符数 × 2（中文 UTF-8 约 2~3 字节的保守下限）+ 每条约 100 字节的
     * JSON 结构开销累加，只用于趋势与阈值判断，不追求与真实响应逐字节一致。
     */
    private long estimateIndexBytes(List<ContentIndexItem> docs) {
        long bytes = 0;
        for (ContentIndexItem d : docs) {
            bytes += 100;
            bytes += 2L * (len(d.getPath()) + len(d.getTitle()) + len(d.getDescription())
                    + len(d.getCategorySlug()) + len(d.getCategoryDir()) + len(d.getDocSlug()));
        }
        return bytes;
    }

    private static long len(String value) {
        return value == null ? 0 : value.length();
    }

    /** 实体 → 公开视图：统一剥离 frontmatter，标题/摘要做结构化字段兜底。 */
    private ContentDocView toView(KnowledgeDocumentEntity doc) {
        CmsMarkdown.Parts parts = CmsMarkdown.split(doc.getContent());
        String title = firstNonBlank(doc.getTitle(), CmsMarkdown.getScalar(parts.frontmatter(), "title"), doc.getFileName());
        return ContentDocView.builder()
                .title(title)
                .description(firstNonBlank(doc.getDescription(),
                        CmsMarkdown.getScalar(parts.frontmatter(), "description")))
                .path(doc.getFilePath())
                .categorySlug(doc.getCategorySlug())
                .categoryDir(doc.getCategoryDir())
                .docSlug(doc.getDocSlug())
                .body(parts.body())
                .frontmatter(parts.frontmatter())
                .revision(doc.getRevision())
                .publishedAt(doc.getPublishedAt())
                .updatedDate(doc.getUpdatedDate())
                .build();
    }

    /** 只接受相对路径，拒绝绝对路径与 .. 穿越；不做文件系统校验（DB-only 下也不存在文件）。 */
    private String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path 不能为空");
        }
        String value = raw.replace('\\', '/').replaceAll("/+", "/");
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        if (value.isBlank() || value.contains("../") || value.startsWith("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path 非法");
        }
        return value;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
