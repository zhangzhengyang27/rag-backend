package com.techcodex.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import com.techcodex.entity.KnowledgeDocumentEntity;
import com.techcodex.repository.KnowledgeDocumentRepository;
import com.techcodex.service.KbLearningService;
import com.techcodex.vo.KbLearningGuideView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 学习导读实现：优先调用 ChatModel 输出结构化 JSON；模型不可用/返回不合法时，
 * 退化为“标题路径 + 首段”的抽取式导读，保证页面永远可用。
 *
 * <p>结果按 docId+contentHash 缓存，文档更新后哈希变化自然失效，避免旧导读串到新内容。</p>
 */
@Slf4j
@Service
public class KbLearningServiceImpl implements KbLearningService {

    private static final int MAX_CONTENT = 6000;
    private final KnowledgeDocumentRepository documentRepository;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;
    private final Map<String, KbLearningGuideView> cache = new ConcurrentHashMap<>();

    public KbLearningServiceImpl(KnowledgeDocumentRepository documentRepository,
                                 ObjectProvider<ChatModel> chatModelProvider,
                                 ObjectMapper objectMapper) {
        this.documentRepository = documentRepository;
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public KbLearningGuideView guide(String libraryId, Long docId) {
        KnowledgeDocumentEntity doc = documentRepository.findByIdAndLibraryId(docId, libraryId)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在或不属于当前空间"));
        String key = docId + ":" + (doc.getContentHash() == null ? "" : doc.getContentHash());
        KbLearningGuideView cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        KbLearningGuideView guide = generate(doc);
        if (cache.size() > 500) {
            cache.clear();
        }
        cache.put(key, guide);
        return guide;
    }

    @Override
    public KbLearningGuideView guideByPath(String libraryId, String filePath) {
        KnowledgeDocumentEntity doc = documentRepository.findByLibraryIdAndFilePath(libraryId, filePath)
                .orElseThrow(() -> new IllegalArgumentException("文档不存在或不属于当前空间"));
        String key = doc.getId() + ":" + (doc.getContentHash() == null ? "" : doc.getContentHash());
        return cache.computeIfAbsent(key, k -> generate(doc));
    }

    private KbLearningGuideView generate(KnowledgeDocumentEntity doc) {
        String content = truncate(doc.getContent(), MAX_CONTENT);
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model != null) {
            try {
                String prompt = """
                        你是技术课程编辑。请基于下面的文档生成学习导读，严格输出 JSON（不要 Markdown 代码块）：
                        {"summary":"一分钟概览，120字以内","sections":[{"title":"小节标题","points":["要点1","要点2"]}],"quiz":[{"question":"自测题","options":["A. ...","B. ...","C. ...","D. ..."],"answer":"A"}]}
                        要求：sections 3~5 个，覆盖文档主线；quiz 3 题，答案必须能在文档中找到。
                        文档标题：%s
                        文档正文：
                        %s
                        """.formatted(doc.getTitle(), content);
                String raw = model.chat(prompt);
                KbLearningGuideView parsed = parse(raw, doc);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("学习导读生成失败，降级为抽取式 - docId: {}, err: {}", doc.getId(), e.getMessage());
            }
        }
        return fallback(doc, content);
    }

    private KbLearningGuideView parse(String raw, KnowledgeDocumentEntity doc) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String json = raw.trim();
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        json = json.substring(start, end + 1);
        try {
            JsonNode root = objectMapper.readTree(json);
            List<KbLearningGuideView.Section> sections = new ArrayList<>();
            for (JsonNode s : root.path("sections")) {
                List<String> points = new ArrayList<>();
                for (JsonNode p : s.path("points")) {
                    if (p.isTextual()) points.add(p.asText());
                }
                sections.add(KbLearningGuideView.Section.builder()
                        .title(s.path("title").asText(""))
                        .points(points)
                        .build());
            }
            List<KbLearningGuideView.Quiz> quiz = new ArrayList<>();
            for (JsonNode q : root.path("quiz")) {
                List<String> options = new ArrayList<>();
                for (JsonNode o : q.path("options")) {
                    if (o.isTextual()) options.add(o.asText());
                }
                quiz.add(KbLearningGuideView.Quiz.builder()
                        .question(q.path("question").asText(""))
                        .options(options)
                        .answer(q.path("answer").asText(""))
                        .build());
            }
            if (sections.isEmpty()) {
                return null;
            }
            return KbLearningGuideView.builder()
                    .docId(doc.getId())
                    .title(doc.getTitle())
                    .summary(root.path("summary").asText(""))
                    .sections(sections)
                    .quiz(quiz)
                    .generatedBy("AI")
                    .build();
        } catch (Exception e) {
            log.warn("学习导读 JSON 解析失败 - docId: {}, err: {}", doc.getId(), e.getMessage());
            return null;
        }
    }

    /** 抽取式降级：按 Markdown 标题切小节，每节取前 2 句；不编造自测题。 */
    private KbLearningGuideView fallback(KnowledgeDocumentEntity doc, String content) {
        List<KbLearningGuideView.Section> sections = new ArrayList<>();
        String currentTitle = "概览";
        List<String> currentPoints = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                if (!currentPoints.isEmpty()) {
                    sections.add(KbLearningGuideView.Section.builder().title(currentTitle).points(currentPoints).build());
                }
                currentTitle = trimmed.replaceAll("^#+\\s*", "").trim();
                currentPoints = new ArrayList<>();
            } else if (!trimmed.isEmpty() && trimmed.length() > 8 && currentPoints.size() < 2) {
                currentPoints.add(trimmed.length() > 160 ? trimmed.substring(0, 160) + "…" : trimmed);
            }
        }
        if (!currentPoints.isEmpty()) {
            sections.add(KbLearningGuideView.Section.builder().title(currentTitle).points(currentPoints).build());
        }
        if (sections.isEmpty()) {
            sections.add(KbLearningGuideView.Section.builder().title("概览").points(List.of(truncate(content, 200))).build());
        }
        return KbLearningGuideView.builder()
                .docId(doc.getId())
                .title(doc.getTitle())
                .summary(truncate(content.replaceAll("\\s+", " ").trim(), 160))
                .sections(sections)
                .quiz(List.of())
                .generatedBy("FALLBACK")
                .build();
    }

    private String truncate(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max);
    }
}
