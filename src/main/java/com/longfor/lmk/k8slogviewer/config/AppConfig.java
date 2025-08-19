package com.longfor.lmk.k8slogviewer.config;

import com.longfor.lmk.k8slogviewer.utils.LogFileWriter;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.Configuration;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.models.V1Namespace;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.util.Config;
import javafx.application.Platform;
import javafx.scene.control.TreeItem;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import static com.longfor.lmk.k8slogviewer.utils.CommonUtils.showAlert;

public class AppConfig {
    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);
    private static final K8sQuery K8S_QUERY = K8sQuery.builder()
            .contextLines(0)
            .tailLines(1000)
            .sinceSeconds(0)
            .follow(true)
            .searchRunning(true)
            .build();
    private static final Preferences PREFS = Preferences.userNodeForPackage(AppConfig.class);
    private static final Map<String, Object> ITEM_MAP = new HashMap<>();
    private static final String ROOT_KEY = "root_key";
    private static final String GIT_BASH_PATH_KEY = "git_bash_path";
    private static final String KUBECONFIG_PATH_KEY = "kubeconfig_path";
    public static final String FILES_GIT_BIN_BASH_EXE = "C:\\Program Files\\Git\\bin\\bash.exe";
    public static final String MAIN_STAGE = "mainStage";

    private AppConfig() {
    }

    public static K8sQuery getK8sQuery() {
        return K8S_QUERY;
    }

    public static LogFileWriter getLogFileWriter(String containerName) throws IOException {
        LogFileWriter logFileWriter = (LogFileWriter)ITEM_MAP.get(containerName);
        if (logFileWriter != null) return logFileWriter;
        logFileWriter = new LogFileWriter("logs/temp-" + containerName + ".log");
        ITEM_MAP.put(containerName, logFileWriter);
        return logFileWriter;
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

    public static TreeItem<String> getRootItem() {
        TreeItem<String> treeItem = (TreeItem<String>) ITEM_MAP.get(ROOT_KEY);
        if (treeItem != null) return treeItem;
        TreeItem<String> root = new TreeItem<>("集群");
        root.setExpanded(true);
        try {
            CoreV1Api coreV1Api = getCoreV1Api();
            List<V1Namespace> namespaces = coreV1Api.listNamespace(null, null, null, null, null, null, null, null, null).getItems();
            namespaces.parallelStream().forEach(ns -> getPods(ns, root, coreV1Api));
        } catch (ApiException e) {
            Platform.runLater(() -> showAlert("错误", "无法加载 Kubernetes 数据: " + e.getResponseBody()));
        }
        ITEM_MAP.put(ROOT_KEY, root);
        return root;
    }

    public static void clearRootItem() {
        ITEM_MAP.remove(ROOT_KEY);
    }

    public static Stage getMainStage() {
        return (Stage) ITEM_MAP.get(MAIN_STAGE);
    }

    public static void setMainStage(Stage stage) {
        ITEM_MAP.put(MAIN_STAGE, stage);
    }

    private static void getPods(V1Namespace ns, TreeItem<String> root, CoreV1Api coreV1Api) {

        String nsName = null;
        if (ns.getMetadata() != null) {
            nsName = ns.getMetadata().getName();
        }
        TreeItem<String> nsItem = new TreeItem<>(nsName);
        List<V1Pod> pods = null;
        try {
            pods = coreV1Api.listNamespacedPod(nsName, null, null, null, null, null, null, null, null, null).getItems();
        } catch (ApiException e) {
            log.info("获取命名空间[{}]pod失败!", nsName);
        }
        if (pods != null) {
            for (V1Pod pod : pods) {
                String podName = null;
                if (pod.getMetadata() != null) {
                    podName = pod.getMetadata().getName();
                }
                if (podName != null) {
                    TreeItem<String> podItem = new TreeItem<>(podName);
                    nsItem.getChildren().add(podItem);
                }
            }
        }
        if (!nsItem.getChildren().isEmpty()) {
            root.getChildren().add(nsItem);
        }
    }


    public static void setRootItem(TreeItem<String> rootItem) {
        ITEM_MAP.put(ROOT_KEY, rootItem);
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
