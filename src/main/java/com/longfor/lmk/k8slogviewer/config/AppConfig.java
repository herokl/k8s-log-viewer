package com.longfor.lmk.k8slogviewer.config;

import java.util.prefs.Preferences;

public class AppConfig {
    private static final Preferences prefs = Preferences.userNodeForPackage(AppConfig.class);
    private static final String GIT_BASH_PATH_KEY = "git_bash_path";
    private static final String KUBECONFIG_PATH_KEY = "kubeconfig_path";

    public static void setGitBashPath(String path) {
        prefs.put(GIT_BASH_PATH_KEY, path);
    }

    public static String getGitBashPath() {
        return prefs.get(GIT_BASH_PATH_KEY, null);
    }

    public static void setKubeConfigPath(String path) {
        prefs.put(KUBECONFIG_PATH_KEY, path);
    }

    public static String getKubeConfigPath() {
        return prefs.get(KUBECONFIG_PATH_KEY, null);
    }
}
