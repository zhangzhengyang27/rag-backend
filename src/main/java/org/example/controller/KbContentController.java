package org.example.controller;

import org.example.dto.ContentDocView;
import org.example.dto.ContentIndexItem;
import org.example.dto.ContentIndexView;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.library.KbLibrary;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.util.CmsMarkdown;
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
 * <p>与 /admin/kb/cms 的边界：这里只读、免鉴权、只暴露已发布 CMS 内容；
 * 管理面（草稿、版本、导入、删除）仍留在 /admin/**。路径参数使用 docs 相对路径，
 * 因为它是 CMS 自导入起就稳定存在的业务主键，前端 URL 由 categorySlug + docSlug 另行生成。</p>
 */
@RestController
@RequestMapping("/content")
public class KbContentController {

    private static final String SOURCE_CMS = "CMS";
    private static final String PUBLISHED = "PUBLISHED";

    private final KnowledgeDocumentRepository documentRepository;

    public KbContentController(KnowledgeDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    /** 全站导航索引（约 4.5k 条，单次请求约 2MB；前端与 Next 数据缓存共同兜底）。 */
    @GetMapping("/index")
    public ResponseEntity<ContentIndexView> index() {
        List<ContentIndexItem> docs = documentRepository.findPublishedContentIndex(
                KbLibrary.DEFAULT_LIBRARY, SOURCE_CMS, PUBLISHED);
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
                .findPublishedByCategory(KbLibrary.DEFAULT_LIBRARY, SOURCE_CMS, PUBLISHED, slug)
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

        // 双保险：即使误传了草稿/非 CMS 路径，也不能从公开接口泄露未发布内容
        if (!SOURCE_CMS.equals(doc.getSourceType()) || !PUBLISHED.equals(doc.getPublishStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在");
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(60, TimeUnit.SECONDS).cachePublic())
                .body(toView(doc));
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
