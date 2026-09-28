package org.example.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.KbRuntimeConfigEntity;
import org.example.repository.KbRuntimeConfigRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 知识库运行时配置存储：管理端在线调整检索参数 / 配额限额 / 内容安全等配置，
 * 即时生效、无需重启。取值优先级：DB 覆盖值（kb_runtime_config 表）→ properties 启动默认。
 * <p>版本号随每次变更递增，供缓存方（如敏感词表）感知变更重建缓存。</p>
 */
@Slf4j
@Component
public class RuntimeConfigStore {

    /** 配置值类型（决定解析与校验方式）。 */
    public enum Type { INT, LONG, DOUBLE, BOOL, TEXT }

    /**
     * 单个可配置项：键、类型、分组、说明、启动默认值来源与可选取值范围（min/max，含边界）。
     * min/max 仅约束数值型（INT/LONG/DOUBLE），为 null 表示该侧不设界。
     */
    public record ConfigMeta(String key, Type type, String category, String description,
                             Supplier<Object> defaultValue, Double min, Double max) {
        public ConfigMeta(String key, Type type, String category, String description, Supplier<Object> defaultValue) {
            this(key, type, category, description, defaultValue, null, null);
        }
    }

    /** 配置项视图（管理端展示）：当前生效值 + 默认值 + 是否已覆盖。 */
    public record ConfigItemView(String key, String value, Object defaultValue, boolean overridden,
                                 Type type, String category, String description) {
    }

    private final KbConfig kbConfig;
    private final KbRuntimeConfigRepository repository;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final Map<String, ConfigMeta> metas = new LinkedHashMap<>();
    private final Map<String, String> overrides = new ConcurrentHashMap<>();
    private final AtomicLong version = new AtomicLong(1);

    public RuntimeConfigStore(KbConfig kbConfig, KbRuntimeConfigRepository repository,
                              org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.kbConfig = kbConfig;
        this.repository = repository;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        registerMetas();
    }

    /**
     * 加载 DB 覆盖到内存。启动时执行一次；此后每分钟对齐一次 DB——
     * 多实例部署时其他节点在线调整的配置最迟 60s 生效，人工改库亦同。
     */
    @PostConstruct
    @org.springframework.scheduling.annotation.Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void reload() {
        load();
    }

    void load() {
        // 先读入临时表，成功后整体替换：定时 reload 遇到瞬时 DB 故障时不得清空既有覆盖
        Map<String, String> loaded = new LinkedHashMap<>();
        try {
            for (KbRuntimeConfigEntity e : repository.findAll()) {
                if (e.getCfgKey() != null && e.getCfgValue() != null) {
                    loaded.put(e.getCfgKey(), e.getCfgValue());
                }
            }
        } catch (Exception e) {
            // 读取失败保留现状（覆盖值与内存不丢），仅记日志
            log.warn("运行时配置加载失败，保留当前覆盖 - err: {}", e.getMessage());
            return;
        }
        boolean changed = !loaded.equals(overrides);
        overrides.clear();
        overrides.putAll(loaded);
        // 内容有变化（含其他实例在线调整被本实例对齐）时递增版本，
        // 驱动 version 缓存消费方（如敏感词表）重建，保证多实例对齐对所有消费方生效
        if (changed) {
            version.incrementAndGet();
        }
        log.info("运行时配置加载完成 - 覆盖项: {} / 可配置项: {}", overrides.size(), metas.size());
    }

