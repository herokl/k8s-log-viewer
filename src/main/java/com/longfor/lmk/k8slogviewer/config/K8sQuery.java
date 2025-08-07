package com.longfor.lmk.k8slogviewer.config;

import org.fxmisc.richtext.CodeArea;

import java.util.List;

public class K8sQuery {
    private List<CodeArea> codeAreas;
    private String namespace;
    private String podName;
    private String keyword;
    private int contextLines;
    private long sinceSeconds;
    private boolean follow;
    private int tailLines;
    private boolean searchRunning;
    private boolean headerCaptured;

    // 私有构造函数，只能由 Builder 调用
    private K8sQuery(Builder builder) {
        this.contextLines = builder.contextLines;
        this.tailLines = builder.tailLines;
        this.sinceSeconds = builder.sinceSeconds;
        this.follow = builder.follow;
        this.searchRunning = builder.searchRunning;
    }

    // K8sQuery 类的 getter 和 setter 方法...

    // 静态方法，用于获取 Builder 实例
    public static Builder builder() {
        return new Builder();
    }

    // 内部 Builder 类
    public static class Builder {
        private int contextLines = 0; // 默认值
        private int tailLines = 1000; // 默认值
        private int sinceSeconds = 0; // 默认值
        private boolean follow = true; // 默认值
        private boolean searchRunning = true; // 默认值

        public Builder contextLines(int contextLines) {
            this.contextLines = contextLines;
            return this;
        }

        public Builder tailLines(int tailLines) {
            this.tailLines = tailLines;
            return this;
        }

        public Builder sinceSeconds(int sinceSeconds) {
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

    public List<CodeArea> getCodeAreas() {
        return codeAreas;
    }

    public void setCodeAreas(List<CodeArea> codeAreas) {
        this.codeAreas = codeAreas;
    }

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

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public int getContextLines() {
        return contextLines;
    }

    public void setContextLines(int contextLines) {
        this.contextLines = contextLines;
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
