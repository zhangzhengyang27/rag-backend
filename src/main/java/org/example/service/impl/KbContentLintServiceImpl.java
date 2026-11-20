package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.service.KbContentLintService;
import org.example.vo.KbLintIssueView;
import org.example.vo.KbLintReportView;
import org.springframework.stereotype.Service;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内容 Lint 实现：扫描空间内全部 Markdown，检查相对链接断链与同名标题。
 *
 * <p>口径边界：只检查相对路径（GitHub 风格 .md）；以 /、#、http(s)、mailto 开头的链接
 * 属于站点路由/外链，交给前端路由或外部站点，不在本 Lint 里制造假阳性。
 * 同名标题只提示，不阻断发布；真正的合并/改名必须由作者确认。</p>
 */
@Slf4j
@Service
public class KbContentLintServiceImpl implements KbContentLintService {

    /** Markdown 行内链接；group1 为图片感叹号（图片按资源处理，不参与文档断链），group2 为目标。 */
    private static final Pattern LINK = Pattern.compile("(!?)\\[[^\\]]*\\]\\(\\s*([^)\\s]+)(?:\\s+\"[^\"]*\")?\\s*\\)");

    private static final int PR_SUMMARY_LIMIT = 20;

    private final KnowledgeDocumentRepository documentRepository;

