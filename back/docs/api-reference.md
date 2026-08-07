# 智能情报分析平台 - API 参考文档

## 基础信息

- **Base URL**: `http://localhost:8080/api`
- **数据格式**: JSON
- **编码**: UTF-8

### 项目隔离请求头 (Project Isolation)
为了在多项目环境下实现数据与问答上下文的完全隔离，调用本平台的大部分 API 时，**必须在 HTTP Header 中携带项目标识**：
- **Header Key**: `X-Project-Id`
- **Header Value**: 对应的项目 ID 整数值（例如：`11`）

---

## API 路由总览

| 前缀 | 模块 | 说明 |
|------|------|------|
| /api/projects | 项目管理 | 项目 CRUD、详情统计 |
| /api/dashboard | 仪表盘 | 统计数据、最新动态、进行中项目 |
| /api/documents | 资料管理 | CRUD + 筛选分页 + 知识重新抽取 |
| /api/reports | 报告管理 | 报告 CRUD + 筛选分页 |
| /api/knowledge-entries | 知识词条 | 词条分页、审核、批量审核、表格编辑、按库统计 |
| /api/qa-chat | 智能问答 | RAG 问答引擎问答交互与会话管理 |
| /api/qa | 问答记录 | 问答历史查询、统计与会话管理 |
| /api/kg | 知识图谱 | 节点/边/社区图谱重构与获取 |
| /api/search | 统一搜索 | 跨表搜索与系统全面统计 |
| /api/settings | 系统配置 | 基础配置读写 |
| /api/llm-configs | LLM 配置 | 大模型配置管理、连接测试、提供商列表 |
| /api/search-config | 搜索配置 | 网络搜索引擎配置、开关、测试 |
| /api/upload | 文件上传 | 原始文件上传、批量上传、网页爬取、状态查询、AI分类 |
| /api/sources | 来源管理 | 来源文件树、文件夹/文件导入、来源删除与刷新 |
| /api/media | 媒体处理 | OCR 识别、表格合并、图片服务、PDF 封面 |
| /api/vector | 向量管理 | 向量索引状态、重建索引与语义搜索测试 |
| /api/analysis | 分析报告 | 分析报告 CRUD 与统计 |
| /api/risks | 风险预警 | 风险预警 CRUD 与统计 |
| /api/decisions | 决策管理 | 决策条目 CRUD |
| /api/deep-researches | 深度研究 | 深度研究任务创建、查询、取消与删除 |

---

## 1. 仪表盘 API

### GET /api/dashboard/stats
获取仪表盘统计数据。

**响应示例**:
```json
{
  "doc_count": 15,
  "document_count": 15,
  "report_count": 6,
  "qa_count": 4,
  "project_count": 5,
  "active_projects": 8,
  "kg_node_count": 25
}
```

### GET /api/dashboard/latest-documents
获取最新动态信息。

**参数**:
- `limit` (int, 默认 5): 返回条数

### GET /api/dashboard/active-projects
获取进行中的项目。

**参数**:
- `limit` (int, 默认 5): 返回条数

---

## 2. 资料管理 API

### GET /api/documents
列出资料，支持筛选和分页。

**参数**:
- `docType` (str, 可选): dynamic/report/translation/chart/policy/news
- `categoryL1` (str, 可选): 一级分类
- `status` (str, 可选): parsed/processing/pending
- `keyword` (str, 可选): 搜索关键词
- `page` (int, 默认 1): 页码
- `pageSize` (int, 默认 20): 每页条数

**响应示例**:
```json
{
  "total": 15,
  "page": 1,
  "pageSize": 20,
  "items": [
    {
      "id": 1,
      "title": "无人集群协同作战研究报告",
      "categoryL1": "作战理论",
      "categoryL2": "协同作战",
      "docType": "report",
      "keywords": "无人集群,协同作战",
      "status": "parsed",
      "uploadTime": "2026-06-15T10:30:00"
    }
  ]
}
```

### GET /api/documents/{id}
获取单个资料详情。

### POST /api/documents/
创建资料。

**请求体**:
```json
{
  "title": "新资料标题",
  "categoryL1": "装备发展",
  "categoryL2": "无人机",
  "docType": "report",
  "keywords": "无人机,装备",
  "status": "parsed"
}
```

