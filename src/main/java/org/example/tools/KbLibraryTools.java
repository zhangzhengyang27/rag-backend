package org.example.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.example.dto.KbDocumentView;
import org.example.library.KbLibrary;
import org.example.service.KbDocumentService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 知识库清单工具：供问答助手回答"库里有哪（几）篇文档/什么内容/多少篇"这类
 * 元问题——此类问题与文档正文无语义重叠，向量检索命中为 0，必须靠清单工具兜底。
 *
 * <p>空间安全：工具在 LLM 流式回调线程上执行，SecurityContext（ThreadLocal）不可见，
 * 因此当前请求的空间集合由服务层在请求线程上解析后按对话ID绑定（{@link #bind}），
 * 工具经对话ID取回；取不到绑定（未注册/已过期）一律拒绝（fail-closed），
 * 绝不静默回落公共空间。</p>
 */
@Component
@Slf4j
public class KbLibraryTools {

    /** 绑定有效期：一次问答的工具调用窗口远小于该值，过期即视为请求已结束。 */
    static final long BINDING_TTL_MILLIS = 15 * 60 * 1000L;
    /** 绑定表上限：超过即先清过期，仍满则拒绝新绑定（正常流量远达不到）。 */
    private static final int MAX_BINDINGS = 500;

    private static final String NO_CONTEXT_MESSAGE =
            "（无法确认当前知识库空间，已跳过清单查询。请如实告知用户暂时无法列出文档清单，建议稍后重试。）";

    private final KbDocumentService kbDocumentService;

    /**
     * 对话ID → 空间绑定。静态表：工具是单例 Bean，而绑定随请求产生与结束，
     * 由服务层在请求线程上 bind、超时或 unbind 清理。
     */
    private static final Map<String, Binding> BINDINGS = new ConcurrentHashMap<>();

    private record Binding(List<String> libraryIds, long expiresAt) {
    }

    public KbLibraryTools(KbDocumentService kbDocumentService) {
        this.kbDocumentService = kbDocumentService;
    }

    /** 服务层在请求线程上调用：把本次问答的检索空间集合绑定到对话ID（幂等覆盖）。 */
    public static void bind(String conversationId, List<String> libraryIds) {
        if (conversationId == null || conversationId.isBlank() || libraryIds == null || libraryIds.isEmpty()) {
            return;
        }
        BINDINGS.entrySet().removeIf(e -> e.getValue().expiresAt() < System.currentTimeMillis());
        if (BINDINGS.size() >= MAX_BINDINGS) {
            log.warn("清单工具绑定表已满，拒绝新绑定 - conversationId: {}", conversationId);
            return;
        }
        BINDINGS.put(conversationId, new Binding(List.copyOf(libraryIds), System.currentTimeMillis() + BINDING_TTL_MILLIS));
    }

    /** 对话结束/异常时解除绑定（可选调用；TTL 过期兜底覆盖所有路径）。 */
    public static void unbind(String conversationId) {
        if (conversationId != null) {
            BINDINGS.remove(conversationId);
        }
    }

    private static Binding bindingOf(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return null;
        }
        Binding binding = BINDINGS.get(conversationId);
        if (binding == null || binding.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        return binding;
    }

    @Tool("列出当前知识库空间的文档清单（标题/文件名/索引状态/上传时间与总数）。当用户询问知识库里有哪些文档、有什么内容、多少篇等清单/概况类问题时调用")
    public String listDocuments(@P("当前对话ID（conversationId，见对话上下文）") String conversationId) {
        Binding binding = bindingOf(conversationId);
        if (binding == null) {
            log.warn("清单工具缺少空间绑定已拒绝执行（fail-closed） - conversationId: {}", conversationId);
            return NO_CONTEXT_MESSAGE;
        }
        StringBuilder sb = new StringBuilder();
        for (String libraryId : binding.libraryIds()) {
            var page = kbDocumentService.list(null, null, libraryId, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "id")));
            sb.append(libraryLabel(libraryId)).append("：共 ").append(page.getTotalElements()).append(" 篇");
            if (page.getTotalElements() > page.getContent().size()) {
                sb.append("（以下为最新的 ").append(page.getContent().size()).append(" 篇）");
            }
            sb.append("\n");
            if (page.getContent().isEmpty()) {
                sb.append("（该空间暂无文档）\n");
            }
            int idx = 1;
            for (KbDocumentView doc : page.getContent()) {
                sb.append(idx++).append(". 《").append(doc.getTitle()).append("》（")
                        .append(doc.getFileName()).append("，").append(statusText(doc.getStatus()))
                        .append("，").append(doc.getCreatedDate() == null ? "-" : doc.getCreatedDate())
                        .append("）\n");
            }
            sb.append("\n");
        }
        log.info("文档清单查询完成 - conversationId: {}, spaces: {}", conversationId, binding.libraryIds());
        return sb.toString().trim();
    }

    private String libraryLabel(String libraryId) {
        return KbLibrary.isPersonal(libraryId)
                ? "个人知识库（" + libraryId + "）"
                : "公共知识库（" + libraryId + "）";
    }

    private String statusText(Object status) {
        String name = String.valueOf(status);
        return switch (name) {
            case "INDEXED" -> "已索引";
            case "PENDING" -> "处理中";
            case "FAILED" -> "失败";
            default -> name;
        };
    }
}
