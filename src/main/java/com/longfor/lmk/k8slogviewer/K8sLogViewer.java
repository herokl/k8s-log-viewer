package com.longfor.lmk.k8slogviewer;

import com.longfor.lmk.k8slogviewer.config.AppConfig;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Objects;

public class K8sLogViewer extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException {
        AppConfig.setMainStage(primaryStage);
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
        try {
            launch(args);
        } catch (Exception e) {
            writeErrorToFile(e); // 写入日志
        }
    }

    private static void writeErrorToFile(Exception e) {
        try (PrintWriter writer = new PrintWriter("error.log")) {
            e.printStackTrace(writer);
        } catch (IOException ioException) {
            ioException.fillInStackTrace();
        }
    }
}