### PUT /api/documents/{id}
更新资料。

### DELETE /api/documents/{id}
删除资料。

### POST /api/documents/{id}/re-extract
重新抽取文档的知识词条（删除旧词条后重新调用 LLM 抽取）。

**响应**:
```json
{
  "status": "success",
  "message": "重新抽取完成",
  "entry_count": 5,
  "deleted_old": 3
}
```

---

## 3. 报告管理 API

### GET /api/reports
列出报告。

**参数**:
- `reportType` (str, 可选): research/evaluation/feasibility
- `categoryL1` (str, 可选): 一级分类
- `status` (str, 可选): parsed/processing/pending
- `keyword` (str, 可选): 搜索关键词
- `page` (int, 默认 1)
- `pageSize` (int, 默认 20)

### GET /api/reports/{id}
获取单个报告详情。

### POST /api/reports/
创建报告。

### DELETE /api/reports/{id}
删除报告。

---

## 4. 知识词条 API

### GET /api/knowledge-entries
分页查询词条。

**参数**:
- `library` (str, 可选): report/dynamic/translation/chart
- `entryType` (str, 可选): concept/image/table
- `status` (str, 可选): approved/pending/rejected
- `keyword` (str, 可选): 搜索关键词（匹配标题、内容、关键词）
- `page` (int, 默认 1)
- `pageSize` (int, 默认 20)

**响应示例**:
```json
{
  "total": 50,
  "page": 1,
  "pageSize": 20,
  "items": [
    {
      "id": 54,
      "title": "社区医疗",
      "library": "report",
      "entryType": "concept",
      "keywords": "社区,医疗",
      "status": "approved",
      "confidence": 0.85,
      "createdAt": "2026-07-01 10:00:00"
    }
  ]
}
```

### GET /api/knowledge-entries/stats/by-library
按资料库统计词条数量。

**响应示例**:
```json
{
  "report": 25,
  "dynamic": 10,
  "translation": 8,
  "chart": 7
}
```

### GET /api/knowledge-entries/{id}
获取单个词条详情。

### POST /api/knowledge-entries/
手动创建词条。

**请求体**:
```json
{
  "title": "词条标题",
  "library": "report",
  "entryType": "concept",
  "content": "词条内容",
  "keywords": "关键词1,关键词2",
  "status": "pending"
}
```

### PUT /api/knowledge-entries/{id}
更新词条。

### PUT /api/knowledge-entries/{id}/review
审核词条。

**参数** (URL Params):
- `status` (str, 必填): approved/rejected
- `reviewer` (str, 可选): 审核人

### PUT /api/knowledge-entries/{id}/table-markdown
更新词条的表格 Markdown 内容。

**请求体**:
```json
{
  "tableMarkdown": "| 列1 | 列2 |\n|------|------|\n| 数据1 | 数据2 |"
}
```

### DELETE /api/knowledge-entries/{id}
删除词条。

### PUT /api/knowledge-entries/batch-review
批量审核词条。

**请求体**:
```json
{
  "ids": [1, 2, 3],
  "status": "approved",
  "reviewer": "审核人"
}
```

---

## 5. 知识图谱 API

### GET /api/kg/nodes
获取所有图谱节点。

### GET /api/kg/edges
获取所有图谱边。

### GET /api/kg/graph
获取完整图谱 (节点+边+社区+统计)。

**响应示例**:
```json
{
  "nodes": [...],
  "edges": [...],
  "communities": [
    {
      "id": 0,
      "members": [...],
      "cohesion": 0.45
    }
  ],
  "stats": {
    "node_count": 25,
    "edge_count": 44,
    "community_count": 5,
    "edge_types": {
      "keyword_overlap": 44
    }
  }
}
```

### GET /api/kg/insights
获取图谱洞察 (孤立节点、惊奇连接)。

### POST /api/kg/build
手动触发知识图谱构建（从知识词条自动创建节点和边）。

**响应**:
```json
{
  "message": "图谱构建完成",
  "node_count": 25,
  "edge_count": 44
}
```

---

## 6. 统一搜索 API

### GET /api/search
跨表统一搜索。

**参数**:
- `q` (str, 必填): 搜索关键词
- `scope` (str, 可选): documents/reports/qa/analysis/all
- `limit` (int, 默认 20): 每类返回条数

