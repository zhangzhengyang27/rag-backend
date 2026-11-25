package com.techcodex.service;

import com.techcodex.dto.CmsDocumentDetailView;
import com.techcodex.dto.CmsDocumentSaveRequest;
import com.techcodex.dto.CmsImportRequest;
import com.techcodex.dto.CmsImportResult;
import com.techcodex.dto.CmsVersionView;
import com.techcodex.dto.KbDocumentView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * 站点内容 CMS：数据库为唯一内容真相；docs/ 镜像是否写回由 app.kb.cms.mirror-enabled 控制。
 *
 * <p>职责边界：只管理 sourceType=CMS 的站点文档；上传/URL/个人知识库仍走
 * {@link KbDocumentService}。发布后写库并重建向量；镜像模式下再写 docs 文件。</p>
 */
public interface KbCmsService {

    /** CMS 文档列表（仅 CMS 来源），支持标题/路径关键字与分类目录过滤。 */
    Page<KbDocumentView> list(String keyword, String categoryDir, String libraryId, Pageable page);

    /** 编辑器详情：正文与 frontmatter 分离返回。 */
    CmsDocumentDetailView detail(Long id, String libraryId);

    /** 新建并直接发布（单管理员模式）；写库 + 初始版本 + 索引，镜像模式额外写 docs。 */
    CmsDocumentDetailView create(CmsDocumentSaveRequest request, String libraryId, String operator);

    /** 更新并直接发布；expectedRevision 非空时做乐观锁校验，冲突返回 409。 */
    CmsDocumentDetailView update(Long id, CmsDocumentSaveRequest request, String libraryId, String operator);

    /** 删除 CMS 文档：文件移入 .cms-trash（可恢复），同时清理分块/向量/版本。 */
    void delete(Long id, String libraryId, String operator);

    /** 版本列表（不含正文）。 */
    List<CmsVersionView> versions(Long id, String libraryId);

    /** 回滚到指定版本：以旧正文生成一个新版本，保留完整历史。 */
    CmsDocumentDetailView restore(Long id, Long versionId, String libraryId, String operator);

    /**
     * 把 docs/ 目录一次性收编为 CMS：分类映射由调用方（前端 docs-config）传入。
     * 存量 SYNC/目录导入文档按 filePath 原地收编，不重建向量；缺失文档新建为 PENDING。
     */
    CmsImportResult importDocs(CmsImportRequest request, String libraryId, String operator);
}
