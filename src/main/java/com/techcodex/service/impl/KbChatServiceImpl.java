package com.techcodex.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import com.techcodex.aiservice.KbAssistant;
import com.techcodex.common.CircuitBreaker;
import com.techcodex.common.CitationGuard;
import com.techcodex.common.RateLimiter;
import com.techcodex.common.ThinkingStripper;
import com.techcodex.common.TokenEstimator;
import com.techcodex.content.ContentGuard;
import com.techcodex.dto.KbChunkHit;
import com.techcodex.entity.ChatHistoryEntity;
import com.techcodex.repository.ChatHistoryRepository;
import com.techcodex.repository.ChatHistoryRepository.ConversationIdRow;
import com.techcodex.service.KbChatService;
import com.techcodex.service.KbSearchService;
import com.techcodex.service.UsageStatService;
import com.techcodex.vo.ChatEvent;
import com.techcodex.vo.ChatHistoryVo;
import com.techcodex.vo.ConversationVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * 知识库问答服务实现：校验（空/长度/限流/熔断）→ 检索增强流式回答 → done 前同步持久化聊天历史。
 */
@Slf4j
@Service
public class KbChatServiceImpl implements KbChatService {

    private final KbAssistant kbAssistant;
    private final RateLimiter rateLimiter;
    private final CircuitBreaker circuitBreaker;
    private final KbSearchService kbSearchService;
    private final ObjectMapper objectMapper;
    private final ChatHistoryRepository chatHistoryRepository;
    private final Executor chatHistoryExecutor;
    private final Executor queryRewriteExecutor;
    private final ChatModel chatModel;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final com.techcodex.config.KbConfig kbConfig;
    private final com.techcodex.repository.KnowledgeDocumentRepository documentRepository;
    /** 运行时配置存储：意图路由/拒答/改写等开关在线可调，问答链路每次读取生效值。 */
    private final com.techcodex.config.RuntimeConfigStore runtimeConfig;
    private final com.techcodex.quota.QuotaService quotaService;
    private final ContentGuard contentGuard;
    private final UsageStatService usageStatService;
    private final int messageMaxLength;

    /** 语义缓存：字段注入避免把缓存依赖扩散到既有构造函数与测试；缓存自身失败会降级 miss。 */
    @Autowired
    private com.techcodex.service.SemanticCacheService semanticCacheService;

    /** 内容缺口：旁路采集负向信号，写入失败不影响问答。 */
    @Autowired
    private com.techcodex.service.ContentGapService contentGapService;

    /** 文档内问答整文档回退的正文上限（字符）：超长文档截断注入，防撑爆上下文。 */
    private static final int DOC_FALLBACK_MAX_CHARS = 12_000;

    /** 知识库无相关内容时的兜底回答：同时用于 SSE 提示与历史落库，保证刷新/重开会话后提示仍在。 */
    private static final String NOT_FOUND_ANSWER =
            "知识库中未检索到与您问题明确相关的内容。请尝试换个更具体的说法，或联系管理员补充文档。";

    /** 单轮问答展示的引用来源上限：超出部分对用户无增量信息，只会挤占回答区域。 */
    private static final int MAX_SOURCES = 8;

    public KbChatServiceImpl(KbAssistant kbAssistant,
                             RateLimiter rateLimiter,
                             CircuitBreaker circuitBreaker,
                             KbSearchService kbSearchService,
                             ObjectMapper objectMapper,
                             ChatHistoryRepository chatHistoryRepository,
                             @Qualifier("chatHistoryExecutor") Executor chatHistoryExecutor,
                             @Qualifier("queryRewriteExecutor") Executor queryRewriteExecutor,
                             @Qualifier("openAiChatModel") ChatModel chatModel,
                             org.springframework.transaction.PlatformTransactionManager transactionManager,
                             com.techcodex.config.KbConfig kbConfig,
                             com.techcodex.repository.KnowledgeDocumentRepository documentRepository,
                             com.techcodex.config.RuntimeConfigStore runtimeConfig,
                             com.techcodex.quota.QuotaService quotaService,
                             ContentGuard contentGuard,
                             UsageStatService usageStatService,
                             @Value("${app.limit.chat-message-max-length:1000}") int messageMaxLength) {
        this.kbAssistant = kbAssistant;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.kbSearchService = kbSearchService;
        this.objectMapper = objectMapper;
        this.chatHistoryRepository = chatHistoryRepository;
        this.chatHistoryExecutor = chatHistoryExecutor;
        this.queryRewriteExecutor = queryRewriteExecutor;
        this.chatModel = chatModel;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.kbConfig = kbConfig;
        this.documentRepository = documentRepository;
        this.runtimeConfig = runtimeConfig;
        this.quotaService = quotaService;
        this.contentGuard = contentGuard;
        this.usageStatService = usageStatService;
        this.messageMaxLength = messageMaxLength;
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message) {
        return chatStream(userId, conversationId, message, null, null, null);
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message, String docPath) {
        return chatStream(userId, conversationId, message, docPath, null, null);
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message, String docPath, String scope) {
        return chatStream(userId, conversationId, message, docPath, scope, null);
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message,
                                                    String docPath, String scope, String category) {
        return chatStream(userId, conversationId, message, docPath, scope, category, false);
    }

