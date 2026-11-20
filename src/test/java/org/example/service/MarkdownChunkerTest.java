package org.example.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MarkdownChunker 单测：覆盖首期最关键的三个回归点——
 * 代码块不能丢、标题路径要正确、frontmatter 不能进检索正文。
 */
class MarkdownChunkerTest {

    @Test
    void keepsFencedCodeAndLanguage() {
        String markdown = """
                # HashMap

                扩容时会重新计算索引。

                ```java
                public class HashMapDemo {
                    void put() {
                        map.put("k", "v");
                    }
                }
                ```

                以上就是示例。
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo(MarkdownChunker.TYPE_CODE);
            assertThat(chunk.codeLang()).isEqualTo("java");
            assertThat(chunk.text()).contains("public class HashMapDemo");
        });
        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo(MarkdownChunker.TYPE_TEXT);
            assertThat(chunk.text()).contains("扩容时会重新计算索引");
        });
    }

    @Test
    void headingPathTracksNesting() {
        String markdown = """
                # Java

                ## 集合

                ### HashMap

                正文内容。
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).hasSize(1);
        MarkdownChunker.Chunk chunk = chunks.get(0);
        assertThat(chunk.headingPath()).isEqualTo("Java > 集合 > HashMap");
        assertThat(chunk.heading()).isEqualTo("HashMap");
        assertThat(chunk.anchor()).isEqualTo("hashmap");
        assertThat(chunk.headingOrdinal()).isEqualTo(3);
    }

    @Test
    void headingAnchorRemovesPunctuationAndDeduplicates() {
        String markdown = """
                # 1. 快速开始

                正文一。

                ## 1. 快速开始

                正文二。
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(0).anchor()).isEqualTo("1-快速开始");
        assertThat(chunks.get(1).anchor()).isEqualTo("1-快速开始-1");
    }

    @Test
    void hashInsideCodeFenceIsNotHeading() {
        String markdown = """
                # 标题

                ```bash
                # 这是注释，不是标题
                echo hi
                ```
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).chunkType()).isEqualTo(MarkdownChunker.TYPE_CODE);
        assertThat(chunks.get(0).headingPath()).isEqualTo("标题");
        assertThat(chunks.get(0).text()).contains("# 这是注释");
    }

    @Test
    void skipsYamlFrontmatter() {
        String markdown = """
                ---
                title: 测试文档
                slug: /docs/test
                ---

                # 正文标题

                正文内容。
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).doesNotContain("title: 测试文档");
        assertThat(chunks.get(0).text()).contains("正文内容");
        assertThat(chunks.get(0).headingPath()).isEqualTo("正文标题");
    }

    @Test
    void emptyDocumentReturnsEmpty() {
        assertThat(MarkdownChunker.chunk("", 500, 60)).isEmpty();
        assertThat(MarkdownChunker.chunk("   \n\n  ", 500, 60)).isEmpty();
    }

    @Test
    void longProseIsSplitWithinLimit() {
        String sentence = "这是一句用于测试分块长度的技术说明。";
        String markdown = "# 长文\n\n" + sentence.repeat(120);
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk ->
                assertThat(chunk.text().length()).isLessThanOrEqualTo(500));
    }

    @Test
    void inlineLinkKeepsTextAndDropsUrl() {
        String markdown = "# 链接\n\n参考 [官方文档](/docs/java/hashmap) 的实现。";
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("官方文档");
        assertThat(chunks.get(0).text()).doesNotContain("/docs/java/hashmap");
    }

    @Test
    void longCodeBlockSplitsWithoutLosingContent() {
        StringBuilder code = new StringBuilder("```java\n");
        for (int i = 0; i < 40; i++) {
            code.append("int value").append(i).append(" = ").append(i).append(";\n");
        }
        code.append("```\n");
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk("# 代码\n\n" + code, 200, 20);

        assertThat(chunks).isNotEmpty();
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.chunkType()).isEqualTo(MarkdownChunker.TYPE_CODE);
            assertThat(chunk.codeLang()).isEqualTo("java");
        });
        assertThat(chunks.stream().map(MarkdownChunker.Chunk::text).reduce("", String::concat))
                .contains("int value0 = 0;")
                .contains("int value39 = 39;");
    }
    @Test
    void tableRowsStayTogetherAndKeepHeader() {
        String markdown = """
                # 对比

                下表是三种盒模型：

                | 类型 | box-sizing | 宽度含义 |
                | --- | --- | --- |
                | 标准盒模型 | content-box | 只含 content |
                | 替代盒模型 | border-box | 含 padding/border |

                结论见上表。
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).anySatisfy(chunk -> {
            assertThat(chunk.text()).contains("| 类型 | box-sizing | 宽度含义 |");
            assertThat(chunk.text()).contains("| 替代盒模型 | border-box | 含 padding/border |");
        });
    }

    @Test
    void setextHeadingBecomesHeadingPath() {
        String markdown = """
                快速开始
                ===

                正文内容。

                安装步骤
                ---

                第二步正文。
                """;
        List<MarkdownChunker.Chunk> chunks = MarkdownChunker.chunk(markdown, 500, 60);

        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.headingPath()).isEqualTo("快速开始"));
        assertThat(chunks).anySatisfy(chunk ->
                assertThat(chunk.headingPath()).isEqualTo("快速开始 > 安装步骤"));
    }

}
