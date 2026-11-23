package org.example.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Date;
import java.util.List;

/**
 * 站点公开内容索引：前端启动后一次拉取，用于首页、分类页、侧栏、sitemap 与 RSS。
 */
@Data
@Builder
public class ContentIndexView {

    private int total;

    private Date generatedAt;

    private List<ContentIndexItem> docs;
}