**响应示例**:
```json
{
  "query": "无人",
  "total": 8,
  "results": {
    "documents": [...],
    "reports": [...],
    "qa": [...],
    "analysis": [...]
  }
}
```

### GET /api/search/stats/full
获取系统全面统计数据（返回各表的记录数）。

**响应**:
```json
{
  "documents": 15,
  "reports": 5,
  "qa_records": 4,
  "analysis_reports": 6,
  "risk_alerts": 3,
  "decisions": 2,
  "projects": 5,
  "kg_nodes": 25,
  "kg_edges": 44
}
```

---

## 7. 文件上传 API

### POST /api/upload/
单文件上传。

**参数** (multipart/form-data):
- `file`: 文件
- `categoryL1` (str, 可选, 默认 ""): 一级分类
- `categoryL2` (str, 可选, 默认 ""): 二级分类
- `docType` (str, 可选, 默认 report): 文档类型 (report/dynamic/translation/chart/policy/news)
- `sourceOrigin` (str, 可选, 默认 ""): 来源出处说明
- `sourceDocId` (long, 可选): 关联的源文档 ID
- `sourcePage` (int, 可选): 关联的源文档页码
- `fileType` (str, 可选, 默认 ""): 文件类型标识 (image/table/document)，用于区分图片/表格/普通文档的处理流程

**响应**:
```json
{
  "status": "success",
  "id": 16,
  "filename": "报告.pdf",
  "title": "生物科技_研究报告_3",
  "size": 102400,
  "sha256": "a1b2c3d4...",
  "message": "已提交处理"
}
```

### POST /api/upload/batch
批量上传文件。

**参数** (multipart/form-data):
- `files`: 文件列表（多个文件）
- `categoryL1` (str, 可选, 默认 ""): 一级分类
- `categoryL2` (str, 可选, 默认 ""): 二级分类
- `docType` (str, 可选, 默认 report): 文档类型
- `library` (str, 可选, 默认 report): 目标库
- `autoClassify` (boolean, 可选, 默认 false): 是否启用 AI 智能分类
- `sourceOrigin` (str, 可选, 默认 ""): 来源出处说明

**响应**:
```json
{
  "total": 3,
  "results": [
    {
      "filename": "报告1.pdf",
      "status": "success",
      "id": 16,
      "library": "report",
      "category": "装备发展",
      "entry_count": 5
    }
  ]
}
```

### GET /api/upload/task/{docId}
查询指定文档的上传任务状态。

**路径参数**:
- `docId` (long, 必填): 文档 ID

**响应**:
```json
{
  "taskId": "task-uuid-001",
  "docId": 16,
  "status": "completed",
  "progress": 100,
  "message": "处理完成",
  "startTime": "2026-07-04T10:00:00",
  "endTime": "2026-07-04T10:02:30"
}
```

**任务状态枚举**: `pending` (排队中) | `processing` (处理中) | `completed` (完成) | `failed` (失败) | `cancelled` (已取消)

### GET /api/upload/tasks
查询所有活跃上传任务列表。

**响应**:
```json
[
  {
    "taskId": "task-uuid-001",
    "docId": 16,
    "fileName": "报告.pdf",
    "status": "completed",
    "progress": 100
  }
]
```

### POST /api/upload/task/{docId}/cancel
取消指定的上传任务。

**路径参数**:
- `docId` (long, 必填): 文档 ID

**响应**:
```json
{
  "status": "success",
  "message": "任务已取消"
}
```

### POST /api/upload/check-hash
检查文件 SHA256 (增量缓存)。

**参数** (multipart/form-data):
- `file`: 文件

**响应**:
```json
{
  "exists": true,
  "existing_id": 16,
  "title": "报告.pdf"
}
```

### GET /api/upload/file-types
获取支持的文件类型列表。

**响应**:
```json
{
  "extensions": ["pdf", "docx", "txt", "md", "csv", "..."],
  "accept": ".pdf,.docx,.txt,.md,.csv,..."
}
```

### GET /api/upload/libraries
获取目标库列表。

