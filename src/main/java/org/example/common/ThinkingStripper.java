package org.example.common;

import reactor.core.publisher.Flux;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 流式"思考/过程"片段剥离器。
 *
 * <p>知识问答走工具编排时，推理模型偶发会把检索、验证等内部过程的叙述（如"我来检索…"
 * "基于两次检索和补充检索所获得的知识库文档证据，已经足够回答该问题。现在组织最终答案。"
 *）放到最终正文之前一起输出。系统提示已要求禁止，但在推理型模型上无法完全根除，
 * 故在服务端对答案开头做前导剥离，作为兜底硬过滤。</p>
 *
 * <p>为兼顾流式体验：以<b>完整句子</b>为最小剥离单元，而不把正文本体缓存到下一个 Markdown
 * 标题或整段结束。开头的每个完整句先落在缓存中——命中"思考句"模式则丢弃该句；
 * 一旦出现正文标识（Markdown 标题 {@code ## }）或在未命中思考模式下读到正常正文句，
 * 即判定正文开始，把已缓存内容一次性透传，其后每个 token 直达流。无标题的普通短回答
 * 也随第一个完整句即时输出，不会攒到流末尾。</p>
 */
public final class ThinkingStripper {

    private ThinkingStripper() {
    }

    /** 前导缓冲上限（字符）。无句号/正文标识的连续长文本超过该值即按兜底规则判定一次。 */
    private static final int MAX_HEAD_LEN = 120;

    /** 标题/正文标识：从第一个 markdown 二级标题处视为正文正式开始的强信号。 */
    private static final String BODY_MARK = "## ";

    /** 思考特征词：命中即认为该前缀属于过程性叙述。 */
    private static final Pattern THINKING_HINT = Pattern.compile(
            "(让我|我来|我(正要|将|即将|准备|现在|先|首先|开始)|我找到|我获得|我已完成|我完成|"
            + "基于.*(检索|搜索|查询|证据)|(检索|搜索|查询).*(证据|结果|片段)|"
            + "验证(证据|一下|结果)|证据.*(足够|充分|充分性|已足够)|足够回答|回答该问题|"
            + "组织(最终)?答案|现在(开始|给出|组织|进入)|以下(是)?(我)?(基于|根据).*(答案|回答))");

    /** 思考句模式：整句以"第一人称/过程引导"开头且整体短，视为应丢弃的过程句而非正文。 */
    private static final Pattern THINKING_SENTENCE_START = Pattern.compile(
            "^(我|让我|我(来|先|将|正|现在|准备|要|已经)|基于|经过|通过|现在|接下来|好的，我|开始(检索|回答|组织))");

    /** 完整句分隔符：中文句号/感叹/问号/换行/英文句点。 */
    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[。！？!?\\n]");

    /**
     * 兜底模式：整段前导（无标点分隔的长连续文本）整体仍为过程性叙述（第一人称/过程词开头、
     * 无实质正文特征）时丢弃。用于 {@code MAX_HEAD_LEN} 阈值与流末尾 flush 的判定。
     */
    private static final Pattern THINKING_END = Pattern.compile(
            "^(让我|我来|我(正|将|准备|现在|先|首先|开始|已经)|好的，我|现在|接下来)\\S*$");

    /**
     * 对 token 流做前导剥离：丢弃开头的思考/过程片段，正文及后续正常透传。
     * 每次订阅通过 {@link Flux#defer} 持有独立状态，避免并发/重订阅污染。
     */
    public static Flux<String> stripPrefix(Flux<String> tokens) {
        return Flux.defer(() -> {
            final StringBuilder buf = new StringBuilder();
            final boolean[] started = {false};
            Flux<String> body = tokens.handle((token, sink) -> {
                buf.append(token);
                if (started[0]) {
                    sink.next(token);
                    return;
                }
                String h = buf.toString();
                // 1) 正文标识：Markdown 标题出现即正文开始
                int mark = h.indexOf(BODY_MARK);
                if (mark >= 0) {
                    String pre = h.substring(0, mark);
                    boolean think = !pre.isBlank() && THINKING_HINT.matcher(pre).find();
                    sink.next(h.substring(think ? mark : 0));
                    started[0] = true;
                    buf.setLength(0);
                    return;
                }
                // 2) 完整句子边界：以句为单元判定是否正文
                Matcher m = SENTENCE_BOUNDARY.matcher(h);
                if (m.find()) {
                    int end = m.end();
                    String sentence = h.substring(0, end);
                    boolean thinking = isThinkingSentence(sentence);
                    if (thinking) {
                        // 过程句：丢弃，并清空以便继续判定下一句；body 后续 token 将重建缓冲
                        buf.setLength(0);
                    } else {
                        // 正常正文首句：透传整段缓冲，进入正文直接流式
                        sink.next(buf.toString());
                        started[0] = true;
                        buf.setLength(0);
                    }
                    return;
                }
                // 3) 兜底：无句号的长连续文本达到上限
                if (buf.length() >= MAX_HEAD_LEN) {
                    boolean think = THINKING_END.matcher(h).matches();
                    sink.next(think ? "" : h);
                    started[0] = true;
                    buf.setLength(0);
                }
            });
            // 流结束时把残留前缀 flush 出去，避免短回答被卡住
            return body.concatWith(Flux.defer(() -> {
                if (started[0] || buf.length() == 0) {
                    return Flux.empty();
                }
                String whole = buf.toString();
                boolean think = isThinkingSentence(whole) || THINKING_END.matcher(whole).matches();
                return Flux.just(think ? "" : whole);
            }));
        });
    }

    /** 整句判定是否为待剥离的思考/过程句：以过程引导开头且较短。 */
    private static boolean isThinkingSentence(String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return true;
        }
        // 过长的句子不作为思考句，避免误伤正文（思考过程叙述通常较短）
        if (sentence.length() > MAX_HEAD_LEN) {
            return false;
        }
        return THINKING_SENTENCE_START.matcher(sentence).find()
                && THINKING_HINT.matcher(sentence).find();
    }
}