package com.longfor.lmk.k8slogviewer.config;

import java.io.File;
import java.util.prefs.Preferences;

/**
 * 应用偏好设置管理器，使用 {@link java.util.prefs.Preferences} 持久化存储。
 */
public final class AppPreferences {

    private static final Preferences PREFS = Preferences.userNodeForPackage(AppPreferences.class);
    private static final String GIT_BASH_PATH_KEY = "git_bash_path";
    private static final String KUBECONFIG_PATH_KEY = "kubeconfig_path";
    private static final String MAX_LOG_SIZE_MB_KEY = "max_log_size_mb";
    private static final String DEFAULT_WINDOWS_BASH = "C:\\Program Files\\Git\\bin\\bash.exe";
    private static final String DEFAULT_UNIX_BASH = "/bin/bash";
    private static final int DEFAULT_MAX_LOG_SIZE_MB = 100;

    private AppPreferences() {
        throw new IllegalStateException("Utility class");
    }

    // ==================== Git Bash Path ====================

    public static void setGitBashPath(String path) {
        PREFS.put(GIT_BASH_PATH_KEY, path);
    }

    public static String getGitBashPath() {
        return PREFS.get(GIT_BASH_PATH_KEY, null);
    }

    // ==================== KubeConfig Path ====================

    public static void setKubeConfigPath(String path) {
        PREFS.put(KUBECONFIG_PATH_KEY, path);
    }

    public static String getKubeConfigPath() {
        return PREFS.get(KUBECONFIG_PATH_KEY, null);
    }

    // ==================== Max Log Size (MB) ====================

    public static void setMaxLogSizeMB(int maxMB) {
        PREFS.putInt(MAX_LOG_SIZE_MB_KEY, Math.max(1, maxMB));
    }

    public static int getMaxLogSizeMB() {
        return PREFS.getInt(MAX_LOG_SIZE_MB_KEY, DEFAULT_MAX_LOG_SIZE_MB);
    }

    // ==================== Log Retention Days ====================

    public static void setLogRetentionDays(int days) {
        PREFS.putInt("log_retention_days", Math.max(0, days));
    }

    public static int getLogRetentionDays() {
        return PREFS.getInt("log_retention_days", 0); // 默认 3 天，与 logback.xml 保持一致
    }

    // ==================== 环境自动检测 ====================

    /**
     * 自动检测并初始化 Git Bash 和 kubeconfig 路径。
     *
     * @return true 表示检测成功，false 表示需要用户手动配置
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

    /**
     * 检测 Git Bash 可执行文件路径
     */
    public static String detectGitBashPath() {
        if (!isWindows()) {
            return DEFAULT_UNIX_BASH;
        }

        // 优先检查默认安装路径
        File defaultPath = new File(DEFAULT_WINDOWS_BASH);
        if (defaultPath.exists()) {
            return defaultPath.getAbsolutePath();
        }

        // 从 PATH 环境变量搜索
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(File.pathSeparator)) {
                File bashFile = new File(dir, "bash.exe");
                if (bashFile.exists()) {
                    return bashFile.getAbsolutePath();
                }
            }
        }
        return null;
    }

    /**
     * 检测 KubeConfig 文件路径
     */
    public static String detectKubeConfigPath() {
        String userHome = System.getProperty("user.home");
        File defaultKubeConfig = new File(userHome, ".kube/config");
        return defaultKubeConfig.exists() ? defaultKubeConfig.getAbsolutePath() : null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
