package com.longfor.lmk.k8slogviewer;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Objects;

public class K8sLogViewer extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        AppConfig.setMainStage(primaryStage);
        // 加载 FXML 文件
        FXMLLoader fxmlLoader = new FXMLLoader(K8sLogViewer.class.getResource("/com/longfor/lmk/k8slogviewer/main.fxml"));
        Scene scene = new Scene(fxmlLoader.load(), 1200, 800);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/styles.css")).toExternalForm());
        primaryStage.setTitle("Kubernetes 日志查看器");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}