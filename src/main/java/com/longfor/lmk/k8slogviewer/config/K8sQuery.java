package com.longfor.lmk.k8slogviewer.config;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class K8sQuery {
    private String namespace;
    private String podName;
    private String keyword;
    private int contextLines;
    private long sinceSeconds;
    private boolean follow;
    private int tailLines;
    private boolean searchRunning;
}
