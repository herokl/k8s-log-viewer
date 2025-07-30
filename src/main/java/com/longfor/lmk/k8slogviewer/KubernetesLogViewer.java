package com.longfor.lmk.k8slogviewer;

import com.longfor.lmk.k8slogviewer.controller.MainController;
import com.longfor.lmk.k8slogviewer.controller.LogController;
import com.longfor.lmk.k8slogviewer.util.InstanceUtils;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.util.Config;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

import static com.longfor.lmk.k8slogviewer.util.InstanceUtils.getCoreV1ApiInstance;

/**
 * Kubernetes 日志查看器主应用程序
 */
public class KubernetesLogViewer extends Application {

    private MainController mainController; // 主控制器引用

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 初始化 Kubernetes 客户端
        try {
            ApiClient client = Config.defaultClient();
            Configuration.setDefaultApiClient(client);
            LogController.initialize(getCoreV1ApiInstance(), this::updateLogArea);
        } catch (Exception e) {
            throw new RuntimeException("无法初始化 Kubernetes 客户端: " + e.getMessage());
        }

        // 加载主界面 FXML
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/longfor/lmk/k8slogviewer/main.fxml"));
        Scene scene = new Scene(loader.load(), 1200, 800);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());

        // 获取主控制器并注入 API
        mainController = loader.getController();
        mainController.setApi(getCoreV1ApiInstance());

        // 设置舞台
        primaryStage.setTitle("Kubernetes 日志查看器");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() {
        // 清理资源
        InstanceUtils.getLogControllerInstance().shutdown();
    }

    /**
     * 更新日志显示区域
     * @param content 日志内容
     */
    private void updateLogArea(String content) {
        if (mainController != null) {
            mainController.updateLogArea(content);
        }
    }

    /**
     * 更新 Kubernetes API 客户端
     * @param kubeconfigPath kubeconfig 文件路径
     */
    public void updateApi(String kubeconfigPath) {
        try {
            ApiClient client = Config.fromConfig(kubeconfigPath);
            Configuration.setDefaultApiClient(client);
            InstanceUtils.getLogControllerInstance().updateApi(getCoreV1ApiInstance());
        } catch (Exception e) {
            throw new RuntimeException("无法加载 kubeconfig 文件: " + e.getMessage());
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}