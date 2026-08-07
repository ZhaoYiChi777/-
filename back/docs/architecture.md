# 智能情报分析平台 - 系统架构文档

## 1. 项目概述

军事/防务智能情报分析平台，基于 RAG 架构与知识图谱技术，提供资料获取、分析报告、智能问答（SmartQA）、深度研究（DeepResearch）、知识图谱五大核心功能。

## 2. 技术栈

| 层级 | 技术 | 说明 |
|------|------|------|
| 前端 | Vue 3 + Element Plus + TypeScript | 组件化 SPA 架构，Element Plus UI 组件库 |
| 后端 | Spring Boot 4.1 + MyBatis-Plus | Java 企业级框架，ORM 数据访问层 |
| 数据库 | SQLite (WAL 模式) | 轻量嵌入式数据库 |
| OCR 服务 | SiliconFlow API | 图片/表格 OCR 识别，支持表格图片转 Markdown |
| 向量嵌入 | SiliconFlow Embedding API | 文本向量化，用于语义检索 |
| 图片描述 | VLM（视觉语言模型） | 自动生成图片描述文本，支持图片内容的语义检索 |
| 异步任务 | 异步任务队列 | 处理耗时的 OCR、向量嵌入等后台任务 |

## 3. 核心模块

| 模块 | 功能 | 说明 |
|------|------|------|
| 上传管理 | 文件上传、批量上传、任务状态管理 | 支持单文件/批量上传，异步任务队列处理，任务取消 |
| OCR 处理 | 图片/表格 OCR 识别 | SiliconFlow API 驱动，图片识别为 Markdown 表格，支持多张表格合并 |
| 向量搜索 | 语义检索、相似度匹配 | 基于 SiliconFlow Embedding 的文本/图片/表格向量化与相似度检索 |
| 智能问答 | SmartQA 问答引擎 | 基于 RAG 的语义问答，支持 Markdown 渲染、图片显示、引用来源面板 |
| 深度研究 | DeepResearch 分析引擎 | 多步推理、跨资料综合、过程可视化的深度研究功能 |

## 4. 目录结构

```
范/
├── frontend-vue/             # 前端项目 (Vue 3 + TypeScript)
│   ├── src/
│   │   ├── views/           # 页面组件
│   │   │   ├── admin/       # 后台页面
│   │   │   └── portal/      # 前台页面
│   │   ├── api/             # API 接口封装
│   │   ├── router/          # 路由配置
│   │   ├── store/           # 状态管理
│   │   └── components/      # 公共组件
│   └── package.json
├── backend-springboot/       # 后端项目 (Spring Boot)
│   ├── src/main/java/com/intel/
│   │   ├── controller/      # REST API 控制器
│   │   ├── service/         # 业务逻辑层
│   │   ├── mapper/          # MyBatis-Plus 数据访问层
│   │   ├── model/           # 数据模型
│   │   └── config/          # 配置类
│   └── src/main/resources/
│       └── schema-v2.sql    # 数据库建表脚本
├── docs/                    # 项目文档
└── pom.xml                  # Maven 配置
```

## 5. 数据库表结构

| 表名 | 用途 | 关键字段 |
|------|------|----------|
| documents | 资料库 | id, title, category_l1, category_l2, doc_type, file_hash, keywords, status |
| reports | 研究报告 | id, title, report_type, category_l1, project_name, summary |
| qa_records | 智能问答 | id, question, answer, confidence, sources, session_id |
| analysis_reports | 分析报告 | id, title, analysis_type, content |
| risk_alerts | 结论冲突 | id, title, severity, source_a, source_b |
| decisions | 方向建议 | id, title, score, analysis, suggestion |
| projects | 项目库 | id, name, description, status |
| organizations | 组织架构 | id, name, parent_id |
| kg_nodes | 图谱节点 | id, label, node_type, community_id |
| kg_edges | 图谱边 | source_id, target_id, edge_type, weight |
| settings | 系统配置 | key, value |
| upload_tasks | 上传任务 | id, doc_id, status, file_name, progress |
| media_files | 媒体文件 | id, doc_id, file_type, file_path, ocr_result, vector_id |

## 6. 分类体系 (一级标签)

- 战略规划
- 作战理论
- 装备发展
- 关键技术
- 作战力量
- 作战运用
- 典型项目
- 原始资料库

## 7. 知识图谱模型

- **四信号关联度**: direct (x3.0), source_overlap (x4.0), adamic_adar (x1.5), type_affinity (x1.0)
- **节点类型**: 技术, 装备, 机构, 项目, 概念, 资料 (按类型着色)
- **默认数据**: 25 节点, 44 边
- **图谱洞察**: 孤立节点检测 (连接度 ≤ 1 的节点)

## 8. 启动方式

```bash
# 后端启动 (Spring Boot 端口: 8080)
cd backend-springboot
./mvnw spring-boot:run

# 前端启动 (Vue 开发端口: 5173，反向代理 /api 到 8080)
cd frontend-vue
npm run dev
```

访问: http://localhost:5173 (前端) / http://localhost:8080 (后端 API)