    @Override
    public Flux<ServerSentEvent<String>> chatStream(String userId, String conversationId, String message,
                                                    String docPath, String scope, String category,
                                                    boolean regenerate) {
        // 文档相对路径归一（docs 根相对，'/' 分隔）：空串视为全局问答
        final String pathFilter = (docPath == null || docPath.isBlank()) ? null : docPath.trim().replace('\\', '/');
        // 课程分类过滤：与 pathFilter 同为 docId 集合交集语义；单文档锁定优先，category 失效
        final String categoryFilter = pathFilter != null || category == null || category.isBlank()
                ? null : category.trim();
        if (userId == null || userId.isBlank()) {
            return Flux.just(ChatEvent.error("用户ID不能为空。").toSse());
        }
        if (message == null || message.isBlank()) {
            return Flux.just(ChatEvent.error("请输入您要咨询的问题。").toSse());
        }
        if (message.length() > messageMaxLength) {
            log.warn("消息超长已拦截 - length: {}, max: {}", message.length(), messageMaxLength);
            return Flux.just(ChatEvent.error("问题过长，请控制在 " + messageMaxLength + " 字以内。").toSse());
        }
        // O2：输入内容安全审核（命中敏感词直接拦截，不消耗限流/配额，也不进入 LLM）
        if (contentGuard.isEnabled()) {
            java.util.Optional<String> violation = contentGuard.findViolation(message);
            if (violation.isPresent()) {
                log.warn("输入命中敏感词已拦截 - userId: {}, word: {}", userId, violation.get());
                return Flux.just(ChatEvent.error("内容包含敏感词，请重新输入。").toSse());
            }
        }
        if (!rateLimiter.tryAcquire(userId)) {
            log.warn("触发限流 - userId: {}", userId);
            return Flux.just(ChatEvent.error("请求过于频繁，请稍后再试。").toSse());
        }
        if (!circuitBreaker.allowRequest()) {
            log.warn("LLM 熔断中，拒绝请求 - userId: {}", userId);
            return Flux.just(ChatEvent.error("服务繁忙，请稍后再试。").toSse());
        }
        // M3：每日问答配额扣减（超限返回可读错误，不进入 LLM）。
        // 空间在此处（请求线程，仍持有登录态）一次性解析：后续 SSE 流式回调运行在
        // Reactor/LLM 流线程上，SecurityContext（ThreadLocal）不可见，再读会错误回落默认空间。
        // 配额/历史/检索范围统一以"个人知识库空间"（u-{username}）为主：存量用户 token 仍指
        // default 时同样定位到自己的私有库，且与「我的知识库」配额展示同源。
        // 身份在请求线程（仍持有登录态）一次性解析：后续 SSE 流式回调运行在
        // Reactor/LLM 流线程上，SecurityContext（ThreadLocal）不可见。
        final String requestUsername = com.techcodex.security.SecurityUtil.currentUsername();
        final String libraryId = requestUsername == null || requestUsername.isBlank()
                ? com.techcodex.security.SecurityUtil.currentLibraryId(kbConfig.getPublicLibrary())
                : com.techcodex.library.KbLibrary.personalCode(requestUsername);
        // 检索范围（scope）在请求线程一并解析为空间集合：文档内问答（docPath）锁定公共文档；
        // 否则按 scope 展开（public=default / mine=个人空间 / all=两者并集）。
        final java.util.List<String> scopeLibraries = pathFilter != null
                ? java.util.List.of(kbConfig.getPublicLibrary())
                : com.techcodex.library.KbScopes.resolve(scope, requestUsername, libraryId, kbConfig.getPublicLibrary());
        try {
            quotaService.consumeChat(libraryId);
        } catch (com.techcodex.exception.QuotaExceededException e) {
            log.warn("问答配额超限 - userId: {}, msg: {}", userId, e.getMessage());
            return Flux.just(ChatEvent.error(e.getMessage()).toSse());
        }
        // 对话ID：为空则新建（回写前端的首帧）；非空则沿用，保证多轮上下文归属同一对话
        String cid = (conversationId == null || conversationId.isBlank())
                ? UUID.randomUUID().toString()
                : conversationId.trim();
        // 重新生成：先删掉该对话最后一轮问答（上一条助手回答 + 对应提问），
        // 让本轮回答以"替换"而非"追加"的方式落库，否则同一问答会在历史里出现两份
        if (regenerate) {
            purgeLastTurn(userId, cid);
        }
        // 把本次问答的检索空间集合绑定到对话ID：Agentic 工具（文档清单）在无登录态的
        // 回调线程上执行，经对话ID换取空间，fail-closed 于未绑定的对话。
        com.techcodex.tools.KbLibraryTools.bind(cid, scopeLibraries);
        log.info("知识问答开始 - userId: {}, conversationId: {}, docScope: {}, message: {}",
                userId, cid, pathFilter == null ? "-" : pathFilter, message);
        StringBuilder buffer = new StringBuilder();
        long startNanos = System.nanoTime();
        // 缓存上下文要包含检索范围/文档锁定/分类，避免把不同范围的回答互相复用
        final String cacheContext = String.join(",", scopeLibraries)
                + "|" + (pathFilter == null ? "-" : pathFilter)
                + "|" + (categoryFilter == null ? "-" : categoryFilter)
                + "|k=" + runtimeConfig.getInt("retrieval.final-top-k");
        // 语义缓存按“实际文档空间”隔离，而不是按登录用户的个人库：public 检索读的是 default，
        // 若用个人库做失效键，管理员重建 default 文档后公共问答缓存永远不会失效。
        // 多空间（scope=all）无法用单版本键表达失效，直接跳过缓存，避免脏复用。
        final String cacheLibraryId = scopeLibraries.size() == 1 ? scopeLibraries.get(0) : null;
        // 缺口归到公共库，个人库问题不会进入后台；public/所有范围含 default 时统一落 default
        final String gapLibraryId = scopeLibraries.contains(kbConfig.getPublicLibrary())
                ? kbConfig.getPublicLibrary() : libraryId;

        // 意图路由：非知识类问题（寒暄/简单指令/感谢等）无需检索知识库，直接普通对话，跳过检索与溯源。
        // 文档内问答（docScope 存在）按定义就是知识问题，跳过闲聊路由，保证始终带文档上下文回答。
        if (pathFilter == null && runtimeConfig.getBool("retrieval.intent-routing-enabled") && isNonKnowledgeIntent(message)) {
            log.info("识别为闲聊意图，跳过知识库检索 - userId: {}, message: {}", userId, truncateForLog(message));
            String history = buildHistory(userId, cid, message);
            return Flux.concat(
                    Flux.just(ChatEvent.conversation(cid).toSse()),
                    Flux.just(ChatEvent.intention("general_chat").toSse()),
                    ThinkingStripper.stripPrefix(kbAssistant.answer(userId, cid, history, message, ""))
                            .map(text -> {
                                // O2：输出脱敏（SSE 与入库同源安全）
                                String safe = contentGuard.sanitize(text);
                                buffer.append(safe);
                                return ChatEvent.token(safe).toSse();
                            }),
                    Flux.just(ChatEvent.done("完成").toSse())
            ).concatWith(Flux.defer(() -> {
                saveHistory(userId, cid, message, buffer.toString(), libraryId);
                recordUsage(libraryId, userId, cid, message, buffer.toString(), history, "", 0, startNanos);
                return Flux.empty();
            }))
            .doOnComplete(() -> circuitBreaker.recordSuccess())
            .doOnError(error -> {
                circuitBreaker.recordFailure();
                log.error("知识问答失败 - userId: {}, conversationId: {}", userId, cid, error);
            });
        }

        // 语义缓存命中：直接回放回答与 sources，跳过查询改写/检索/LLM；仍需落历史与用量，保证会话连续。
        java.util.Optional<com.techcodex.service.SemanticCacheService.Hit> cached =
                cacheLibraryId == null
                        ? java.util.Optional.empty()
                        : semanticCacheService.lookup(message, cacheLibraryId, cacheContext);
        if (cached.isPresent()) {
            com.techcodex.service.SemanticCacheService.Hit hit = cached.get();
            List<KbChunkHit> cachedSources = dedupeAndCap(hit.sources());
            markCitationIds(cachedSources);
            String cachedSourcesJson = toJson(cachedSources);
            log.info("语义缓存命中 - userId: {}, similarity: {}", userId, hit.similarity());
            return Flux.concat(
                    Flux.just(ChatEvent.conversation(cid).toSse()),
                    Flux.just(ChatEvent.phase("语义缓存命中，正在回放…").toSse()),
                    Flux.just(ChatEvent.sources(cachedSourcesJson).toSse()),
                    Flux.just(ChatEvent.token(hit.answer()).toSse()),
                    Flux.just(ChatEvent.done("完成").toSse())
            ).concatWith(Flux.defer(() -> {
                saveHistory(userId, cid, message, hit.answer(), libraryId);
                recordUsage(libraryId, userId, cid, message, hit.answer(), "", "", cachedSources.size(),
                        startNanos, true, TokenEstimator.estimate(hit.answer()));
                scheduleGapSignals(gapLibraryId, userId, cid, message, cachedSources, hit.answer(), false, false, false);
                return Flux.empty();
            })).doOnComplete(() -> circuitBreaker.recordSuccess())
                    .doOnError(error -> {
                        circuitBreaker.recordFailure();
                        log.error("缓存回放失败 - userId: {}, conversationId: {}", userId, cid, error);
                    });
        }

        // 检索前用 DeepSeek 做查询改写（1~3 条子查询，提升召回质量），失败/超时降级为原 query
        double topScore = 0d;
        boolean relevant = true;
        String sourcesJson = "[]";
        List<KbChunkHit> sources = java.util.Collections.emptyList();
        try {
            List<String> rewritten = rewriteQuery(message);
            // 多路并行检索知识库，把溯源引用作为 sources 事件推给前端
            // 召回上限由配置 app.kb.retrieval.final-top-k 驱动，避免与配置脱节；
            // docScope 非空时检索范围锁定到该文档（filePath 前缀匹配）；
            // 空间与用户名都在请求线程解析后显式传入（异步线程读不到 SecurityContext；
            // 用户名取登录态而非 userId 参数，服务级直调无登录态时 ACL 自然豁免）
            sources = kbSearchService.searchMultiAs(scopeLibraries,
                    com.techcodex.security.SecurityUtil.currentUsername(), rewritten, runtimeConfig.getInt("retrieval.final-top-k"), pathFilter, categoryFilter);

            // 文档内问答（docScope）的 0 命中兜底：总结/概览类问题（如 UI 示例「总结要点」）
            // 与正文片段无语义重叠，向量检索必然 0 命中并被相关度判定拒绝；但用户已显式
            // 锁定该文档，此时直接加载文档正文作为整文档证据（截断注入），而非拒答。
            if (pathFilter != null && sources.isEmpty()) {
                var scopedDoc = documentRepository.findByLibraryIdAndFilePath(kbConfig.getPublicLibrary(), pathFilter);
                if (scopedDoc.isPresent() && scopedDoc.get().getContent() != null && !scopedDoc.get().getContent().isBlank()) {
                    String content = scopedDoc.get().getContent();
                    String text = content.length() > DOC_FALLBACK_MAX_CHARS
                            ? content.substring(0, DOC_FALLBACK_MAX_CHARS) + "…（长文档已截断）" : content;
                    sources = List.of(KbChunkHit.builder()
                            .docId(scopedDoc.get().getId())
                            .chunkIndex(0)
                            .title(scopedDoc.get().getTitle())
                            .filePath(scopedDoc.get().getFilePath())
                            .text(text)
                            .score(1.0)
                            .source("DOCUMENT")
                            .build());
                    log.info("文档内问答检索 0 命中，已回退为整文档证据 - docId: {}, path: {}, 注入字符: {}",
                            scopedDoc.get().getId(), pathFilter, text.length());
                }
            }
            // 来源去重与截断（同文档同小节保留最高分），避免重复分块/低分噪声挤满引用区
            sources = dedupeAndCap(sources);
            markCitationIds(sources);
            sourcesJson = toJson(sources);

            // 相关度判定（RelevanceChecker 拒答）：
            // 即便触发知识库检索，若融合后最高相关分仍低于阈值，判定知识库无足够相关内容，
            // 拒绝基于资料硬答（抑制幻觉），推 intention=knowledge_not_found 供前端给出空态提示。
            // 注意：relevance-min-score 基于 reranker 分数（logit）口径，仅在语义重排生效时才启用拒答；
            // 未开重排时 TOP 分是向量余弦相似度、量纲不同，宁可不拒答也不误伤高相关命中。
            topScore = sources.stream()
                    .map(KbChunkHit::getScore)
                    .filter(Objects::nonNull)
                    .mapToDouble(Double::doubleValue)
                    .max()
                    .orElse(0d);
            boolean rerankOn = runtimeConfig.getBool("retrieval.rerank-enabled");
            relevant = !sources.isEmpty()
                    && (!runtimeConfig.getBool("retrieval.relevance-check-enabled")
                        || !rerankOn || topScore >= runtimeConfig.getDouble("retrieval.relevance-min-score"));
        } catch (Exception e) {
            // 检索链路异常（嵌入模型/Redis/向量库故障）不抛 500，降级为可读错误（熔断由调用方按错误计数）
            log.error("检索链路异常 - userId: {}, message: {}", userId, truncateForLog(message), e);
            return Flux.concat(
                    Flux.just(ChatEvent.conversation(cid).toSse()),
                    Flux.just(ChatEvent.error("检索服务暂时不可用，请稍后再试。").toSse())
            );
        }
        // 清单类元问题（"库里有哪些文档/多少篇"）与文档正文无语义重叠，检索必然 0 命中；
        // 不走拒答短路，放行到回答路径由模型调用 listDocuments 工具查询真实清单。
        boolean libraryMetaQuestion = pathFilter == null && LIBRARY_META_QUESTION.matcher(message).find();
        if (!relevant && !libraryMetaQuestion) {
            log.info("相关度判定：知识库无足够相关内容 - userId: {}, topScore: {}", userId, String.format("%.3f", topScore));
            final int hitCount = sources.size();
            final java.util.List<KbChunkHit> gapSources = java.util.List.copyOf(sources);
            return Flux.concat(
                    Flux.just(ChatEvent.conversation(cid).toSse()),
                    // 拒答时不下发 sources：检索结果均为低分/不相关片段，展示成"引用来源"只会误导用户
                    Flux.just(ChatEvent.intention("knowledge_not_found").toSse()),
                    Flux.just(ChatEvent.done("完成").toSse())
            ).concatWith(Flux.defer(() -> {
                // 拒答分支不产生实质回答：历史里写入兜底文案而非空串，
                // 否则重新打开会话时，空回答会被前端渲染成永久"加载中"骨架
                saveHistory(userId, cid, message, NOT_FOUND_ANSWER, libraryId);
                recordUsage(libraryId, userId, cid, message, "", "", "", hitCount, startNanos);
                scheduleGapSignals(gapLibraryId, userId, cid, message, gapSources, "", false, true, false);
                return Flux.empty();
            }))
            .doOnComplete(() -> circuitBreaker.recordSuccess())
            .doOnError(error -> {
                circuitBreaker.recordFailure();
                log.error("知识问答失败 - userId: {}, conversationId: {}", userId, cid, error);
            });
        }

        // 多轮上下文：同对话内最近若干条历史（拼接文本注入，保留 AiService 的工具调用能力）
        String history = buildHistory(userId, cid, message);
        String sourcesContext = buildSourcesContext(sources);
        if (libraryMetaQuestion && sources.isEmpty()) {
            // 预检索 0 命中的清单类元问题：不注入空证据，改为引导模型调用 listDocuments 工具
            sourcesContext = "（预检索未命中：该问题是在询问知识库空间的文档清单/概况。"
                    + "请调用 listDocuments 工具查询当前知识库空间的文档列表，并严格基于工具返回的真实清单回答，禁止编造文档。）";
        }
        final String promptSourcesContext = sourcesContext;
        final int hitCount = sources.size();
        final java.util.List<KbChunkHit> cacheSources = sources;
        final CitationGuard citationGuard = new CitationGuard(hitCount);
        return Flux.concat(
                Flux.just(ChatEvent.conversation(cid).toSse()),
                Flux.just(ChatEvent.phase("正在检索知识库并组织回答…").toSse()),
                Flux.just(ChatEvent.sources(sourcesJson).toSse()),
                ThinkingStripper.stripPrefix(kbAssistant.answer(userId, cid, history, message, sourcesContext)).map(text -> {
                    // O2：输出脱敏（SSE 与入库同源安全）
                    String safe = contentGuard.sanitize(text);
                    String clean = citationGuard.filter(safe);
                    buffer.append(clean);
                    return ChatEvent.token(clean).toSse();
                }),
                Flux.defer(() -> {
                    // 流结束才把引用缓存尾部的未闭合文本吐出，避免吞正文
                    String tail = citationGuard.flush();
                    if (tail.isEmpty()) return Flux.<ServerSentEvent<String>>empty();
                    buffer.append(tail);
                    return Flux.just(ChatEvent.token(tail).toSse());
                }),
                Flux.just(ChatEvent.done("完成").toSse())
        ).concatWith(Flux.defer(() -> {
                    // 在发出 done 前同步落库，确保完成时会话列表/历史立即可查（避免异步竞态导致新建会话不即时出现）
                    saveHistory(userId, cid, message, buffer.toString(), libraryId);
                    recordUsage(libraryId, userId, cid, message, buffer.toString(), history, promptSourcesContext, hitCount, startNanos);
                    scheduleGapSignals(gapLibraryId, userId, cid, message, cacheSources, buffer.toString(),
                            !history.isBlank(), false, false);
                    // 缓存写入放在回答完成后，避免把流式中间态写进缓存；失败只降级 miss
                    if (cacheLibraryId != null) {
                        semanticCacheService.put(message, cacheLibraryId, cacheContext, buffer.toString(), cacheSources);
                    }
                    return Flux.empty();
                }))
                .doOnComplete(() -> circuitBreaker.recordSuccess())
                .doOnError(error -> {
                    circuitBreaker.recordFailure();
                    log.error("知识问答失败 - userId: {}, conversationId: {}", userId, cid, error);
                });
    }

