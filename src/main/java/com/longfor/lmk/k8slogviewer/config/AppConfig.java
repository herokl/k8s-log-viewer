package com.longfor.lmk.k8slogviewer.config;

import com.longfor.lmk.k8slogviewer.service.ClusterTreeService;
import javafx.stage.Stage;

/**
 * 应用全局配置中心。
 * 职责已拆分到：
 * - {@link AppPreferences} 偏好设置持久化
 * - {@link K8sClientManager} K8s API 客户端
 * - {@link ClusterTreeService} 集群树数据
 *
 * 本类仅保留 K8sQuery 全局单例和 Stage 引用。
 */
public class AppConfig {

    private static final K8sQuery K8S_QUERY = K8sQuery.builder()
            .contextLines(0)
            .tailLines(1000)
            .sinceSeconds(0)
            .follow(true)
            .searchRunning(true)
            .build();

    private static Stage mainStage;

    private AppConfig() {
    }

    public static K8sQuery getK8sQuery() {
        return K8S_QUERY;
    }

    public static Stage getMainStage() {
        return mainStage;
    }

    public static void setMainStage(Stage stage) {
        mainStage = stage;
    }
}
