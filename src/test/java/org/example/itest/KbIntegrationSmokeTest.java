package org.example.itest;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.example.dto.AuthRequest;
import org.example.dto.AuthResponse;
import org.example.dto.KbChunkHit;
import org.example.dto.KbDocumentView;
import org.example.dto.KbImportTaskView;
import org.example.entity.KbDocumentStatus;
import org.example.entity.KnowledgeDocumentEntity;
import org.example.entity.RetrievalTraceEntity;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.repository.RetrievalTraceRepository;
import org.example.repository.UserRepository;
import org.example.service.AdminUserService;
import org.example.service.AuthService;
import org.example.service.KbChatService;
import org.example.service.KbDocumentService;
import org.example.service.KbEvalService;
import org.example.service.KbFeedbackService;
import org.example.service.KbSearchService;
import org.example.service.KbTraceService;
import org.example.service.UsageStatService;
import org.example.vo.ChatFeedbackView;
import org.example.vo.KbEvalCaseView;
import org.example.vo.RetrievalTraceView;
import org.example.vo.UserQuotaView;
import org.example.vo.UserUsageView;
import org.example.vo.UserView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 集成冒烟测试（真实 MySQL + Redis Stack + 本地 ONNX 向量模型，经 itest profile 与生产数据隔离）：
 * <ol>
 *   <li>注册 → 登录 → 混合检索 → SSE 问答（LLM mock）→ 反馈落库 的主链路；</li>
 *   <li>docs 同步对账四用例：新增 / 内容更新 / 改名 / 删除（含 category 解析入库）；</li>
 *   <li>ACL 白名单检索过滤（授权用户只见授权文档，未授权用户不可见）。</li>
 * </ol>
 * LLM 不参与：问答链路 mock 流式模型（AiService 管线保持真实），其余全部走真实 Bean 与存储。
 */
@SpringBootTest
@ActiveProfiles("itest")
class KbIntegrationSmokeTest {

    @Autowired private AuthService authService;
    @Autowired private KbDocumentService kbDocumentService;
    @Autowired private KbSearchService kbSearchService;
    @Autowired private KbChatService kbChatService;
    @Autowired private KbFeedbackService kbFeedbackService;
    @Autowired private UserRepository userRepository;
    @Autowired private org.example.repository.ChatHistoryRepository chatHistoryRepository;
    @Autowired private KnowledgeDocumentRepository documentRepository;
    @Autowired private AdminUserService adminUserService;
    @Autowired private KbEvalService kbEvalService;
    @Autowired private KbTraceService kbTraceService;
    @Autowired private RetrievalTraceRepository retrievalTraceRepository;
    @Autowired private org.example.quota.QuotaService quotaService;
    @Autowired private UsageStatService usageStatService;
    @Autowired private org.example.config.RuntimeConfigStore runtimeConfig;
    @Autowired private org.example.service.KbAlertService kbAlertService;
    @Autowired private org.example.security.LoginGuard loginGuard;

    /** LLM 不进集成测试：starter 的 AiService Bean 定义会被其 BFPP 强制接管（@Primary/@MockitoBean 均无法覆盖），
     *  因此 mock 它底层的流式模型 Bean，让真实 AiService 管线（提示模板/工具装配/流式事件）跑在 mock 模型上 */
    @MockitoBean("openAiStreamingChatModel")
    private StreamingChatModel streamingChatModel;

