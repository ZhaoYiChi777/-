
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
DROP TABLE IF EXISTS `analysis_reports`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `analysis_reports` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `analysis_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category_l1` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_count` int DEFAULT '0',
  `status` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'pending',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `content` longtext COLLATE utf8mb4_unicode_ci,
  `summary` text COLLATE utf8mb4_unicode_ci,
  `analyst` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_analysis_reports_project_id` (`project_id`),
  KEY `idx_analysis_reports_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `decisions`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `decisions` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `decision_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `score` double DEFAULT NULL,
  `analysis` longtext COLLATE utf8mb4_unicode_ci,
  `suggestion` text COLLATE utf8mb4_unicode_ci,
  `content` longtext COLLATE utf8mb4_unicode_ci,
  `priority` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_decisions_project_id` (`project_id`)
) ENGINE=InnoDB AUTO_INCREMENT=4 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `deep_researches`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `deep_researches` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `topic` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `status` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'queued',
  `search_queries` longtext COLLATE utf8mb4_unicode_ci,
  `source_count` int DEFAULT '0',
  `synthesis` longtext COLLATE utf8mb4_unicode_ci,
  `thinking_process` longtext COLLATE utf8mb4_unicode_ci,
  `progress` int DEFAULT '0',
  `error` text COLLATE utf8mb4_unicode_ci,
  `llm_config` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `completed_at` datetime DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_deep_researches_project_id` (`project_id`),
  KEY `idx_deep_researches_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `documents`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `documents` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `category_l1` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category_l2` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `doc_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `file_path` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `file_hash` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `keywords` text COLLATE utf8mb4_unicode_ci,
  `status` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'pending',
  `upload_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `meta_info` text COLLATE utf8mb4_unicode_ci,
  `source_origin` text COLLATE utf8mb4_unicode_ci,
  `source_path` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_identity` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `folder_context` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  `url` varchar(2000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_doc_id` bigint DEFAULT NULL,
  `source_page` int DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_documents_project_id` (`project_id`),
  KEY `idx_documents_status` (`status`)
) ENGINE=InnoDB AUTO_INCREMENT=37 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `kg_edges`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kg_edges` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `source_id` bigint NOT NULL,
  `target_id` bigint NOT NULL,
  `edge_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `weight` double DEFAULT '1',
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kg_edges_project_id` (`project_id`),
  KEY `idx_kg_edges_source_id` (`source_id`),
  KEY `idx_kg_edges_target_id` (`target_id`)
) ENGINE=InnoDB AUTO_INCREMENT=2970 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `kg_nodes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kg_nodes` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `label` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `node_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `community_id` int DEFAULT '0',
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kg_nodes_project_id` (`project_id`)
) ENGINE=InnoDB AUTO_INCREMENT=842 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `kg_build_events`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kg_build_events` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `job_id` bigint NOT NULL,
  `project_id` bigint DEFAULT NULL,
  `event_type` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `message` text COLLATE utf8mb4_unicode_ci,
  `payload_json` longtext COLLATE utf8mb4_unicode_ci,
  `created_at` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kg_build_events_job_id` (`job_id`),
  KEY `idx_kg_build_events_project_id` (`project_id`),
  KEY `idx_kg_build_events_type` (`event_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `kg_build_jobs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kg_build_jobs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint DEFAULT NULL,
  `status` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `build_mode` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `graph_version` bigint DEFAULT NULL,
  `total_entries` int DEFAULT '0',
  `processed_entries` int DEFAULT '0',
  `node_count` int DEFAULT '0',
  `edge_count` int DEFAULT '0',
  `error_message` text COLLATE utf8mb4_unicode_ci,
  `started_at` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `finished_at` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kg_build_jobs_project_id` (`project_id`),
  KEY `idx_kg_build_jobs_status` (`status`),
  KEY `idx_kg_build_jobs_graph_version` (`graph_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `kg_entry_build_states`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kg_entry_build_states` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `entry_id` bigint NOT NULL,
  `entry_hash` varchar(128) COLLATE utf8mb4_unicode_ci NOT NULL,
  `graph_version` bigint DEFAULT NULL,
  `node_id` bigint DEFAULT NULL,
  `status` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `last_built_at` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_kg_entry_build_state_project_entry` (`project_id`,`entry_id`),
  KEY `idx_kg_entry_build_states_project_id` (`project_id`),
  KEY `idx_kg_entry_build_states_status` (`status`),
  KEY `idx_kg_entry_build_states_graph_version` (`graph_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `kg_relation_candidates`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `kg_relation_candidates` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `project_id` bigint NOT NULL,
  `source_entry_id` bigint DEFAULT NULL,
  `target_entry_id` bigint DEFAULT NULL,
  `relation_type` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `confidence` double DEFAULT '0',
  `evidence` text COLLATE utf8mb4_unicode_ci,
  `reason` text COLLATE utf8mb4_unicode_ci,
  `extractor` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `graph_version` bigint DEFAULT NULL,
  `status` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL,
  `created_at` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_kg_relation_candidates_project_id` (`project_id`),
  KEY `idx_kg_relation_candidates_type` (`relation_type`),
  KEY `idx_kg_relation_candidates_status` (`status`),
  KEY `idx_kg_relation_candidates_graph_version` (`graph_version`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `knowledge_entries`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_entries` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `entry_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'concept',
  `entry_library` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'report',
  `document_id` bigint DEFAULT NULL,
  `source_name` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `content` longtext COLLATE utf8mb4_unicode_ci,
  `keywords` text COLLATE utf8mb4_unicode_ci,
  `category_l1` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category_l2` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'pending',
  `confidence` double DEFAULT '0.8',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `reviewer` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `embedding` longtext COLLATE utf8mb4_unicode_ci COMMENT 'JSON array for vector search',
  `media_type` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'text',
  `media_path` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_origin` text COLLATE utf8mb4_unicode_ci,
  `table_markdown` longtext COLLATE utf8mb4_unicode_ci,
  `project_id` bigint DEFAULT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `related` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_knowledge_entries_project_id` (`project_id`),
  KEY `idx_knowledge_entries_status` (`status`),
  KEY `idx_knowledge_entries_document_id` (`document_id`)
) ENGINE=InnoDB AUTO_INCREMENT=55 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `llm_configs`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `llm_configs` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `provider` varchar(100) COLLATE utf8mb4_unicode_ci NOT NULL,
  `api_key` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `model` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `base_url` varchar(1000) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `enabled` tinyint(1) DEFAULT '1',
  `purpose` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'chat',
  `max_context_size` int DEFAULT '4096',
  `api_mode` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `azure_api_version` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `description` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=117 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `projects`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `projects` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `name` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `status` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'active',
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB AUTO_INCREMENT=12 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `qa_records`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `qa_records` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `question` text COLLATE utf8mb4_unicode_ci NOT NULL,
  `answer` longtext COLLATE utf8mb4_unicode_ci,
  `confidence` double DEFAULT NULL,
  `sources` text COLLATE utf8mb4_unicode_ci,
  `user_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `session_id` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `created_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `category` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  `images` longtext COLLATE utf8mb4_unicode_ci,
  `tables` longtext COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`id`),
  KEY `idx_qa_records_project_id` (`project_id`),
  KEY `idx_qa_records_session_id` (`session_id`)
) ENGINE=InnoDB AUTO_INCREMENT=59 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `reports`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `reports` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `report_type` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category_l1` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category_l2` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `project_name` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `status` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'pending',
  `upload_time` datetime DEFAULT CURRENT_TIMESTAMP,
  `summary` text COLLATE utf8mb4_unicode_ci,
  `abstract` longtext COLLATE utf8mb4_unicode_ci,
  `content` longtext COLLATE utf8mb4_unicode_ci,
  `author` varchar(255) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `pages` int DEFAULT NULL,
  `source_count` int DEFAULT '0',
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_reports_project_id` (`project_id`),
  KEY `idx_reports_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `risk_alerts`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `risk_alerts` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL,
  `severity` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `description` text COLLATE utf8mb4_unicode_ci,
  `source_a` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `source_b` varchar(500) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `category` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `detected_at` datetime DEFAULT CURRENT_TIMESTAMP,
  `reporter` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL,
  `project_id` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_risk_alerts_project_id` (`project_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `settings`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `settings` (
  `setting_key` varchar(255) COLLATE utf8mb4_unicode_ci NOT NULL,
  `value` longtext COLLATE utf8mb4_unicode_ci,
  `description` text COLLATE utf8mb4_unicode_ci,
  PRIMARY KEY (`setting_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
