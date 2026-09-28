package org.example.scheduler;

import lombok.extern.slf4j.Slf4j;
import org.example.service.KbDocumentService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * docs 目录同步触发器：为服务前端重新发布场景设计，默认不做周期轮询。
 *
 * <p>触发方式：
 * <ul>
 *   <li>启动对账：应用就绪后延迟 {@code app.kb.docs-sync.initial-delay-ms} 触发一次
 *       （默认开启，覆盖"后端随前端一起重新发布"的场景）；</li>
 *   <li>发布钩子：前端重新发布后由流水线调用 {@code POST /admin/kb/documents/sync}；</li>
 *   <li>周期轮询：仅开发期需要时打开（{@code app.kb.docs-sync.interval-ms > 0}），生产默认关闭，
 *       避免 docs 几乎不变时的空转。</li>
 * </ul></p>
 */
@Slf4j
@Component
public class KbDocsSyncScheduler {

    private final KbDocumentService kbDocumentService;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kb-docs-sync-trigger");
        t.setDaemon(true);
        return t;
    });
    /** 防止启动对账与轮询首跑重叠触发。 */
    private final AtomicBoolean lastTriggerAccepted = new AtomicBoolean(false);

    @Value("${app.kb.docs-sync.enabled:true}")
    private boolean enabled;

    @Value("${app.kb.docs-sync.sync-on-startup:true}")
    private boolean syncOnStartup;

    @Value("${app.kb.docs-sync.interval-ms:0}")
    private long intervalMs;

    @Value("${app.kb.docs-sync.initial-delay-ms:20000}")
    private long initialDelayMs;

    @Value("${app.kb.docs-sync.root:${KB_DOCS_ROOT:../tech-codex/docs}}")
    private String root;

    @Value("${app.kb.docs-sync.library-code:default}")
    private String libraryCode;

    public KbDocsSyncScheduler(KbDocumentService kbDocumentService) {
        this.kbDocumentService = kbDocumentService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!enabled) {
            log.info("docs 目录同步未启用（app.kb.docs-sync.enabled=false），仅可通过 POST /admin/kb/documents/sync 手动触发");
            return;
        }
        if (syncOnStartup) {
            executor.schedule(this::trigger, Math.max(0, initialDelayMs), TimeUnit.MILLISECONDS);
            log.info("docs 启动对账已排程 - {}ms 后触发, root: {}, library: {}", initialDelayMs, root, libraryCode);
        }
        if (intervalMs > 0) {
            executor.scheduleWithFixedDelay(this::trigger,
                    Math.max(initialDelayMs, 1000), intervalMs, TimeUnit.MILLISECONDS);
            log.info("docs 同步轮询已启用 - interval: {}ms（生产环境建议保持默认关闭，用发布钩子触发）", intervalMs);
        } else {
            log.info("docs 同步轮询未启用（interval-ms=0）：启动对账 + 发布钩子/手动触发");
        }
    }

    private void trigger() {
        try {
            kbDocumentService.syncDocs(null, "docs-sync", libraryCode);
            lastTriggerAccepted.set(true);
            log.info("docs 同步已触发 - root: {}, library: {}", root, libraryCode);
        } catch (IllegalArgumentException e) {
            log.info("docs 同步跳过：{}", e.getMessage());
        } catch (Exception e) {
            log.warn("docs 同步触发失败: {}", e.getMessage());
        }
    }
}
