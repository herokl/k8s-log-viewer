package com.longfor.lmk.k8slogviewer.config;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用偏好设置管理器，使用本地 JSON 文件持久化存储（项目目录 logs/ 下）。
 */
public final class AppPreferences {

    private static final Logger log = LoggerFactory.getLogger(AppPreferences.class);

    /** 偏好设置文件路径：项目目录下 config/prefs.json */
    private static final Path PREFS_PATH = Paths.get("config", "prefs.json");

    /** 判断是否首次运行（偏好文件不存在） */
    public static boolean isFirstRun() {
        return !PREFS_PATH.toFile().exists();
    }

    private static final String KUBECONFIG_PATH_KEY = "kubeconfig_path";
    private static final String KUBECONFIG_PROFILES_KEY = "kubeconfig_profiles";
    private static final String ACTIVE_PROFILE_NAME_KEY = "active_kubeconfig_profile";
    private static final String MAX_LOG_SIZE_MB_KEY = "max_log_size_mb";
    private static final int DEFAULT_MAX_LOG_SIZE_MB = 2048;
    private static final String LOG_FLUSH_INTERVAL_KEY = "log_flush_interval_ms";
    private static final int DEFAULT_LOG_FLUSH_INTERVAL_MS = 50;
    private static final String SEARCH_REFRESH_INTERVAL_KEY = "search_refresh_interval_ms";
    private static final int DEFAULT_SEARCH_REFRESH_INTERVAL_MS = 1000;
    private static final String TREE_AUTO_REFRESH_KEY = "tree_auto_refresh";
    private static final boolean DEFAULT_TREE_AUTO_REFRESH = false;
    private static final String TREE_AUTO_REFRESH_INTERVAL_KEY = "tree_auto_refresh_interval_sec";
    private static final int DEFAULT_TREE_AUTO_REFRESH_INTERVAL_SEC = 20;
    private static final String SELECTED_NAMESPACES_KEY = "selected_namespaces_";

    private static final Gson GSON = new Gson();
    private static final Type PROFILE_LIST_TYPE = new TypeToken<List<KubeConfigProfile>>() {}.getType();
    private static final Type STRING_LIST_TYPE = new TypeToken<List<String>>() {}.getType();

    /** 内存中的键值对缓存 */
    private static Map<String, String> prefsMap = new HashMap<>();

    private AppPreferences() {
        throw new IllegalStateException("Utility class");
    }

    // ==================== 持久化层 ====================

