package org.example.dto;

import java.util.Date;

/**
 * 收藏条目视图：收藏关系 + 文档展示信息（标题/路径实时取自 kb_document）。
 */
public record KbFavoriteView(Long docId, String libraryId, String title, String filePath,
                             String space, Date createdAt) {
}
