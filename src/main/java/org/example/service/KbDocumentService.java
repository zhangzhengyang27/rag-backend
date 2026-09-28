package org.example.service;

import org.example.dto.KbDocumentView;
import org.example.dto.KbImportTaskView;
import org.example.dto.KbStatsView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.multipart.MultipartFile;

/**
 * 知识库文档管理服务：文档导入（单文件/目录）、查询、删除、索引重建与统计。
 */
public interface KbDocumentService {

    /**
     * 分页查询文档列表，keyword 匹配标题或文件名；限定空间。
     *
     * @param status 状态过滤（INDEXED/PENDING/FAILED），null 或空串不过滤
     */
    Page<KbDocumentView> list(String keyword, String status, String libraryId, Pageable page);

    /**
     * 重建单篇文档索引（同步执行）：重置为 PENDING 后重新分块/向量化，
     * 失败则标记 FAILED 并抛出业务异常。失败文档修复入口。
     */
    void reindex(Long id, String libraryId);

    /**
     * 在线编辑文档（可选标题）并重建索引（同步执行）；docs 同步受管（SYNC）文档拒绝编辑，
     * 提示到源仓库修改后同步。返回更新后的详情视图（含原文）。
     */
    KbDocumentView updateContent(Long id, String libraryId, String title, String content, String operator);

    /** 文档详情，withContent=true 时返回 Markdown 原文；限定空间。 */
    KbDocumentView get(Long id, String libraryId, boolean withContent);

    /** 单文件上传并立即索引；限定空间。 */
    KbDocumentView upload(MultipartFile file, String title, String userId, String libraryId);

    /** URL 抓取导入：抓取网页正文转 Markdown 后入库并索引；限定空间。 */
    KbDocumentView importUrl(String url, String userId, String libraryId);

    /** 删除文档并清理分块与向量；限定空间。 */
    void delete(Long id, String libraryId);

    /** 目录批量导入（递归扫描 .md），返回任务ID用于轮询进度；限定空间。 */
    String importDirectory(String path, String userId, String libraryId);

    /** docs 目录自动同步（对账：新增/内容更新/改名/删除），path 缺省用配置的同步根目录；返回任务ID。 */
    String syncDocs(String path, String userId, String libraryId);

    /** 查询导入/重建任务进度。 */
    KbImportTaskView getImportTask(String taskId);

    /** 重建当前空间向量索引（异步，返回任务ID）。 */
    String rebuildIndex(String libraryId);

    /** 知识库统计（限定空间）。 */
    KbStatsView stats(String libraryId);
}
