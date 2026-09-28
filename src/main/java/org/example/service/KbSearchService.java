package org.example.service;

import org.example.dto.KbChunkHit;
import org.example.dto.SearchOptions;

import java.util.List;

/**
 * 知识库检索服务：融合向量语义检索与关键词精确检索，返回带溯源（标题/路径）的分块命中。
 * 同时提供面向 LLM 的拼接上下文，用于 RAG 问答时的证据注入。
 */
public interface KbSearchService {

    /** 混合检索，返回按相关度排序的命中分块。 */
    List<KbChunkHit> search(String query, int topK);

    /**
     * 带参数覆盖的混合检索（A/B 评测用）。
     *
     * <p>在 {@link #search(String, int)} 基础上，允许对单次检索临时覆盖 rerank/bm25/topK 等参数，
     * 不改动全局配置，供同用例集做检索配置横向对比。options 为 null 或字段为 null 时沿用默认值。</p>
     *
     * @param options 参数覆盖快照，可为 null
     */
    List<KbChunkHit> search(String query, int topK, SearchOptions options);

    /**
     * 带目录过滤的混合检索。
     *
     * @param pathFilter 目录前缀过滤（匹配 filePath 以该前缀开头），可为 null/空表示不过滤
     */
    List<KbChunkHit> search(String query, int topK, String pathFilter);

    /**
     * 带目录 + 空间过滤的混合检索。
     *
     * @param space 知识库空间过滤（文档 space 字段精确匹配），可为 null/空表示不过滤
     */
    List<KbChunkHit> search(String query, int topK, String pathFilter, String space);

    /**
     * 多查询并行混合检索：对多个子查询（改写拆解结果）并行检索并按 docId#chunkIndex 去重合并。
     * 用于查询改写为多路时提升召回覆盖率。
     */
    List<KbChunkHit> searchMulti(List<String> queries, int topK);

    /**
     * 带目录过滤的多查询并行混合检索（语义同 {@link #searchMulti(List, int)}）。
     *
     * @param pathFilter 目录前缀过滤（匹配 filePath 前缀，传完整文件路径即为"仅在该文档内检索"），可为 null
     */
    List<KbChunkHit> searchMulti(List<String> queries, int topK, String pathFilter);

    /**
     * 显式身份检索：空间与用户名由调用方在持有登录态的线程上解析后传入。
     * 供异步子查询、调度任务等脱离请求线程的场景使用——那些线程读不到
     * SecurityContext（ThreadLocal），必须显式传递身份，避免跨空间检索与 ACL 绕过。
     *
     * @param libraryId 检索目标空间（必填）
     * @param username 当前用户名（可为 null，此时不应用 ACL 白名单）
     */
    List<KbChunkHit> searchAs(String libraryId, String username, String query, int topK,
                              String pathFilter, String space, SearchOptions options);

    /**
     * 多空间显式身份检索：对每个空间独立做「范围过滤 ∩ ACL」后并集合并，供检索范围
     * scope=all（公共 ∪ 个人/团队空间）使用。空空间列表返回空结果。
     *
     * @param libraryIds 检索目标空间集合（去重有序，公共空间建议在前），必填非空
     * @param username  当前用户名（可为 null，此时不应用各空间的 ACL 白名单）
     */
    List<KbChunkHit> searchAs(List<String> libraryIds, String username, String query, int topK,
                              String pathFilter, String space, SearchOptions options);

    /**
     * 多空间多查询并行检索：语义同 {@link #searchMulti(List, int, String)}，但空间集合与
     * 用户名由调用方在持有登录态的线程上解析后显式传入（scope 感知的问答链路用）。
     */
    List<KbChunkHit> searchMultiAs(List<String> libraryIds, String username, List<String> queries,
                                   int topK, String pathFilter);

    /** 面向 LLM 的检索：返回带来源标注的拼接文本，供问答助手注入。 */
    String retrieveForLlm(String query, int topK);
}
