package org.example.service.impl;

import org.example.dto.KbFavoriteView;
import org.example.entity.KbFavoriteEntity;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.repository.KbFavoriteRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收藏服务单测：幂等收藏/取消、孤儿条目机会式清理、文档不存在拒绝。
 */
@ExtendWith(MockitoExtension.class)
class KbFavoriteServiceImplTest {

    @Mock
    private KbFavoriteRepository favoriteRepository;
    @Mock
    private KnowledgeDocumentRepository documentRepository;

    @InjectMocks
    private KbFavoriteServiceImpl service;

    private KnowledgeDocumentEntity doc(long id) {
        KnowledgeDocumentEntity d = new KnowledgeDocumentEntity();
        d.setId(id);
        d.setTitle("文档" + id);
        d.setFilePath("04-前端/x" + id + ".md");
        d.setSpace("前端");
        d.setLibraryId("default");
        return d;
    }

    private KbFavoriteEntity fav(long docId, Date created) {
        KbFavoriteEntity f = new KbFavoriteEntity();
        f.setUsername("alice");
        f.setDocId(docId);
        f.setLibraryId("default");
        f.setCreatedDate(created);
        return f;
    }

    @Test
    void listJoinsDocInfoAndCleansOrphans() {
        PageRequest page = PageRequest.of(0, 20);
        KbFavoriteEntity alive = fav(1L, new Date(1000L));
        KbFavoriteEntity orphan = fav(2L, new Date(2000L));
        when(favoriteRepository.findByUsernameOrderByIdDesc("alice", page))
                .thenReturn(new PageImpl<>(List.of(alive, orphan), page, 2));
        when(documentRepository.findAllById(any())).thenReturn(List.of(doc(1L)));

        var result = service.list("alice", page);

        assertThat(result.getContent()).hasSize(1);
        KbFavoriteView view = result.getContent().get(0);
        assertThat(view.docId()).isEqualTo(1L);
        assertThat(view.title()).isEqualTo("文档1");
        verify(favoriteRepository).deleteByUsernameAndDocId("alice", 2L);
    }

    @Test
    void addIsIdempotentAndRejectsMissingDoc() {
        when(documentRepository.findById(1L)).thenReturn(Optional.of(doc(1L)));
        when(favoriteRepository.findByUsernameAndDocId("alice", 1L))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(fav(1L, new Date(1000L))));
        when(favoriteRepository.save(any(KbFavoriteEntity.class))).thenAnswer(inv -> {
            KbFavoriteEntity e = inv.getArgument(0);
            e.setCreatedDate(new Date(1000L));
            return e;
        });

        service.add("alice", 1L);
        // 第二次收藏命中既有条目，不再插入
        service.add("alice", 1L);
        verify(favoriteRepository, org.mockito.Mockito.times(1)).save(any(KbFavoriteEntity.class));

        when(documentRepository.findById(9L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.add("alice", 9L)).hasMessageContaining("文档不存在");
    }

    @Test
    void removeIsIdempotent() {
        service.remove("alice", 1L);
        verify(favoriteRepository).deleteByUsernameAndDocId("alice", 1L);
        // 未收藏时同样无异常（仓库 delete 本就幂等）
        verify(favoriteRepository, never()).findById(1L);
    }
}
