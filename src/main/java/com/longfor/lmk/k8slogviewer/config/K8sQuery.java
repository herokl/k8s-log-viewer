package com.longfor.lmk.k8slogviewer.config;

/**
 * K8s 日志查询参数，使用 Builder 模式创建。
 * 纯数据对象，不包含任何 UI 组件引用。
 */
public class K8sQuery {

    private String namespace;
    private String podName;
    private long sinceSeconds;
    private boolean follow;
    private int tailLines;
    private boolean searchRunning;
    private boolean headerCaptured;

    private K8sQuery(Builder builder) {
        this.tailLines = builder.tailLines;
        this.sinceSeconds = builder.sinceSeconds;
        this.follow = builder.follow;
        this.searchRunning = builder.searchRunning;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 重置运行时状态（切换 Pod 时调用）。
     * headerCaptured 初始化为 true，让 LogFetchService 输出的头部信息先展示在 headerArea，
     * 直到遇到"分割线"后再切换到 logArea。
     */
    public void resetRuntimeState() {
        this.headerCaptured = true;
    }

    // ==================== Builder ====================

    public static class Builder {
        private int tailLines = 1000;
        private long sinceSeconds = 0;
        private boolean follow = true;
        private boolean searchRunning = true;

        public Builder tailLines(int tailLines) {
            this.tailLines = tailLines;
            return this;
        }

        public Builder sinceSeconds(long sinceSeconds) {
            this.sinceSeconds = sinceSeconds;
            return this;
        }

        public Builder follow(boolean follow) {
            this.follow = follow;
            return this;
        }

        public Builder searchRunning(boolean searchRunning) {
            this.searchRunning = searchRunning;
            return this;
        }

        public K8sQuery build() {
            return new K8sQuery(this);
        }
    }

    // ==================== Getter / Setter ====================

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getPodName() {
        return podName;
    }

    public void setPodName(String podName) {
        this.podName = podName;
    }

    public long getSinceSeconds() {
        return sinceSeconds;
    }

    public void setSinceSeconds(long sinceSeconds) {
        this.sinceSeconds = sinceSeconds;
    }

    public boolean isFollow() {
        return follow;
    }

    public void setFollow(boolean follow) {
        this.follow = follow;
    }

    public int getTailLines() {
        return tailLines;
    }

    public void setTailLines(int tailLines) {
        this.tailLines = tailLines;
    }

    public boolean isSearchRunning() {
        return searchRunning;
    }

    public void setSearchRunning(boolean searchRunning) {
        this.searchRunning = searchRunning;
    }

    public boolean isHeaderCaptured() {
        return headerCaptured;
    }

    public void setHeaderCaptured(boolean headerCaptured) {
        this.headerCaptured = headerCaptured;
    }
}
