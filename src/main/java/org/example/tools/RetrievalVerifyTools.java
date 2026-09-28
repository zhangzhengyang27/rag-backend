package org.example.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 两级检索 Step 化的验证环节（verify）：
 * {@code firstSearch → verify → refine} 中的中段。模型在首次检索得到片段后，
 * 调用本工具的 verify 判定"当前证据是否足够回答该问题"，决定是否进入补充检索 refine。
 *
 * <p>verify 直接复用模型传入的检索证据（evidence），以问题的关键信息在证据中的覆盖情况做
 * 轻量判定，<b>不再自行发起点检索</b>——既避免与上游重复计算，也让判定与模型实际看到的
 * 片段保持一致。足够 → 直接作答；不足 → 建议补充。</p>
 */
@Component
@Slf4j
public class RetrievalVerifyTools {

    /** 问题内抓取关键信息用：标识符（类名/API/错误码）≥3 位，中文词 ≥2 字。 */
    private static final Pattern KEYWORD = Pattern.compile("[A-Za-z0-9_]{3,}|[\\u4e00-\\u9fff]{2,}");

    @Tool("判断针对该问题，知识库现有检索证据是否足以回答。返回'足够'或'不足'+建议补充的检索点；模型据此决定是否调用补充检索")
    public String verifySufficiency(
            @P("待回答的用户问题") String question,
            @P("首次检索已获得的证据片段摘要，供判断参考") String evidence) {
        if (question == null || question.isBlank()) {
            return "问题为空，无需检索。";
        }
        if (evidence == null || evidence.isBlank()) {
            log.info("证据充分性判定 - question: {}, 无证据片段", question);
            return "不足：当前没有检索到任何证据片段，直接作答风险较高。建议发起一次聚焦的补充检索 refineSearch。";
        }
        List<String> keywords = extractKeywords(question);
        int hits = 0;
        for (String kw : keywords) {
            if (evidence.contains(kw)) {
                hits++;
            }
        }
        log.info("证据充分性判定 - question: {}, 关键词命中 {}/{}", question, hits, keywords.size());
        if (keywords.isEmpty() || hits == 0) {
            return "不足：现有证据与问题关键信息无明显吻合，直接作答风险较高。"
                    + "建议从问题中的关键技术术语/缺失概念（如 API、类名、字段名、错误码）发起一次聚焦的补充检索 refineSearch。";
        }
        return "足够：现有证据覆盖了该问题的关键信息，直接基于证据组织回答即可，无需额外补充检索。";
    }

    /** 从问题中抽取关键信息词（标识符/中文词），去重、限量，作证据覆盖率判断依据。 */
    private List<String> extractKeywords(String question) {
        List<String> out = new ArrayList<>();
        Matcher m = KEYWORD.matcher(question);
        while (m.find() && out.size() < 5) {
            String w = m.group();
            if (!out.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }
}