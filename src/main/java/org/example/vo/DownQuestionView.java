package org.example.vo;

import java.util.Date;

/**
 * 点踩问题聚合行（周报「内容补强清单」）：按问题原文聚合计数，
 * 附最近一条评语与时间，供管理员定位需要补强/修正的文档主题。
 */
public record DownQuestionView(String question, long count, String lastComment, Date lastAt) {
}
