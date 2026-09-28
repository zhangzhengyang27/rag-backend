package org.example.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Data;
import org.hibernate.annotations.Comment;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.util.Date;

/**
 * 实体公共基类，统一维护主键、审计字段与乐观锁版本号。
 */
@MappedSuperclass
@EntityListeners({AuditingEntityListener.class})
@Data
public abstract class BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("主键ID")
    private Long id;

    @Version
    @Comment("乐观锁版本号，防止并发更新互相覆盖")
    private Long version;

    @CreatedDate
    @Column(updatable = false)
    @Comment("创建时间")
    private Date createdDate;

    @LastModifiedDate
    @Comment("更新时间")
    private Date updatedDate;
}
