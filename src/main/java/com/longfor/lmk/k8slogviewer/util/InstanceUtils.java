package com.longfor.lmk.k8slogviewer.util;

import com.longfor.lmk.k8slogviewer.controller.LogController;
import io.kubernetes.client.openapi.apis.CoreV1Api;

public class InstanceUtils {
    private InstanceUtils() {}
    private static LogController logControllerInstance;
    private static CoreV1Api coreV1ApiInstance;
    /**
     * 获取单例实例
     *
     * @return LogControllerUtil 实例
     */
    public static LogController getLogControllerInstance() {
        if (logControllerInstance == null) {
            logControllerInstance = new LogController();
        }
        return logControllerInstance;
    }

    /**
     * 获取单例实例
     *
     * @return LogControllerUtil 实例
     */
    public static CoreV1Api getCoreV1ApiInstance() {
        if (coreV1ApiInstance == null) {
            coreV1ApiInstance = new CoreV1Api();
        }
        return coreV1ApiInstance;
    }
}
