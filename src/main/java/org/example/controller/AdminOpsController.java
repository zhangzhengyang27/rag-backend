package org.example.controller;

import org.example.security.LoginGuard;
import org.example.security.SecurityUtil;
import org.example.service.AuditLogService;
import org.example.service.KbAlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端运营告警与安全可观测接口（需 ADMIN 角色）：
 * 告警查看/处理，登录锁定账号查看/解锁。
 */
@RestController
@RequestMapping("/admin/kb")
public class AdminOpsController {

    @Autowired
    private KbAlertService kbAlertService;

    @Autowired
    private LoginGuard loginGuard;

    @Autowired
    private AuditLogService auditLogService;

    /** 告警分页（时间倒序）；resolved 可选过滤（true=已处理，false=未处理，缺省全部）。 */
    @GetMapping("/alerts")
    public PagedModel<org.example.vo.KbAlertView> alerts(
            @RequestParam(value = "resolved", required = false) Boolean resolved,
            @PageableDefault(size = 20, sort = "id", direction = Sort.Direction.DESC) Pageable page) {
        Page<org.example.vo.KbAlertView> result = kbAlertService.page("default", resolved, page);
        return new PagedModel<>(result);
    }

    /** 当前未处理告警（管理总览页展示）。 */
    @GetMapping("/alerts/open")
    public List<org.example.vo.KbAlertView> openAlerts() {
        return kbAlertService.openAlerts("default");
    }

    /** 处理告警（标记已处理并记录处理人）。 */
    @PutMapping("/alerts/{id}/resolve")
    public void resolveAlert(@PathVariable("id") Long id) {
        kbAlertService.resolve(id, SecurityUtil.currentUsername());
        auditLogService.record("default", SecurityUtil.currentUsername(), "ALERT_RESOLVE",
                "ALERT", id, "处理告警 #" + id);
    }

    /** 当前登录锁定中的账号（Redis 共享状态，多实例一致）。 */
    @GetMapping("/security/logins")
    public List<LoginGuard.LockedAccount> lockedAccounts() {
        return loginGuard.listLocked();
    }

    /** 管理员解锁登录失败锁定的账号（清空失败计数与锁定标记）。 */
    @DeleteMapping("/security/logins/{username}")
    public void unlockAccount(@PathVariable("username") String username) {
        loginGuard.unlock(username);
        auditLogService.record("default", SecurityUtil.currentUsername(), "USER_UNLOCK",
                "USER", null, "解锁登录锁定账号：" + username);
    }
}