    private void registerMetas() {
        // 检索召回
        metas.put("retrieval.vector-top-n", new ConfigMeta("retrieval.vector-top-n", Type.INT, "检索召回",
                "向量召回候选数（先宽后精）", () -> kbConfig.getVectorTopN()));
        metas.put("retrieval.bm25-top-n", new ConfigMeta("retrieval.bm25-top-n", Type.INT, "检索召回",
                "BM25 关键词召回候选数", () -> kbConfig.getBm25TopN()));
        metas.put("retrieval.rrf-k", new ConfigMeta("retrieval.rrf-k", Type.INT, "检索召回",
                "RRF 融合常数 k（排名越靠后惩罚越大）", () -> kbConfig.getRrfK()));
        metas.put("retrieval.final-top-k", new ConfigMeta("retrieval.final-top-k", Type.INT, "检索召回",
                "融合后最终返回给 LLM 的片段数", () -> kbConfig.getFinalTopK()));
        metas.put("retrieval.min-score", new ConfigMeta("retrieval.min-score", Type.DOUBLE, "检索召回",
                "向量召回最低分数过滤", () -> kbConfig.getRetrievalMinScore(), 0.0, 1.0));
        metas.put("retrieval.bm25-enabled", new ConfigMeta("retrieval.bm25-enabled", Type.BOOL, "检索召回",
                "BM25 关键词通道开关", () -> kbConfig.isBm25Enabled()));
        // 重排与改写
        metas.put("retrieval.rerank-enabled", new ConfigMeta("retrieval.rerank-enabled", Type.BOOL, "重排与改写",
                "语义重排开关（重排模型需启动时已加载，否则不生效）", () -> kbConfig.isRerankEnabled()));
        metas.put("retrieval.rerank-top-n", new ConfigMeta("retrieval.rerank-top-n", Type.INT, "重排与改写",
                "参与精排的候选数", () -> kbConfig.getRerankTopN()));
        metas.put("retrieval.query-rewrite-enabled", new ConfigMeta("retrieval.query-rewrite-enabled", Type.BOOL, "重排与改写",
                "检索前 LLM 查询改写开关", () -> kbConfig.isQueryRewriteEnabled()));
        // 拒答与意图
        metas.put("retrieval.intent-routing-enabled", new ConfigMeta("retrieval.intent-routing-enabled", Type.BOOL, "拒答与意图",
                "意图路由：寒暄/指令类问题跳过知识库检索", () -> kbConfig.isIntentRoutingEnabled()));
        metas.put("retrieval.relevance-check-enabled", new ConfigMeta("retrieval.relevance-check-enabled", Type.BOOL, "拒答与意图",
                "相关度拒答开关", () -> kbConfig.isRelevanceCheckEnabled()));
        metas.put("retrieval.relevance-min-score", new ConfigMeta("retrieval.relevance-min-score", Type.DOUBLE, "拒答与意图",
                "相关度阈值：融合后最高分低于该值即拒答", () -> kbConfig.getRelevanceMinScore(), 0.0, 1.0));
        // 配额
        metas.put("quota.user-doc-limit", new ConfigMeta("quota.user-doc-limit", Type.LONG, "个人库配额",
                "个人知识库文档数默认限额（0=不限制；用户级覆盖优先）", () -> kbConfig.getSelfServiceDocLimit()));
        metas.put("quota.user-retrieval-limit", new ConfigMeta("quota.user-retrieval-limit", Type.LONG, "个人库配额",
                "个人知识库每日检索默认限额（0=不限制）", () -> kbConfig.getSelfServiceRetrievalLimit()));
        metas.put("quota.user-chat-limit", new ConfigMeta("quota.user-chat-limit", Type.LONG, "个人库配额",
                "个人知识库每日问答默认限额（0=不限制）", () -> kbConfig.getSelfServiceChatLimit()));
        // 内容安全
        metas.put("safety.enabled", new ConfigMeta("safety.enabled", Type.BOOL, "内容安全",
                "敏感词拦截/脱敏总开关", () -> kbConfig.isSafetyEnabled()));
        metas.put("safety.sensitive-words", new ConfigMeta("safety.sensitive-words", Type.TEXT, "内容安全",
                "内联敏感词（逗号分隔；classpath 词表文件内容始终叠加生效）", () -> kbConfig.getSafetySensitiveWords()));
        // 评测告警
        metas.put("eval.hitrate-threshold", new ConfigMeta("eval.hitrate-threshold", Type.DOUBLE, "评测告警",
                "评测命中率告警阈值（0~1，低于该值产生告警）", () -> kbConfig.getEvalHitrateThreshold(), 0.0, 1.0));
    }

