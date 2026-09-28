package org.example.service;

import org.example.vo.UserQuotaView;
import org.example.vo.UserView;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Map;

/**
 * 管理端用户与配额管理：列表检索、角色/状态变更、密码重置、个人库限额调整。
 */
public interface AdminUserService {

    /** 用户分页，keyword 模糊匹配用户名或昵称（id 倒序）。 */
    Page<UserView> list(String keyword, Pageable page);

    /** 变更用户角色（USER/ADMIN）。引导管理员不可降级，不可变更自己的角色。 */
    UserView updateRole(Long id, String role, String operator);

    /** 变更用户状态（ACTIVE/DISABLED）。不可禁用自己与引导管理员。 */
    UserView updateStatus(Long id, String status, String operator);

    /** 管理员重置用户密码（不校验旧密码），返回脱敏视图。 */
    void resetPassword(Long id, String newPassword, String operator);

    /** 全部用户个人知识库配额一览（用量 + 生效限额），keyword 过滤同 {@link #list}。 */
    Page<UserQuotaView> quotaPage(String keyword, Pageable page);

    /**
     * 调整用户个人知识库限额覆盖。body 语义：key 不存在=不变更；值为 -1=清除覆盖（跟随全局默认）；
     * >=0=设置该值（0=不限制）。
     *
     * @return 更新后的配额视图
     */
    UserQuotaView updateLimits(Long id, Map<String, Object> body, String operator);
}
