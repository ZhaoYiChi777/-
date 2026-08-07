package com.intelligence.platform.controller;

import com.intelligence.platform.service.VectorSearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class HealthController {

    @Autowired(required = false)
    private VectorSearchService vectorSearchService;

    @Value("${app.version:1.0.0}")
    private String appVersion;

    @Autowired
    private DataSource dataSource;

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "UP");
        result.put("version", appVersion);
        result.put("timestamp", Instant.now().toString());

        // 运行时间（秒）
        long uptimeSeconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
        result.put("uptime", uptimeSeconds);

        // 数据库连接测试
        result.put("database", buildDatabaseInfo());

        // 向量索引状态
        if (vectorSearchService != null) {
            result.put("vectorIndex", vectorSearchService.getStats());
        }

        // JVM 信息
        result.put("jvm", buildJvmInfo());

        return ResponseEntity.ok(result);
    }

    private Map<String, Object> buildDatabaseInfo() {
        Map<String, Object> db = new LinkedHashMap<>();
        db.put("type", "MySQL");
        try (var conn = dataSource.getConnection()) {
            db.put("version", conn.getMetaData().getDatabaseProductVersion());
            db.put("url", conn.getMetaData().getURL());
            db.put("status", "UP");
        } catch (Exception e) {
            db.put("status", "DOWN");
            db.put("error", e.getMessage());
        }
        return db;
    }

    private Map<String, Object> buildJvmInfo() {
        Map<String, Object> jvm = new LinkedHashMap<>();
        Runtime runtime = Runtime.getRuntime();

        long usedMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long maxMB = runtime.maxMemory() / (1024 * 1024);

        Map<String, Object> memory = new LinkedHashMap<>();
        memory.put("used", usedMB);
        memory.put("max", maxMB);
        memory.put("unit", "MB");
        jvm.put("memory", memory);

        jvm.put("threads", Thread.activeCount());

        return jvm;
    }
}
