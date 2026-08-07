# 智能情报分析平台 - 启动说明

## 技术栈

- **后端**：Spring Boot 4.1 + MyBatis-Plus + SQLite
- **前端**：Vue 3 + TypeScript + Element Plus + Vite

## 快速启动

### 1. 启动后端

```bash
cd backend-springboot
./mvnw spring-boot:run
```

后端运行在 http://localhost:8080

### 2. 启动前端（开发模式）

```bash
cd frontend-vue
npm install  # 首次需要
npm run dev
```

前端运行在 http://localhost:5173，API 请求自动代理到后端。

## 生产构建

```bash
cd frontend-vue
npm run build
```

构建产物在 `frontend-vue/dist/` 目录。

## 页面地址

### 前台（Portal）

| 页面 | 路径 |
|------|------|
| 首页 | http://localhost:5173/portal |
| 资料上传 | http://localhost:5173/portal/upload |
| 分析报告 | http://localhost:5173/portal/analysis |
| 智能问答 | http://localhost:5173/portal/qa |
| 知识图谱 | http://localhost:5173/portal/kg |

### 后台管理（Admin）

| 页面 | 路径 |
|------|------|
| 仪表盘 | http://localhost:5173/admin |
| 动态信息 | http://localhost:5173/admin/info-dynamic |
| 研究报告 | http://localhost:5173/admin/reports |
| 译丛译著库 | http://localhost:5173/admin/translations |
| 图表 | http://localhost:5173/admin/charts |
| 项目库 | http://localhost:5173/admin/projects |
| 组织架构 | http://localhost:5173/admin/org |
| 智能问答 | http://localhost:5173/admin/qa |
| 分析报告 | http://localhost:5173/admin/analysis |
| 结论冲突 | http://localhost:5173/admin/risk |
| 方向建议 | http://localhost:5173/admin/decision |
| 系统配置 | http://localhost:5173/admin/settings |
| 知识图谱 | http://localhost:5173/admin/kg |

## API 接口

后端 API 前缀：`http://localhost:8080/api`

| 模块 | 端点 |
|------|------|
| 仪表盘 | `/api/dashboard/stats`, `/api/dashboard/latest-documents`, `/api/dashboard/active-projects` |
| 资料管理 | `/api/documents` |
| 报告管理 | `/api/reports` |
| 智能问答 | `/api/qa`, `/api/qa/sessions`, `/api/qa/session/{id}` |
| 分析报告 | `/api/analysis`, `/api/analysis/stats` |
| 结论冲突 | `/api/risks`, `/api/risks/stats` |
| 方向建议 | `/api/decisions` |
| 知识图谱 | `/api/kg/nodes`, `/api/kg/edges`, `/api/kg/graph`, `/api/kg/insights` |
| 系统配置 | `/api/settings` |
| 文件上传 | `/api/upload` |
| 统一搜索 | `/api/search`, `/api/search/stats/full` |
| 组织架构 | `/api/organizations`, `/api/organizations/tree` |
| 项目库 | `/api/projects` |

## 项目结构

```
.
├── backend-springboot/     # Spring Boot 后端
│   ├── src/main/java/com/intelligence/platform/
│   │   ├── controller/     # 13 个 REST Controller
│   │   ├── entity/         # 10 个实体类
│   │   ├── mapper/         # 10 个 MyBatis-Plus Mapper
│   │   ├── config/         # 配置类
│   │   └── common/         # 通用类（Result, PageResult, GlobalExceptionHandler）
│   ├── src/main/resources/
│   │   └── application.properties
│   └── pom.xml
├── frontend-vue/           # Vue 3 前端
│   ├── src/
│   │   ├── api/            # Axios API 封装
│   │   ├── views/
│   │   │   ├── portal/     # 前台 5 个页面
│   │   │   └── admin/      # 后台 13 个页面
│   │   └── router/         # Vue Router 路由配置
│   ├── vite.config.ts
│   └── package.json
├── data/                   # SQLite 数据库
│   └── app.db
├── config/                 # 配置文件
│   └── mcporter.json
└── docs/                   # 项目文档
```

## 数据库

SQLite 数据库文件：`data/app.db`

Spring Boot 后端通过相对路径 `../data/app.db`（从 `backend-springboot/` 出发）访问数据库。

