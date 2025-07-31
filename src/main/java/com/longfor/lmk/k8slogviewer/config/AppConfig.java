package com.longfor.lmk.k8slogviewer.config;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreApi;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.util.Config;
import javafx.application.Platform;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.prefs.Preferences;

import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showAlert;

@Slf4j
public class AppConfig {
    private static final Preferences PREFS = Preferences.userNodeForPackage(AppConfig.class);
    private static final String GIT_BASH_PATH_KEY = "git_bash_path";
    private static final String KUBECONFIG_PATH_KEY = "kubeconfig_path";
    public static final String FILES_GIT_BIN_BASH_EXE = "C:\\Program Files\\Git\\bin\\bash.exe";

    private AppConfig() {
    }

    /**
     * 初始化 Git Bash 路径和 kubeconfig 路径（Windows 和 macOS/Linux 支持）
     */
    public static boolean initializeEnvironment() {
        String gitBashPath = detectGitBashPath();
        String kubeConfigPath = detectKubeConfigPath();
        if (gitBashPath == null || kubeConfigPath == null) {
            return false;
        }
        setGitBashPath(gitBashPath);
        setKubeConfigPath(kubeConfigPath);
        return true;
    }

    public static CoreV1Api getCoreV1Api() {
        ApiClient client = null;
        try {
            String kubeConfigPath = getKubeConfigPath();
            client = kubeConfigPath != null ? Config.fromConfig(kubeConfigPath) : Config.defaultClient();
        } catch (IOException e) {
            log.error("Failed to load kubeconfig", e);
            Platform.runLater(() -> showAlert("错误", "初始化 Kubernetes 客户端 失败，请检查配置文件: " + e.getMessage()));
        }
        Configuration.setDefaultApiClient(client);
        return new CoreV1Api();
    }

    public static void setGitBashPath(String path) {
        PREFS.put(GIT_BASH_PATH_KEY, path);
    }

    public static String getGitBashPath() {
        return PREFS.get(GIT_BASH_PATH_KEY, null);
    }

    public static void setKubeConfigPath(String path) {
        PREFS.put(KUBECONFIG_PATH_KEY, path);
    }

    public static String getKubeConfigPath() {
        return PREFS.get(KUBECONFIG_PATH_KEY, null);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static String detectGitBashPath() {
        if (!isWindows()) {
            return "/bin/bash";
        }

        // 优先检查默认路径
        String defaultPath = FILES_GIT_BIN_BASH_EXE;
        if (new File(defaultPath).exists()) {
            return defaultPath;
        }

        // 从 PATH 环境变量中查找 bash.exe
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String path : pathEnv.split(";")) {
                File bashFile = new File(path, "bash.exe");
                if (bashFile.exists()) {
                    return bashFile.getAbsolutePath();
                }
            }
        }

        return null;
    }

    /**
     * 检测 KubeConfig 文件
     */
    private static String detectKubeConfigPath() {
        String userHome = System.getProperty("user.home");
        File defaultKubeConfig = new File(userHome, ".kube/config");
        if (defaultKubeConfig.exists()) {
            return defaultKubeConfig.getAbsolutePath();
        }

        return null;
    }
}