    /**
     * 组装多轮上下文文本：取同对话最近若干条历史（倒序读出需正序），末尾补当前问题。
     * 历史为异步落库，存在轻微竞态（上一轮可能尚未写入），属可接受降级。
     */
    private String buildHistory(String userId, String conversationId, String currentMessage) {
        List<ChatHistoryEntity> recent = chatHistoryRepository
                .findTop10BySessionIdAndConversationIdOrderByIdDesc(userId, conversationId);
        StringBuilder sb = new StringBuilder();
        // 倒序 → 正序（最早在前）
        for (int i = recent.size() - 1; i >= 0; i--) {
            ChatHistoryEntity e = recent.get(i);
            String who = "user".equals(e.getRole()) ? "用户" : "助手";
            sb.append(who).append("：").append(e.getContent()).append("\n");
        }
        sb.append("用户：").append(currentMessage);
        return sb.toString();
    }

    @Override
    public Page<ChatHistoryVo> queryHistory(String userId, String conversationId, Pageable page) {
        Page<ChatHistoryEntity> entityPage = (conversationId == null || conversationId.isBlank())
                ? chatHistoryRepository.findAllBySessionId(userId, page)
                : chatHistoryRepository.findAllBySessionIdAndConversationId(userId, conversationId, page);
        List<ChatHistoryVo> voList = new ArrayList<>(entityPage.getContent().size());
        for (ChatHistoryEntity entity : entityPage.getContent()) {
            voList.add(ChatHistoryVo.of(entity.getRole(), entity.getContent(), entity.getCreatedDate()));
        }
        return new PageImpl<>(voList, entityPage.getPageable(), entityPage.getTotalElements());
    }

