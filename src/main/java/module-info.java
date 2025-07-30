module com.longfor.lmk.k8slogviewer {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.controlsfx.controls;
    requires org.kordamp.bootstrapfx.core;
    requires client.java.api;
    requires client.java;
    requires static lombok;
    requires org.slf4j;
    requires com.google.gson;
    requires java.prefs;

    opens com.longfor.lmk.k8slogviewer to javafx.fxml;
    exports com.longfor.lmk.k8slogviewer;
    // 导出 controller 包给 javafx.fxml
    exports com.longfor.lmk.k8slogviewer.controller to javafx.fxml;

    // 打开 controller 包以允许 FXML 加载器反射访问
    opens com.longfor.lmk.k8slogviewer.controller to javafx.fxml;

}