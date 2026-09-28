package org.example.dto;

import lombok.Builder;
import lombok.Data;
import org.example.entity.KbDocumentStatus;
import org.example.entity.KnowledgeDocumentEntity;

import java.util.Date;

/**
 * 知识库文档视图：列表与详情展示。
 */
@Data
@Builder
public class KbDocumentView {

    private Long id;

    private String title;

    private String fileName;

    private String filePath;

    private Long fileSize;

    private Integer chunkCount;

    private KbDocumentStatus status;

    /** 来源类型：UPLOAD/URL/DIRECTORY/SYNC（SYNC 为 docs 同步受管，编辑受约束） */
    private String sourceType;

    private String importedBy;

    private Date createdDate;

    private Date updatedDate;

    private String content;

    public static KbDocumentView fromEntity(KnowledgeDocumentEntity e, boolean withContent) {
        return KbDocumentView.builder()
                .id(e.getId())
                .title(e.getTitle())
                .fileName(e.getFileName())
                .filePath(e.getFilePath())
                .fileSize(e.getFileSize())
                .chunkCount(e.getChunkCount())
                .status(e.getStatus())
                .sourceType(e.getSourceType())
                .importedBy(e.getImportedBy())
                .createdDate(e.getCreatedDate())
                .updatedDate(e.getUpdatedDate())
                .content(withContent ? e.getContent() : null)
                .build();
    }
}
