package org.example.controller;

import org.example.config.RuntimeConfigStore;
import org.example.service.AuditLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 知识库运行时配置管理接口（需 ADMIN 角色）：
 * 在线查看与调整检索参数 / 个人库配额 / 内容安全 / 评测告警阈值，即时生效、无需重启。
 */
@RestController
@RequestMapping("/admin/kb")
public class AdminConfigController {

    @Autowired
    private RuntimeConfigStore runtimeConfig;

    @Autowired
    private AuditLogService auditLogService;

    /** 全部可配置项：当前生效值 + 默认值 + 是否已覆盖 + 分组说明。 */
    @GetMapping("/config")
    public List<RuntimeConfigStore.ConfigItemView> list() {
        return runtimeConfig.view();
    }

    /**
     * 批量更新配置。JSON body：{ "retrieval.final-top-k": "8", ... }；
     * 值为空串或 null 表示清除该键的覆盖（恢复跟随启动默认）。非法键/非法取值整批拒绝。
     */
    @PutMapping("/config")
    public Map<String, Object> update(@RequestBody Map<String, String> values) {
        List<String> changed = runtimeConfig.update(values, operator());
        auditLogService.record("default", operator(), "CONFIG_UPDATE", "CONFIG", null,
                "运行时配置更新：" + String.join("，", changed));
        return Map.of("changed", changed);
    }

    private String operator() {
        return org.example.security.SecurityUtil.currentUsername();
    }
}
