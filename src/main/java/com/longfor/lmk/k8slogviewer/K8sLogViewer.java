package com.longfor.lmk.k8slogviewer;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import com.longfor.lmk.k8slogviewer.service.PodLogFileManager;
import com.longfor.lmk.k8slogviewer.utils.ExecutorManager;
import com.longfor.lmk.k8slogviewer.utils.LogCleaner;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class K8sLogViewer extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        // 启动时清理过期日志
        LogCleaner.cleanExpiredLogs();

        AppConfig.setMainStage(primaryStage);
        primaryStage.setOnCloseRequest(event -> {
            // 关闭时清理过期日志
            LogCleaner.cleanExpiredLogs();
            // 关闭时清理所有历史日志，仅保留每个 Pod 最新一份
            new PodLogFileManager().cleanAllButLatest();
            ExecutorManager.shutdownAll();
            Platform.exit();
            System.exit(0);
        });
        // 加载 FXML 文件
        FXMLLoader fxmlLoader = new FXMLLoader(K8sLogViewer.class.getResource("/com/longfor/lmk/k8slogviewer/main.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1200, 800);
        primaryStage.setTitle("Kubernetes 日志查看器");
        primaryStage.getIcons().add(new Image(Objects.requireNonNull(
                K8sLogViewer.class.getResourceAsStream("/com/longfor/lmk/k8slogviewer/icons/k8s.png"))));
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}