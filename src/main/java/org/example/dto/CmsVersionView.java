package org.example.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;

/** CMS 版本快照的列表视图（不含正文，避免一次返回几十份大文本）。 */
@Data
@Builder
public class CmsVersionView {

    private Long id;

    private Integer revision;

    private String title;

    private String description;

    private String changeNote;

    private String createdBy;

    private Date createdDate;
}
