package org.example.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.example.entity.KbContentGapEntity;
import org.example.repository.KbContentGapRepository;
import org.example.service.KbContentDraftService;
import org.example.vo.KbContentDraftView;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 内容生产草稿：缺口原文本身就是最好的选题；模型只负责补结构，
 * 不自动写完整文章（避免无来源内容直接入库）。模型不可用时给通用四段式大纲。
 */
@Slf4j
@Service
public class KbContentDraftServiceImpl implements KbContentDraftService {

    private final KbContentGapRepository gapRepository;
    private final ObjectProvider<ChatModel> chatModelProvider;
    private final ObjectMapper objectMapper;

    public KbContentDraftServiceImpl(KbContentGapRepository gapRepository,
                                     ObjectProvider<ChatModel> chatModelProvider,
                                     ObjectMapper objectMapper) {
        this.gapRepository = gapRepository;
        this.chatModelProvider = chatModelProvider;
        this.objectMapper = objectMapper;
    }

    @Override
    public KbContentDraftView draft(String libraryId, Long gapId) {
        KbContentGapEntity gap = gapRepository.findById(gapId)
                .filter(g -> libraryId.equals(g.getLibraryId()))
                .orElseThrow(() -> new IllegalArgumentException("内容缺口不存在"));
        ChatModel model = chatModelProvider.getIfAvailable();
        if (model != null) {
            try {
                String prompt = """
                        你是技术内容主编。为下面这个高频未解决问题设计一篇知识库文章大纲，严格输出 JSON（不要代码块）：
                        {"title":"文章标题","summary":"一句话说明文章要解决什么","outline":[{"heading":"小节标题","points":["要点"]}],"researchQuestions":["发布前必须查证的问题"]}
                        要求：标题具体、可搜索；outline 4~6 节；points 每节 2~3 条；不要编造具体数字/版本结论。
                        问题：%s
                        频次：%d
                        """.formatted(gap.getQuestion(), gap.getFrequency() == null ? 0 : gap.getFrequency());
                String raw = model.chat(prompt);
                KbContentDraftView parsed = parse(raw, gap);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                log.warn("内容大纲生成失败，降级模板 - gapId: {}, err: {}", gapId, e.getMessage());
            }
        }
        return fallback(gap);
    }

    private KbContentDraftView parse(String raw, KbContentGapEntity gap) {
        if (raw == null) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
            List<KbContentDraftView.Section> outline = new ArrayList<>();
            for (JsonNode s : root.path("outline")) {
                List<String> points = new ArrayList<>();
                for (JsonNode p : s.path("points")) if (p.isTextual()) points.add(p.asText());
                outline.add(KbContentDraftView.Section.builder()
                        .heading(s.path("heading").asText(""))
                        .points(points)
                        .build());
            }
            List<String> research = new ArrayList<>();
            for (JsonNode q : root.path("researchQuestions")) if (q.isTextual()) research.add(q.asText());
            if (outline.isEmpty()) return null;
            return KbContentDraftView.builder()
                    .gapId(gap.getId())
                    .sourceQuestion(gap.getQuestion())
                    .title(root.path("title").asText(gap.getQuestion()))
                    .summary(root.path("summary").asText(""))
                    .outline(outline)
                    .researchQuestions(research)
                    .generatedBy("AI")
                    .build();
        } catch (Exception e) {
            log.warn("内容大纲 JSON 解析失败 - gapId: {}, err: {}", gap.getId(), e.getMessage());
            return null;
        }
    }

    private KbContentDraftView fallback(KbContentGapEntity gap) {
        List<KbContentDraftView.Section> outline = List.of(
                KbContentDraftView.Section.builder().heading("问题定义与适用场景").points(List.of("明确这个问题的边界", "列出读者最常见的误解")).build(),
                KbContentDraftView.Section.builder().heading("核心概念与原理").points(List.of("用一张图/一段话解释关键机制", "给出最小可运行示例")).build(),
                KbContentDraftView.Section.builder().heading("常见方案与取舍").points(List.of("方案 A/B 的适用条件", "性能、复杂度与维护成本对比")).build(),
                KbContentDraftView.Section.builder().heading("落地步骤与排错").points(List.of("按步骤给出操作路径", "列出可观测指标与常见错误")).build()
        );
        return KbContentDraftView.builder()
                .gapId(gap.getId())
                .sourceQuestion(gap.getQuestion())
                .title(gap.getQuestion())
                .summary("面向高频缺口生成的通用大纲；AI 暂不可用，发布前需人工补全来源与结论。")
                .outline(outline)
                .researchQuestions(List.of("官方文档/源码依据", "版本适用范围", "真实可复现示例"))
                .generatedBy("FALLBACK")
                .build();
    }
}
