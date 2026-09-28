package org.example.controller;

import org.example.dto.KbChunkHit;
import org.example.service.KbChatService;
import org.example.service.KbSearchService;
import org.example.vo.ChatHistoryVo;
import org.example.vo.ConversationVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索与问答接口（需登录）。用户身份一律取自登录态（JWT），不接受请求参数传入。
 */
@RestController
@RequestMapping("/api/kb")
public class KbController {

    @Autowired
    private KbChatService kbChatService;

    @Autowired
    private KbSearchService kbSearchService;

    @Autowired
    private org.example.quota.QuotaService quotaService;

    @Autowired
    private org.example.service.KbFeedbackService kbFeedbackService;

    @Autowired
    private org.example.service.KbDocumentService kbDocumentService;

    /** 用户侧配额/范围的主空间：个人知识库（u-{username}），无 DB 依赖。 */
    private String primaryLibrary() {
        String username = org.example.security.SecurityUtil.currentUsername();
        if (username == null || username.isBlank()) {
            return org.example.security.SecurityUtil.currentLibraryId("default");
        }
        return org.example.library.KbLibrary.personalCode(username);
    }

    private String currentUserId() {
        // principal 为 record 类型时 getName() 返回 toString()，必须显式取 username
        return org.example.security.SecurityUtil.currentUsername();
    }

    /**
     * 知识问答：检索增强生成（RAG），SSE 流式输出。
     * 使用 POST + form body 传递 message，避免把正文（中文长文本 URL 编码后体积大）塞进 URL/请求头，
     * 规避 URL 长度限制与日志泄露风险。
     *
     * @param docPath 可选，文档相对路径（docs 根相对）；传入时问答范围锁定到该篇文档（"问这篇文档"）
     * @param scope   可选检索范围：public（缺省，仅公共文档）/ mine（当前空间）/ all（公共+当前空间）
     * @param category 可选课程分类过滤（kb_document.category 精确匹配）；docPath 传入时失效
     */
    @PostMapping(value = "/chat-stream", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE + ";charset=utf-8")
    public Flux<ServerSentEvent<String>> chatStream(@RequestParam("message") String message,
                                                     @RequestParam(value = "conversationId", required = false) String conversationId,
                                                     @RequestParam(value = "docPath", required = false) String docPath,
                                                     @RequestParam(value = "scope", required = false) String scope,
                                                     @RequestParam(value = "category", required = false) String category) {
        return kbChatService.chatStream(currentUserId(), conversationId, message, docPath, scope, category);
    }

    /**
     * 提交问答反馈（点赞/点踩，可附评语）。点踩条目供管理端回流评测集（Golden Set）。
     */
    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestParam("rating") String rating,
                                        @RequestParam(value = "conversationId", required = false) String conversationId,
                                        @RequestParam(value = "question", required = false) String question,
                                        @RequestParam(value = "answer", required = false) String answer,
                                        @RequestParam(value = "comment", required = false) String comment) {
        return kbFeedbackService.submit(currentUserId(), conversationId, rating, question, answer, comment);
    }

    /**
     * 公共知识库可用的课程分类清单（frontmatter category 去重升序），
     * 供前端问答/检索的分类过滤器（与 /search、/chat-stream 的 category 参数同口径）。
     */
    @GetMapping("/categories")
    public List<String> categories() {
        return kbDocumentService.categories("default");
    }

    /**
     * 混合检索，返回带溯源的分块命中。可选 path 按目录前缀过滤（如 "04-前端/"），space 按空间过滤（如 "前端"）；
     * scope 检索范围：public（缺省，仅公共文档）/ mine（当前空间）/ all（公共+当前空间）；
     * category 按课程分类过滤（kb_document.category 精确匹配）。
     */
    @GetMapping("/search")
    public List<KbChunkHit> search(@RequestParam("query") String query,
                                   @RequestParam(value = "k", defaultValue = "5") int k,
                                   @RequestParam(value = "path", required = false) String path,
                                   @RequestParam(value = "space", required = false) String space,
                                   @RequestParam(value = "scope", required = false) String scope,
                                   @RequestParam(value = "category", required = false) String category) {
        // M3：每日检索配额扣减（仅用户侧 /search 扣减；chat-stream 内部检索与 admin 工具不计）——
        // 记在用户个人空间上，与「我的知识库」配额展示同源
        quotaService.consumeRetrieval(primaryLibrary());
        List<String> librarys = org.example.library.KbScopes.resolve(scope,
                org.example.security.SecurityUtil.currentUsername(), primaryLibrary(), "default");
        return kbSearchService.searchAs(librarys, org.example.security.SecurityUtil.currentUsername(),
                query, k, path, space, category, null);
    }

    /**
     * 问答历史。
     */
    @GetMapping("/history")
    public PagedModel<ChatHistoryVo> history(@PageableDefault(size = 20, direction = Sort.Direction.DESC, sort = "id") Pageable page,
                                             @RequestParam(value = "conversationId", required = false) String conversationId) {
        Page<ChatHistoryVo> result = kbChatService.queryHistory(currentUserId(), conversationId, page);
        return new PagedModel<>(result);
    }

    /**
     * 清空问答历史。conversationId 为空时清空全部（旧行为）。
     */
    @PostMapping("/clear-history")
    public void clearHistory(@RequestParam(value = "conversationId", required = false) String conversationId) {
        kbChatService.clearHistory(currentUserId(), conversationId);
    }

    /**
     * 当前用户的对话（会话）列表，按最后活动时间倒序。
     */
    @GetMapping("/conversations")
    public List<ConversationVo> conversations() {
        return kbChatService.listConversations(currentUserId());
    }

    /**
     * 删除指定对话及其全部消息。
     */
    @DeleteMapping("/conversations/{conversationId}")
    public void deleteConversation(@PathVariable("conversationId") String conversationId) {
        kbChatService.deleteConversation(currentUserId(), conversationId);
    }
}
