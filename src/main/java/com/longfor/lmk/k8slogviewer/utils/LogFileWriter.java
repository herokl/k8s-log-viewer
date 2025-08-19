package com.longfor.lmk.k8slogviewer.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class LogFileWriter {
    private static final Logger log = LoggerFactory.getLogger(LogFileWriter.class);
    private final BufferedWriter writer;
    private final File logFile;

    public LogFileWriter(String path) throws IOException {
        this.logFile = new File(path);
        writer = new BufferedWriter(new FileWriter(logFile, true));
    }

    public synchronized void appendLine(String line) {
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to write line to file", e);
        }
    }

    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            log.error("Failed to close file", e);
        }
    }

    // 新增方法：获取日志文件对象
    public File getLogFile() {
        return logFile;
    }

    // 可选：获取文件路径
    public String getLogFilePath() {
        return logFile.getAbsolutePath();
    }
}