**响应**:
```json
[
  { "value": "report", "label": "研究报告库" },
  { "value": "dynamic", "label": "动态信息库" },
  { "value": "translation", "label": "译丛译著库" },
  { "value": "chart", "label": "图表数据库" },
  { "value": "policy", "label": "政策文件库" },
  { "value": "news", "label": "新闻资讯库" }
]
```

### POST /api/upload/from-url
输入一个网页的 URL，系统自动爬取其网页正文并清洗入库。

**请求 Headers**:
- `X-Project-Id`: 项目ID（必填）

**参数** (URL Params):
- `url` (str, 必填): 网页 URL 地址
- `title` (str, 可选, 默认 ""): 自定义标题，不传则自动提取
- `categoryL1` (str, 可选, 默认 ""): 一级分类
- `categoryL2` (str, 可选, 默认 ""): 二级分类
- `docType` (str, 可选, 默认 dynamic): 资料类型
- `sourceOrigin` (str, 可选, 默认 ""): 来源出处说明

**响应示例**:
```json
{
  "status": "success",
  "id": 89,
  "title": "网页抓取文章标题",
  "url": "https://example.com/article",
  "entry_count": 3,
  "message": "URL内容已抓取，LLM已抽取 3 个知识词条"
}
```

### POST /api/upload/analyze
AI 智能分析文档分类（根据文件内容推荐分类和目标库）。

**参数** (multipart/form-data):
- `file`: 文件

**响应**:
```json
{
  "categoryL1": "装备发展",
  "docType": "report",
  "library": "report"
}
```

---

## 8. 媒体处理 API

### POST /api/media/ocr-table
表格图片 OCR 识别。

**参数** (multipart/form-data):
- `file`: 表格图片文件

**说明**: 调用 OCR API 识别图片中的表格结构，返回 Markdown 格式的表格数据。

**响应**:
```json
{
  "status": "success",
  "markdown": "| 列1 | 列2 | 列3 |\n|------|------|------|\n| 数据1 | 数据2 | 数据3 |"
}
```

### POST /api/media/merge-tables
合并多个已有的表格图片条目为一个合并后的 Markdown 表格。

**参数** (JSON):
```json
{
  "entryIds": [101, 102, 103],
  "title": "合并表格标题"
}
```

**参数说明**:
- `entryIds` (list[int], 必填): 需要合并的表格条目 ID 列表，按顺序合并
- `title` (str, 可选, 默认 "合并表格"): 合并后的表格标题

**响应**:
```json
{
  "status": "success",
  "markdown": "| 列1 | 列2 | 列3 |\n|------|------|------|\n| 表1数据 | ... | ... |\n| 表2数据 | ... | ... |",
  "entry_id": 200,
  "message": "已合并 3 个表格片段"
}
```

### POST /api/media/ocr-existing
将已有的图片条目通过 OCR 转为表格条目。

**参数** (JSON):
```json
{
  "entryIds": [1, 2, 3]
}
```

**响应**:
```json
{
  "status": "success",
  "results": [
    { "id": 1, "status": "success", "markdown": "| 列1 | 列2 |..." }
  ],
  "message": "已处理 3 个条目"
}
```

### POST /api/media/reprocess-images
批量重新处理失败的图片条目（重新生成 VLM 描述）。

**参数** (JSON, 可选):
```json
{
  "entryIds": [16, 17, 18]
}
```

**说明**: 如果不传 `entryIds`，则自动处理所有失败的图片条目。

**响应**:
```json
{
  "status": "success",
  "results": [
    { "id": 16, "title": "图表1", "status": "success" },
    { "id": 17, "status": "error", "message": "OCR 服务超时" }
  ],
  "message": "重新处理完成: 2/3"
}
```

### GET /api/media/{docId}/{filename}
获取媒体文件（图片等）的二进制内容。

**路径参数**:
- `docId` (str): 文档 ID
- `filename` (str): 文件名

### GET /api/media/pdf-cover/{docId}
获取 PDF 文档的封面图片（PNG 格式）。

**路径参数**:
- `docId` (long): 文档 ID

### GET /api/media/doc-file/{docId}
根据文档 ID 直接提供原始文件（图片、PDF 等）。

**路径参数**:
- `docId` (long): 文档 ID

---

## 9. 智能问答与 RAG API

