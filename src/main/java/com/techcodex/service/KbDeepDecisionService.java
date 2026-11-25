package com.techcodex.service;

import java.util.Map;

/**
 * 深度检索（GraphRAG/多跳）立项门禁：不默认开工，先用数据判断是否值得投入。
 * 返回 recommendation（GO/DEFER）、checks 与证据指标，供管理端和阶段决策文档引用。
 */
public interface KbDeepDecisionService {

    Map<String, Object> gate();
}
