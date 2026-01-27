package com.longfor.lmk.k8slogviewer.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

public class LogPersistenceService {

    private static final Path LOG_DIR = Paths.get("logs");
    private static final String FILE_PREFIX = "k8s-log-";

    private BufferedWriter writer;
    private LocalDate currentDate;

    public LogPersistenceService() {
        try {
            Files.createDirectories(LOG_DIR);
            rotateIfNeeded();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized void append(String line) {
        try {
            rotateIfNeeded();
            writer.write(line);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void rotateIfNeeded() throws IOException {
        LocalDate today = LocalDate.now();
        if (writer == null || !today.equals(currentDate)) {
            if (writer != null) writer.close();
            currentDate = today;
            Path file = LOG_DIR.resolve(FILE_PREFIX + today + ".log");
            writer = Files.newBufferedWriter(
                    file,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
        }
    }

    public void close() {
        try {
            if (writer != null) writer.close();
        } catch (IOException ignored) {}
    }
}