### POST /api/qa-chat/ask
执行智能 RAG 问答，系统自动根据上下文进行实体检索和知识库检索，并生成带原文引用的答案。

**请求 Headers**:
- `X-Project-Id`: 项目ID（可选）

**请求体 (JSON)**:
```json
{
  "question": "社区医疗主要承担什么功能？",
  "sessionId": "uuid-xxx-xxx"
}
```

**参数说明**:
- `question` (str, 必填): 用户问题
- `sessionId` (str, 可选): 会话 ID，不传则自动生成

**响应示例**:
```json
{
  "answer": "根据相关报告，社区医疗主要承担以下功能...",
  "confidence": 0.70,
  "sources": [
    { "index": 1, "entry_id": 54, "title": "社区医疗", "library": "report", "media_type": "text", "source_name": "...", "source_origin": "...", "content": "..." }
  ],
  "tables": [
    { "id": 101, "score": 0.85, "title": "医疗资源分布", "table_markdown": "|...|", "source_origin": "...", "source_name": "..." }
  ],
  "images": [
    { "id": 201, "score": 0.78, "caption": "社区医疗中心", "media_path": "...", "title": "...", "source_origin": "...", "source_name": "..." }
  ],
  "entry_count": 5,
  "session_id": "uuid-xxx-xxx"
}
```

### GET /api/qa-chat/sessions
获取所有问答会话列表。

**响应**:
```json
[
  {
    "session_id": "uuid-xxx",
    "title": "第一个问题...",
    "first_msg": "2026-07-01 10:00:00",
    "msg_count": 3
  }
]
```

### GET /api/qa-chat/session/{sessionId}
获取指定会话的所有消息。

### DELETE /api/qa-chat/session/{sessionId}
删除指定会话及其所有消息记录。

---

## 10. 问答记录 API

### GET /api/qa
分页查询问答记录。

**参数**:
- `category` (str, 可选): 分类筛选
- `keyword` (str, 可选): 搜索关键词（匹配问题和回答）
- `page` (int, 默认 1)
- `pageSize` (int, 默认 20)

### GET /api/qa/stats
获取问答统计数据。

**响应示例**:
```json
{
  "total": 15,
  "avg_confidence": 0.725
}
```

### GET /api/qa/sessions
获取所有问答会话列表。

**响应**:
```json
[
  {
    "session_id": "uuid-xxx",
    "title": "第一个问题...",
    "first_msg": "2026-07-01 10:00:00",
    "msg_count": 3
  }
]
```

### GET /api/qa/session/{sessionId}
获取指定会话的所有消息。

### DELETE /api/qa/session/{sessionId}
删除指定会话及其所有消息记录。

### POST /api/qa/
手动创建问答记录。

### DELETE /api/qa/{id}
删除单条问答记录。

---

## 11. 项目管理 API

### GET /api/projects
获取当前平台所有的项目列表。

**响应示例**:
```json
{
  "items": [
    {
      "id": 11,
      "name": "生物科技",
      "description": "生物医疗研究项目",
      "status": "active",
      "createdAt": "2026-06-15 10:00:00"
    }
  ],
  "total": 5
}
```

### GET /api/projects/{id}
获取单个项目信息。

### POST /api/projects/
新建一个项目。

**请求体 (JSON)**:
```json
{
  "name": "新项目名称",
  "description": "项目描述信息"
}
```

**响应示例**:
```json
{
  "id": 12,
  "message": "创建成功"
}
```

### DELETE /api/projects/{id}
删除指定项目。

### GET /api/projects/{id}/detail
获取项目详情（包含文档、词条、问答等统计信息）。

**响应示例**:
```json
{
  "id": 11,
  "name": "生物科技",
  "description": "生物医疗研究项目",
  "status": "active",
  "createdAt": "2026-06-15 10:00:00",
  "docCount": 15,
  "entryCount": 50,
  "qaCount": 4,
  "reportCount": 6,
  "documents": [...],
  "entries": [...],
  "qaRecords": [...]
}
```

---

## 12. 系统配置 API

### GET /api/settings
获取所有系统配置项（返回 key-value 映射）。

**响应示例**:
```json
{
  "qa_max_entries": "10",
  "qa_image_topK": "5",
  "qa_image_threshold": "0.6"
}
```

