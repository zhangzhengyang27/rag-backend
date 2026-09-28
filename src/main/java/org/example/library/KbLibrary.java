package org.example.library;

/**
 * 知识库空间常量。
 *
 * <p>空间标识（library_id 列）划分两类数据：{@code default} 为公共知识库（docs 同步目标），
 * {@code u-{username}} 为用户个人知识库。这里保留 "library" 命名仅因列名与 JWT claim 沿用，
 * 语义上已无"空间管理"概念。</p>
 */
public final class KbLibrary {

    /** 公共知识库空间标识（docs 同步目标） */
    public static final String DEFAULT_LIBRARY = "default";

    /** 个人知识库空间前缀：u-{username}，注册时写入用户默认空间 */
    public static final String PERSONAL_PREFIX = "u-";

    private KbLibrary() {
    }

    /** 是否个人知识库空间（配额只约束个人空间，公共空间不限量）。 */
    public static boolean isPersonal(String spaceCode) {
        return spaceCode != null && spaceCode.startsWith(PERSONAL_PREFIX);
    }

    /** 按用户名派生其个人知识库空间标识（无 DB 依赖）。 */
    public static String personalCode(String username) {
        return PERSONAL_PREFIX + username;
    }
}
