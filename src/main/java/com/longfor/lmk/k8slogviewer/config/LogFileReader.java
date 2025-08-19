package com.longfor.lmk.k8slogviewer.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;

public class LogFileReader {
    private final File logFile;
    private final int maxLines; // 每次最多保留的行数
    private final Deque<String> buffer = new LinkedList<>();

    public LogFileReader(File logFile, int maxLines) {
        this.logFile = logFile;
        this.maxLines = maxLines;
    }

    // 从文件尾部加载最新日志
    public List<String> loadTail() throws IOException {
        List<String> allLines = Files.readAllLines(logFile.toPath());
        int fromIndex = Math.max(0, allLines.size() - maxLines);
        buffer.clear();
        buffer.addAll(allLines.subList(fromIndex, allLines.size()));
        return new ArrayList<>(buffer);
    }

    // 向下滚动时加载更多
    public List<String> loadNext(int count, int offset) throws IOException {
        List<String> allLines = Files.readAllLines(logFile.toPath());
        int fromIndex = Math.min(allLines.size(), offset);
        int toIndex = Math.min(allLines.size(), fromIndex + count);
        return allLines.subList(fromIndex, toIndex);
    }

    // 向上滚动时加载更多
    public List<String> loadPrevious(int count, int offset) throws IOException {
        List<String> allLines = Files.readAllLines(logFile.toPath());
        int fromIndex = Math.max(0, offset - count);
        return allLines.subList(fromIndex, offset);
    }
}