### PUT /api/settings/
批量更新系统配置。

**请求体 (JSON)**:
```json
{
  "qa_max_entries": "15",
  "qa_image_topK": "3"
}
```

**响应**:
```json
{
  "message": "更新了 2 项配置"
}
```

---

## 13. LLM 配置 API

### GET /api/llm-configs
获取所有 LLM 配置列表。

### GET /api/llm-configs/active
获取当前启用的 Chat LLM 配置。

### GET /api/llm-configs/active-embedding
获取当前启用的 Embedding 配置。

### GET /api/llm-configs/{id}
获取单个 LLM 配置详情。

### POST /api/llm-configs/
创建 LLM 配置。

### PUT /api/llm-configs/{id}
更新 LLM 配置。

### PUT /api/llm-configs/{id}/toggle
启用/禁用 LLM 配置。

**响应**:
```json
{
  "message": "已启用"
}
```

### DELETE /api/llm-configs/{id}
删除 LLM 配置。

### GET /api/llm-configs/providers
获取所有支持的 LLM 提供商列表。

**响应示例**:
```json
[
  { "value": "openai", "label": "OpenAI" },
  { "value": "deepseek", "label": "DeepSeek" },
  { "value": "qwen", "label": "通义千问" }
]
```

### POST /api/llm-configs/test
测试 LLM 连接（发送实际请求验证）。

**请求体**: LlmConfig 对象

**响应**:
```json
{
  "success": true,
  "message": "连接测试成功（openai / gpt-4）",
  "reply": "连接成功"
}
```

---

## 14. 搜索配置 API

### GET /api/search-config
获取搜索配置（所有 `search_` 前缀的配置项）。

### PUT /api/search-config
保存搜索配置。

**请求体 (JSON)**:
```json
{
  "provider": "baidu",
  "api_key": "",
  "api_cx": ""
}
```

### GET /api/search-config/enabled
获取搜索开关状态。

**响应**:
```json
{
  "enabled": true
}
```

### PUT /api/search-config/enabled
设置搜索开关。

**请求体 (JSON)**:
```json
{
  "enabled": true
}
```

### GET /api/search-config/providers
获取支持的搜索引擎列表。

**响应示例**:
```json
[
  { "value": "baidu", "label": "百度搜索", "desc": "中国最大搜索引擎，无需API密钥" },
  { "value": "google", "label": "Google Custom Search", "desc": "需要API Key和CX" },
  { "value": "tavily", "label": "Tavily", "desc": "专为AI搜索设计的API" }
]
```

### POST /api/search-config/test
测试搜索（发送真实搜索请求）。

**请求体 (JSON, 可选)**:
```json
{
  "query": "测试关键词"
}
```

**响应**:
```json
{
  "success": true,
  "message": "搜索成功，返回 3 条结果",
  "count": 3,
  "results": [
    { "title": "...", "url": "...", "snippet": "..." }
  ]
}
```

---

## 15. 来源管理 API

### GET /api/sources/tree
获取来源目录树（扫描 raw/sources/ 目录）。

**响应示例**:
```json
{
  "tree": [
    {
      "name": "论文",
      "path": "论文",
      "is_dir": true,
      "children": [
        { "name": "transformer.pdf", "path": "论文/transformer.pdf", "is_dir": false, "size": 102400, "imported": true, "doc_id": 5 }
      ]
    }
  ],
  "root": "/path/to/raw/sources",
  "total_documents": 15,
  "sources_count": 12
}
```

### POST /api/sources/import-folder
导入文件夹中的所有文件。

**参数** (URL Params):
- `folderPath` (str, 必填): 本地文件夹路径
- `categoryL1` (str, 可选, 默认 ""): 一级分类
- `categoryL2` (str, 可选, 默认 ""): 二级分类
- `sourceOrigin` (str, 可选, 默认 ""): 来源出处说明

**响应**:
```json
{
  "status": "success",
  "imported": 5,
  "skipped": 1,
  "total": 6,
  "results": [
    { "filename": "file.pdf", "status": "success", "id": 10, "source_path": "...", "folder_context": "...", "entry_count": 3 }
  ]
}
```

### POST /api/sources/import-file
导入单个文件到来源目录。

