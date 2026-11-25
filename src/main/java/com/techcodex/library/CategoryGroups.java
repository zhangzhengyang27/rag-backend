package com.techcodex.library;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 问答/检索的分类归并：文档 frontmatter 的 category 粒度很细（公共库 47 个），
 * 直接暴露给用户既难选择又近义重叠（CSS 与 CSS基础、管理 与 技术管理、前端 与 前端工程化）。
 * <p>
 * 这里把它们归并为 10 个顶级分类：下拉只展示顶级，检索时顶级名展开为原分类集合（IN 查询）；
 * 用户仍可下钻选择单个原分类，保留精确过滤能力。新增文档若出现未登记的分类，
 * 由调用方归入「其他」，避免分类从下拉里静默消失。
 */
public final class CategoryGroups {

    /** 顶级分类：name 是展示名（刻意不与任何原分类重名，避免二义性），children 是原分类 */
    public record Group(String name, List<String> children) {
    }

    /** 未登记的原分类统一归到此组 */
    public static final String OTHER = "其他";

    private static final List<Group> GROUPS = List.of(
            new Group("前端基础", List.of("CSS", "CSS基础", "HTML5", "JavaScript", "TypeScript")),
            new Group("前端框架", List.of("React", "Vue", "Next.js", "Taro")),
            new Group("前端与跨端", List.of("前端", "前端工程化", "Electron")),
            new Group("后端与架构", List.of("Java", "Node.js", "Python", "架构与工程", "全栈工程", "分布式", "设计模式")),
            new Group("数据与中间件", List.of("MySQL", "数据库基础", "Redis")),
            new Group("运维与部署", List.of("Linux 系统", "Nginx", "Docker 容器", "部署与运维实践",
                    "Git 版本控制", "CI-CD 与测试基础设施")),
            new Group("测试与质量", List.of("测试基础", "测试模型与流程", "测试前沿与质量管理", "测试开发与平台工程",
                    "API测试", "性能测试", "GUI自动化测试", "移动端测试", "单元测试与代码级测试", "调试")),
            new Group("产品与技术管理", List.of("产品", "技术管理", "管理", "技术与商业案例解读")),
            new Group("工具与基础", List.of("tools", "计算机基础", "Vim", "附录")),
            new Group("安全", List.of("安全"))
    );

    private static final Map<String, String> CHILD_TO_GROUP = buildIndex();

    private CategoryGroups() {
    }

    private static Map<String, String> buildIndex() {
        Map<String, String> index = new HashMap<>();
        for (Group group : GROUPS) {
            for (String child : group.children()) {
                index.put(child, group.name());
            }
        }
        return index;
    }

    public static List<Group> groups() {
        return GROUPS;
    }

    /** 是否为已登记的顶级分类名 */
    public static boolean isGroup(String name) {
        if (name == null) {
            return false;
        }
        for (Group group : GROUPS) {
            if (group.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /** 是否已在任一顶级分组下登记过（用于把新增分类归入「其他」） */
    public static boolean isKnownChild(String category) {
        return category != null && CHILD_TO_GROUP.containsKey(category);
    }

    /**
     * 解析检索过滤值：顶级分类名 → 其全部原分类；原分类名 → 自身（精确匹配）；
     * 未登记的分类同样按自身处理（不吞掉数据）。
     */
    public static List<String> resolve(String category) {
        if (category == null || category.isBlank()) {
            return List.of();
        }
        String trimmed = category.trim();
        for (Group group : GROUPS) {
            if (group.name().equals(trimmed)) {
                return new ArrayList<>(group.children());
            }
        }
        return List.of(trimmed);
    }
}
