package com.techcodex.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 站点公开内容索引项（只含元数据，不携带正文）。
 *
 * <p>公开内容 API 要一次返回全站约 4.5k 篇文档的导航信息，若直接查实体会把
 * content LONGTEXT 一并载入（约 170MB）；这里用投影 DTO 只取列表所需字段。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ContentIndexItem {

    private Long id;

    private String title;

    private String description;

    /** 站点分类 slug，对应 /docs/{slug} */
    private String categorySlug;

    /** 站点分类目录（docs 相对路径），用于前端还原分类归属 */
    private String categoryDir;

    /** 分类内文档 slug（不含 .md） */
    private String docSlug;

    /** docs 相对路径（含 .md），作为详情页与站内链接的稳定主键 */
    private String path;

    /** 原始 Markdown 字节数，用于 llms-full 体积预算 */
    private Long fileSize;

    private Date createdDate;

    private Date updatedDate;

    private Date publishedAt;

    private Integer revision;
}