**参数** (multipart/form-data):
- `file`: 文件
- `subFolder` (str, 可选, 默认 ""): 子文件夹名
- `categoryL1` (str, 可选, 默认 ""): 一级分类
- `categoryL2` (str, 可选, 默认 ""): 二级分类
- `sourceOrigin` (str, 可选, 默认 ""): 来源出处说明

**响应**:
```json
{
  "status": "success",
  "id": 10,
  "source_path": "subFolder/file.pdf",
  "source_identity": "...",
  "folder_context": "...",
  "entry_count": 3
}
```

### DELETE /api/sources/{id}
删除来源文件（同时删除磁盘文件和数据库记录）。

### POST /api/sources/refresh
刷新来源目录（重新扫描 raw/sources/ 并入库新文件）。

**响应**:
```json
{
  "status": "success",
  "found": 20,
  "new": 3,
  "message": "扫描完成：发现 20 个文件，新增 3 个"
}
```

---

## 16. 向量管理 API

### POST /api/vector/rebuild
重建向量索引（从数据库加载所有已审核词条）。

**响应**:
```json
{
  "message": "索引重建完成",
  "indexed_count": 50
}
```

### GET /api/vector/stats
获取向量索引统计信息。

### POST /api/vector/search
语义搜索测试。

**请求体 (JSON)**:
```json
{
  "query": "无人集群",
  "topK": 5
}
```

**响应**:
```json
{
  "results": [
    { "id": 54, "score": 0.92, "title": "无人集群协同", "content": "..." }
  ],
  "count": 1
}
```

---

## 17. 分析报告 API

### GET /api/analysis
分页查询分析报告。

**参数**:
- `analysisType` (str, 可选): 分析类型
- `categoryL1` (str, 可选): 一级分类
- `status` (str, 可选): 状态
- `page` (int, 默认 1)
- `pageSize` (int, 默认 20)

### GET /api/analysis/stats
获取分析报告统计数据。

**响应示例**:
```json
{
  "total": 6,
  "by_type": {
    "research": 3,
    "evaluation": 2,
    "feasibility": 1
  }
}
```

### GET /api/analysis/{id}
获取单个分析报告详情。

### POST /api/analysis/
创建分析报告。

### DELETE /api/analysis/{id}
删除分析报告。

---

## 18. 风险预警 API

### GET /api/risks
分页查询风险预警。

**参数**:
- `severity` (str, 可选): 风险等级
- `category` (str, 可选): 风险分类
- `page` (int, 默认 1)
- `pageSize` (int, 默认 20)

### GET /api/risks/stats
获取风险预警统计数据。

**响应示例**:
```json
{
  "total": 3,
  "by_severity": {
    "high": 1,
    "medium": 2
  }
}
```

### POST /api/risks/
创建风险预警。

### DELETE /api/risks/{id}
删除风险预警。

---

## 19. 决策管理 API

### GET /api/decisions
分页查询决策条目。

**参数**:
- `decisionType` (str, 可选): 决策类型
- `category` (str, 可选): 决策分类
- `page` (int, 默认 1)
- `pageSize` (int, 默认 20)

### POST /api/decisions/
创建决策条目。

### DELETE /api/decisions/{id}
删除决策条目。

---

## 20. 深度研究 API

### GET /api/deep-researches
分页查询深度研究任务。

**参数**:
- `status` (str, 可选): queued/running/completed/failed/cancelled
- `page` (int, 默认 1)
- `pageSize` (int, 默认 20)

### GET /api/deep-researches/{id}
获取单个深度研究任务详情。

### POST /api/deep-researches/
创建并启动深度研究任务。

**请求体 (JSON)**:
```json
{
  "topic": "无人集群技术发展趋势"
}
```

**响应**:
```json
{
  "id": 1,
  "status": "queued",
  "message": "研究任务已创建，正在执行..."
}
```

### PUT /api/deep-researches/{id}/cancel
取消研究任务。

**响应**:
```json
{
  "message": "任务已取消"
}
```

### DELETE /api/deep-researches/{id}
删除研究任务。

---

## 错误处理

所有 API 错误返回统一格式:
```json
{
  "detail": "错误描述信息"
}
```

**常见错误码**:
- 404: 资源不存在
- 422: 参数验证失败
- 500: 服务器内部错误
