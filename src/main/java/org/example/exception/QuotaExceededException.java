package org.example.exception;

/** 配额超限异常（文档数/每日检索/每日问答），统一映射为 429。 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
