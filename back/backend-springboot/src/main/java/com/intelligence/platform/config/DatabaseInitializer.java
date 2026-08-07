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

@Component
public class DatabaseInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @Autowired
    private DataSource dataSource;

    @Override
    public void run(String... args) {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            ensureGraphBuildTables(stmt);
            migrateSchema(conn, stmt);
            initSettings(stmt);
        } catch (Exception e) {
            log.warn("DatabaseInitializer failed: {}", e.getMessage());
        }
    }

    private void ensureGraphBuildTables(Statement stmt) {
        String[] statements = {
                """
                CREATE TABLE IF NOT EXISTS kg_build_jobs (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    project_id BIGINT,
                    status VARCHAR(50) NOT NULL,
                    build_mode VARCHAR(50) NOT NULL,
                    graph_version BIGINT,
                    total_entries INT DEFAULT 0,
                    processed_entries INT DEFAULT 0,
                    node_count INT DEFAULT 0,
                    edge_count INT DEFAULT 0,
                    error_message TEXT,
                    started_at VARCHAR(50),
                    finished_at VARCHAR(50),
                    INDEX idx_kg_build_jobs_project_id (project_id),
                    INDEX idx_kg_build_jobs_status (status),
                    INDEX idx_kg_build_jobs_graph_version (graph_version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS kg_entry_build_states (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    project_id BIGINT NOT NULL,
                    entry_id BIGINT NOT NULL,
                    entry_hash VARCHAR(128) NOT NULL,
                    graph_version BIGINT,
                    node_id BIGINT,
                    status VARCHAR(50) NOT NULL,
                    last_built_at VARCHAR(50),
                    UNIQUE KEY uk_kg_entry_build_state_project_entry (project_id, entry_id),
                    INDEX idx_kg_entry_build_states_project_id (project_id),
                    INDEX idx_kg_entry_build_states_status (status),
                    INDEX idx_kg_entry_build_states_graph_version (graph_version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS kg_relation_candidates (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    project_id BIGINT NOT NULL,
                    source_entry_id BIGINT,
                    target_entry_id BIGINT,
                    relation_type VARCHAR(100) NOT NULL,
                    confidence DOUBLE DEFAULT 0,
                    evidence TEXT,
                    reason TEXT,
                    extractor VARCHAR(100),
                    graph_version BIGINT,
                    status VARCHAR(50) NOT NULL,
                    created_at VARCHAR(50),
                    INDEX idx_kg_relation_candidates_project_id (project_id),
                    INDEX idx_kg_relation_candidates_type (relation_type),
                    INDEX idx_kg_relation_candidates_status (status),
                    INDEX idx_kg_relation_candidates_graph_version (graph_version)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """,
                """
                CREATE TABLE IF NOT EXISTS kg_build_events (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    job_id BIGINT NOT NULL,
                    project_id BIGINT,
                    event_type VARCHAR(100) NOT NULL,
                    message TEXT,
                    payload_json LONGTEXT,
                    created_at VARCHAR(50),
                    INDEX idx_kg_build_events_job_id (job_id),
                    INDEX idx_kg_build_events_project_id (project_id),
                    INDEX idx_kg_build_events_type (event_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """
        };

        for (String statement : statements) {
            try {
                stmt.execute(statement);
            } catch (Exception e) {
                log.debug("Graph build table init skipped: {}", e.getMessage());
            }
        }
    }

    private void initSettings(Statement stmt) {
        String[][] settings = {
                {"liteparse_enabled", "true", "Enable LiteParse local document parser"},
                {"liteparse_cli_path", "lit", "LiteParse CLI executable path"},
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

    private void migrateSchema(Connection conn, Statement stmt) {
        String[][] migrations = {
                {"documents", "source_origin", "TEXT"},
                {"documents", "source_path", "TEXT"},
                {"documents", "source_identity", "TEXT"},
                {"documents", "folder_context", "TEXT"},
                {"documents", "url", "TEXT"},
                {"documents", "source_doc_id", "BIGINT"},
                {"documents", "source_page", "INT"},
                {"knowledge_entries", "media_type", "VARCHAR(50) DEFAULT 'text'"},
                {"knowledge_entries", "media_path", "VARCHAR(1000)"},
                {"knowledge_entries", "source_origin", "TEXT"},
                {"knowledge_entries", "table_markdown", "LONGTEXT"},
                {"knowledge_entries", "description", "TEXT"},
                {"knowledge_entries", "related", "TEXT"},
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
        for (String[] migration : migrations) {
            String tableName = migration[0];
            String columnName = migration[1];
            String columnDef = migration[2];
            try {
                DatabaseMetaData meta = conn.getMetaData();
                try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, tableName, columnName)) {
                    if (rs.next()) {
                        continue;
                    }
                }
                stmt.execute(String.format("ALTER TABLE `%s` ADD COLUMN `%s` %s", tableName, columnName, columnDef));
                added++;
                log.info("Migration: added column {}.{}", tableName, columnName);
            } catch (Exception e) {
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