    /**
     * 初始化时从文件加载所有偏好到内存。
     * 必须在应用启动时调用一次。
     */
    public static void loadFromFile() {
        File file = PREFS_PATH.toFile();
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            @SuppressWarnings("unchecked")
            Map<String, String> map = GSON.fromJson(reader, Map.class);
            if (map != null) {
                prefsMap = map;
            }
        } catch (Exception e) {
            log.warn("加载偏好设置文件失败: {}", e.getMessage());
        }
    }

    /** 防重入标记：避免 saveToFile 递归或并发调用 */
    private static boolean saving = false;

    /** 将内存中的偏好写入文件 */
    public static void saveToFile() {
        if (saving) return;
        saving = true;
        try {
            File dir = PREFS_PATH.getParent().toFile();
            if (!dir.exists()) dir.mkdirs();
            try (FileWriter writer = new FileWriter(PREFS_PATH.toFile())) {
                GSON.toJson(prefsMap, writer);
            }
        } catch (IOException e) {
            log.error("保存偏好设置文件失败: {}", e.getMessage());
        } finally {
            saving = false;
        }
    }

    // ==================== 键值访问（内部使用） ====================

    private static String get(String key, String defaultValue) {
        return prefsMap.getOrDefault(key, defaultValue);
    }

    private static void put(String key, String value) {
        prefsMap.put(key, value);
        saveToFile();
    }

    private static int getInt(String key, int defaultValue) {
        String v = get(key, null);
        if (v == null || v.isBlank()) return defaultValue;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return defaultValue; }
    }

    private static void putInt(String key, int value) {
        put(key, String.valueOf(value));
    }

    private static boolean getBoolean(String key, boolean defaultValue) {
        String v = get(key, null);
        if (v == null) return defaultValue;
        return Boolean.parseBoolean(v.trim());
    }

    private static void putBoolean(String key, boolean value) {
        put(key, String.valueOf(value));
    }

    // ==================== KubeConfig Profiles (多配置管理) ====================

    /**
     * 获取所有 KubeConfig 配置列表。
     */
    public static List<KubeConfigProfile> getKubeConfigProfiles() {
        String json = get(KUBECONFIG_PROFILES_KEY, null);
        if (json == null || json.isBlank()) {
            String legacyPath = get(KUBECONFIG_PATH_KEY, null);
            if (legacyPath != null) {
                List<KubeConfigProfile> profiles = new ArrayList<>();
                String name = new File(legacyPath).getParentFile() != null
                        ? new File(legacyPath).getParentFile().getName()
                        : "default";
                profiles.add(new KubeConfigProfile(name, legacyPath));
                setKubeConfigProfiles(profiles);
                setActiveProfileName(name);
                return profiles;
            }
            return new ArrayList<>();
        }
        try {
            List<KubeConfigProfile> profiles = GSON.fromJson(json, PROFILE_LIST_TYPE);
            return profiles != null ? profiles : new ArrayList<>();
        } catch (Exception e) {
            log.warn("解析 kubeconfig 配置列表失败，重置为空", e);
            return new ArrayList<>();
        }
    }

    public static void setKubeConfigProfiles(List<KubeConfigProfile> profiles) {
        put(KUBECONFIG_PROFILES_KEY, GSON.toJson(profiles));
    }

    public static String getActiveProfileName() {
        return get(ACTIVE_PROFILE_NAME_KEY, null);
    }

    public static void setActiveProfileName(String name) {
        put(ACTIVE_PROFILE_NAME_KEY, name);
    }

    /**
     * 获取当前激活的配置对象。
     */
    public static KubeConfigProfile getActiveProfile() {
        String activeName = getActiveProfileName();
        if (activeName == null) {
            List<KubeConfigProfile> profiles = getKubeConfigProfiles();
            return profiles.isEmpty() ? null : profiles.get(0);
        }
        for (KubeConfigProfile p : getKubeConfigProfiles()) {
            if (activeName.equals(p.getName())) {
                return p;
            }
        }
        // 激活的名称不在列表中，返回第一个
        List<KubeConfigProfile> profiles = getKubeConfigProfiles();
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    /**
     * 添加一个 KubeConfig 配置，如果同名则覆盖。
     */
    public static void addProfile(KubeConfigProfile profile) {
        List<KubeConfigProfile> profiles = new ArrayList<>(getKubeConfigProfiles());
        profiles.removeIf(p -> p.getName().equals(profile.getName()));
        profiles.add(profile);
        setKubeConfigProfiles(profiles);
    }

    /**
     * 删除指定名称的 KubeConfig 配置。
     */
    public static void removeProfile(String name) {
        List<KubeConfigProfile> profiles = new ArrayList<>(getKubeConfigProfiles());
        profiles.removeIf(p -> p.getName().equals(name));
        setKubeConfigProfiles(profiles);
        // 如果删除的是当前激活的配置，切换到第一个
        if (name.equals(getActiveProfileName())) {
            setActiveProfileName(profiles.isEmpty() ? null : profiles.get(0).getName());
        }
    }

    // ==================== KubeConfig Path (向后兼容) ====================

    public static void setKubeConfigPath(String path) {
        put(KUBECONFIG_PATH_KEY, path);
    }

    public static String getKubeConfigPath() {
        KubeConfigProfile active = getActiveProfile();
        if (active != null) return active.getPath();
        return get(KUBECONFIG_PATH_KEY, null);
    }

    // ==================== Max Log Size (MB) ====================

    public static void setMaxLogSizeMB(int maxMB) {
        putInt(MAX_LOG_SIZE_MB_KEY, Math.max(1, maxMB));
    }

    public static int getMaxLogSizeMB() {
        return getInt(MAX_LOG_SIZE_MB_KEY, DEFAULT_MAX_LOG_SIZE_MB);
    }

    // ==================== Log Retention Days ====================

    public static void setLogRetentionDays(int days) {
        putInt("log_retention_days", Math.max(0, days));
    }

    public static int getLogRetentionDays() {
        return getInt("log_retention_days", 0);
    }

    // ==================== Log Flush Interval (ms) ====================

    public static void setLogFlushIntervalMs(int ms) {
        putInt(LOG_FLUSH_INTERVAL_KEY, Math.max(10, Math.min(1000, ms)));
    }

    public static int getLogFlushIntervalMs() {
        return getInt(LOG_FLUSH_INTERVAL_KEY, DEFAULT_LOG_FLUSH_INTERVAL_MS);
    }

    // ==================== Search Refresh Interval (ms) ====================

    public static void setSearchRefreshIntervalMs(int ms) {
        putInt(SEARCH_REFRESH_INTERVAL_KEY, Math.max(100, Math.min(5000, ms)));
    }

    public static int getSearchRefreshIntervalMs() {
        return getInt(SEARCH_REFRESH_INTERVAL_KEY, DEFAULT_SEARCH_REFRESH_INTERVAL_MS);
    }

    // ==================== Tree Auto Refresh ====================

    public static void setTreeAutoRefresh(boolean enabled) {
        putBoolean(TREE_AUTO_REFRESH_KEY, enabled);
    }

    public static boolean isTreeAutoRefresh() {
        return getBoolean(TREE_AUTO_REFRESH_KEY, DEFAULT_TREE_AUTO_REFRESH);
    }

    public static void setTreeAutoRefreshIntervalSec(int sec) {
        putInt(TREE_AUTO_REFRESH_INTERVAL_KEY, Math.max(1, Math.min(300, sec)));
    }

    public static int getTreeAutoRefreshIntervalSec() {
        return getInt(TREE_AUTO_REFRESH_INTERVAL_KEY, DEFAULT_TREE_AUTO_REFRESH_INTERVAL_SEC);
    }

    // ==================== 环境自动检测 ====================

    /**
     * 自动检测并初始化 kubeconfig 路径。
     * 如果已有配置列表则直接使用；否则尝试检测默认路径并添加为配置。
     *
     * @return true 表示检测成功，false 表示需要用户手动配置
     */
    public static boolean initializeEnvironment() {
        // 已有配置列表，直接使用
        List<KubeConfigProfile> existing = getKubeConfigProfiles();
        if (!existing.isEmpty()) {
            return true;
        }
        // 尝试检测默认 kubeconfig
        String kubeConfigPath = detectKubeConfigPath();
        if (kubeConfigPath == null) {
            return false;
        }
        // 将默认路径添加为第一个配置
        String name = new File(kubeConfigPath).getParentFile() != null
                ? new File(kubeConfigPath).getParentFile().getName()
                : "default";
        addProfile(new KubeConfigProfile(name, kubeConfigPath));
        setActiveProfileName(name);
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

    // ==================== 选中的命名空间（按配置名分别存储） ====================

    /**
     * 获取指定配置名下选中的命名空间列表。
     */
    public static List<String> getSelectedNamespaces(String profileName) {
        String key = SELECTED_NAMESPACES_KEY + (profileName != null ? profileName : "default");
        String json = get(key, null);
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<String> list = GSON.fromJson(json, STRING_LIST_TYPE);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            log.warn("解析选中命名空间失败，重置为空", e);
            return new ArrayList<>();
        }
    }

    public static void setSelectedNamespaces(String profileName, List<String> namespaces) {
        String key = SELECTED_NAMESPACES_KEY + (profileName != null ? profileName : "default");
        put(key, GSON.toJson(namespaces));
    }
}
