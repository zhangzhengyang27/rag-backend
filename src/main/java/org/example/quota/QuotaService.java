package org.example.quota;

public interface QuotaService {

    /** 文档数配额校验（导入前调用，超限抛 QuotaExceededException）。 */
    void assertDocumentQuota(String libraryId);

    /** 每日检索配额扣减（用户检索 API 边界调用一次）。 */
    void consumeRetrieval(String libraryId);

    /** 每日问答配额扣减（chat-stream 边界调用一次）。 */
    void consumeChat(String libraryId);

    /** 配额使用快照（前端用量条）：文档数 + 当日检索/问答计数，只读不扣减。 */
    QuotaUsage usage(String libraryId);
}
