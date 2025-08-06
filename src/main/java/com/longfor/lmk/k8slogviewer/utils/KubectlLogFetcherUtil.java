package com.longfor.lmk.k8slogviewer.utils;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Slf4j
public class KubectlLogFetcherUtil {
    private static File cachedScriptFile = null;
    private static File extractScriptToTempFile(String resourcePath) throws IOException {
        if (cachedScriptFile != null && cachedScriptFile.exists()) {
            return cachedScriptFile;
        }
        InputStream inputStream = KubectlLogFetcherUtil.class.getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new FileNotFoundException("脚本文件未找到: " + resourcePath);
        }
        File tempFile = File.createTempFile("k8s-log-fetcher", ".sh");
        tempFile.deleteOnExit();
        Files.copy(inputStream, tempFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (!tempFile.setExecutable(true)) {
            log.warn("设置脚本文件权限失败: {}", tempFile.getAbsolutePath());
        }
        cachedScriptFile = tempFile;
        return tempFile;
    }

    private static String[] buildCommandArgs(K8sQuery query, String scriptPath) {
        String bashPath = AppConfig.getGitBashPath();
        return new String[]{
                bashPath,
                scriptPath,
                query.getNamespace(),
                query.getPodName(),
                query.getKeyword() == null ? "" : query.getKeyword(),
                String.valueOf(query.getTailLines()),
                String.valueOf(query.getContextLines()),
                String.valueOf(query.isFollow()),
                String.valueOf(query.getSinceSeconds())
        };
    }

    public static String fetchOnce(K8sQuery query, String resourceScriptPath) throws IOException {
        if (query.isFollow()) {
            throw new IllegalArgumentException("follow=true 不支持同步模式");
        }
        File scriptFile = extractScriptToTempFile(resourceScriptPath);
        String[] cmd = buildCommandArgs(query, scriptFile.getAbsolutePath());
        return runShellCommand(cmd);
    }

    public static void fetchStreaming(K8sQuery query, String resourceScriptPath, Consumer<String> logLineConsumer) throws IOException {
        File scriptFile = extractScriptToTempFile(resourceScriptPath);
        String[] cmd = buildCommandArgs(query, scriptFile.getAbsolutePath());
        log.info("执行命令: {}", String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        String kubeconfig = AppConfig.getKubeConfigPath();
        if (kubeconfig != null) {
            pb.environment().put("KUBECONFIG", kubeconfig);
        }
        Process process = pb.start();
        log.info("启动进程: {}", process.pid());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        SingleProcessManager.register(process, executor);
        Platform.runLater(() -> query.getCodeAreas().forEach(LogStyleUtil::clear));
        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logLineConsumer.accept(line);
                }
            } catch (IOException e) {
                log.error("读取日志流失败: {}", e.getMessage(), e);
            } finally {
                log.info("日志流结束");
                executor.shutdown();
            }
        });
    }

    private static String runShellCommand(String[] cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            String kubeconfig = AppConfig.getKubeConfigPath();
            if (kubeconfig != null) {
                pb.environment().put("KUBECONFIG", kubeconfig);
            }
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            log.error("执行 shell 命令失败: {}", e.getMessage(), e);
            return "日志获取失败: " + e.getMessage();
        }
    }
}