    private String newUsername() {
        return "itest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private String newMarker() {
        return "mk" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 轮询后台任务直到 DONE（FAILED 直接断言失败并列出错误），超时 90s */
    private KbImportTaskView awaitTaskDone(String taskId) throws Exception {
        long deadline = System.currentTimeMillis() + 90_000;
        while (System.currentTimeMillis() < deadline) {
            KbImportTaskView view = kbDocumentService.getImportTask(taskId);
            if ("DONE".equals(view.getStatus())) {
                return view;
            }
            if ("FAILED".equals(view.getStatus())) {
                assertEquals("DONE", view.getStatus(), "任务失败: " + view.getErrors());
            }
            Thread.sleep(200);
        }
        throw new AssertionError("任务超时未完成: " + taskId);
    }

    private AuthRequest req(String username) {
        AuthRequest r = new AuthRequest();
        r.setUsername(username);
        r.setPassword("pass123456");
        return r;
    }

    private KnowledgeDocumentEntity uploadDoc(String marker, String category) {
        String frontmatter = category == null ? "" : ("---\ntitle: 冒烟" + marker + "\ncategory: " + category + "\n---\n\n");
        String md = frontmatter + "# 冒烟测试 " + marker + "\n\n正文包含唯一标记 " + marker + "。\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "smoke-" + marker + ".md", "text/markdown", md.getBytes(StandardCharsets.UTF_8));
        KbDocumentView view = kbDocumentService.upload(file, null, newUsername(), "default");
        assertNotNull(view.getId(), "上传文档应成功入库并索引");
        return documentRepository.findById(view.getId()).orElseThrow();
    }

    /** 用例 1：注册 → 登录 → 检索 → SSE 问答 → 历史落库 → 反馈提交/管理端可查 */
    @Test
    void chatChain_registerSearchAskFeedback() {
        String username = newUsername();
        AuthResponse registered = authService.register(req(username));
        assertNotNull(registered.getToken(), "注册应返回 token");
        assertEquals("USER", registered.getRole());
        AuthResponse loggedIn = authService.login(req(username));
        assertNotNull(loggedIn.getToken(), "登录应返回 token");

        String marker = newMarker();
        KnowledgeDocumentEntity doc = uploadDoc(marker, null);

        // 混合检索（真实 embedding + BM25/关键词）能命中刚入库的文档
        List<KbChunkHit> hits = kbSearchService.search(marker, 3);
        assertTrue(hits.stream().anyMatch(h -> marker.equals(String.valueOf(h.getDocId()))
                        || (h.getText() != null && h.getText().contains(marker))),
                "检索应命中刚上传的文档，实际: " + hits.size() + " 条");
        assertEquals(doc.getId(), hits.stream().filter(h -> h.getText() != null && h.getText().contains(marker))
                .findFirst().orElseThrow().getDocId());

        // SSE 问答（流式模型 mock + 真实 AiService 管线），校验 conversation/token/done 事件与历史落库
        org.mockito.Mockito.doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse("这是集成测试的回答。");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("这是集成测试的回答。"))
                    .build());
            return null;
        }).when(streamingChatModel).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        org.mockito.Mockito.doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse("这是集成测试的回答。");
            handler.onCompleteResponse(ChatResponse.builder()
                    .aiMessage(AiMessage.from("这是集成测试的回答。"))
                    .build());
            return null;
        }).when(streamingChatModel).doChat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        String question = "请解释 " + marker;
        List<ServerSentEvent<String>> events = kbChatService
                .chatStream(username, null, question, null)
                .collectList()
                .block(Duration.ofSeconds(60));
        assertNotNull(events, "问答事件流不应为空");
        assertTrue(events.stream().anyMatch(e -> "conversation".equals(e.event())), "缺少 conversation 事件");
        assertTrue(events.stream().anyMatch(e -> "token".equals(e.event()) && "这是集成测试的回答。".equals(e.data())),
                "缺少 mock 生成的 token 事件");
        assertTrue(events.stream().anyMatch(e -> "done".equals(e.event())), "缺少 done 事件");
        assertTrue(chatHistoryRepository.findAllBySessionId(username, PageRequest.of(0, 10)).getTotalElements() >= 2,
                "问答后应落库用户+助手两条历史");

        // 反馈：提交点踩 → 管理端按 DOWN 可查（评测回流的入口）
        var submitted = kbFeedbackService.submit(username, null, "DOWN", question, "这是集成测试的回答。", "回答太短");
        assertEquals("DOWN", submitted.get("rating"));
        Page<ChatFeedbackView> page = kbFeedbackService.page("default", "DOWN",
                PageRequest.of(0, 20, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
        assertTrue(page.getContent().stream().anyMatch(v -> submitted.get("id").equals(v.getId())),
                "管理端反馈分页应包含刚提交的点踩");
    }

    /** 用例 2：docs 同步对账四用例——新增 / 内容更新 / 改名保 ID / 删除（含 frontmatter category 解析） */
    @Test
    void docsSync_createUpdateRenameDelete() throws Exception {
        Path root = Files.createTempDirectory("kb-sync-itest");
        String marker = newMarker();
        String category = "itest-cat-" + marker.substring(0, 6);
        String content = "---\ntitle: 冒烟同步\ncategory: " + category + "\n---\n\n# 冒烟同步\n\n标记 " + marker + "\n";

        // 1) 新增
        Path a = root.resolve("A-第一篇.md");
        Files.writeString(a, content, StandardCharsets.UTF_8);
        // 顶级目录带排序编号前缀时，space 取目录名但不带编号
        Path nested = Files.createDirectories(root.resolve("04-前端"));
        Files.writeString(nested.resolve("01-嵌套.md"), content, StandardCharsets.UTF_8);
        awaitTaskDone(kbDocumentService.syncDocs(root.toString(), "itest", "default"));
        KnowledgeDocumentEntity created = documentRepository
                .findByLibraryIdAndFilePath("default", "A-第一篇.md").orElseThrow(() -> new AssertionError("同步后应存在 A-第一篇.md"));
        assertEquals("SYNC", created.getSourceType());
        assertEquals(category, created.getCategory(), "frontmatter category 应解析入库");
        KnowledgeDocumentEntity nestedDoc = documentRepository
                .findByLibraryIdAndFilePath("default", "04-前端/01-嵌套.md").orElseThrow(() -> new AssertionError("同步后应存在嵌套文档"));
        assertEquals("前端", nestedDoc.getSpace(), "space 应为去掉编号前缀的顶级目录名");

        // 2) 内容更新：复用 docId，hash 变化
        Long docId = created.getId();
        Files.writeString(a, content + "\n追加段落 " + newMarker() + "\n", StandardCharsets.UTF_8);
        awaitTaskDone(kbDocumentService.syncDocs(root.toString(), "itest", "default"));
        KnowledgeDocumentEntity updated = documentRepository.findById(docId).orElseThrow();
        assertEquals(docId, updated.getId());
        assertNotEquals(created.getContentHash(), updated.getContentHash(), "内容更新后 hash 应变化");

        // 3) 改名（内容不变）：按 hash 识别，docId 保持、路径更新；
        //    第 2 步对账不得把改名后的新路径误判为"路径冲突"计入失败
        Path renamed = root.resolve("B-改名后.md");
        Files.move(a, renamed);
        KbImportTaskView renameView = awaitTaskDone(kbDocumentService.syncDocs(root.toString(), "itest", "default"));
        KnowledgeDocumentEntity afterRename = documentRepository.findById(docId).orElseThrow();
        assertEquals("B-改名后.md", afterRename.getFilePath(), "改名应原地更新 filePath");
        assertEquals(0, renameView.getFailed(), "改名同步不应产生失败: " + renameView.getErrors());
        assertTrue(renameView.getErrors().isEmpty(), "改名同步不应有错误条目: " + renameView.getErrors());

        // 4) 删除：路径与 hash 均消失 → 文档与分块清理
        Files.delete(renamed);
        awaitTaskDone(kbDocumentService.syncDocs(root.toString(), "itest", "default"));
        assertTrue(documentRepository.findById(docId).isEmpty(), "删除后文档应不存在");
    }

    /**
     * 用例 3：管理端运营闭环——用户管理（角色/禁用/重置密码）、配额用户级覆盖、
     * 失败文档筛选与重试索引、点踩回流评测用例、检索 Trace 读取、用户维度用量。
     */
    @Test
    void adminOps_usersQuotaDocsEvalTrace() {
        String username = newUsername();
        authService.register(req(username));
        org.example.entity.UserEntity user = userRepository.findByUsername(username).orElseThrow();

        // 1) 用户列表可按关键字搜到
        Page<UserView> users = adminUserService.list(username.substring(0, 8), PageRequest.of(0, 20));
        assertTrue(users.getContent().stream().anyMatch(u -> username.equals(u.getUsername())),
                "用户列表应包含新注册用户");

        // 2) 角色变更 → ADMIN
        assertEquals("ADMIN", adminUserService.updateRole(user.getId(), "ADMIN", "admin").getRole());

        // 3) 重置密码 → 旧密码失效、新密码可登录
        adminUserService.resetPassword(user.getId(), "newPass456", "admin");
        assertThrows(IllegalArgumentException.class, () -> authService.login(req(username)),
                "重置密码后旧密码应失效");
        AuthRequest newReq = req(username);
        newReq.setPassword("newPass456");
        assertNotNull(authService.login(newReq).getToken(), "新密码应可登录");

        // 4) 禁用 → 登录拒绝；启用后恢复
        adminUserService.updateStatus(user.getId(), "DISABLED", "admin");
        IllegalArgumentException locked = assertThrows(IllegalArgumentException.class,
                () -> authService.login(newReq), "禁用后登录应被拒绝");
        assertTrue(locked.getMessage().contains("禁用"));
        adminUserService.updateStatus(user.getId(), "ACTIVE", "admin");
        assertNotNull(authService.login(newReq).getToken(), "启用后应可重新登录");

        // 5) 配额用户级覆盖 → usage 反映覆盖值；-1 清除恢复默认
        UserQuotaView updated = adminUserService.updateLimits(user.getId(), Map.of("docLimit", 5, "chatLimit", 3), "admin");
        assertEquals(5, updated.getDocLimit());
        assertTrue(updated.isDocLimitCustom());
        org.example.quota.QuotaUsage usage = quotaService.usage("u-" + username);
        assertEquals(5, usage.docLimit(), "个人库配额应反映用户级覆盖");
        assertEquals(3, usage.chatDailyLimit());
        UserQuotaView cleared = adminUserService.updateLimits(user.getId(), Map.of("docLimit", -1), "admin");
        assertFalse(cleared.isDocLimitCustom(), "-1 应清除覆盖恢复默认");

        // 6) 失败文档：状态过滤可查 → 重试索引恢复 INDEXED
        KnowledgeDocumentEntity doc = uploadDoc(newMarker(), null);
        doc.setStatus(KbDocumentStatus.FAILED);
        documentRepository.save(doc);
        Page<KbDocumentView> failed = kbDocumentService.list(null, "FAILED", "default", PageRequest.of(0, 20));
        assertTrue(failed.getContent().stream().anyMatch(d -> doc.getId().equals(d.getId())),
                "状态过滤应能查出失败文档");
        kbDocumentService.reindex(doc.getId(), "default");
        assertEquals(KbDocumentStatus.INDEXED,
                documentRepository.findById(doc.getId()).orElseThrow().getStatus(), "重试索引后应恢复 INDEXED");

        // 7) 点踩反馈 → 回流 Golden Set 用例 → 列表可查 → 删除
        Map<String, Object> submitted = kbFeedbackService.submit(username, null, "DOWN",
                "为什么集成测试会失败", "回答内容", "答非所问");
        Long caseId = kbEvalService.addCaseFromFeedback("default", (Long) submitted.get("id"), "集成测试", null, "admin");
        assertNotNull(caseId, "点踩应成功回流为评测用例");
        Page<KbEvalCaseView> cases = kbEvalService.listCases(PageRequest.of(0, 20));
        assertTrue(cases.getContent().stream().anyMatch(c -> caseId.equals(c.getId()) && c.getNote().contains("回流")),
                "用例列表应包含回流用例且备注含来源");
        kbEvalService.deleteCase(caseId);
        assertTrue(kbEvalService.listCases(PageRequest.of(0, 20)).getContent().stream()
                .noneMatch(c -> caseId.equals(c.getId())), "删除后用例应不存在");

        // 8) 检索 Trace 落库可读（keyword 命中原文）
        RetrievalTraceEntity trace = new RetrievalTraceEntity();
        trace.setLibraryId("default");
        trace.setUserId(username);
        trace.setOriginalQuery("trace 探针 " + username);
        trace.setSearchQuery("trace 探针");
        trace.setResultCount(3);
        retrievalTraceRepository.save(trace);
        Page<RetrievalTraceView> traces = kbTraceService.page(null, username, "trace 探针", PageRequest.of(0, 5));
        assertTrue(traces.getContent().stream().anyMatch(t -> username.equals(t.getUserId())),
                "Trace 分页应能按用户+关键字查出记录");

        // 9) 用户维度用量 Top 排行（探针用户记 3 条，问答数超过其他仅 1 条的存量用户，确保进 Top-N）
        usageStatService.record("default", username, null, "排行探针", "回答", 10, 20, 100, 3);
        usageStatService.record("default", username, null, "排行探针", "回答", 10, 20, 100, 3);
        usageStatService.record("default", username, null, "排行探针", "回答", 10, 20, 100, 3);
        List<UserUsageView> top = usageStatService.topUsers("default", 7, 10);
        assertTrue(top.stream().anyMatch(v -> username.equals(v.getUserId()) && v.getQuestions() >= 3),
                "Top 用户排行应包含刚记录用量的用户");
        assertTrue(top.get(0).getQuestions() >= top.get(top.size() - 1).getQuestions(),
                "排行应按问答数降序");
    }

    /** 用例 4：P2 平台化——运行时配置热更新、文档在线编辑、运营告警、登录防爆破锁定/解锁。 */
    @Test
    void adminOps2_configEditAlertsGuard() throws Exception {
        // 1) 运行时配置：默认值读取 → 覆盖即时生效 → 清除恢复默认 → 非法值/未知键拒绝
        int defaultTopK = runtimeConfig.getInt("retrieval.final-top-k");
        runtimeConfig.update(Map.of("retrieval.final-top-k", String.valueOf(defaultTopK + 1)), "admin");
        assertEquals(defaultTopK + 1, runtimeConfig.getInt("retrieval.final-top-k"), "覆盖后应读取新值");
        runtimeConfig.update(Map.of("retrieval.final-top-k", ""), "admin");
        assertEquals(defaultTopK, runtimeConfig.getInt("retrieval.final-top-k"), "空值应恢复默认");
        assertThrows(IllegalArgumentException.class,
                () -> runtimeConfig.update(Map.of("retrieval.final-top-k", "abc"), "admin"), "非法取值应拒绝");
        assertThrows(IllegalArgumentException.class,
                () -> runtimeConfig.update(Map.of("unknown.key", "1"), "admin"), "未知键应拒绝");

        // 2) 文档在线编辑：非受管可编辑（hash 变化 + 重建索引成功）
        KnowledgeDocumentEntity doc = uploadDoc(newMarker(), null);
        String newContent = "# 编辑后标题\n\n新内容 " + newMarker();
        KbDocumentView edited = kbDocumentService.updateContent(doc.getId(), "default", "编辑后文档", newContent, "admin");
        assertEquals("编辑后文档", edited.getTitle());
        KnowledgeDocumentEntity afterEdit = documentRepository.findById(doc.getId()).orElseThrow();
        assertNotEquals(doc.getContentHash(), afterEdit.getContentHash(), "编辑后内容 hash 应变化");
        assertEquals(KbDocumentStatus.INDEXED, afterEdit.getStatus(), "编辑后应重建索引成功");

        // 3) docs 同步受管（SYNC）文档拒绝在线编辑
        Path syncRoot = Files.createTempDirectory("kb-edit-itest");
        Files.writeString(syncRoot.resolve("s.md"), "# 受管文档\n\n内容 " + newMarker(), StandardCharsets.UTF_8);
        awaitTaskDone(kbDocumentService.syncDocs(syncRoot.toString(), "itest", "default"));
        KnowledgeDocumentEntity syncDoc = documentRepository
                .findByLibraryIdAndFilePath("default", "s.md").orElseThrow();
        IllegalArgumentException syncErr = assertThrows(IllegalArgumentException.class,
                () -> kbDocumentService.updateContent(syncDoc.getId(), "default", null, "新内容", "admin"));
        assertTrue(syncErr.getMessage().contains("受管"), "SYNC 文档编辑应被拒绝并提示");

        // 4) 运营告警：写入 → 同类去重 → 处理后消失（先清历史避免跨次运行残留）
        kbAlertService.openAlerts("default").stream()
                .filter(a -> "ITEST_ALERT".equals(a.getType()))
                .forEach(a -> kbAlertService.resolve(a.getId(), "admin"));
        assertTrue(kbAlertService.raise("default", "ITEST_ALERT", "WARN", "测试告警", "detail"), "首次写入应成功");
        assertFalse(kbAlertService.raise("default", "ITEST_ALERT", "WARN", "测试告警", "detail"),
                "同类未处理告警应去重");
        assertEquals(1, kbAlertService.openAlerts("default").stream()
                .filter(a -> "ITEST_ALERT".equals(a.getType())).count());
        Long alertId = kbAlertService.openAlerts("default").stream()
                .filter(a -> "ITEST_ALERT".equals(a.getType())).findFirst().orElseThrow().getId();
        kbAlertService.resolve(alertId, "admin");
        assertTrue(kbAlertService.openAlerts("default").stream()
                .noneMatch(a -> "ITEST_ALERT".equals(a.getType())), "处理后不应再出现在未处理列表");

        // 5) 登录防爆破（Redis 实现）：达阈值锁定 → 列表可见 → 解锁恢复
        String victim = newUsername();
        for (int i = 0; i < 10; i++) {
            loginGuard.recordFailure(victim);
        }
        assertTrue(loginGuard.isLocked(victim), "连续失败达阈值应锁定");
        assertTrue(loginGuard.listLocked().stream().anyMatch(l -> victim.equals(l.username())),
                "锁定列表应包含该账号");
        loginGuard.unlock(victim);
        assertFalse(loginGuard.isLocked(victim), "解锁后应可正常登录");
    }

}
