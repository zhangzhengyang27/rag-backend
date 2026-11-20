package org.example.service.impl;

import org.example.entity.KnowledgeDocumentEntity;
import org.example.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** 内容 Lint 口径：相对链接断链可检出，外链/站点绝对路由不误报，同名标题按篇计数。 */
@ExtendWith(MockitoExtension.class)
class KbContentLintServiceImplTest {

    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @InjectMocks
    private KbContentLintServiceImpl service;

    private KnowledgeDocumentEntity doc(Long id, String title, String path, String content) {
        KnowledgeDocumentEntity d = new KnowledgeDocumentEntity();
        d.setId(id);
        d.setTitle(title);
        d.setFilePath(path);
        d.setContent(content);
        return d;
    }

    @Test
    void detectsBrokenRelativeLinkButIgnoresExternalAndAbsolute() {
        when(documentRepository.findAllByLibraryId("default")).thenReturn(List.of(
                doc(1L, "A", "a/one.md", "[ok](two.md) [bad](../x/missing.md) [ext](https://x.com) [route](/docs/a)"),
                doc(2L, "B", "a/two.md", "正文")));

        var report = service.lint("default", 200);

        assertThat(report.getDocCount()).isEqualTo(2);
        assertThat(report.getLinkCount()).isEqualTo(2); // 只统计 ok + bad 两个相对链接
        assertThat(report.getBrokenLinkCount()).isEqualTo(1);
        assertThat(report.getIssues()).anySatisfy(i -> {
            assertThat(i.getType()).isEqualTo("BROKEN_LINK");
            assertThat(i.getDetail()).contains("../x/missing.md");
        });
    }

    @Test
    void directoryLinksWithOrWithoutSlashAreNotBrokenWhenDirExists() {
        when(documentRepository.findAllByLibraryId("default")).thenReturn(List.of(
                doc(1L, "A", "a/one.md",
                        "[slash](../b/) [noSlash](../b) [file](../b/x.md) [missing](../b/nope.md)"),
                doc(2L, "B", "b/x.md", "正文")));

        var report = service.lint("default", 200);

        // 目录链接（带/不带斜杠）与真实文件链接都应有效；只有不存在的文件才算断链。
        assertThat(report.getLinkCount()).isEqualTo(4);
        assertThat(report.getBrokenLinkCount()).isEqualTo(1);
        assertThat(report.getIssues()).anySatisfy(i -> {
            assertThat(i.getType()).isEqualTo("BROKEN_LINK");
            assertThat(i.getDetail()).contains("../b/nope.md");
        });
    }

    @Test
    void omittedMdExtensionWorksWhenFilenameContainsDot() {
        when(documentRepository.findAllByLibraryId("default")).thenReturn(List.of(
                doc(1L, "A", "a/one.md", "[next](../b/12-Next.jsAppRouter数据加载)"),
                doc(2L, "B", "b/12-Next.jsAppRouter数据加载.md", "正文")));

        var report = service.lint("default", 200);

        assertThat(report.getLinkCount()).isEqualTo(1);
        assertThat(report.getBrokenLinkCount()).isZero();
    }

    @Test
    void duplicateTitlesCountPerDocument() {
        when(documentRepository.findAllByLibraryId("default")).thenReturn(List.of(
                doc(1L, "系统架构", "a/one.md", "x"),
                doc(2L, "系统架构", "a/two.md", "y"),
                doc(3L, "独立标题", "c/three.md", "z")));

        var report = service.lint("default", 200);

        assertThat(report.getDuplicateTitleCount()).isEqualTo(2);
        assertThat(report.getPrSummary()).contains("同名文档 2 篇");
        assertThat(report.getPrSummary()).contains("不会自动修改文档");
    }
}
