package com.longfor.lmk.k8slogviewer.pojo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class LogBuffer {

    private final int maxLines;
    private final Deque<String> buffer = new ArrayDeque<>();

    public LogBuffer(int maxLines) {
        this.maxLines = maxLines;
    }

    public synchronized void add(String line) {
        buffer.addLast(line);
        if (buffer.size() > maxLines) {
            buffer.removeFirst();
        }
    }

    public synchronized List<String> snapshot() {
        return new ArrayList<>(buffer);
    }

    public synchronized int size() {
        return buffer.size();
    }
}
