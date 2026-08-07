package com.intelligence.platform.config;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.File;

@Component
public class ShutdownHook {
    private static final Logger logger = LoggerFactory.getLogger(ShutdownHook.class);

    @Autowired
    private DataSource dataSource;

    @PreDestroy
    public void onShutdown() {
        logger.info("Application is shutting down gracefully...");

        // 1. 等待当前请求完成（由 Spring Boot graceful shutdown 机制保证，最多 10 秒）
        logger.info("Graceful shutdown initiated, active requests will be completed...");

        // 2. 关闭数据库连接池
        logger.info("Closing database connections...");
        if (dataSource instanceof HikariDataSource) {
            ((HikariDataSource) dataSource).close();
            logger.info("Database connection pool closed.");
        }

        // 3. 清理临时文件
        logger.info("Cleaning up temporary files...");
        cleanupTempFiles();

        logger.info("Shutdown cleanup completed. Goodbye!");
    }

    private void cleanupTempFiles() {
        try {
            String tempDir = System.getProperty("java.io.tmpdir");
            if (tempDir != null) {
                File dir = new File(tempDir);
                File[] tempFiles = dir.listFiles((d, name) ->
                        name.startsWith("intelligence-platform-") || name.startsWith("upload-"));
                if (tempFiles != null) {
                    int cleaned = 0;
                    for (File file : tempFiles) {
                        if (file.delete()) {
                            cleaned++;
                        }
                    }
                    logger.info("Cleaned up {} temporary files.", cleaned);
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to clean up some temporary files: {}", e.getMessage());
        }
    }
}
