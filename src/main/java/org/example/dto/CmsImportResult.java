package org.example.dto;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** docs 目录收编结果：区分收编存量、新建、跳过与失败，便于运维判断是否需要重建索引。 */
@Data
@Builder
public class CmsImportResult {

    private int scanned;

    private int adopted;

    private int created;

    private int skipped;

    private int failed;

    @Builder.Default
    private List<String> errors = new ArrayList<>();
}