## Web 模式使用指南

Web 模式允许用户通过浏览器直接访问平台，无需安装 Tauri 桌面应用。所有核心功能（智能问答、深度研究、知识图谱、矛盾检测等）在 Web 模式下均可正常使用。

### 启动方式

#### 方式一：一键启动（推荐）

```bash
./start-web.sh
```

该脚本会自动完成以下操作：
1. 检查 MySQL 服务状态，未运行则自动启动
2. 检查 `intelligence_platform` 数据库，不存在则自动创建
3. 后台启动 Spring Boot 后端服务（端口 8080）
4. 等待后端健康检查通过
5. 后台启动 Vue 前端开发服务器（端口 5173）

启动完成后终端会显示访问地址和进程 PID。

#### 方式二：手动分别启动

```bash
# 终端 1：启动后端
cd backend-springboot
./mvnw spring-boot:run

# 终端 2：启动前端
cd frontend-vue
npm install  # 首次运行需要
npm run dev
```

#### 方式三：生产环境部署

```bash
# 构建前端静态文件
cd frontend-vue
npm run build

# 将 dist/ 目录部署到 Nginx 或其他 Web 服务器
# 配置反向代理将 /api 请求转发到 http://localhost:8080
```

### 浏览器兼容性

| 浏览器 | 最低版本 | 备注 |
|--------|---------|------|
| Chrome | 90+ | 推荐 |
| Firefox | 88+ | 完全支持 |
| Safari | 15+ | 完全支持 |
| Edge | 90+ | 完全支持（Chromium 内核） |

> 不支持 Internet Explorer。浏览器需支持 ES2020+ 和 CSS Grid 布局。

### 桌面模式与 Web 模式功能对比

| 功能 | 桌面模式 (Tauri) | Web 模式 |
|------|-----------------|----------|
| 词条百科 | ✅ | ✅ |
| Graph-RAG 智能问答 | ✅ | ✅ |
| 深度研究 | ✅ | ✅ |
| 知识图谱可视化 | ✅ | ✅ |
| 矛盾检测 | ✅ | ✅ |
| 方向建议 | ✅ | ✅ |
| 资料上传与管理 | ✅ | ✅ |
| 系统配置 | ✅ | ✅ |
| 项目隔离 | ✅ | ✅ |
| 系统托盘 | ✅ | ❌ |
| 全局快捷键 | ✅ | ❌ |
| 自动更新 | ✅ | ❌ |
| 启动加载动画 | ✅ | ❌ |
| 后端自动管理 | ✅（Tauri Sidecar） | ❌（需手动启停） |
| 多设备访问 | ❌ | ✅ |
| 远程部署 | ❌ | ✅ |

### 常见问题

**Q: Web 模式下前端无法连接后端，提示网络错误？**

A: 确认后端服务已启动并运行在 8080 端口。可通过访问 http://localhost:8080/api/health 检查。如果前端开发服务器配置了代理，检查 `vite.config.ts` 中的 proxy 配置是否正确指向后端地址。

**Q: 如何让局域网内其他设备访问？**

A: 启动前端时默认只监听 localhost。如需局域网访问，修改 `vite.config.ts` 中 `server.host` 为 `'0.0.0.0'`，然后其他设备通过 `http://<你的IP>:5173` 访问。后端同理，确保 `server.address` 配置为 `0.0.0.0`。

**Q: 停止服务后端口仍被占用？**

A: 使用以下命令查找并终止占用端口的进程：
```bash
lsof -i :8080  # 查看后端端口
lsof -i :5173  # 查看前端端口
kill -9 <PID>  # 强制终止
```

**Q: 生产环境如何部署？**

A: 执行 `npm run build` 构建前端静态文件，使用 Nginx 或类似服务器托管 `dist/` 目录，并配置反向代理将 `/api` 请求转发到 Spring Boot 后端（8080 端口）。后端可通过 `java -jar backend.jar` 或 systemd 服务管理。

**Q: Web 模式下数据存在哪里？**

A: 与桌面模式相同，所有数据存储在 MySQL 数据库 `intelligence_platform` 中，上传的文件存储在后端运行目录的 `uploads/` 文件夹下。