    /** 生效值：覆盖优先，未覆盖回退默认。未知键抛业务异常。 */
    public String get(String key) {
        ConfigMeta meta = metas.get(key);
        if (meta == null) {
            throw new IllegalArgumentException("未知配置键: " + key);
        }
        String override = overrides.get(key);
        if (override != null) {
            return override;
        }
        Object d = meta.defaultValue().get();
        return d == null ? "" : String.valueOf(d);
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key).trim());
    }

    public long getLong(String key) {
        return Long.parseLong(get(key).trim());
    }

    public double getDouble(String key) {
        return Double.parseDouble(get(key).trim());
    }

    public boolean getBool(String key) {
        return Boolean.parseBoolean(get(key).trim());
    }

    /** 当前版本号（每次变更 +1），供缓存方感知变更。 */
    public long version() {
        return version.get();
    }

    /** 全部配置项视图（管理端展示）。 */
    public List<ConfigItemView> view() {
        List<ConfigItemView> items = new ArrayList<>(metas.size());
        for (ConfigMeta meta : metas.values()) {
            Object d = meta.defaultValue().get();
            String defaultValue = d == null ? "" : String.valueOf(d);
            String override = overrides.get(meta.key());
            items.add(new ConfigItemView(meta.key(), override != null ? override : defaultValue,
                    defaultValue, override != null, meta.type(), meta.category(), meta.description()));
        }
        return items;
    }

    /**
     * 批量更新配置：value 为空串/null 视为清除覆盖（恢复默认）；否则按键类型校验后落库并即时生效。
     * 两阶段执行——先对全部条目完成键与取值校验，全部通过后才落库，保证「非法取值整批拒绝」无部分应用。
     *
     * @return 实际变更的键列表
     */
    public List<String> update(Map<String, String> values, String operator) {
        // 归一化到可变副本（入参可能是 Map.of 等不可变实现，不能就地改写 entry）
        Map<String, String> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : values.entrySet()) {
            String v = e.getValue();
            normalized.put(e.getKey(), v == null ? "" : v.trim());
        }
        // 第一阶段：全量校验（未知键 + 类型/范围），任一非法即整批拒绝
        for (Map.Entry<String, String> e : normalized.entrySet()) {
            ConfigMeta meta = metas.get(e.getKey());
            if (meta == null) {
                throw new IllegalArgumentException("未知配置键: " + e.getKey());
            }
            if (!e.getValue().isEmpty()) {
                validate(meta, e.getValue());
            }
        }
        // 第二阶段：DB 写入收敛到单个事务（批量原子），成功后内存才生效
        List<String> changed = new ArrayList<>();
        Map<String, String> applied = new LinkedHashMap<>();
        List<String> removedKeys = new ArrayList<>();
        transactionTemplate.executeWithoutResult(tx -> {
            for (Map.Entry<String, String> e : normalized.entrySet()) {
                String key = e.getKey();
                String value = e.getValue();
                if (value.isEmpty()) {
                    if (overrides.containsKey(key)) {
                        repository.findByCfgKey(key).ifPresent(repository::delete);
                        removedKeys.add(key);
                        changed.add(key + " 恢复默认");
                    }
                    continue;
                }
                KbRuntimeConfigEntity entity = repository.findByCfgKey(key).orElseGet(() -> {
                    KbRuntimeConfigEntity n = new KbRuntimeConfigEntity();
                    n.setCfgKey(key);
                    return n;
                });
                entity.setCfgValue(value);
                entity.setUpdatedBy(operator);
                repository.save(entity);
                applied.put(key, value);
                changed.add(key + "=" + value);
            }
        });
        applied.forEach(overrides::put);
        removedKeys.forEach(overrides::remove);
        if (!changed.isEmpty()) {
            version.incrementAndGet();
            log.info("运行时配置更新 - operator: {}, 变更: {}", operator, changed);
        }
        return changed;
    }

    /** 按类型校验取值合法性（非法即抛业务异常，全部拒绝以保证批量原子观感）。 */
    private void validate(ConfigMeta meta, String value) {
        try {
            switch (meta.type()) {
                case INT -> {
                    int v = Integer.parseInt(value);
                    if (v < 0) throw new NumberFormatException();
                }
                case LONG -> {
                    long v = Long.parseLong(value);
                    if (v < 0) throw new NumberFormatException();
                }
                case DOUBLE -> {
                    double v = Double.parseDouble(value);
                    if (!Double.isFinite(v) || v < 0 || v > 1e6) throw new NumberFormatException();
                }
                case BOOL -> {
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        throw new NumberFormatException();
                    }
                }
                case TEXT -> {
                    if (value.length() > 5000) throw new NumberFormatException();
                }
            }
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("配置 " + meta.key() + " 取值非法: \"" + value + "\"（期望 " + meta.type() + "）");
        }
        if (meta.min() != null || meta.max() != null) {
            double v;
            try {
                v = Double.parseDouble(value);
            } catch (NumberFormatException ex) {
                return; // 非 DOUBLE 键不会有范围约束
            }
            if ((meta.min() != null && v < meta.min()) || (meta.max() != null && v > meta.max())) {
                throw new IllegalArgumentException(
                        "配置 " + meta.key() + " 取值超界: " + value + "（允许范围 " + meta.min() + " ~ " + meta.max() + "）");
            }
        }
    }
}
