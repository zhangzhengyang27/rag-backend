package com.techcodex.dto;

import lombok.Data;

/**
 * CMS 新建/更新请求。更新时 expectedRevision 为乐观锁校验值：
 * 编辑器打开时拿到的版本号，提交时必须一致，防止两个窗口互相覆盖。
 */
@Data
public class CmsDocumentSaveRequest {

    private String title;

    private String description;

    /** 站点分类目录（如 04-前端/03-JavaScript），决定镜像文件父目录 */
    private String categoryDir;

    /** 站点分类 slug（如 javascript），决定详情页 URL 第一段 */
    private String categorySlug;

    /** 分类内 slug（相对分类目录、不含 .md） */
    private String docSlug;

    /** 去掉 frontmatter 的 Markdown 正文 */
    private String body;

    /** 原 frontmatter 块；新建时可空，由后端生成 */
    private String frontmatter;

    /** 更新时的版本号；新建忽略 */
    private Integer expectedRevision;

    /** 变更说明（写入版本快照） */
    private String changeNote;
}
