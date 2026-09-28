package org.example.tools;

import org.example.dto.KbDocumentView;
import org.example.entity.KbDocumentStatus;
import org.example.service.KbDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 知识库清单工具单测：按对话绑定取空间清单、未绑定/未注册对话 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class KbLibraryToolsTest {

    @Mock
    private KbDocumentService kbDocumentService;

    private KbLibraryTools tools;

    @BeforeEach
    void setUp() {
        tools = new KbLibraryTools(kbDocumentService);
    }

    @AfterEach
    void tearDown() {
        KbLibraryTools.unbind("conv-1");
        KbLibraryTools.unbind("conv-unknown");
    }

    private KbDocumentView doc(String title) {
        return KbDocumentView.builder()
                .id(1L).title(title).fileName("refactor-verify.md")
                .status(KbDocumentStatus.INDEXED).createdDate(new Date())
                .build();
    }

    @Test
    void listDocumentsReturnsBoundLibraryListing() {
        KbLibraryTools.bind("conv-1", List.of("u-alice"));
        var page = new PageImpl<>(List.of(doc("重构验证文档")), PageRequest.of(0, 10), 1);
        when(kbDocumentService.list(isNull(), isNull(), eq("u-alice"), any())).thenReturn(page);

        String out = tools.listDocuments("conv-1");

        assertThat(out).contains("个人知识库（u-alice）");
        assertThat(out).contains("共 1 篇");
        assertThat(out).contains("《重构验证文档》");
        assertThat(out).contains("已索引");
    }

    @Test
    void listDocumentsFailsClosedWithoutBinding() {
        String out = tools.listDocuments("conv-unknown");

        assertThat(out).contains("无法确认当前知识库空间");
    }

    @Test
    void unbindRemovesBinding() {
        KbLibraryTools.bind("conv-1", List.of("u-alice"));
        KbLibraryTools.unbind("conv-1");

        assertThat(tools.listDocuments("conv-1")).contains("无法确认当前知识库空间");
    }

    @Test
    void listingCoversEachBoundLibrary() {
        KbLibraryTools.bind("conv-1", List.of("default", "u-alice"));
        when(kbDocumentService.list(isNull(), isNull(), eq("default"), any()))
                .thenReturn(new PageImpl<>(List.of(doc("公共文档A")), PageRequest.of(0, 10), 1));
        when(kbDocumentService.list(isNull(), isNull(), eq("u-alice"), any()))
                .thenReturn(new PageImpl<>(List.of(doc("私有文档B")), PageRequest.of(0, 10), 1));

        String out = tools.listDocuments("conv-1");

        assertThat(out).contains("公共知识库（default）").contains("《公共文档A》");
        assertThat(out).contains("个人知识库（u-alice）").contains("《私有文档B》");
    }
}
