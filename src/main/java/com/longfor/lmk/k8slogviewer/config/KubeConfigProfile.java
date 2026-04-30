package com.longfor.lmk.k8slogviewer.config;

import java.util.Objects;

/**
 * KubeConfig 配置项，包含名称和文件路径。
 */
public class KubeConfigProfile {

    private String name;
    private String path;

    public KubeConfigProfile() {
    }

    public KubeConfigProfile(String name, String path) {
        this.name = name;
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        KubeConfigProfile that = (KubeConfigProfile) o;
        return Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
