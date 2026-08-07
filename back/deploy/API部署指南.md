# API 部署列举与配置指南

本文说明本项目部署时涉及哪些 API、在哪里修改、是否必须配置，以及如何验证。

## 1. API 配置位置总览

| 类型 | 配置位置 | 是否必须 | 说明 |
|---|---|---|---|
| 前端访问后端 API | `frontend-vue/nginx.conf`、`frontend-vue/src/api/index.ts` | 必须保持正确 | Docker 部署下前端通过 `/api` 反向代理到后端 |
| 后端服务端口 | `docker-compose.yml`、`backend-springboot/Dockerfile` | 必须保持一致 | 默认 `8080` |
| 知识图谱计算 API | `docker-compose.yml` 中 `KG_COMPUTE_URL` | 必须保持正确 | 默认 `http://kg-compute:8101`，容器内部服务名 |
| MySQL 连接 | `docker-compose.yml`、`.env` | 必须 | 后端通过 `mysql:3306` 访问 MySQL 容器 |
| LLM 对话模型 API | `.env`、后台系统配置、`init-db/02-init-data.sql` | 智能功能必须 | 用于问答、分析、生成 |
| 抽取模型 API | `.env`、后台系统配置 | 文档知识抽取建议配置 | 可复用默认 LLM |
| VLM/OCR 模型 API | `.env`、后台系统配置 | 图片/表格识别需要 | 可接 SiliconFlow、通义等视觉模型 |
| Embedding API | `.env`、后台系统配置 | 向量检索/RAG 必须 | 用于文档向量化和问答召回 |
| Rerank API | `.env`、后台系统配置 | 推荐 | 用于检索结果重排 |
| 搜索 API | 后台系统配置、`settings` 表 | 可选 | 深度研究/联网搜索需要 |
| parser-service API | `PARSER_SERVICE_URL` | 可选 | 当前未纳入 Docker Compose，一键部署不启动 |

## 2. 前端访问后端 API

Docker 部署时，浏览器访问前端：

```text
http://服务器IP/
```

前端请求后端时走相对路径：

```text
/api
```

相关文件：

```text
frontend-vue/src/api/index.ts
frontend-vue/nginx.conf
```

当前配置逻辑：

```ts
baseURL: isTauri ? 'http://localhost:8080/api' : '/api'
```

Docker Web 部署场景下走 `/api`，然后 Nginx 转发：

```nginx
location /api/ {
    proxy_pass http://backend:8080;
}
```

一般不需要改。如果你改了后端容器服务名或后端容器端口，才需要同步改 `nginx.conf`。

验证：

```bash
curl -I http://127.0.0.1/
curl -fsS http://127.0.0.1:8080/api/health
```

## 3. 后端 API

后端默认端口：

```text
容器内：8080
宿主机：8080
```

相关文件：

```text
docker-compose.yml
backend-springboot/Dockerfile
backend-springboot/src/main/resources/application.properties
```

Compose 映射：

```yaml
ports:
  - "8080:8080"
```

健康检查：

```text
http://127.0.0.1:8080/api/health
```

如果服务器 `8080` 被占用，只建议改冒号左侧宿主机端口：

```yaml
ports:
  - "18080:8080"
```

容器内部端口仍保持 `8080`。

## 4. 知识图谱计算 API

相关配置在 `docker-compose.yml`：

```yaml
KG_COMPUTE_URL: http://kg-compute:8101
```

这是后端容器访问 `kg-compute` 容器的内部地址，不是公网地址。

默认端口：

```text
容器内：8101
宿主机：8101
```

一般不需要改。除非你修改 `kg-compute` 服务名或容器内部端口。

验证：

```bash
docker compose ps kg-compute
curl -fsS http://127.0.0.1:8101/
```

如果根路径没有响应，以容器日志为准：

```bash
docker compose logs --tail 100 kg-compute
```

## 5. MySQL API/连接

MySQL 是容器服务，不建议公网开放。

相关配置：

```text
docker-compose.yml
.env
.env.example
```

后端容器内部连接：

```text
jdbc:mysql://mysql:3306/intelligence_platform
```

MySQL 密码来自：

```text
MYSQL_ROOT_PASSWORD
```

部署前建议修改 `.env`：

```env
MYSQL_ROOT_PASSWORD=换成强密码
```

注意：如果 MySQL 容器已经初始化过，再改 `.env` 里的密码不一定会自动修改已有数据库密码。首次部署前改最稳。

## 6. LLM / VLM / Embedding / Rerank API

这些是外部模型 API，主要配置在 `.env`，也可以在系统后台配置页维护。

相关文件：

```text
.env
.env.example
docker-compose.yml
backend-springboot/src/main/resources/application.properties
init-db/02-init-data.sql
```

### 6.1 默认对话 LLM

用途：

```text
智能问答
分析生成
总结生成
普通文本推理
```

变量：

```env
LLM_API_KEY=
LLM_MODEL=deepseek-chat
LLM_PROVIDER=deepseek
LLM_API_BASE_URL=https://api.deepseek.com
```

示例：

