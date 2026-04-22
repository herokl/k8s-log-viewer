package com.longfor.lmk.k8slogviewer.service;

import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class PodLogFileManager {

    private static final Logger log = LoggerFactory.getLogger(PodLogFileManager.class);
    private static final String LOG_ROOT = Paths.get("logs") + "/k8s_log_viewer";

    private BufferedWriter writer;
    private Path currentLogFile;
    private long lastSizeCheckTime = 0;
    private static final long SIZE_CHECK_INTERVAL_MS = 5000; // 5秒检查一次

    public synchronized void switchPod(String podName) throws IOException {
        close();

        String fileName = String.format("%s_%s.log",
                podName,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")));

        Path dir = Paths.get(LOG_ROOT, podName);
        Files.createDirectories(dir);

        this.currentLogFile = dir.resolve(fileName);
        this.writer = new BufferedWriter(new FileWriter(currentLogFile.toFile(), true));
        this.lastSizeCheckTime = System.currentTimeMillis();

        // 切换 Pod 时清理旧文件（按时间）
        cleanOldFiles(dir, Duration.ofDays(2));
    }

    public synchronized void append(String line) {
        if (writer == null) return;
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();

            // 定期检查目录总大小
            long now = System.currentTimeMillis();
            if (now - lastSizeCheckTime > SIZE_CHECK_INTERVAL_MS) {
                checkAndCleanSizeLimit();
                lastSizeCheckTime = now;
            }
        } catch (IOException e) {
            log.error("写入日志失败: {}", e.getMessage());
        }
    }

    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException e) {
                log.debug("关闭日志写入器时出错", e);
            }
        }
        writer = null;
        currentLogFile = null;
    }

    public Path getCurrentLogFile() {
        return currentLogFile;
    }

    /**
     * 检查当前日志文件大小是否超限，超限则清除历史日志，保留最新部分
     */
    private void checkAndCleanSizeLimit() {
        if (currentLogFile == null || writer == null) {
            return;
        }

        try {
            File file = currentLogFile.toFile();
            long currentSize = file.length();
            int maxMB = AppPreferences.getMaxLogSizeMB();
            long maxBytes = (long) maxMB * 1024 * 1024;

            if (currentSize > maxBytes) {
                log.info("当前日志文件超过 {} MB ({} MB)，清除历史日志: {}", maxMB, currentSize / 1024 / 1024, currentLogFile.getFileName());
                
                // 关闭当前 writer
                writer.close();
                writer = null;
                
                // 读取文件所有行
                List<String> allLines = Files.readAllLines(currentLogFile);
                
                // 计算需要保留的行数（保留最新的，目标是控制在最大容量的50%左右）
                int linesToKeep = Math.max(100, allLines.size() / 2);
                List<String> recentLines = allLines.stream()
                        .skip(Math.max(0, allLines.size() - linesToKeep))
                        .toList();
                
                // 重新写入文件，只保留最新的日志
                Files.write(currentLogFile, recentLines, StandardOpenOption.TRUNCATE_EXISTING);
                
                // 重新打开 writer
                writer = new BufferedWriter(new FileWriter(currentLogFile.toFile(), true));
                
                log.info("已清除历史日志，保留最新 {} 行，当前大小约 {} MB", recentLines.size(), file.length() / 1024 / 1024);
            }
        } catch (IOException e) {
            log.warn("检查或清除历史日志时出错: {}", e.getMessage());
        }
    }

    /**
     * 删除超过 retention 的旧文件
     */
    private void cleanOldFiles(Path dir, Duration retention) {
        try {
            Files.list(dir).forEach(path -> {
                try {
                    File file = path.toFile();
                    Instant lastModified = Instant.ofEpochMilli(file.lastModified());
                    Instant cutoff = Instant.now().minus(retention);
                    if (lastModified.isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                    }
                } catch (IOException e) {
                    log.debug("删除旧文件失败: {}", path, e);
                }
            });
        } catch (IOException e) {
            log.debug("列出目录失败: {}", dir, e);
        }
    }
}
