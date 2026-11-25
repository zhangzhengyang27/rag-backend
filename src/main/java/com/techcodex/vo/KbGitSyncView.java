package com.techcodex.vo;

import lombok.Builder;
import lombok.Data;

/**
 * GitHub 同步受理结果：立即返回导入任务 ID，由前端轮询现有导入任务接口。
 */
@Data
@Builder
public class KbGitSyncView {

    private String repoUrl;
    private String branch;
    private String path;
    private String localPath;
    private String taskId;
}