    @Override
    public void clearHistory(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            chatHistoryRepository.deleteBySessionId(userId);
        } else {
            chatHistoryRepository.deleteBySessionIdAndConversationId(userId, conversationId);
        }
    }

    @Override
    public List<ConversationVo> listConversations(String userId) {
        List<ConversationIdRow> rows = chatHistoryRepository.findConversationIdsBySessionId(userId);
        if (rows.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        // 批量取回首条用户消息与最新消息实体，一次查询定位，避免逐会话 N+1 查询
        java.util.Set<Long> firstIds = new java.util.HashSet<>();
        java.util.Set<Long> lastIds = new java.util.HashSet<>();
        for (ConversationIdRow row : rows) {
            if (row.getFirstUserId() != null) {
                firstIds.add(row.getFirstUserId());
            }
            if (row.getLastId() != null) {
                lastIds.add(row.getLastId());
            }
        }
        java.util.Map<Long, ChatHistoryEntity> firstById = toByIdMap(chatHistoryRepository.findAllById(firstIds));
        java.util.Map<Long, ChatHistoryEntity> lastById = toByIdMap(chatHistoryRepository.findAllById(lastIds));

        List<ConversationVo> result = new ArrayList<>(rows.size());
        for (ConversationIdRow row : rows) {
            String cid = row.getConversationId();
            if (cid == null || cid.isBlank()) {
                continue;
            }
            ChatHistoryEntity first = row.getFirstUserId() == null ? null : firstById.get(row.getFirstUserId());
            ChatHistoryEntity last = row.getLastId() == null ? null : lastById.get(row.getLastId());
            String title = first != null ? truncateTitle(first.getContent()) : "";
            Date createdAt = first != null ? first.getCreatedDate() : null;
            Date updatedAt = last != null
                    ? (last.getUpdatedDate() != null ? last.getUpdatedDate() : last.getCreatedDate())
                    : null;
            // 标题兜底：未取到用户消息时用会话ID尾部
            if (title.isEmpty()) {
                title = cid.length() > 24 ? cid.substring(0, 24) : cid;
            }
            result.add(ConversationVo.of(cid, title, row.getCnt(), createdAt, updatedAt));
        }
        return result;
    }

    /** 将实体列表按 id 建索引，便于批量引用。 */
    private java.util.Map<Long, ChatHistoryEntity> toByIdMap(Iterable<ChatHistoryEntity> entities) {
        java.util.Map<Long, ChatHistoryEntity> map = new java.util.HashMap<>();
        for (ChatHistoryEntity e : entities) {
            map.put(e.getId(), e);
        }
        return map;
    }

    @Override
    public void deleteConversation(String userId, String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        chatHistoryRepository.deleteBySessionIdAndConversationId(userId, conversationId);
    }

    /** 会话标题截取，避免超长。 */
    private String truncateTitle(String content) {
        if (content == null) {
            return "";
        }
        String line = content.lines().filter(s -> !s.isBlank()).findFirst().orElse("");
        line = line.replaceAll("\\s+", " ").trim();
        return line.length() <= 30 ? line : line.substring(0, 30) + "…";
    }

    /** 给问答溯源命中编号：与回答中的 [n] 一一对应，顺序即 sources 事件顺序。 */
    private void markCitationIds(List<KbChunkHit> sources) {
        if (sources == null) return;
        for (int i = 0; i < sources.size(); i++) {
            sources.get(i).setCitationId(String.valueOf(i + 1));
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("溯源序列化失败", e);
            return "[]";
        }
    }

    /**
     * 把 service 层已检索命中片段组装为可读证据文本，直接作为"首轮检索结果"注入模型，
     * 避免模型再经 firstSearch 工具重复全量检索（消除重复首轮检索）。片段即最终会推给前端的溯源集。
     */
    /** 父级上下文注入上限 800 字：只补“短子块”缺失的定义/前置说明。 */
    private String truncateParentContext(String text) {
        return text.length() <= 800 ? text : text.substring(0, 800);
    }

    private String buildSourcesContext(List<KbChunkHit> sources) {
        if (sources == null || sources.isEmpty()) {
            return "（未检索到知识库证据片段）";
        }
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (KbChunkHit hit : sources) {
            sb.append("[").append(hit.getCitationId() == null ? idx : hit.getCitationId()).append("] 《")
                    .append(hit.getTitle() == null ? "" : hit.getTitle()).append("》");
            if (hit.getFilePath() != null && !hit.getFilePath().isBlank()) {
                sb.append("（").append(hit.getFilePath()).append("）");
            }
            if (hit.getScore() != null) {
                sb.append("  相关度≈").append(String.format("%.3f", hit.getScore()));
            }
            if (hit.getParentHeadingPath() != null && !hit.getParentHeadingPath().isBlank()) {
                sb.append("｜章节：").append(hit.getParentHeadingPath());
            }
            String evidence = hit.getText() == null ? "" : hit.getText();
            sb.append("\n").append(evidence);
            // 子块很短时（表格/一句话定义），补父级章节上下文，避免模型只看到孤立命中；
            // 长子块本身已含前后文，不再重复塞父块，控制 prompt 体积。
            if (hit.getParentContext() != null && !hit.getParentContext().isBlank()
                    && evidence.length() < 160) {
                sb.append("\n章节上下文：").append(truncateParentContext(hit.getParentContext()));
            }
            sb.append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 意图路由：基于规则判断是否为"非知识类"意图（寒暄/简单指令/感谢等），是则跳过知识库检索。
     * 采用轻量启发式而非 LLM 分类，避免每次对话额外一次模型调用；宁可误判走检索也不阻塞正常问答。
     * 匹配规则放宽到"以寒暄/致谢词开头 + 短句"（≤14 字），以覆盖"谢谢你刚才的回答"这类致谢；
     * 超过长度上限或包含实质问句则视为知识类，进入正常检索。
     */
    private static final int NON_KNOWLEDGE_MAX_LENGTH = 14;
    /** 清单类元问题：询问知识库里有哪些文档/多少篇等，与文档正文无语义重叠，检索必然 0 命中。 */
    private static final java.util.regex.Pattern LIBRARY_META_QUESTION = java.util.regex.Pattern.compile(
            "(知识库|文档库|资料库|私有库|我的库|库里)[^。？！]{0,8}(有哪些|有什么|都有什么|多少|几篇|列出|清单|列表|目录|概况|简介)"
                    + "|列出[^。？！]{0,6}(文档|清单)|文档清单|库(里|中)(有|都)什么");
    private static final java.util.regex.Pattern NON_KNOWLEDGE_INTENT =
            java.util.regex.Pattern.compile(
                    "^(你好|您好|嗨|哈喽|hello|hi|hey|谢谢|感谢|谢了|多谢|再见|拜拜|在吗|你是谁|你能做什么|介绍一下自己|"
                            + "好的|可以|没问题|收到|ok|嗯|哦|over)\\s*[^\\p{Punct}]{0,10}[\\p{Punct}〜～]*$",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

    private boolean isNonKnowledgeIntent(String message) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        // 以寒暄/致谢词开头、整体为短句 → 非知识类
        return trimmed.length() <= NON_KNOWLEDGE_MAX_LENGTH && NON_KNOWLEDGE_INTENT.matcher(trimmed).matches();
    }

    /** 日志用消息截断，避免超长消息刷爆日志。 */
    private String truncateForLog(String message) {
        if (message == null) {
            return "";
        }
        String line = message.replaceAll("\\s+", " ").trim();
        return line.length() <= 30 ? line : line.substring(0, 30) + "…";
    }

    /**
     * 检索前查询改写：用 DeepSeek 把用户口语化/多轮问题改写为更适合知识库检索的查询，
     * 保留关键技术术语、去除口语冗余。带超时与降级——任何异常或超时都退回原始 query，
     * 保证检索链路不因此中断。
     */
    private static final String REWRITE_PROMPT =
            "你是一个技术文档知识库的检索查询改写器。请把用户的提问改写为 1~3 条最适合向量/关键词检索的简洁子查询，"
                    + "覆盖问题的不同方面，保留关键技术术语（API 名、错误码、类名）。"
                    + "每条子查询用换行分隔，只输出查询本身，不要编号、不要解释、不要加引号。\n"
                    + "用户问题：";

    /**
     * 查询改写：返回 1~3 条子查询（多路并行检索用）。任何异常/超时降级为仅原 query。
     * 在专用线程池上执行（阻塞 IO 不占用公共 ForkJoinPool）；超时后中断任务，避免超时请求
     * 在后台继续消耗 LLM 配额与线程。
     */
    private List<String> rewriteQuery(String message) {
        if (!runtimeConfig.getBool("retrieval.query-rewrite-enabled")) {
            return java.util.Collections.singletonList(message);
        }
        CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> chatModel.chat(REWRITE_PROMPT + message), queryRewriteExecutor);
        try {
            String rewritten = future.get(kbConfig.getQueryRewriteTimeoutMs(), TimeUnit.MILLISECONDS);
            if (rewritten == null || rewritten.isBlank()) {
                return java.util.Collections.singletonList(message);
            }
            List<String> queries = java.util.Arrays.stream(rewritten.split("\\r?\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .limit(3)
                    .collect(java.util.stream.Collectors.toList());
            return queries.isEmpty() ? java.util.Collections.singletonList(message) : queries;
        } catch (Exception e) {
            future.cancel(true);
            log.warn("查询改写失败/超时，降级使用原 query: {}", e.getMessage());
            return java.util.Collections.singletonList(message);
        }
    }

    /**
     * 异步采集缺口信号：未答、零引用、追问三类由问答链路产生，点踩由反馈入口单独写入。
     *
     * @param hasHistory 是否已有对话上下文（追问信号只在多轮里采集）
     * @param refused    拒答分支直接记 NO_ANSWER
     * @param cached     缓存回放只检查引用，不再重复判未答
     */
    private void scheduleGapSignals(String libraryId, String userId, String conversationId, String question,
                                    java.util.List<KbChunkHit> sources, String answer,
                                    boolean hasHistory, boolean refused, boolean cached) {
        if (contentGapService == null) return;
        try {
            chatHistoryExecutor.execute(() -> {
                try {
                    java.util.List<Long> docIds = sources == null ? java.util.List.of() :
                            sources.stream().map(KbChunkHit::getDocId).filter(java.util.Objects::nonNull).distinct().toList();
                    int citationCount = sources == null ? 0 : sources.size();
                    if (refused || citationCount == 0) {
                        contentGapService.recordSignal(libraryId, userId, conversationId, question,
                                "NO_ANSWER", docIds, null, refused ? "相关度拒答" : "检索 0 命中");
                    }
                    if (!refused && !hasValidCitation(answer, citationCount)) {
                        contentGapService.recordSignal(libraryId, userId, conversationId, question,
                                "ZERO_CITATION", docIds, null, "回答无有效 [n] 引用");
                    }
                    // 追问只在已有上下文的短问/承接词上采集；首轮问题不产生该信号
                    if (!cached && hasHistory && looksLikeFollowUp(question)) {
                        contentGapService.recordSignal(libraryId, userId, conversationId, question,
                                "FOLLOW_UP", docIds, null, "多轮追问");
                    }
                } catch (Exception e) {
                    // 旁路失败只吞掉，不向 SSE 线程抛出
                }
            });
        } catch (Exception ignored) {
            // 线程池拒绝时放弃该次信号，不影响回答
        }
    }

    /** 校验回答中的 [n] 是否至少有一个落在本次 sources 范围。 */
    private boolean hasValidCitation(String answer, int citationCount) {
        if (answer == null || citationCount <= 0) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[([1-9][0-9]*)\\]").matcher(answer);
        while (m.find()) {
            try {
                int n = Integer.parseInt(m.group(1));
                if (n >= 1 && n <= citationCount) return true;
            } catch (NumberFormatException ignored) {
                // 超长数字视为无效引用
            }
        }
        return false;
    }

    /** 短问/承接词视为追问；这是可解释的保守启发式，不做语义模型推断。 */
    private boolean looksLikeFollowUp(String question) {
        if (question == null) return false;
        String q = question.trim();
        if (q.length() <= 12) return true;
        return q.matches("^(那|那么|为什么|具体|还有|再|怎么|如何|可以|这个|继续|它|这).*")
                || q.endsWith("呢") || q.contains("没懂") || q.contains("详细");
    }

    /**
     * O1 用量埋点：估算输入/输出 token，经聊天历史线程池异步落库（旁路写入，失败仅记日志）。
     * 输入 token = 问题 + 历史 + 证据上下文；输出 token = 回答文本估算。
     * libraryId 由调用方在请求线程解析后传入——本方法在 SSE 流线程执行，读不到 SecurityContext。
     */
    private void recordUsage(String libraryId, String userId, String conversationId, String question, String answer,
                             String history, String sourcesContext, int hitCount, long startNanos) {
        recordUsage(libraryId, userId, conversationId, question, answer, history, sourcesContext, hitCount, startNanos,
                false, 0);
    }

    private void recordUsage(String libraryId, String userId, String conversationId, String question, String answer,
                             String history, String sourcesContext, int hitCount, long startNanos,
                             boolean cacheHit, int cacheSavedTokens) {
        try {
            int inputTokens = TokenEstimator.estimate(question)
                    + TokenEstimator.estimate(history)
                    + TokenEstimator.estimate(sourcesContext);
            int outputTokens = TokenEstimator.estimate(answer);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            chatHistoryExecutor.execute(() ->
                    usageStatService.record(libraryId, userId, conversationId, question, answer,
                            inputTokens, outputTokens, elapsedMs, hitCount, cacheHit, cacheSavedTokens));
        } catch (Exception e) {
            log.warn("记录聊天用量失败 - userId: {}, err: {}", userId, e.getMessage());
        }
    }

    /** 同步落库问答历史；libraryId 由调用方在请求线程解析后传入（流式回调线程无登录态）。 */
    /**
     * 重新生成：删除该对话最后一轮问答。
     * 仅在最后一条确实是助手回答时才连带删除其前面的用户提问；数据异常（最后一条是用户消息等）
     * 时只删这一条，避免误删更早的完整问答。
     */
    private void purgeLastTurn(String userId, String conversationId) {
        try {
            List<ChatHistoryEntity> lastTwo = chatHistoryRepository
                    .findTop2BySessionIdAndConversationIdOrderByIdDesc(userId, conversationId);
            if (lastTwo.isEmpty()) {
                return;
            }
            ChatHistoryEntity newest = lastTwo.get(0);
            if ("ai".equals(newest.getRole())) {
                lastTwo.forEach(chatHistoryRepository::delete);
            } else {
                chatHistoryRepository.delete(newest);
            }
        } catch (Exception e) {
            // 清理失败不阻断重生成：宁可历史多一条，也不能让"重新生成"点了没反应
            log.warn("重新生成前清理上一轮历史失败 - userId: {}, conversationId: {}", userId, conversationId, e);
        }
    }

    /**
     * 引用来源去重 + 截断：同一文档同一小节只保留分数最高的一块，
     * 保持首次出现的顺序（与模型回答里的引用编号大体一致），最多保留 MAX_SOURCES 条。
     */
    private List<KbChunkHit> dedupeAndCap(List<KbChunkHit> sources) {
        if (sources == null || sources.size() <= 1) {
            return sources;
        }
        java.util.Map<String, KbChunkHit> best = new java.util.LinkedHashMap<>();
        for (KbChunkHit hit : sources) {
            if (hit == null) {
                continue;
            }
            String key = (hit.getFilePath() == null ? "" : hit.getFilePath())
                    + "#" + (hit.getHeadingPath() == null ? "" : hit.getHeadingPath());
            KbChunkHit current = best.get(key);
            if (current == null || scoreOf(hit) >= scoreOf(current)) {
                best.put(key, hit);
            }
        }
        List<KbChunkHit> result = new ArrayList<>(best.values());
        // 按相关度降序：引用区先展示最相关的文档，低分噪声沉底并在截断时被丢弃。
        // 这里排序也决定了 sources 事件顺序与 prompt 证据顺序，二者必须保持一致，
        // 模型正文里的 [n] 编号才与引用卡一一对应（编号在排序之后统一生成）。
        result.sort((a, b) -> Double.compare(scoreOf(b), scoreOf(a)));
        // 负分（rerank logit < 0）基本等同"不相关"：直接从引用区剔除。
        // 若过滤后一条不剩（低分边界场景），保留原结果，避免回答完全没有出处。
        List<KbChunkHit> positive = result.stream()
                .filter(hit -> hit.getScore() == null || hit.getScore() >= 0)
                .toList();
        if (!positive.isEmpty()) {
            result = new ArrayList<>(positive);
        }
        return result.size() <= MAX_SOURCES ? result : new ArrayList<>(result.subList(0, MAX_SOURCES));
    }

    private static double scoreOf(KbChunkHit hit) {
        return hit.getScore() == null ? Double.NEGATIVE_INFINITY : hit.getScore();
    }

    private void saveHistory(String userId, String conversationId, String question, String answer, String libraryId) {
        try {
            // 同步落库：在 SSE 流 end( done ) 前完成，保证会话列表/历史即时可查。
            // 两条消息（用户+助手）收敛到单个事务，避免只写一半导致会话缺角色消息。
            transactionTemplate.executeWithoutResult(status -> {
                ChatHistoryEntity q = new ChatHistoryEntity();
                q.setSessionId(userId);
                q.setLibraryId(libraryId);
                q.setConversationId(conversationId);
                q.setRole("user");
                q.setContent(question);
                chatHistoryRepository.save(q);

                ChatHistoryEntity a = new ChatHistoryEntity();
                a.setSessionId(userId);
                a.setLibraryId(libraryId);
                a.setConversationId(conversationId);
                a.setRole("ai");
                a.setContent(answer);
                chatHistoryRepository.save(a);
            });
        } catch (Exception e) {
            log.warn("聊天历史落库失败 - userId: {}", userId, e);
        }
    }
}
