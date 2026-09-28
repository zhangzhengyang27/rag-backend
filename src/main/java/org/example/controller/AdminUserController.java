package org.example.controller;

import org.example.service.AdminUserService;
import org.example.vo.UserQuotaView;
import org.example.vo.UserView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 管理端用户与配额管理接口（需 ADMIN 角色，见 SecurityConfig /admin/** 规则）。
 * 提供用户列表检索、角色/状态变更、密码重置与个人知识库限额调整。
 */
@RestController
@RequestMapping("/admin/kb")
public class AdminUserController {

    @Autowired
    private AdminUserService adminUserService;

    /** 用户分页（keyword 模糊匹配用户名/昵称）。 */
    @GetMapping("/users")
    public PagedModel<UserView> listUsers(
            @RequestParam(value = "keyword", required = false) String keyword,
            @org.springframework.data.web.PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        return new PagedModel<>(adminUserService.list(keyword, page));
    }

    /** 变更用户角色（USER/ADMIN）。引导管理员不可降级，不可变更自己的角色。 */
    @PutMapping("/users/{id}/role")
    public UserView updateRole(@PathVariable("id") Long id, @RequestParam("role") String role) {
        return adminUserService.updateRole(id, role, currentUserId());
    }

    /** 变更用户状态（ACTIVE/DISABLED）；禁用后登录与既有 token 均立即失效。 */
    @PutMapping("/users/{id}/status")
    public UserView updateStatus(@PathVariable("id") Long id, @RequestParam("status") String status) {
        return adminUserService.updateStatus(id, status, currentUserId());
    }

    /** 管理员重置用户密码（表单体传参，避免密码进 URL/日志）。 */
    @PutMapping("/users/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable("id") Long id,
                                             @RequestParam("newPassword") String newPassword) {
        adminUserService.resetPassword(id, newPassword, currentUserId());
        return Map.of("ok", true);
    }

    /** 全部用户个人知识库配额一览（用量 + 生效限额 + 是否自定义覆盖）。 */
    @GetMapping("/quotas")
    public PagedModel<UserQuotaView> quotaPage(
            @RequestParam(value = "keyword", required = false) String keyword,
            @org.springframework.data.web.PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        return new PagedModel<>(adminUserService.quotaPage(keyword, page));
    }

    /**
     * 调整用户个人知识库限额。JSON body 语义：key 不存在=不变更；-1=清除覆盖（跟随全局默认）；
     * >=0=设置该值（0=不限制）。
     */
    @PutMapping("/quotas/{userId}")
    public UserQuotaView updateLimits(@PathVariable("userId") Long userId,
                                      @RequestBody Map<String, Object> body) {
        return adminUserService.updateLimits(userId, body, currentUserId());
    }

    private String currentUserId() {
        return org.example.security.SecurityUtil.currentUsername();
    }
}