    public KbContentLintServiceImpl(KnowledgeDocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    @Override
    public KbLintReportView lint(String libraryId, int limit) {
        int cap = Math.max(1, Math.min(limit, 500));
        List<KnowledgeDocumentEntity> docs = documentRepository.findAllByLibraryId(libraryId);
        Map<String, KnowledgeDocumentEntity> byPath = new HashMap<>();
        Set<String> dirs = new HashSet<>();
        for (KnowledgeDocumentEntity d : docs) {
            if (d.getFilePath() != null) {
                String path = normalize(d.getFilePath());
                byPath.put(path, d);
                int slash = path.indexOf('/');
                while (slash > 0) {
                    dirs.add(path.substring(0, slash));
                    slash = path.indexOf('/', slash + 1);
                }
            }
        }

        List<KbLintIssueView> broken = new ArrayList<>();
        int linkCount = 0;
        for (KnowledgeDocumentEntity d : docs) {
            if (d.getContent() == null || d.getFilePath() == null) {
                continue;
            }
            Matcher m = LINK.matcher(stripCode(d.getContent()));
            while (m.find()) {
                if ("!".equals(m.group(1))) {
                    continue; // 图片是资源文件，不在 kb_document 路径表里，避免误判断链
                }
                String raw = m.group(2).trim().replaceAll("^<|>$", "");
                if (isExternal(raw)) {
                    continue;
                }
                linkCount++;
                String target = resolveLink(d.getFilePath(), raw);
                if (target == null || exists(byPath, dirs, target)) {
                    continue;
                }
                broken.add(KbLintIssueView.builder()
                        .docId(d.getId())
                        .type(KbLintIssueView.BROKEN_LINK)
                        .title(d.getTitle())
                        .filePath(d.getFilePath())
                        .detail("链接目标不存在：" + raw)
                        .suggestion("确认目标是否改名/移动；若已迁移，把 " + raw + " 更新为实际相对路径，或删除该链接")
                        .owner(d.getOwner())
                        .build());
            }
        }

        // 同名标题：按规范化标题分组，只输出组内文档（每篇各一条，便于按文档定位）。
        Map<String, List<KnowledgeDocumentEntity>> byTitle = new HashMap<>();
        for (KnowledgeDocumentEntity d : docs) {
            String title = d.getTitle() == null ? "" : d.getTitle().trim().toLowerCase(Locale.ROOT);
            if (!title.isEmpty()) {
                // 同名标题只在同一顶层项目桶内算重复：跨项目都叫“00-导航”是正常结构，
                // 全站统一计数会把 300+ 篇正常导航误报成重复。
                String bucket = parentDir(d.getFilePath());
                byTitle.computeIfAbsent(bucket + "|" + title, k -> new ArrayList<>()).add(d);
            }
        }
        List<KbLintIssueView> duplicates = new ArrayList<>();
        for (List<KnowledgeDocumentEntity> group : byTitle.values()) {
            if (group.size() < 2) {
                continue;
            }
            String paths = group.stream().limit(3).map(KnowledgeDocumentEntity::getFilePath)
                    .reduce((a, b) -> a + "、" + b).orElse("");
            for (KnowledgeDocumentEntity d : group) {
                duplicates.add(KbLintIssueView.builder()
                        .docId(d.getId())
                        .type(KbLintIssueView.DUPLICATE_TITLE)
                        .title(d.getTitle())
                        .filePath(d.getFilePath())
                        .detail("同名文档 " + group.size() + " 篇：" + paths)
                        .suggestion("确认是否为同一主题的重复稿；保留一篇并在另一篇顶部加 canonical 跳转，或改用更精确的标题")
                        .owner(d.getOwner())
                        .build());
            }
        }

        List<KbLintIssueView> issues = new ArrayList<>();
        broken.stream().limit(cap).forEach(issues::add);
        if (issues.size() < cap) {
            duplicates.stream().limit(cap - issues.size()).forEach(issues::add);
        }

        // 断链责任人聚合：同一团队队列的问题一次派给一个人，避免 64 条逐条指派。
        Map<String, Integer> brokenByOwner = new java.util.TreeMap<>();
        for (KbLintIssueView issue : broken) {
            String owner = issue.getOwner() == null || issue.getOwner().isBlank() ? "UNASSIGNED" : issue.getOwner();
            brokenByOwner.merge(owner, 1, Integer::sum);
        }

        String prSummary = buildPrSummary(broken, duplicates);
        log.info("内容 Lint 完成 - library: {}, docs: {}, links: {}, broken: {}, duplicateTitles: {}",
                libraryId, docs.size(), linkCount, broken.size(), duplicates.size());
        return KbLintReportView.builder()
                .libraryId(libraryId)
                .docCount(docs.size())
                .linkCount(linkCount)
                .brokenLinkCount(broken.size())
                .duplicateTitleCount(duplicates.size())
                .brokenByOwner(brokenByOwner)
                .issues(issues)
                .prSummary(prSummary)
                .scannedAt(new Date())
                .build();
    }

    /** 去掉围栏代码与行内代码：代码里的 [a](b) 是示例/类型标注，不是文档链接。 */
    private static String stripCode(String content) {
        return content
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("(?s)~~~.*?~~~", " ")
                .replaceAll("`[^`\\n]*`", " ");
    }

    /** 同名标题只在同一目录内算重复；跨目录同名往往是不同章节的正常命名。 */
    private static String parentDir(String filePath) {
        if (filePath == null) {
            return "";
        }
        int idx = filePath.lastIndexOf('/');
        return idx > 0 ? filePath.substring(0, idx) : "";
    }

    /** 外链、锚点、站点绝对路由、任意 URI scheme 不参与相对路径断链检查。 */
    private static boolean isExternal(String raw) {
        if (raw.startsWith("#") || raw.startsWith("/")) {
            return true;
        }
        // 形如 https:/mailto:/hint: 的目标不是仓库相对路径；排除避免把富文本误报成断链。
        return raw.matches("^[A-Za-z][A-Za-z0-9+.-]*:.*");
    }

    /**
     * 把相对链接解析成文档库内的规范化路径；越界（../ 跳出仓库）返回 null。
     * 目录链接按 index.md 处理，与文档站路由习惯一致。
     */
    private static String resolveLink(String docPath, String raw) {
        String clean = raw.split("#", 2)[0].split("\\?", 2)[0].trim();
        if (clean.isEmpty()) {
            return null;
        }
        clean = clean.replace('\\', '/');
        try {
            // GitHub 风格链接常用 %20 表示空格，先解码才能和落盘文件名对齐。
            clean = URLDecoder.decode(clean, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        if (clean.chars().anyMatch(Character::isWhitespace)) {
            return null; // 未编码的空格属于非法链接，交由作者修，不做断链计数
        }
        Path parent = Paths.get(docPath).getParent();
        if (parent == null) {
            return null;
        }
        Path resolved = parent.resolve(clean).normalize();
        if (resolved.startsWith("..")) {
            return null;
        }
        String key = normalize(resolved.toString());
        if (clean.endsWith("/") || !key.contains(".")) {
            // 目录/无扩展名链接按文档站习惯找 index.md 或同名 .md，二者都不存在才算断链。
            return key + (clean.endsWith("/") ? "index.md" : ".md");
        }
        return key;
    }

    /** 目标存在判定：兼容“目录链接解析成同名 .md”与真实目录 index.md 两种落盘习惯。 */
    private static boolean exists(Map<String, KnowledgeDocumentEntity> byPath, Set<String> dirs, String target) {
        if (byPath.containsKey(target)) {
            return true;
        }
        if (!target.endsWith(".md")) {
            return false;
        }
        String withoutExt = target.substring(0, target.length() - 3);
        if (byPath.containsKey(withoutExt + "/index.md")) {
            return true;
        }
        // 目录链接：目录下只要有文档就算有效，不强制要求 index.md。
        return dirs.contains(withoutExt);
    }

    private static String normalize(String path) {
        return path.replace('\\', '/').replaceFirst("^\\./", "");
    }

    /** 生成可粘贴到 PR 描述的检查清单；只列前若干条，避免评论区刷屏。 */
    private String buildPrSummary(List<KbLintIssueView> broken, List<KbLintIssueView> duplicates) {
        StringBuilder sb = new StringBuilder("## 知识库内容 Lint 建议\n\n");
        Set<String> seen = new HashSet<>();
        int n = 0;
        for (KbLintIssueView issue : broken) {
            if (n++ >= PR_SUMMARY_LIMIT) {
                break;
            }
            seen.add(issue.getFilePath());
            sb.append("- [ ] `").append(issue.getFilePath()).append("`：").append(issue.getDetail()).append('\n');
        }
        for (KbLintIssueView issue : duplicates) {
            if (n++ >= PR_SUMMARY_LIMIT) {
                break;
            }
            if (seen.add(issue.getFilePath())) {
                sb.append("- [ ] `").append(issue.getFilePath()).append("`：").append(issue.getDetail()).append('\n');
            }
        }
        if (n == 0) {
            sb.append("本次扫描未发现断链或同名标题。\n");
        }
        sb.append("\n> 本清单仅提示，不会自动修改文档；请作者确认后手工提交 PR。");
        return sb.toString();
    }
}
