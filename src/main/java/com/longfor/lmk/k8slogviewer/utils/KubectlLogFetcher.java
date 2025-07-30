package com.longfor.lmk.k8slogviewer.utils;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 工具类：使用 Git 提供的 bash.exe 执行 kubectl 命令，支持上下文和时间过滤。
 */
@Slf4j
public class KubectlLogFetcher {

    private static String bashPath = null;

    /**
     * 优先使用用户设置的 Git Bash 路径，其次自动查找
     */
    private static String resolveBashPath() {
        String userDefined = AppConfig.getGitBashPath();
        if (userDefined != null && new File(userDefined).exists()) {
            return userDefined;
        }
        return findBashPath();
    }

    public static String findBashPath() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            return "/bin/bash";
        }

        String defaultPath = "C:\\Program Files\\Git\\bin\\bash.exe";
        if (new File(defaultPath).exists()) {
            return defaultPath;
        }

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String path : pathEnv.split(";")) {
                File bashFile = new File(path, "bash.exe");
                if (bashFile.exists()) {
                    return bashFile.getAbsolutePath();
                }
            }
        }

        return null;
    }

    public static List<String> fetchLogWithContext(
            String namespace,
            String podName,
            String containerName,
            String keyword,
            int contextLines,
            Integer sinceSeconds
    ) {
        List<String> result = new ArrayList<>();
        bashPath = resolveBashPath();
        if (bashPath == null && System.getProperty("os.name").toLowerCase().contains("win")) {
            result.add("❌ 未找到 Git 的 bash.exe，请安装 Git 或手动配置路径");
            return result;
        }

        try {
            String baseCmd = String.format("kubectl logs %s -n %s", podName, namespace);
            if (containerName != null && !containerName.isEmpty()) {
                baseCmd += " -c " + containerName;
            }
            if (sinceSeconds != null) {
                baseCmd += " --since=" + sinceSeconds + "s";
            }

            String fullCmd = baseCmd;
            if (keyword != null && !keyword.isEmpty()) {
                String safeKeyword = keyword.replace("\"", "\\\"").replace("$", "\\$");
                fullCmd += " | grep -C " + contextLines + " \"" + safeKeyword + "\" | tail -n " + contextLines * 5;
            } else {
                fullCmd += " --tail=" + 100;
            }

            log.info("执行命令: {}", fullCmd); // 用于调试命令

            String[] cmd = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new String[]{bashPath, "-c", fullCmd}
                    : new String[]{"bash", "-c", fullCmd};

            ProcessBuilder processBuilder = new ProcessBuilder(cmd).redirectErrorStream(true);
            String kubeconfig = System.getProperty("KUBECONFIG");
            if (kubeconfig != null) {
                processBuilder.environment().put("KUBECONFIG", kubeconfig);
            }

            Process process = processBuilder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                boolean inContextBlock = false;

                while ((line = reader.readLine()) != null) {
                    // 如果匹配到关键字上下文块
                    if (keyword != null && line.contains(keyword)) {
                        // 如果之前有一个上下文块，先加个分隔符
                        if (inContextBlock) {
                            result.add("-------- 上下文块结束 --------");
                        }
                        result.add("-------- 上下文块开始 --------");
                        inContextBlock = true;
                    }
                    result.add(line);
                }

                // 如果最后一个块没有结束，添加结束标记
                if (inContextBlock) {
                    result.add("-------- 上下文块结束 --------");
                }
            }

            int code = process.waitFor();
            if (code != 0) {
                result.add("⚠️ 命令执行异常，退出码: " + code);
            }

            if (result.isEmpty()) {
                result.add("⚠️ 未找到匹配的日志，请检查关键字或时间范围");
            }

        } catch (Exception e) {
            result.add("❌ 执行失败: " + e.getMessage());
            log.error("日志获取失败", e);
        }

        return result;
    }


    /**
     * 获取日志（支持 tail 和 since 参数）
     *
     * @param namespace     命名空间
     * @param podName       pod 名
     * @param containerName 容器名
     * @param tailLines     尾行数
     * @param sinceSeconds  时间范围（秒）
     * @param follow        是否实时跟踪
     * @return Process 对象（实时流）或日志列表（非实时）
     */
    public static Object fetchLogs(
            String namespace,
            String podName,
            String containerName,
            int tailLines,
            Integer sinceSeconds,
            boolean follow
    ) {
        List<String> result = new ArrayList<>();
        bashPath = resolveBashPath();
        if (bashPath == null && System.getProperty("os.name").toLowerCase().contains("win")) {
            result.add("❌ 未找到 Git 的 bash.exe，请安装 Git 或手动配置路径");
            return result;
        }

        try {
            String baseCmd = String.format("kubectl logs %s -n %s", podName, namespace);
            if (containerName != null && !containerName.isEmpty()) {
                baseCmd += " -c " + containerName;
            }
            if (tailLines > 0) {
                baseCmd += " --tail=" + tailLines;
            }
            if (sinceSeconds != null) {
                baseCmd += " --since=" + sinceSeconds + "s";
            }
            if (follow) {
                baseCmd += " --follow";
            }

            String[] cmd = System.getProperty("os.name").toLowerCase().contains("win")
                    ? new String[]{bashPath, "-c", baseCmd}
                    : new String[]{"bash", "-c", baseCmd};

            ProcessBuilder processBuilder = new ProcessBuilder(cmd).redirectErrorStream(true);
            String kubeconfig = System.getProperty("KUBECONFIG");
            if (kubeconfig != null) {
                processBuilder.environment().put("KUBECONFIG", kubeconfig);
            }
            log.info("执行命令: {}", baseCmd);
            Process process = processBuilder.start();
            if (follow) {
                return process;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.add(line);
                }
            }

            int code = process.waitFor();
            if (code != 0) {
                result.add("⚠️ 命令执行异常，退出码: " + code);
            }

        } catch (Exception e) {
            result.add("❌ 执行失败: " + e.getMessage());
            log.error("日志拉取失败", e);
        }

        return result;
    }
}
