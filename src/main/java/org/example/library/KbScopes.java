package org.example.library;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 检索范围（scope）解析：把前端三态范围参数解析为目标空间集合。
 * <ul>
 *   <li>public（缺省）：仅公共文档空间（default），与既有行为一致；</li>
 *   <li>mine：仅当前用户的<b>个人空间</b>（u-{username}，按用户名派生）；</li>
 *   <li>all：公共空间 ∪ 个人空间。</li>
 * </ul>
 * mine/all 与 token 指向解耦：存量用户 token 仍指 default 时，私有范围同样定位到
 * 自己的个人空间，不会错误检索公共文档。username 为空（无登录态的服务级调用）时
 * 回落到 fallbackLibrary。未识别的取值一律按 public 处理，保证旧客户端行为不变。
 */
public final class KbScopes {

    public static final String SCOPE_PUBLIC = "public";
    public static final String SCOPE_MINE = "mine";
    public static final String SCOPE_ALL = "all";

    private KbScopes() {
    }

    /** 解析 scope 为去重且有序（公共在前）的目标空间列表，结果非空。 */
    public static List<String> resolve(String scope, String username, String fallbackLibrary, String defaultLibrary) {
        String mine = username == null || username.isBlank()
                ? (fallbackLibrary == null || fallbackLibrary.isBlank() ? defaultLibrary : fallbackLibrary)
                : KbLibrary.PERSONAL_PREFIX + username;
        LinkedHashSet<String> librarys = new LinkedHashSet<>();
        if (SCOPE_MINE.equalsIgnoreCase(scope)) {
            librarys.add(mine);
        } else if (SCOPE_ALL.equalsIgnoreCase(scope)) {
            librarys.add(defaultLibrary);
            librarys.add(mine);
        } else {
            // public / null / 未知取值：仅公共空间
            librarys.add(defaultLibrary);
        }
        return new ArrayList<>(librarys);
    }
}
