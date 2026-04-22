package com.longfor.lmk.k8slogviewer.utils;

import com.longfor.lmk.k8slogviewer.config.AppPreferences;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志文件清理工具，在应用启动和关闭时执行。
 */
public final class LogCleaner {

    private static final Logger log = LoggerFactory.getLogger(LogCleaner.class);
    private static final Pattern LOGBACK_LOG_PATTERN = Pattern.compile("k8s-log-viewer\\.(\\d{4}-\\d{2}-\\d{2})\\.log");

    private LogCleaner() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * 清理过期日志文件，保留最近 retentionDays 天。
     */
    public static void cleanExpiredLogs() {
        int retentionDays = AppPreferences.getLogRetentionDays();
        log.info("开始清理日志文件，保留最近 {} 天", retentionDays);

        cleanLogbackLogs(retentionDays);
        cleanPodLogCache(retentionDays);

        log.info("日志清理完成");
    }

    /**
     * 清理 logback 应用日志
     */
    private static void cleanLogbackLogs(int retentionDays) {
        Path logsDir = Paths.get("logs");
        if (!Files.exists(logsDir)) return;

        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsDir, "k8s-log-viewer.*.log")) {
            for (Path logFile : stream) {
                String fileName = logFile.getFileName().toString();
                Matcher m = LOGBACK_LOG_PATTERN.matcher(fileName);
                if (m.find()) {
                    LocalDate fileDate = LocalDate.parse(m.group(1), DateTimeFormatter.ISO_LOCAL_DATE);
                    if (fileDate.isBefore(cutoffDate)) {
                        Files.deleteIfExists(logFile);
                        log.info("删除过期应用日志: {}", fileName);
                    }
                }
            }
        } catch (IOException e) {
            log.warn("清理 logback 日志失败: {}", e.getMessage());
        }
    }

    /**
     * 清理 Pod 日志缓存目录
     */
    private static void cleanPodLogCache(int retentionDays) {
        Path podLogRoot = Paths.get("logs/k8s_log_viewer");
        if (!Files.exists(podLogRoot)) return;

        Instant cutoffInstant = Instant.now().minus(retentionDays, ChronoUnit.DAYS);

        try {
            Files.walk(podLogRoot)
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".log"))
                    .forEach(logFile -> {
                        try {
                            File f = logFile.toFile();
                            Instant lastModified = Instant.ofEpochMilli(f.lastModified());
                            if (lastModified.isBefore(cutoffInstant)) {
                                Files.deleteIfExists(logFile);
                                log.info("删除过期 Pod 日志: {}", logFile.getFileName());
                            }
                        } catch (IOException e) {
                            log.warn("删除日志文件失败: {}", logFile);
                        }
                    });

            // 删除空目录
            Files.walk(podLogRoot)
                    .filter(Files::isDirectory)
                    .filter(LogCleaner::isEmptyDir)
                    .forEach(dir -> {
                        try {
                            Files.deleteIfExists(dir);
                            log.info("删除空目录: {}", dir.getFileName());
                        } catch (IOException e) {
                            // ignore
                        }
                    });

        } catch (IOException e) {
            log.warn("清理 Pod 日志缓存失败: {}", e.getMessage());
        }
    }

    private static boolean isEmptyDir(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            return !stream.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }
}
