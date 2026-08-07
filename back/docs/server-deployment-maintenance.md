# 智能信息平台服务器部署与维护说明

本文档记录本次在 Ubuntu 内网服务器上部署智能信息平台的实际过程、关键配置、问题修复和后续维护方式。服务器项目目录为：

```bash
/data/iscas/智能信息平台/back
```

`docker-compose.yml` 位于 `back` 目录下，因此所有 Docker Compose 命令都应在该目录执行。

## 一、部署环境

服务器系统：

```text
Linux gs4-AS-4124GS-TNR 6.11.0-25-generic Ubuntu 24.04 x86_64
```

Docker 版本：

```text
Docker 28.0.1
```

项目采用 Docker Compose 部署，主要服务包括：

| 服务 | 容器名 | 镜像 | 说明 |
| --- | --- | --- | --- |
| frontend | intel-platform-frontend | intelligence-platform/frontend:offline | 前端 Nginx 服务 |
| backend | intel-platform-backend | intelligence-platform/backend:offline | Spring Boot 后端 |
| mysql | intel-platform-mysql | mysql:8.0 | 项目数据库 |
| kg-compute | intel-platform-kg-compute | intelligence-platform/kg-compute:offline | 知识图谱计算服务 |

## 二、项目上传与离线部署方式

由于服务器处于内网环境，不能稳定访问 Docker Hub、Maven 仓库、Alpine 软件源等外部网络，因此采用离线镜像部署方式。

本机完成镜像构建：

```powershell
cd "C:\Users\lenovo\legal_term\智能信息平台\back"
docker compose build
```

导出镜像包：

```powershell
docker save -o "intelligence-platform-images.tar" `
  "intelligence-platform/backend:offline" `
  "intelligence-platform/frontend:offline" `
  "intelligence-platform/kg-compute:offline"
