package org.example.quota.impl;

import org.example.config.RuntimeConfigStore;
import org.example.entity.UserEntity;
import org.example.exception.QuotaExceededException;
import org.example.library.KbLibrary;
import org.example.quota.QuotaDailyCounter;
import org.example.quota.QuotaService;
import org.example.repository.KnowledgeDocumentRepository;
import org.example.repository.UserRepository;
import org.springframework.stereotype.Service;

/**
 * 配额实现：只约束个人知识库空间（u-{username}）；公共空间（default）不限量。
 * 限额取值优先级：用户级覆盖（sys_user.doc/retrieval/chat_limit，管理端可调）
 * → 运行时配置（管理端在线可调）→ 全局配置 {@code app.kb.library.user-*-limit}（0=不限制）。
 */
@Service
public class QuotaServiceImpl implements QuotaService {

    private static final String PREFIX = "quota:";
    private static final String METRIC_RETRIEVAL = "retrieval";
    private static final String METRIC_CHAT = "chat";

    private static final String KEY_DOC_LIMIT = "quota.user-doc-limit";
    private static final String KEY_RETRIEVAL_LIMIT = "quota.user-retrieval-limit";
    private static final String KEY_CHAT_LIMIT = "quota.user-chat-limit";

    private final RuntimeConfigStore runtimeConfig;
    private final KnowledgeDocumentRepository documentRepository;
    private final QuotaDailyCounter counter;
    private final UserRepository userRepository;

    public QuotaServiceImpl(RuntimeConfigStore runtimeConfig,
                            KnowledgeDocumentRepository documentRepository,
                            QuotaDailyCounter counter,
                            UserRepository userRepository) {
        this.runtimeConfig = runtimeConfig;
        this.documentRepository = documentRepository;
        this.counter = counter;
        this.userRepository = userRepository;
    }

    @Override
    public void assertDocumentQuota(String libraryId) {
        UserEntity user = personalUser(libraryId);
        long limit = personalLimit(libraryId, user == null ? null : user.getDocLimit(), runtimeConfig.getLong(KEY_DOC_LIMIT));
        if (limit <= 0) {
            return; // 0 表示不限制
        }
        long count = documentRepository.countByLibraryId(libraryId);
        if (count >= limit) {
            throw new QuotaExceededException("私有知识库文档数已达上限（" + limit + "），无法继续导入");
        }
    }

    @Override
    public void consumeRetrieval(String libraryId) {
        UserEntity user = personalUser(libraryId);
        consume(libraryId, METRIC_RETRIEVAL, user == null ? null : user.getRetrievalLimit(),
                runtimeConfig.getLong(KEY_RETRIEVAL_LIMIT), "检索");
    }

    @Override
    public void consumeChat(String libraryId) {
        UserEntity user = personalUser(libraryId);
        consume(libraryId, METRIC_CHAT, user == null ? null : user.getChatLimit(),
                runtimeConfig.getLong(KEY_CHAT_LIMIT), "问答");
    }

    private void consume(String libraryId, String metric, Long override, long configuredLimit, String label) {
        long limit = personalLimit(libraryId, override, configuredLimit);
        if (limit <= 0) {
            return;
        }
        long used = counter.increment(org.example.quota.QuotaDailyCounter.dayKey(PREFIX, libraryId, metric));
        if (used > limit) {
            throw new QuotaExceededException("私有知识库今日" + label + "次数已达上限（" + limit + "）");
        }
    }

    /** 非个人空间（公共）不限量；个人空间取用户覆盖值，无覆盖时取配置限额（0=不限制）。 */
    private long personalLimit(String libraryId, Long override, long configuredLimit) {
        if (!KbLibrary.isPersonal(libraryId)) {
            return 0L;
        }
        return override != null ? Math.max(0L, override) : Math.max(0L, configuredLimit);
    }

    @Override
    public org.example.quota.QuotaUsage usage(String libraryId) {
        UserEntity user = personalUser(libraryId);
        boolean personal = KbLibrary.isPersonal(libraryId);
        long docUsed = documentRepository.countByLibraryId(libraryId);
        long retrievalUsed = counter.peek(org.example.quota.QuotaDailyCounter.dayKey(PREFIX, libraryId, METRIC_RETRIEVAL));
        long chatUsed = counter.peek(org.example.quota.QuotaDailyCounter.dayKey(PREFIX, libraryId, METRIC_CHAT));
        return new org.example.quota.QuotaUsage(
                docUsed, personal ? personalLimit(libraryId, user == null ? null : user.getDocLimit(), runtimeConfig.getLong(KEY_DOC_LIMIT)) : 0L,
                retrievalUsed, personal ? personalLimit(libraryId, user == null ? null : user.getRetrievalLimit(), runtimeConfig.getLong(KEY_RETRIEVAL_LIMIT)) : 0L,
                chatUsed, personal ? personalLimit(libraryId, user == null ? null : user.getChatLimit(), runtimeConfig.getLong(KEY_CHAT_LIMIT)) : 0L);
    }

    /** 个人空间对应的用户实体（u-{username} → username 反查）；非个人空间或用户不存在返回 null。 */
    private UserEntity personalUser(String libraryId) {
        if (libraryId == null || !libraryId.startsWith(KbLibrary.PERSONAL_PREFIX)) {
            return null;
        }
        return userRepository.findByUsername(libraryId.substring(KbLibrary.PERSONAL_PREFIX.length())).orElse(null);
    }
}
