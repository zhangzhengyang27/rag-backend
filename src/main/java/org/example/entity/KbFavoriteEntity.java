package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.Comment;

/**
 * 文档收藏：用户对知识库文档（公共或个人空间）的收藏关系。
 * (username, docId) 唯一——重复收藏幂等；文档删除后由列表查询机会式清理孤儿条目。
 */
@EqualsAndHashCode(callSuper = true)
@Table(name = "kb_favorite",
        uniqueConstraints = @UniqueConstraint(name = "uk_kb_favorite_user_doc", columnNames = {"username", "doc_id"}))
@Entity
@Data
@Comment("文档收藏")
public class KbFavoriteEntity extends BaseEntity {

    @Comment("收藏者用户名")
    @Column(length = 64, nullable = false)
    private String username;

    @Comment("被收藏文档ID（kb_document.id）")
    @Column(name = "doc_id", nullable = false)
    private Long docId;

    @Comment("文档所属空间（default / u-{username}）")
    @Column(length = 64)
    private String libraryId;
}
