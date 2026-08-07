-- ============================================================
-- MySQL 8.x 完整 Schema（替代原 SQLite schema）
-- 所有表使用 InnoDB 引擎，utf8mb4 字符集
-- ============================================================

-- 1. 项目表
CREATE TABLE IF NOT EXISTS projects (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(50) DEFAULT 'active',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. 文档表
CREATE TABLE IF NOT EXISTS documents (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    category_l1 VARCHAR(100),
    category_l2 VARCHAR(100),
    doc_type VARCHAR(50),
    file_path VARCHAR(1000),
    file_hash VARCHAR(255),
    keywords TEXT,
    status VARCHAR(50) DEFAULT 'pending',
    upload_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    meta_info TEXT,
    source_origin TEXT,
    source_path VARCHAR(1000),
    source_identity VARCHAR(500),
    folder_context VARCHAR(500),
    project_id BIGINT,
    url VARCHAR(2000),
    source_doc_id BIGINT,
    source_page INT,
    INDEX idx_documents_project_id (project_id),
    INDEX idx_documents_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. 知识词条表（LLM抽取）
CREATE TABLE IF NOT EXISTS knowledge_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    entry_type VARCHAR(50) DEFAULT 'concept',
    entry_library VARCHAR(50) DEFAULT 'report',
    document_id BIGINT,
    source_name VARCHAR(500),
    content LONGTEXT,
    keywords TEXT,
    category_l1 VARCHAR(100),
    category_l2 VARCHAR(100),
    status VARCHAR(50) DEFAULT 'pending',
    confidence DOUBLE DEFAULT 0.8,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    reviewer VARCHAR(100),
    embedding LONGTEXT COMMENT 'JSON array for vector search',
    media_type VARCHAR(50) DEFAULT 'text',
    media_path VARCHAR(1000),
    source_origin TEXT,
    table_markdown LONGTEXT,
    project_id BIGINT,
    description TEXT,
    related TEXT,
    INDEX idx_knowledge_entries_project_id (project_id),
    INDEX idx_knowledge_entries_status (status),
    INDEX idx_knowledge_entries_document_id (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 4. LLM配置表
CREATE TABLE IF NOT EXISTS llm_configs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    provider VARCHAR(100) NOT NULL,
    api_key VARCHAR(1000),
    model VARCHAR(255) NOT NULL,
    base_url VARCHAR(1000),
    enabled TINYINT(1) DEFAULT 1,
    purpose VARCHAR(50) DEFAULT 'chat',
    max_context_size INT DEFAULT 4096,
    api_mode VARCHAR(50),
    azure_api_version VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    description TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. 深度研究表
CREATE TABLE IF NOT EXISTS deep_researches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    topic VARCHAR(500) NOT NULL,
    status VARCHAR(50) DEFAULT 'queued',
    search_queries LONGTEXT,
    source_count INT DEFAULT 0,
    synthesis LONGTEXT,
    progress INT DEFAULT 0,
    error TEXT,
    llm_config VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    completed_at DATETIME,
    project_id BIGINT,
    INDEX idx_deep_researches_project_id (project_id),
    INDEX idx_deep_researches_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. 问答记录表
CREATE TABLE IF NOT EXISTS qa_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question TEXT NOT NULL,
    answer LONGTEXT,
    confidence DOUBLE,
    sources TEXT,
    user_name VARCHAR(100),
    session_id VARCHAR(255),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    category VARCHAR(100),
    project_id BIGINT,
    images LONGTEXT,
    tables LONGTEXT,
    INDEX idx_qa_records_project_id (project_id),
    INDEX idx_qa_records_session_id (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. 报告表
CREATE TABLE IF NOT EXISTS reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    report_type VARCHAR(100),
    category_l1 VARCHAR(100),
    category_l2 VARCHAR(100),
    project_name VARCHAR(255),
    status VARCHAR(50) DEFAULT 'pending',
    upload_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    summary TEXT,
    `abstract` LONGTEXT,
    content LONGTEXT,
    author VARCHAR(255),
    pages INT,
    source_count INT DEFAULT 0,
    project_id BIGINT,
    INDEX idx_reports_project_id (project_id),
    INDEX idx_reports_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. 分析报告表
CREATE TABLE IF NOT EXISTS analysis_reports (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    analysis_type VARCHAR(100),
    category_l1 VARCHAR(100),
    source_count INT DEFAULT 0,
    status VARCHAR(50) DEFAULT 'pending',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    content LONGTEXT,
    summary TEXT,
    analyst VARCHAR(255),
    project_id BIGINT,
    INDEX idx_analysis_reports_project_id (project_id),
    INDEX idx_analysis_reports_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 9. 决策表
CREATE TABLE IF NOT EXISTS decisions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    decision_type VARCHAR(100),
    score DOUBLE,
    analysis LONGTEXT,
    suggestion TEXT,
    content LONGTEXT,
    priority VARCHAR(50),
    source VARCHAR(500),
    category VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    project_id BIGINT,
    INDEX idx_decisions_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 10. 风险预警表
CREATE TABLE IF NOT EXISTS risk_alerts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(500) NOT NULL,
    severity VARCHAR(50),
    description TEXT,
    source_a VARCHAR(500),
    source_b VARCHAR(500),
    category VARCHAR(100),
    detected_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    reporter VARCHAR(100),
    project_id BIGINT,
    INDEX idx_risk_alerts_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 11. 知识图谱节点表
CREATE TABLE IF NOT EXISTS kg_nodes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    label VARCHAR(500) NOT NULL,
    node_type VARCHAR(100),
    description TEXT,
    community_id INT DEFAULT 0,
    project_id BIGINT,
    INDEX idx_kg_nodes_project_id (project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 12. 知识图谱边表
CREATE TABLE IF NOT EXISTS kg_edges (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL,
    edge_type VARCHAR(100),
    weight DOUBLE DEFAULT 1.0,
    project_id BIGINT,
    INDEX idx_kg_edges_project_id (project_id),
    INDEX idx_kg_edges_source_id (source_id),
    INDEX idx_kg_edges_target_id (target_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 13. 系统设置表
CREATE TABLE IF NOT EXISTS settings (
    setting_key VARCHAR(255) PRIMARY KEY,
    value LONGTEXT,
    description TEXT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- 初始化数据：LLM配置（SiliconFlow 默认启用，其余平台预置但禁用）
-- 用户只需填入对应平台的 API Key 后启用即可
-- ============================================================

-- DeepSeek（深度求索）- 默认启用
INSERT IGNORE INTO llm_configs (name, provider, api_key, model, base_url, enabled, purpose, max_context_size, description) VALUES
    ('DeepSeek-Chat',      'deepseek', NULL, 'deepseek-chat',     'https://api.deepseek.com', 1, 'chat',   64000, 'DeepSeek V4 Chat模型'),
    ('DeepSeek-Extract',   'deepseek', NULL, 'deepseek-chat',     'https://api.deepseek.com', 1, 'extract',64000, 'DeepSeek 词条抽取'),
    ('DeepSeek-Reasoner',  'deepseek', NULL, 'deepseek-reasoner', 'https://api.deepseek.com', 0, 'chat',   64000, 'DeepSeek 推理模型');

-- SiliconFlow（硅基流动）- 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, max_context_size, description) VALUES
    ('SiliconFlow-Chat',      'siliconflow', 'Qwen/Qwen2.5-72B-Instruct',    'https://api.siliconflow.cn/v1', 0, 'chat',      32768, 'SiliconFlow: 主对话LLM'),
    ('SiliconFlow-Embedding', 'siliconflow', 'BAAI/bge-large-zh-v1.5',       'https://api.siliconflow.cn/v1', 0, 'embedding',     0, 'SiliconFlow: Embedding模型');

-- Qwen 通义千问（阿里云）- 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('通义千问-Max',  'qwen', 'qwen-max',  'https://dashscope.aliyuncs.com/compatible-mode/v1', 0, 'chat', '阿里云通义千问旗舰模型'),
    ('通义千问-Plus', 'qwen', 'qwen-plus', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 0, 'chat', '阿里云通义千问增强模型'),
    ('通义Embedding', 'qwen', 'text-embedding-v3', 'https://dashscope.aliyuncs.com/compatible-mode/v1', 0, 'embedding', '阿里云通义文本向量模型');

-- Moonshot / Kimi - 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('Moonshot-128k', 'moonshot', 'moonshot-v1-128k', 'https://api.moonshot.cn/v1', 0, 'chat', '月之暗面Kimi 128K上下文'),
    ('Moonshot-32k',  'moonshot', 'moonshot-v1-32k',  'https://api.moonshot.cn/v1', 0, 'chat', '月之暗面Kimi 32K上下文');

-- MiniMax - 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('MiniMax-Text-01', 'minimax', 'MiniMax-Text-01', 'https://api.minimax.chat/v1', 0, 'chat', 'MiniMax旗舰模型');

-- StepFun 阶跃星辰 - 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('Step-2-16K', 'stepfun', 'step-2-16k', 'https://api.stepfun.com/v1', 0, 'chat', '阶跃星辰Step-2模型');

-- Hunyuan 混元（腾讯）- 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('混元-Pro',      'hunyuan', 'hunyuan-pro',      'https://api.hunyuan.cloud.tencent.com/v1', 0, 'chat', '腾讯混元旗舰模型'),
    ('混元-Standard', 'hunyuan', 'hunyuan-standard', 'https://api.hunyuan.cloud.tencent.com/v1', 0, 'chat', '腾讯混元标准模型');

-- Doubao 豆包（字节跳动）- 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('豆包-Pro',  'doubao', 'doubao-pro-32k',  'https://ark.cn-beijing.volces.com/api/v3', 0, 'chat', '字节跳动豆包Pro 32K模型'),
    ('豆包-Lite', 'doubao', 'doubao-lite-32k', 'https://ark.cn-beijing.volces.com/api/v3', 0, 'chat', '字节跳动豆包Lite 32K模型');

-- OpenAI - 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('GPT-4o',                 'openai', 'gpt-4o',                  'https://api.openai.com/v1', 0, 'chat',      'OpenAI GPT-4o旗舰模型'),
    ('GPT-4o-mini',            'openai', 'gpt-4o-mini',             'https://api.openai.com/v1', 0, 'chat',      'OpenAI GPT-4o-mini'),
    ('OpenAI-Embedding-Large', 'openai', 'text-embedding-3-large',  'https://api.openai.com/v1', 0, 'embedding', 'OpenAI文本向量模型');

-- Anthropic Claude - 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('Claude-3.5-Sonnet', 'anthropic', 'claude-3-5-sonnet-20241022', 'https://api.anthropic.com/v1', 0, 'chat', 'Anthropic Claude 3.5 Sonnet');

-- Google Gemini - 预置，禁用
INSERT IGNORE INTO llm_configs (name, provider, model, base_url, enabled, purpose, description) VALUES
    ('Gemini-1.5-Pro', 'google', 'gemini-1.5-pro', NULL, 0, 'chat', 'Google Gemini 1.5 Pro'),
    ('Gemini-1.5-Flash', 'google', 'gemini-1.5-flash', NULL, 0, 'chat', 'Google Gemini 1.5 Flash');

-- ============================================================
-- 初始化数据：搜索配置
-- ============================================================
INSERT IGNORE INTO settings (setting_key, value, description) VALUES
    ('search_enabled', 'true', '网络搜索开关'),
    ('search_provider', 'baidu', '搜索引擎提供商'),
    ('search_baidu_api_key', '', '百度API Key'),
    ('search_baidu_secret_key', '', '百度Secret Key'),
    ('search_google_api_key', '', 'Google Custom Search API Key'),
    ('search_google_cx', '', 'Google Custom Search CX'),
    ('search_api_key', '', '其他搜索API Key'),
    ('search_searxng_url', '', 'SearXNG自托管地址'),
    ('research_max_sources', '20', '深度研究最大来源数量'),
    ('research_max_results_per_query', '10', '每个查询最大结果数');

-- ============================================================
-- 初始化数据：文件上传配置
-- ============================================================
INSERT IGNORE INTO settings (setting_key, value, description) VALUES
    ('upload_max_file_size', '104857600', '上传文件最大大小（字节）'),
    ('upload_allowed_types', 'pdf,doc,docx,xls,xlsx,ppt,pptx,txt,md,markdown,csv,jpg,jpeg,png,gif,bmp,webp', '允许上传的文件类型');

-- ============================================================
-- 初始化数据：图片检索 / 问答配置
-- ============================================================
INSERT IGNORE INTO settings (setting_key, value, description) VALUES
    ('qa_image_topK', '5', '问答图片检索最大返回数量'),
    ('qa_image_threshold', '0.6', '问答图片检索最低相似度阈值'),
    ('qa_image_enabled', 'true', '问答中是否返回相关图片');