```env
LLM_API_KEY=你的真实Key
LLM_MODEL=deepseek-chat
LLM_PROVIDER=deepseek
LLM_API_BASE_URL=https://api.deepseek.com
```

### 6.2 抽取模型

用途：

```text
文档知识抽取
实体抽取
关系抽取
```

变量：

```env
EXTRACT_LLM_API_KEY=
EXTRACT_LLM_MODEL=
EXTRACT_LLM_PROVIDER=
EXTRACT_LLM_BASE_URL=
```

如果留空，后端会回退使用默认 `LLM_*`。

### 6.3 VLM / OCR 模型

用途：

```text
图片理解
表格图片识别
OCR/VLM 描述生成
```

变量：

```env
VLM_API_KEY=
VLM_MODEL=
VLM_PROVIDER=
VLM_API_BASE_URL=
```

如果要做图片/表格解析，必须配置支持视觉输入的模型。

### 6.4 Embedding 模型

用途：

```text
文档向量化
向量搜索
RAG 问答召回
```

变量：

```env
EMBEDDING_API_KEY=
EMBEDDING_MODEL=
EMBEDDING_PROVIDER=
EMBEDDING_API_BASE_URL=
```

没有 Embedding，向量检索和基于文档的智能问答会受到明显影响。

### 6.5 Rerank 模型

用途：

```text
检索结果重排
提高问答召回质量
```

变量：

```env
RERANK_API_KEY=
RERANK_MODEL=
RERANK_PROVIDER=
RERANK_API_BASE_URL=
```

Rerank 可以先不配，但建议生产环境配置。

## 7. 后台系统配置中的 API

前端已经提供 LLM 配置相关接口：

```text
/api/llm-configs
/api/llm-configs/active
/api/llm-configs/active-embedding
/api/llm-configs/test
```

部署完成后，可以在页面里的系统配置/模型配置中维护模型 API。

后端选择优先级大致是：

```text
1. 数据库 llm_configs 中启用的配置
2. .env / 环境变量中的配置
3. application.properties 中的默认值
```

所以生产部署时，推荐：

```text
1. .env 提供基础兜底配置
2. 后台页面配置真实模型
3. 禁止把真实 Key 写死在 SQL 初始化文件中
```

## 8. 搜索 API

深度研究或联网搜索相关配置在 `settings` 表和后台搜索配置接口中。

前端接口：

```text
/api/search-config
/api/search-config/test
```

可能涉及：

```text
search_enabled
search_provider
search_baidu_api_key
search_baidu_secret_key
search_google_api_key
search_google_cx
search_searxng_url
search_api_key
```

如果目标服务器不能访问公网搜索服务，应关闭联网搜索，或配置内网可访问的 SearXNG。

## 9. parser-service API

后端配置项：

```properties
parser.service.url=${PARSER_SERVICE_URL:http://localhost:8100}
```

相关目录：

```text
parser-service
```

当前 `docker-compose.yml` 没有启动 `parser-service`，所以一键 Docker 部署不包含 MinerU、Marker、PaddleOCR-VL 本地解析服务。

如果需要启用，需要单独容器化或在主机上部署 parser-service，并设置：

```env
PARSER_SERVICE_URL=http://parser-service:8100
```

或：

```env
PARSER_SERVICE_URL=http://宿主机IP:8100
```

同时要把该变量加入 `docker-compose.yml` 的 backend environment。

## 10. 安全注意事项

当前 `init-db/02-init-data.sql` 中包含初始化的 `llm_configs` 数据，其中存在已写入的 API Key 字段。

正式部署前必须处理：

```text
1. 不要把真实 API Key 留在 SQL 文件里
2. 已经暴露过的 Key 应立即到服务商控制台轮换或废弃
3. 建议把 SQL 初始化数据里的 api_key 改成 NULL 或空字符串
4. 生产环境用 .env 或后台配置页面录入 Key
```

检查命令：

```bash
grep -R "sk-" -n init-db backend-springboot frontend-vue .env .env.example
```

## 11. 部署后的 API 验证顺序

进入项目根目录：

```bash
cd /root/back
```

验证容器：

```bash
docker compose ps
```

验证后端：

```bash
curl -fsS http://127.0.0.1:8080/api/health
```

验证前端代理：

```bash
curl -fsSI http://127.0.0.1/
curl -fsS http://127.0.0.1/api/health
```

验证模型配置：

```text
打开前端页面 -> 系统配置/模型配置 -> 测试连接
```

验证知识图谱：

```bash
curl -fsS http://127.0.0.1:8080/api/kg/graph
```

验证搜索配置：

```text
打开前端页面 -> 搜索配置 -> 测试搜索
```

## 12. 最小必配清单

首次部署最少需要确认：

```text
1. .env 中 MYSQL_ROOT_PASSWORD 已设置为强密码
2. LLM_API_KEY / LLM_API_BASE_URL / LLM_MODEL 可用
3. 如果需要 RAG，Embedding API 已配置
4. 如果需要图片/表格理解，VLM API 已配置
5. 如果需要联网搜索，搜索 API 或 SearXNG 已配置
6. 80/8080/8101/3306 端口映射符合服务器实际情况
7. init-db/02-init-data.sql 不携带真实可用的 API Key
```
