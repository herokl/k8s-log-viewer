package com.longfor.lmk.k8slogviewer.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

public class LogFileWriter {
    private static final Logger log = LoggerFactory.getLogger(LogFileWriter.class);
    private final BufferedWriter writer;

    public LogFileWriter(String path) throws IOException {
        writer = new BufferedWriter(new FileWriter(path, true));
    }

    public synchronized void appendLine(String line) {
        try {
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            log.error("Failed to write line to file");
        }
    }

    public synchronized void close() {
        try {
            writer.close();
        } catch (IOException e) {
            log.error("Failed to close file");
        }
    }
}
