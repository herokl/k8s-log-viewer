package com.longfor.lmk.k8slogviewer.config;

import com.longfor.lmk.k8slogviewer.utils.CommonUtils;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Kubernetes API 客户端管理器，负责初始化和提供 CoreV1Api 实例。
 */
public final class K8sClientManager {

    private static final Logger log = LoggerFactory.getLogger(K8sClientManager.class);

    private static volatile CoreV1Api coreV1Api;
    private static volatile ApiClient apiClient;

    private K8sClientManager() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * 获取 CoreV1Api 实例，懒初始化。
     * 如果 kubeconfig 路径变化，调用 {@link #reset()} 后重新获取。
     *
     * @return CoreV1Api 实例
     */
    public static CoreV1Api getCoreV1Api() {
        if (coreV1Api == null) {
            synchronized (K8sClientManager.class) {
                if (coreV1Api == null) {
                    initClient();
                }
            }
        }
        return coreV1Api;
    }

    /**
     * 获取 ApiClient 实例（供 Metrics API 等需要底层 HTTP 调用的场景使用）。
     */
    public static ApiClient getApiClient() {
        if (apiClient == null) {
            // 触发初始化
            getCoreV1Api();
        }
        return apiClient;
    }

    /**
     * 重置客户端（kubeconfig 路径变化时调用）
     */
    public static synchronized void reset() {
        coreV1Api = null;
        apiClient = null;
    }

    private static void initClient() {
        try {
            String kubeConfigPath = AppPreferences.getKubeConfigPath();
            apiClient = kubeConfigPath != null
                    ? Config.fromConfig(kubeConfigPath)
                    : Config.defaultClient();
            // 流式日志（follow=true）需要长连接，禁用读取超时
            apiClient.setReadTimeout(0);
            apiClient.setConnectTimeout(30000);
            apiClient.setWriteTimeout(30000);
            Configuration.setDefaultApiClient(apiClient);
            coreV1Api = new CoreV1Api();
        } catch (IOException e) {
            log.error("Failed to load kubeconfig", e);
            Platform.runLater(() -> {
                var stage = AppConfig.getMainStage();
                if (stage != null && stage.getScene() != null) {
                    CommonUtils.showToast(stage.getScene().getRoot(), "✗", "K8s客户端初始化失败，请检查配置文件", "#E74C3C");
                }
            });
        }
    }
}
