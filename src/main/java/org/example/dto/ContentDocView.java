package org.example.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/**
 * 站点公开内容详情：正文已剥离 frontmatter，供前端直接渲染 Markdown。
 */
@Data
@Builder
public class ContentDocView {

    private String title;

    private String description;

    private String path;

    private String categorySlug;

    private String categoryDir;

    private String docSlug;

    /** 剥离 frontmatter 后的 Markdown 正文 */
    private String body;

    private String frontmatter;

    private Integer revision;

    private Date publishedAt;

    private Date updatedDate;
}
