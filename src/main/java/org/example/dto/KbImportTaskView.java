package org.example.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 目录批量导入任务进度视图。
 */
@Data
@Builder
public class KbImportTaskView {

    private String taskId;

    private String status;

    private int total;

    private int processed;

    private int succeeded;

    private int failed;

    @Builder.Default
    private List<String> errors = new ArrayList<>();
}