```

如果只更新后端，可单独导出：

```powershell
docker save -o "intelligence-platform-backend-fixed.tar" "intelligence-platform/backend:offline"
```

将镜像包手动复制到服务器：

```bash
/data/iscas/智能信息平台/back/
```

服务器导入镜像：

```bash
cd /data/iscas/智能信息平台/back
docker load -i intelligence-platform-images.tar
```

如果只更新后端：

```bash
docker load -i intelligence-platform-backend-fixed.tar
```

启动服务：

```bash
docker compose up -d --no-build
```

只重启后端：

```bash
docker compose up -d --no-build --force-recreate backend
```

## 三、端口与访问方式

当前主要对外访问端口：

| 端口 | 说明 |
| --- | --- |
| 80 | 前端网站访问端口 |

浏览器访问：

```text
http://服务器IP/
```

后端容器内部端口为 8080。若宿主机映射过 8889，含义是：

```text
宿主机 8889 -> backend 容器内部 8080
```

正式部署建议只保留前端 80 端口对外开放，不建议直接暴露：

```text
3306  MySQL
8080/8889  Backend
8101  kg-compute
```

查看当前端口：

```bash
docker compose ps
ss -ltnp
```

## 四、MySQL 初始化与权限处理

初始化 SQL 位于：

```bash
/data/iscas/智能信息平台/back/init-db
```

部署时曾遇到 MySQL 初始化目录权限问题：

```text
/docker-entrypoint-initdb.d/: permission denied
```

修复方式：

```bash
chmod 755 init-db
chmod 644 init-db/*.sql
chown -R root:root init-db
```

进入 MySQL：

```bash
docker exec -it intel-platform-mysql mysql -uroot -p
```

选择数据库：

```sql
USE intelligence_platform;
```

查看 LLM 配置：

```sql
SELECT id,name,provider,model,base_url,enabled,purpose,api_mode,LENGTH(api_key) AS key_len
FROM llm_configs
ORDER BY id DESC
LIMIT 10;
```

## 五、LLM API 配置

本次最终采用数据库直接配置方式，绕开 Settings 页面可能存在的字段命名问题。

关键配置如下：

| 字段 | 值 |
| --- | --- |
| provider | custom |
| model | DeepSeek-V4-Flash |
| base_url | http://192.168.200.217:3001/v1 |
| enabled | 1 |
| purpose | chat |
| api_mode | chat_completions |

注意：API Key 不应写入文档明文。若需要重新配置，可进入 MySQL 后执行：

```sql
INSERT INTO llm_configs
(name, provider, api_key, model, base_url, enabled, purpose, max_context_size, api_mode, description)
VALUES
('Gateway DeepSeek', 'custom', '替换为真实API_KEY', 'DeepSeek-V4-Flash',
 'http://192.168.200.217:3001/v1', 1, 'chat', 64000,
 'chat_completions', 'Gateway OpenAI-compatible API');
```

项目后端实际取用规则：

```text
enabled = true
purpose in ('chat', 'both')
order by id desc
limit 1
```

因此最新插入且启用的 `chat` 配置会被优先使用。

## 六、本次 API 调用问题与修复

问题现象：

项目 Settings 页面或问答接口测试 LLM 时失败：

```text
LLM调用 重试3次后仍失败
```

后端日志中出现：

```text
API 错误 (400): Field required, loc: body, input: None
```

排查结果：

1. 服务器可以访问 API 网关。
2. 使用 curl 直接调用网关成功。
3. 数据库中的 LLM 配置已被后端读取到。
4. 问题集中在 Java 后端向网关发送 HTTP 请求时，网关没有正确识别请求 body。

修复方式：

修改后端文件：

```text
backend-springboot/src/main/java/com/intelligence/platform/service/LlmService.java
```

将 Java HttpClient 请求改为：

```java
.version(HttpClient.Version.HTTP_1_1)
.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
```

并增加 import：

```java
import java.nio.charset.StandardCharsets;
```

修改后在本机重新构建后端镜像，导出 tar 包，复制到服务器并导入：

```bash
docker load -i intelligence-platform-backend-fixed.tar
docker compose up -d --no-build --force-recreate backend
```

确认服务器已运行新镜像：

```bash
docker images --format "{{.Repository}}:{{.Tag}} {{.ID}} {{.CreatedAt}}" | grep backend
docker inspect intel-platform-backend --format 'IMAGE={{.Image}} CREATED={{.Created}}'
```

本次确认新镜像 ID 已更新，后端容器也使用了新镜像。

## 七、部署后验证命令

测试 LLM 配置接口：

```bash
read -s GATEWAY_API_KEY

curl -sS -X POST 'http://127.0.0.1/api/llm-configs/test' \
  -H 'Content-Type: application/json' \
  --data '{"name":"Gateway DeepSeek","provider":"custom","apiKey":"'"$GATEWAY_API_KEY"'","model":"DeepSeek-V4-Flash","baseUrl":"http://192.168.200.217:3001/v1","enabled":true,"purpose":"chat","maxContextSize":64000,"apiMode":"chat_completions","description":"Gateway OpenAI-compatible API"}' | python3 -m json.tool
```

成功结果应包含：

```json
{
  "success": true,
  "reply": "连接成功"
}
```

测试完整问答接口：

```bash
curl -sS -X POST 'http://127.0.0.1/api/qa-chat/ask' \
  -H 'Content-Type: application/json' \
  --data '{"question":"请回复：连接成功"}' | python3 -m json.tool
```

成功结果应包含：

```json
{
  "answer": "连接成功"
}
```

本次最终验证结果：

```text
/api/llm-configs/test 成功
/api/qa-chat/ask 成功
网站后端 LLM 调用链路已打通
```

## 八、日常维护命令

查看服务状态：

```bash
cd /data/iscas/智能信息平台/back
docker compose ps
```

查看后端日志：

```bash
docker compose logs --tail=200 backend
```

实时跟踪日志：

```bash
docker compose logs -f backend
```

退出实时日志：

```text
Ctrl + C
```

重启服务：

```bash
docker compose restart backend
docker compose restart frontend
docker compose restart
```

停止服务：

```bash
docker compose down
```

重新启动：

```bash
docker compose up -d --no-build
```

查看镜像：

```bash
docker images | grep intelligence-platform
```

查看容器使用的镜像：

```bash
docker inspect intel-platform-backend --format 'IMAGE={{.Image}} CREATED={{.Created}}'
```

## 九、后续更新流程

后续如果只改后端代码：

1. 在本机修改代码。
2. 本机构建后端镜像：

```powershell
cd "C:\Users\lenovo\legal_term\智能信息平台\back"
docker compose build backend
```

3. 导出后端镜像：

```powershell
docker save -o "intelligence-platform-backend-fixed.tar" "intelligence-platform/backend:offline"
```

4. 手动复制到服务器：

```text
/data/iscas/智能信息平台/back/
```

5. 服务器导入并重建后端容器：

```bash
cd /data/iscas/智能信息平台/back
docker load -i intelligence-platform-backend-fixed.tar
docker compose up -d --no-build --force-recreate backend
```

6. 验证：

```bash
docker compose ps
curl -sS -X POST 'http://127.0.0.1/api/qa-chat/ask' \
  -H 'Content-Type: application/json' \
  --data '{"question":"请回复：连接成功"}' | python3 -m json.tool
```

后续如果改前端，也需要重新构建并导出 frontend 镜像。

## 十、注意事项

1. 不要在文档、聊天记录、截图中暴露完整 API Key。
2. 曾经暴露过的 Key 建议在网关后台重新生成并替换。
3. 不建议在内网服务器上直接 `docker compose build`，因为会访问 Docker Hub、Maven、Alpine 软件源，容易卡住或失败。
4. Settings 页面存在字段命名不一致风险，后续建议修复前端字段：

```text
api_key -> apiKey
base_url -> baseUrl
max_context_size -> maxContextSize
api_mode -> apiMode
```

5. `/api/v4/internal/kubernetes/receptive_agents` 相关日志更像外部探测请求，不作为本项目部署失败依据。
6. 前端 healthcheck 显示 unhealthy 时，不一定代表网站不可访问，应结合 `curl http://127.0.0.1/` 和浏览器访问结果判断。

