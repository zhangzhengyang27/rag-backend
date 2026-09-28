package org.example.content.impl;

import org.example.config.RuntimeConfigStore;
import org.example.content.ContentGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 关键词内容审核实现：敏感词 = 「运行时配置内联词（管理端在线可调）+ classpath 词表文件」。
 * 大小写不敏感 substring 匹配；开关关闭或词表为空时不拦截、不脱敏。
 * 词表按配置版本缓存：管理端调整 sensitive-words 后下一次调用自动重建。
 */
@Component
public class KeywordContentGuard implements ContentGuard {

    private final RuntimeConfigStore runtimeConfig;
    /** classpath 词表文件加载的基础词（启动时读入，始终叠加生效）。 */
    private final List<String> fileWords;

    private volatile long cachedVersion = -1;
    private volatile List<String> cachedWords = List.of();

    public KeywordContentGuard(RuntimeConfigStore runtimeConfig,
                               @Value("${app.kb.safety.sensitive-words-file:sensitive-words.txt}") String wordsFile) {
        this.runtimeConfig = runtimeConfig;
        this.fileWords = loadWordsFile(wordsFile);
    }

    @Override
    public boolean isEnabled() {
        return runtimeConfig.getBool("safety.enabled") && !effectiveWords().isEmpty();
    }

    @Override
    public Optional<String> findViolation(String text) {
        if (!isEnabled() || text == null || text.isEmpty()) {
            return Optional.empty();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        for (String w : effectiveWords()) {
            if (lower.contains(w)) {
                return Optional.of(w);
            }
        }
        return Optional.empty();
    }

    @Override
    public String sanitize(String text) {
        if (!isEnabled() || text == null || text.isEmpty()) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(text);
        for (String w : effectiveWords()) {
            int from = 0;
            while (true) {
                int idx = lower.indexOf(w, from);
                if (idx < 0) {
                    break;
                }
                sb.replace(idx, idx + w.length(), "*".repeat(w.length()));
                from = idx + w.length();
            }
        }
        return sb.toString();
    }

    /** 生效词表（版本缓存）：文件词 + 运行时配置内联词，去重、小写。 */
    private List<String> effectiveWords() {
        long v = runtimeConfig.version();
        if (v != cachedVersion) {
            Set<String> set = new LinkedHashSet<>(fileWords);
            String inline = runtimeConfig.get("safety.sensitive-words");
            if (inline != null && !inline.isBlank()) {
                for (String w : inline.split(",")) {
                    String t = w.trim();
                    if (!t.isEmpty()) {
                        set.add(t.toLowerCase(Locale.ROOT));
                    }
                }
            }
            cachedWords = List.copyOf(set);
            cachedVersion = v;
        }
        return cachedWords;
    }

    /** 加载 classpath 词表文件（按行），去重、忽略空行与注释；缺失/读取失败降级为空基础词表。 */
    private List<String> loadWordsFile(String wordsFile) {
        Set<String> set = new LinkedHashSet<>();
        try {
            ClassPathResource resource = new ClassPathResource(wordsFile);
            if (resource.exists()) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String t = line.trim();
                        if (!t.isEmpty() && !t.startsWith("#")) {
                            set.add(t.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 词表文件缺失/读取失败不阻断启动，降级为仅运行时配置内联词表
        }
        return new ArrayList<>(set);
    }
}
