package org.example.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.example.service.KbDocumentService;
import org.example.service.KbGitSyncService;
import org.example.vo.KbGitSyncView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * GitHub 连接器（Phase 4 范围只做 GitHub）：
 * <ul>
 *   <li>浅克隆（--depth 1）或对已有目录 fetch + reset，实现增量同步；</li>
 *   <li>只同步指定子目录下的 Markdown，复用 KbDocumentService.importDirectory 的
 *       按相对路径增量更新/内容哈希去重；</li>
 *   <li>不解析 PR、不自动改仓库，Lint 建议由内容 Lint 单独产出。</li>
 * </ul>
 *
 * <p>安全边界：只接受 https://github.com/ 的公网仓库；分支/子路径做白名单校验，
 * 避免通过 ../ 或任意 URL 让服务端 clone 非预期内容。私有仓库请用机器上已配置的
 * git credential helper（本服务不接收明文 token，避免进程参数泄漏）。</p>
 */
@Slf4j
@Service
public class KbGitSyncServiceImpl implements KbGitSyncService {

    private static final long GIT_TIMEOUT_SECONDS = 180;

    private final KbDocumentService documentService;
    private final String cloneRoot;

    public KbGitSyncServiceImpl(KbDocumentService documentService,
                                @Value("${app.kb.git-sync.root:/tmp/rag-git-sync}") String cloneRoot) {
        this.documentService = documentService;
        this.cloneRoot = cloneRoot;
    }

    @Override
    public KbGitSyncView sync(String repoUrl, String branch, String path, String userId, String libraryId) {
        String url = normalizeRepoUrl(repoUrl);
        String ref = normalizeBranch(branch);
        String subPath = normalizeSubPath(path);

        String repoName = url.substring(url.lastIndexOf('/') + 1).replaceAll("\\.git$", "");
        String dirName = (repoName + "-" + ref).replaceAll("[^A-Za-z0-9._-]", "-");
        Path target = Paths.get(cloneRoot).resolve(dirName).toAbsolutePath().normalize();
        Path cloneRootPath = Paths.get(cloneRoot).toAbsolutePath().normalize();
        if (!target.startsWith(cloneRootPath)) {
            throw new IllegalArgumentException("非法同步目录");
        }

        try {
            Files.createDirectories(cloneRootPath);
            if (Files.isDirectory(target.resolve(".git"))) {
                runGit(List.of("git", "-C", target.toString(), "fetch", "--depth", "1", "origin", ref));
                runGit(List.of("git", "-C", target.toString(), "reset", "--hard", "FETCH_HEAD"));
            } else {
                runGit(List.of("git", "clone", "--depth", "1", "--branch", ref, url, target.toString()));
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("GitHub 同步失败：" + e.getMessage(), e);
        }

        Path importDir = subPath.isEmpty() ? target : target.resolve(subPath).normalize();
        if (!importDir.startsWith(target) || !Files.isDirectory(importDir)) {
            throw new IllegalArgumentException("仓库内路径不存在: " + subPath);
        }
        String taskId = documentService.importDirectory(importDir.toString(), userId, libraryId);
        log.info("GitHub 同步已受理 - repo: {}, branch: {}, path: {}, task: {}", url, ref, subPath, taskId);
        return KbGitSyncView.builder()
                .repoUrl(url)
                .branch(ref)
                .path(subPath)
                .localPath(importDir.toString())
                .taskId(taskId)
                .build();
    }

    /** 只允许 GitHub 公网 HTTPS 仓库；拒绝 file://、ssh 与内网地址。 */
    static String normalizeRepoUrl(String repoUrl) {
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("repoUrl 不能为空");
        }
        String url = repoUrl.trim().replaceAll("\\.git$", "");
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("repoUrl 不是合法 URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || !"github.com".equalsIgnoreCase(uri.getHost())
                || uri.getPath() == null
                || uri.getPath().split("/").length < 3) {
            throw new IllegalArgumentException("只支持 https://github.com/{owner}/{repo} 形式的仓库");
        }
        return url;
    }

    static String normalizeBranch(String branch) {
        String ref = branch == null || branch.isBlank() ? "main" : branch.trim();
        if (!ref.matches("[A-Za-z0-9._/-]{1,100}") || ref.contains("..") || ref.startsWith("/")) {
            throw new IllegalArgumentException("非法分支名: " + ref);
        }
        return ref;
    }

    static String normalizeSubPath(String path) {
        String sub = path == null ? "" : path.trim().replaceAll("^/|/$", "");
        Path p = Paths.get(sub).normalize();
        if (p.isAbsolute() || p.startsWith("..")) {
            throw new IllegalArgumentException("仓库路径必须是相对路径且不能越界");
        }
        return p.toString().replace('\\', '/');
    }

    /** 执行 git 并收集输出；超时/非零退出统一抛错，避免同步任务静默失败。 */
    private void runGit(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        Process process = pb.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("git 超时（" + GIT_TIMEOUT_SECONDS + "s）");
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("git 退出码 " + process.exitValue() + "：" + tail(output));
        }
        log.debug("git 完成 - {}：{}", command.get(0), tail(output));
    }

    private static String tail(String output) {
        if (output == null) {
            return "";
        }
        String trimmed = output.trim();
        return trimmed.length() <= 400 ? trimmed : trimmed.substring(trimmed.length() - 400);
    }
}
