package org.example.service;

import org.example.vo.KbGitSyncView;

/**
 * GitHub 增量同步：浅克隆/更新仓库到本地临时目录，再复用目录导入实现增量索引。
 */
public interface KbGitSyncService {

    KbGitSyncView sync(String repoUrl, String branch, String path, String userId, String libraryId);
}
