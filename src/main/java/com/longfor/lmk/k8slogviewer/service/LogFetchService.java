package com.longfor.lmk.k8slogviewer.service;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.config.K8sClientManager;
import com.longfor.lmk.k8slogviewer.config.K8sQuery;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import okhttp3.Call;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/**
 * 日志获取服务，统一通过 K8s Java Client 获取日志。
 */
public final class LogFetchService {

    private static final Logger log = LoggerFactory.getLogger(LogFetchService.class);
    private static volatile Call currentCall;

    private LogFetchService() {
        throw new IllegalStateException("Utility class");
    }

    // ==================== 流式获取（K8s Java SDK） ====================

    /**
     * 流式获取日志（含头部信息），兼容旧调用。
     */
    public static void fetchStreaming(Consumer<String> logLineConsumer) throws IOException {
        fetchStreaming(logLineConsumer, true);
    }

    /**
     * 通过 K8s Java SDK 流式获取日志，每行通过 logLineConsumer 回调。
     * @param logLineConsumer 日志行消费者
     * @param emitHeader 是否输出头部信息（首次连接输出，重连不输出）
     */
    public static void fetchStreaming(Consumer<String> logLineConsumer, boolean emitHeader) throws IOException {
        K8sQuery query = AppConfig.getK8sQuery();

        // 构建等价 kubectl 命令字符串（仅用于日志展示）
        String cmdStr = buildCommandString(query);
        log.info("执行命令: {}", cmdStr);

        // 输出头部信息 + 分割线，复用现有 headerArea 机制
        if (emitHeader) {
            emitHeaderInfo(query, logLineConsumer);
        }

        CoreV1Api api = K8sClientManager.getCoreV1Api();
        Integer sinceSeconds = query.getSinceSeconds() > 0 ? (int) query.getSinceSeconds() : null;
        Integer tailLines = query.getTailLines() > 0 ? query.getTailLines() : null;

        cancelCurrentCall();

        Call call;
        try {
            call = api.readNamespacedPodLogCall(
                    query.getPodName(), query.getNamespace(),
                    null,                   // container
                    query.isFollow(),       // follow
                    null,                   // insecureSkipTLSVerifyBackend
                    null,                   // limitBytes
                    null,                   // pretty
                    null,                   // previous
                    sinceSeconds,           // sinceSeconds
                    tailLines,              // tailLines
                    null,                   // timestamps
                    null                    // _callback
            );
        } catch (ApiException e) {
            throw new IOException("K8s API 调用失败: " + e.getResponseBody(), e);
        }
        currentCall = call;

        try {
            Response response = call.execute();
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                String errMsg = body != null ? body.string() : "未知错误";
                throw new IOException("K8s API 调用失败: " + errMsg);
            }

            // 逐行流式读取
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    logLineConsumer.accept(line);
                }
            }
        } catch (IOException e) {
            // 用本方法创建的 call 引用判断，而非 currentCall（切换容器时 currentCall 已指向新请求）
            if (call.isCanceled()) {
                log.info("日志流被取消，静默退出");
                return;
            }
            throw e;
        } finally {
            if (currentCall == call) {
                currentCall = null;
            }
        }
    }

    /**
     * 取消当前正在进行的日志流请求。
     */
    public static void cancelCurrentCall() {
        Call call = currentCall;
        if (call != null && !call.isCanceled()) {
            call.cancel();
            log.info("已取消当前日志流请求");
        }
        currentCall = null;
    }

    // ==================== 一次性获取（K8s Java Client） ====================

    /**
     * 通过 K8s Java Client 获取完整日志文本（用于下载等场景）。
     */
    public static String fetchFullLogs(String namespace, String podName) throws ApiException, IOException {
        CoreV1Api api = K8sClientManager.getCoreV1Api();
        return api.readNamespacedPodLog(
                podName, namespace,
                null,                   // container
                false,                  // follow — 一次性获取，不跟随
                null,                   // insecureSkipTLSVerifyBackend
                null,                   // limitBytes
                null,                   // pretty
                null,                   // previous
                null,                   // sinceSeconds
                null,                   // tailLines
                null                    // timestamps
        );
    }

    // ==================== 内部方法 ====================

    /**
     * 构建等价的 kubectl 命令字符串（仅用于日志展示，不执行）。
     */
    private static String buildCommandString(K8sQuery query) {
        StringBuilder cmd = new StringBuilder();
        cmd.append("kubectl logs \"").append(query.getPodName()).append("\"");
        cmd.append(" -n \"").append(query.getNamespace()).append("\"");

        if (query.getTailLines() > 0) {
            cmd.append(" --tail=").append(query.getTailLines());
        }
        if (query.isFollow()) {
            cmd.append(" -f");
        }
        if (query.getSinceSeconds() > 0) {
            cmd.append(" --since=").append(query.getSinceSeconds()).append("s");
        }

        return cmd.toString();
    }

    /**
     * 输出头部信息行和分割线，让现有的 headerArea 机制继续生效。
     */
    private static void emitHeaderInfo(K8sQuery query, Consumer<String> logLineConsumer) {
        String cmdStr = buildCommandString(query);
        logLineConsumer.accept("[Info] 命名空间: " + query.getNamespace());
        logLineConsumer.accept("[Info] Pod: " + query.getPodName());
        logLineConsumer.accept("[Info] 命令: " + cmdStr);
        logLineConsumer.accept("=================================分割线=================================");
    }

    // JVM 退出时取消当前请求
    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Call call = currentCall;
            if (call != null && !call.isCanceled()) {
                log.info("JVM 退出，取消日志流请求");
                call.cancel();
            }
        }));
    }
}
