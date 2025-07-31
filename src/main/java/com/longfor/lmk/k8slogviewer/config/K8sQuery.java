package com.longfor.lmk.k8slogviewer.config;

import lombok.Data;

@Data
public class K8sQuery {
    private String namespace;
    private String podName;
    private String containerName;
    private String keyword;
    private int contextLines;
    private Integer sinceSeconds;
    private boolean follow;
    private int tailLines;
}
