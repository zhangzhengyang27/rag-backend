package org.example.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * CMS 文档详情：在线编辑器需要的结构。
 * body 是去掉 frontmatter 的 Markdown 正文；frontmatter 原文单独返回，
 * 避免编辑正文时误改 YAML，也便于前端后续扩展元数据表单。
 */
@Data
@Builder
public class CmsDocumentDetailView {

    private Long id;

    private String title;

    private String description;

    private String categorySlug;

    private String categoryDir;

    private String docSlug;

    private String filePath;

    private String publishStatus;

    private Integer revision;

    private Date publishedAt;

    private String publishedBy;

    private Date updatedDate;

    /** 去掉 frontmatter 的 Markdown 正文 */
    private String body;

    /** 原 frontmatter 块（含首尾 ---），由后端合并回写，前端不直接编辑 */
    private String frontmatter;
}
