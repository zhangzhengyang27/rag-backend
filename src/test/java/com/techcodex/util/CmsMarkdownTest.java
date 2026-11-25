package com.techcodex.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CmsMarkdownTest {

    @Test
    void splitAndMergePreserveBody() {
        String raw = "---\ntitle: \"标题\"\ndescription: \"摘要\"\n---\n\n# 正文\n\n内容\n";
        CmsMarkdown.Parts parts = CmsMarkdown.split(raw);
        assertEquals("---\ntitle: \"标题\"\ndescription: \"摘要\"\n---\n", parts.frontmatter());
        assertEquals("\n# 正文\n\n内容\n", parts.body());
        assertEquals(raw, CmsMarkdown.merge(parts.frontmatter(), parts.body()));
    }

    @Test
    void splitWithoutFrontmatterKeepsWholeAsBody() {
        CmsMarkdown.Parts parts = CmsMarkdown.split("# 没有元数据\n正文");
        assertEquals("", parts.frontmatter());
        assertEquals("# 没有元数据\n正文", parts.body());
    }

    @Test
    void setScalarReplacesAndInserts() {
        String fm = "---\ntitle: \"旧标题\"\ntags: [a, b]\n---\n";
        String updated = CmsMarkdown.setScalar(fm, "title", "新标题");
        assertEquals("新标题", CmsMarkdown.getScalar(updated, "title"));
        assertTrue(updated.contains("tags: [a, b]"));

        String inserted = CmsMarkdown.setScalar(fm, "description", "摘要");
        assertEquals("摘要", CmsMarkdown.getScalar(inserted, "description"));
        assertTrue(inserted.indexOf("description:") < inserted.lastIndexOf("---"));
    }

    @Test
    void setScalarRemovesWhenValueNull() {
        String fm = "---\ntitle: \"旧标题\"\ndescription: \"旧摘要\"\n---\n";
        String updated = CmsMarkdown.setScalar(fm, "description", null);
        assertNull(CmsMarkdown.getScalar(updated, "description"));
        assertEquals("旧标题", CmsMarkdown.getScalar(updated, "title"));
    }

    @Test
    void quotesAndBackslashesRoundTrip() {
        String fm = CmsMarkdown.buildFrontmatter("带\"引号\"", "反斜杠 \\ 路径");
        assertEquals("带\"引号\"", CmsMarkdown.getScalar(fm, "title"));
        assertEquals("反斜杠 \\ 路径", CmsMarkdown.getScalar(fm, "description"));
    }

    @Test
    void resolveTitleFallsBackToHeadingThenFileName() {
        assertEquals("元数据标题", CmsMarkdown.resolveTitle("---\ntitle: \"元数据标题\"\n---\n# 正文标题", "x.md"));
        assertEquals("正文标题", CmsMarkdown.resolveTitle("---\ntags: [a]\n---\n# 正文标题", "x.md"));
        assertEquals("x", CmsMarkdown.resolveTitle("正文", "x.md"));
    }
}
