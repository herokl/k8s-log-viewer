module com.longfor.lmk.k8slogviewer {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.controlsfx.controls;
    requires org.kordamp.bootstrapfx.core;
    requires client.java.api;
    requires client.java;
    requires org.slf4j;
    requires com.google.gson;
    requires java.prefs;
    requires com.fasterxml.jackson.databind;
    requires org.fxmisc.richtext;
    requires org.fxmisc.flowless;
    requires reactfx;
    requires java.desktop;
    requires okhttp3;

    opens com.longfor.lmk.k8slogviewer to javafx.fxml;
    exports com.longfor.lmk.k8slogviewer;
    exports com.longfor.lmk.k8slogviewer.controller to javafx.fxml;
    exports com.longfor.lmk.k8slogviewer.model;
    exports com.longfor.lmk.k8slogviewer.config;

    opens com.longfor.lmk.k8slogviewer.ui to javafx.fxml;
    opens com.longfor.lmk.k8slogviewer.controller to javafx.fxml;
    opens com.longfor.lmk.k8slogviewer.config to com.google.gson;
}
