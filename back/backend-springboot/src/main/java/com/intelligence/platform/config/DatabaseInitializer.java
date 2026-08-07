package com.intelligence.platform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 数据库初始化：自动迁移 Schema（幂等 ALTER TABLE ADD COLUMN）
 * 适配 MySQL 8.x
 */
@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @Autowired
    private DataSource dataSource;

    @Override
    public void run(String... args) throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // 自动迁移：确保新列存在（MySQL 版本）
            migrateSchema(conn, stmt);
            // 初始化缺失的系统配置项
            initSettings(stmt);
        } catch (Exception e) {
            log.warn("DatabaseInitializer 执行失败: {}", e.getMessage());
        }
    }

    /**
     * 初始化缺失的系统配置项（幂等，INSERT IGNORE）
     * 确保升级后新配置项自动写入已有数据库
     */
    private void initSettings(Statement stmt) {
        String[][] settings = {
                // LiteParse 本地文档解析引擎
                {"liteparse_enabled", "true", "是否启用 LiteParse 本地文档解析引擎"},
                {"liteparse_cli_path", "lit", "LiteParse CLI 可执行文件路径"},
        };

        int added = 0;
        for (String[] s : settings) {
            try {
                int rows = stmt.executeUpdate(String.format(
                        "INSERT IGNORE INTO settings (setting_key, value, description) VALUES ('%s', '%s', '%s')",
                        s[0], s[1], s[2]));
                if (rows > 0) {
                    added++;
                    log.info("Settings: added setting '{}'", s[0]);
                }
            } catch (Exception e) {
                log.debug("Settings skip '{}': {}", s[0], e.getMessage());
            }
        }
        if (added > 0) {
            log.info("Settings init complete: added {} new settings", added);
        }
    }

    /**
     * 自动迁移数据库 schema（MySQL 兼容，安全添加缺失列）
     * 解决 schema.sql 只在首次创建时执行、后续 ALTER TABLE 不生效的问题
     */
    private void migrateSchema(Connection conn, Statement stmt) {
        String[][] migrations = {
                // documents 表 - 来源追踪字段
                {"documents", "source_origin", "TEXT"},
                {"documents", "source_path", "TEXT"},
                {"documents", "source_identity", "TEXT"},
                {"documents", "folder_context", "TEXT"},
                {"documents", "url", "TEXT"},
                {"documents", "source_doc_id", "BIGINT"},
                {"documents", "source_page", "INT"},
                // knowledge_entries 表 - 多模态字段
                {"knowledge_entries", "media_type", "VARCHAR(50) DEFAULT 'text'"},
                {"knowledge_entries", "media_path", "VARCHAR(1000)"},
                {"knowledge_entries", "source_origin", "TEXT"},
                {"knowledge_entries", "table_markdown", "LONGTEXT"},
                {"knowledge_entries", "description", "TEXT"},
                {"knowledge_entries", "related", "TEXT"},
                // project_id - 数据隔离（所有业务表）
                {"documents", "project_id", "BIGINT"},
                {"knowledge_entries", "project_id", "BIGINT"},
                {"kg_nodes", "project_id", "BIGINT"},
                {"kg_edges", "project_id", "BIGINT"},
                {"qa_records", "project_id", "BIGINT"},
                {"qa_records", "images", "LONGTEXT"},
                {"qa_records", "tables", "LONGTEXT"},
                {"deep_researches", "project_id", "BIGINT"},
                {"analysis_reports", "project_id", "BIGINT"},
                {"reports", "project_id", "BIGINT"},
                {"decisions", "project_id", "BIGINT"},
                {"risk_alerts", "project_id", "BIGINT"},
        };

        int added = 0;
        for (String[] m : migrations) {
            String tableName = m[0];
            String columnName = m[1];
            String columnDef = m[2];
            try {
                // 先检查列是否存在（MySQL 方式）
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, tableName, columnName)) {
                    if (rs.next()) {
                        // 列已存在，跳过
                        continue;
                    }
                }
                stmt.execute(String.format("ALTER TABLE `%s` ADD COLUMN `%s` %s", tableName, columnName, columnDef));
                added++;
                log.info("Migration: added column {}.{}", tableName, columnName);
            } catch (Exception e) {
                // 列已存在或其他错误，跳过
                log.debug("Migration skip {}.{}: {}", tableName, columnName, e.getMessage());
            }
        }
        if (added > 0) {
            log.info("Database migration complete: added {} new columns", added);
        } else {
            log.info("Database schema is up to date (no migration needed)");
        }
    }
}
