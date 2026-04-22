package com.longfor.lmk.k8slogviewer.service;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import com.longfor.lmk.k8slogviewer.utils.SingleProcessManager;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 统一日志获取服务，封装两种获取通道：
 * <ul>
 *   <li>kubectl shell 脚本 - 用于流式实时日志获取</li>
 *   <li>K8s Java Client - 用于一次性日志下载</li>
 * </ul>
 */
public final class LogFetchService {

    private static final Logger log = LoggerFactory.getLogger(LogFetchService.class);
    private static final String STREAMING_SCRIPT = "/scripts/search_logs.sh";
    private static File cachedScriptFile;

    private LogFetchService() {
        throw new IllegalStateException("Utility class");
    }

    // ==================== 流式获取（kubectl 脚本） ====================

    /**
     * 通过 kubectl 脚本流式获取日志，每行通过 logLineConsumer 回调。
     *
     * @param logLineConsumer 每行日志的消费者
     */
    public static void fetchStreaming(Consumer<String> logLineConsumer) throws IOException {
        K8sQuery query = AppConfig.getK8sQuery();
        File scriptFile = extractScript(STREAMING_SCRIPT);
        String[] cmd = buildScriptArgs(query, scriptFile.getAbsolutePath());
        log.info("执行命令: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        String kubeconfig = AppPreferences.getKubeConfigPath();
        if (kubeconfig != null) {
            pb.environment().put("KUBECONFIG", kubeconfig);
        }

        Process process = pb.start();
        log.info("启动进程: {}", process.pid());

        ExecutorService executor = Executors.newSingleThreadExecutor();
        SingleProcessManager.register(process, executor);

        executor.submit(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
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

    // ==================== 一次性获取（K8s Java Client） ====================

    /**
     * 通过 K8s Java Client 获取完整日志文本（用于下载）。
     *
     * @return 完整日志文本
     */
    public static String fetchFullLogs(String namespace, String podName) throws ApiException, IOException {
        CoreV1Api api = K8sClientManager.getCoreV1Api();
        return api.readNamespacedPodLog(
                podName, namespace,
                null, null, null, null, null, null, null, null, null
        );
    }

    // ==================== 内部方法 ====================

    private static File extractScript(String resourcePath) throws IOException {
        if (cachedScriptFile != null && cachedScriptFile.exists()) {
            return cachedScriptFile;
        }
        InputStream inputStream = LogFetchService.class.getResourceAsStream(resourcePath);
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

    private static String[] buildScriptArgs(K8sQuery query, String scriptPath) {
        String bashPath = AppPreferences.getGitBashPath();
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
}
