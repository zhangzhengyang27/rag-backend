package org.example.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.example.config.KbConfig;
import org.example.service.KbSearchService;
import org.springframework.stereotype.Component;

/**
 * 知识库检索工具：供问答助手调用，返回带溯源标注的文档片段。
 *
 * <p>空间安全：本工具在 LLM 流式回调线程上执行，SecurityContext（ThreadLocal）不可见。
 * 检索前必须校验登录态存在——拿不到身份时显式拒绝（fail-closed），绝不静默回落默认空间，
 * 否则会把默认空间的文档泄露给其他空间用户。回答所需证据已由服务层预检索并以 sources 注入，
 * 工具仅承担补充检索职责，拒绝时模型会基于已有证据作答。</p>
 */
@Component
@Slf4j
public class KbSearchTools {

    private static final String NO_AUTH_MESSAGE =
            "（检索上下文不可用：当前无法确认用户空间，已跳过知识库检索。"
                    + "请直接基于系统预检索到的证据回答；若证据不足，请如实说明并建议用户稍后重试。）";

    private final KbSearchService kbSearchService;
    private final int maxResults;

    public KbSearchTools(KbSearchService kbSearchService, KbConfig kbConfig) {
        this.kbSearchService = kbSearchService;
        this.maxResults = kbConfig.getRetrievalMaxResults();
    }

    @Tool("第一步：在知识库中做首轮检索（firstSearch），返回带来源的、与问题相关的文档片段；若返回'未检索到'表明知识库无直接相关内容")
    public String firstSearch(@P("用户问题或检索关键词") String query) {
        if (!hasAuthContext()) {
            log.warn("检索工具缺少登录态已拒绝执行（fail-closed） - tool: firstSearch");
            return NO_AUTH_MESSAGE;
        }
        String result = kbSearchService.retrieveForLlm(query, maxResults);
        log.info("首轮检索完成 - query: {}, 命中长度: {}", query, result.length());
        // 空结果给模型显式信号（而非空串），让 Agent 明确"知识库无相关内容"，可决定是否补充检索或如实作答
        if (result == null || result.isBlank()) {
            return "未检索到与「" + query + "」直接相关的内容。若想提高命中，可换用更具体的关键词再次检索，或判断该问题是否超出知识库范围。";
        }
        return result;
    }

    /**
     * 补充检索工具（AgenticRAG 多轮 Step 的 refine 环节）：
     * 当模型根据首轮检索（firstSearch）+ 验证（verify）判断仍缺少关键信息时，
     * 基于具体缺失知识点发起聚焦的二次检索，逼近复杂/多跳问题的答案。
     */
    @Tool("第三步：当首轮检索与验证后发现仍缺少回答所需的关键信息时，基于缺失的知识点做一次聚焦的补充检索(refine)")
    public String refineSearch(@P("缺失的具体知识点：例如某个API名、类名、字段名、错误码或概念") String missingKnowledge) {
        if (!hasAuthContext()) {
            log.warn("检索工具缺少登录态已拒绝执行（fail-closed） - tool: refineSearch");
            return NO_AUTH_MESSAGE;
        }
        String result = kbSearchService.retrieveForLlm(missingKnowledge, maxResults);
        log.info("补充检索完成 - missingKnowledge: {}, 命中长度: {}", missingKnowledge, result.length());
        if (result == null || result.isBlank()) {
            return "补充检索也未能找到与「" + missingKnowledge + "」相关的内容，知识库中可能确实没有该信息。";
        }
        return result;
    }

    /** 登录态存在才允许检索：无身份时空间无法判定，拒绝优于跨空间泄露。 */
    private boolean hasAuthContext() {
        return org.example.security.SecurityUtil.currentUser().isPresent();
    }
}
