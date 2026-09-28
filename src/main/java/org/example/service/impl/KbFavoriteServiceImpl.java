package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.dto.KbFavoriteView;
import org.example.entity.KbFavoriteEntity;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.repository.KbFavoriteRepository;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.service.KbFavoriteService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文档收藏实现。
 * 孤儿清理策略：文档被删除后其收藏条目不再有展示意义，在列表查询时机会式删除
 * （避免在文档删除主链路里引入跨模块依赖）；分页 total 仍按原始收藏数计算，
 * 清理后下一页自然收敛，对用户无感。
 */
@Slf4j
@Service
public class KbFavoriteServiceImpl implements KbFavoriteService {

    private final KbFavoriteRepository favoriteRepository;
    private final KnowledgeDocumentRepository documentRepository;

    @org.springframework.beans.factory.annotation.Value("${app.kb.library.public-code:default}")
    private String publicLibrary;

    public KbFavoriteServiceImpl(KbFavoriteRepository favoriteRepository,
                                 KnowledgeDocumentRepository documentRepository) {
        this.favoriteRepository = favoriteRepository;
        this.documentRepository = documentRepository;
    }

    @Override
    @Transactional
    public Page<KbFavoriteView> list(String username, Pageable page) {
        Page<KbFavoriteEntity> favorites = favoriteRepository.findByUsernameOrderByIdDesc(username, page);
        List<Long> docIds = favorites.getContent().stream().map(KbFavoriteEntity::getDocId).toList();
        Map<Long, KnowledgeDocumentEntity> docs = docIds.isEmpty()
                ? Map.of()
                : documentRepository.findAllById(docIds).stream()
                        .collect(Collectors.toMap(KnowledgeDocumentEntity::getId, Function.identity()));

        List<KbFavoriteView> views = new ArrayList<>();
        for (KbFavoriteEntity fav : favorites.getContent()) {
            KnowledgeDocumentEntity doc = docs.get(fav.getDocId());
            if (doc == null) {
                // 孤儿收藏（文档已删除）：机会式清理，不影响本次列表其余条目
                favoriteRepository.deleteByUsernameAndDocId(username, fav.getDocId());
                log.info("已清理孤儿收藏 - username: {}, docId: {}", username, fav.getDocId());
                continue;
            }
            views.add(toView(fav, doc));
        }
        return new PageImpl<>(views, page, favorites.getTotalElements());
    }

    @Override
    @Transactional
    public KbFavoriteView add(String username, Long docId) {
        KnowledgeDocumentEntity doc = documentRepository.findById(docId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在"));
        KbFavoriteEntity fav = favoriteRepository.findByUsernameAndDocId(username, docId).orElseGet(() -> {
            KbFavoriteEntity entity = new KbFavoriteEntity();
            entity.setUsername(username);
            entity.setDocId(docId);
            entity.setLibraryId(doc.getLibraryId());
            return favoriteRepository.save(entity);
        });
        return toView(fav, doc);
    }

    @Override
    @Transactional
    public void remove(String username, Long docId) {
        favoriteRepository.deleteByUsernameAndDocId(username, docId);
    }

    @Override
    public boolean isFavorite(String username, Long docId) {
        return favoriteRepository.existsByUsernameAndDocId(username, docId);
    }

    @Override
    public java.util.Optional<PathFavorite> resolveByPath(String username, String path) {
        if (path == null || path.isBlank()) {
            return java.util.Optional.empty();
        }
        return documentRepository.findByLibraryIdAndFilePath(publicLibrary, path.trim())
                .map(doc -> new PathFavorite(doc.getId(),
                        favoriteRepository.existsByUsernameAndDocId(username, doc.getId())));
    }

    private KbFavoriteView toView(KbFavoriteEntity fav, KnowledgeDocumentEntity doc) {
        return new KbFavoriteView(doc.getId(), doc.getLibraryId(), doc.getTitle(), doc.getFilePath(),
                doc.getSpace(), fav.getCreatedDate());
    }
}
