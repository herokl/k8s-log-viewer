package com.longfor.lmk.k8slogviewer.config;

import java.io.File;
import java.util.prefs.Preferences;

/**
 * 应用偏好设置管理器，使用 {@link java.util.prefs.Preferences} 持久化存储。
 */
public final class AppPreferences {

    private static final Preferences PREFS = Preferences.userNodeForPackage(AppPreferences.class);
    private static final String KUBECONFIG_PATH_KEY = "kubeconfig_path";
    private static final String MAX_LOG_SIZE_MB_KEY = "max_log_size_mb";
    private static final int DEFAULT_MAX_LOG_SIZE_MB = 2048;
    private static final String LOG_FLUSH_INTERVAL_KEY = "log_flush_interval_ms";
    private static final int DEFAULT_LOG_FLUSH_INTERVAL_MS = 50;
    private static final String SEARCH_REFRESH_INTERVAL_KEY = "search_refresh_interval_ms";
    private static final int DEFAULT_SEARCH_REFRESH_INTERVAL_MS = 1000;

    private AppPreferences() {
        throw new IllegalStateException("Utility class");
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
        return PREFS.getInt("log_retention_days", 0);
    }

    // ==================== Log Flush Interval (ms) ====================

    public static void setLogFlushIntervalMs(int ms) {
        PREFS.putInt(LOG_FLUSH_INTERVAL_KEY, Math.max(10, Math.min(1000, ms)));
    }

    public static int getLogFlushIntervalMs() {
        return PREFS.getInt(LOG_FLUSH_INTERVAL_KEY, DEFAULT_LOG_FLUSH_INTERVAL_MS);
    }

    // ==================== Search Refresh Interval (ms) ====================

    public static void setSearchRefreshIntervalMs(int ms) {
        PREFS.putInt(SEARCH_REFRESH_INTERVAL_KEY, Math.max(100, Math.min(5000, ms)));
    }

    public static int getSearchRefreshIntervalMs() {
        return PREFS.getInt(SEARCH_REFRESH_INTERVAL_KEY, DEFAULT_SEARCH_REFRESH_INTERVAL_MS);
    }

    // ==================== 环境自动检测 ====================

    /**
     * 自动检测并初始化 kubeconfig 路径。
     *
     * @return true 表示检测成功，false 表示需要用户手动配置
     */
    public static boolean initializeEnvironment() {
        String kubeConfigPath = detectKubeConfigPath();
        if (kubeConfigPath == null) {
            return false;
        }
        setKubeConfigPath(kubeConfigPath);
        return true;
    }

    /**
     * 检测 KubeConfig 文件路径
     */
    public static String detectKubeConfigPath() {
        String userHome = System.getProperty("user.home");
        File defaultKubeConfig = new File(userHome, ".kube/config");
        return defaultKubeConfig.exists() ? defaultKubeConfig.getAbsolutePath() : null;
    }
}
