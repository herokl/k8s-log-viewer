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

public class K8sLogViewer extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        AppConfig.setMainStage(primaryStage);
        primaryStage.setOnCloseRequest(event -> {
            // 1. 先隐藏窗口（UI 立即消失）
            event.consume();
            primaryStage.hide();

            // 2. 后台线程执行清理，完成后退出
            new Thread(() -> {
                LogCleaner.cleanExpiredLogs();
                new PodLogFileManager().cleanAllButLatest();
                ExecutorManager.shutdownAll();
                Platform.exit();
                System.exit(0);
            }, "shutdown-cleanup").start();
        });

        // 加载 FXML 文件
        FXMLLoader fxmlLoader = new FXMLLoader(K8sLogViewer.class.getResource("/com/longfor/lmk/k8slogviewer/main.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1200, 800);
        primaryStage.setTitle("Kubernetes 日志查看器");
        primaryStage.setScene(scene);

        primaryStage.show();

        // 加载窗口/任务栏图标（本地小文件，同步加载无感知）
        String iconUrl = K8sLogViewer.class.getResource("/com/longfor/lmk/k8slogviewer/icons/k8s.png").toExternalForm();
        primaryStage.getIcons().add(new Image(iconUrl));

        // 启动后后台清理过期日志（不阻塞 UI 显示）
        ExecutorManager.submit(LogCleaner::cleanExpiredLogs);
    }

    public static void main(String[] args) {
        launch(args);
    }
}