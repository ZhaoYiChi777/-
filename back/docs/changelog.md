# 更新日志

## 2026-07-12 (v10)

### 核心功能优化与上传稳定性修复

**1. 修复图片/PNG上传与文件校验的稳定性问题**
- 修复 `FileValidationService.isFileCorrupted()` 中对 PNG 格式的过度校验。由于部分截图工具或图像处理软件在 IEND 块后可能追加 EXIF 等其他区块，旧版纯二进制逐个字节匹配会导致误判。现统一采用 JDK 原生 `ImageIO` 进行软完整性检测，确保各平台（如 macOS 截图、Preview 导出的 PNG/JPG 等）均能正常上传并保留软验证能力。
- 修复 `FileValidationService` 在上传 jpg/jpeg 或其他格式时的后缀名和 Magic Bytes 的别名校验不一致问题。规范化了扩展名别名的校验（例如 jpeg 统一规范化为 jpg 后校验），防止误判文件格式不匹配。

**2. 优化 SmartQA 智能问答的文件引用与 @mention 功能**
- 在 `SmartQA.vue` 中新增 `@mention` 功能。用户输入 `@` 字符即可触发实时文档引用浮层，浮层自动拉取最近上传的文件列表，并支持关键字模糊搜索过滤。
- 支持同时提及多个文档并展示为精致的徽章（Badges）。提交提问时，系统会自动在 Prompt 尾部追加引用文件信息，将多源文档直接代入 LLM 的上下文。
- 升级提问框为两行高度的自适应 `<textarea>` 输入框，支持在多行长文输入的同时保持简洁、美观的对话交互。

**3. 优化全局拖拽上传与前台导航逻辑**
- 在 `PortalLayout.vue` 中集成了全局拖放（Global Drag and Drop）支持。当用户从外部将文件拖入页面的任何部分时，会显示一个极其精致半透明的“释放文件即可上传”的高级毛玻璃遮罩。
- 文件释放后，若用户不在上传页面，系统会自动平滑切换到“资料上传”Tab 并给与气泡提示，全面提升了系统的产品化体验。

**4. 优化知识库文件预览格式与全类型支持**
- 重新设计了 `DocLibrary.vue` 中的文件列表结构，为非图表库文件添加了与图表库等同精度的卡片式/清单式元数据预览区。
- 新增自定义文件后缀名色彩徽章（如 PDF、DOCX、XLSX、MD 等各异色彩背景），自动显示文件上传时间和解析状态（解析中、已解析、失败），彻底替代了原本简陋的文本列表。
- 支持了完整 6 个资料库在主工作区的列表，增加了“政策文件库”与“新闻资讯库”在 DocLibrary 里的对应支持，同步扩展了 `libraryLabels` 映射。

**5. 编译与类型验证**
- 前后端均已完成一键全量编译测试，TypeScript 类型检查无任何报错，并补充了全格式真实的集成上传验证用例。

---

## 2026-07-07 (v9)

### 新增 Web 模式支持 + 前端兼容性优化

**1. 新增 Web 模式**
- 支持在浏览器中直接访问平台，无需安装 Tauri 桌面应用
- 新增 `start-web.sh` 一键启动脚本，自动检查 MySQL 服务、创建数据库、启动前后端服务
- 前端自动检测运行环境（Tauri / Web），在 Web 模式下优雅降级 Tauri 专属 API
- Web 模式下所有核心功能（智能问答、深度研究、知识图谱等）完整可用

**2. 优化前端 Tauri API 兼容性**
- 前端 Tauri API 调用增加环境检测，非 Tauri 环境下不再报错
- 系统托盘、全局快捷键、自动更新等桌面专属功能在 Web 模式下自动隐藏
- 统一错误处理，避免 Web 模式下出现 Tauri 相关的控制台警告

**3. 新增 start-web.sh 启动脚本**
- 自动检测并启动 MySQL 服务
- 自动检查并创建 `intelligence_platform` 数据库
- 后台启动 Spring Boot 后端和 Vue 前端
- 等待后端健康检查通过后再启动前端
- 支持 `Ctrl+C` 一键停止所有服务

**4. 优化图表库智能标签功能**
- 图表上传时支持自动分类标签（图片/表格）
- 图表库列表支持按标签筛选和搜索

**5. 更新 .gitignore**
- 添加 `tests/` 目录规则，测试文件不纳入版本控制

---

## 2026-07-07 (v8)

### SQLite → MySQL 数据库迁移 + 全栈优化

**1. 数据库迁移**
- 从 SQLite 迁移到 MySQL 8.x，所有 13 张表使用 MySQL 语法重建
- `pom.xml` 移除 `sqlite-jdbc`，添加 `mysql-connector-j`
- `application.properties` 改为 MySQL JDBC URL，支持环境变量配置
- 修复 MySQL 保留字冲突：`library` → `entry_library`，`key` → `setting_key`，`abstract` 加反引号
- `spring.sql.init.mode=never` 避免重复初始化

**2. Tauri Sidecar 更新**
- `sidecar.rs` 添加 `MysqlSettings` 结构体，支持从 `config.json` 读取 MySQL 连接配置
- JVM 启动参数改为 MySQL JDBC URL
移除 SQLite 专用 `--enable-native-access` 参数

**3. 后端 API 完善**
- `HealthController` 改为报告 MySQL 连接状态
- 移除 `DocumentController`/`KnowledgeEntryController` 中的 SQLite 分页 fallback
- 补全缺失端点：Decision PUT/GET、Report PUT、Risk PUT/GET、Project PUT
- 修复项目隔离：ReportController、RiskController、AnalysisController 添加 projectId 过滤

**4. 前端优化**
- QA 问答页面从假回答改为真实 LLM API 调用
- 6 处空状态页面添加引导文案
- `reExtractDocument` API 函数补全

**5. 数据安全**
- `.gitignore` 添加 `Test-Project/` 规则
- 所有运行时数据（uploads/、data/、*.db）不入 Git

## 2026-07-07 (v7)

### 桌面应用 LLM 配置迁移 + DMG 重新构建

**1. LLM 配置从 .env 到桌面应用的完整链路**
- 新增 `LlmSettings` 结构体到 `DesktopConfig`，支持在 `config.json` 中存储 LLM 配置
- 新增 `inject_llm_args` 函数，将 config.json 中的 LLM 设置作为 Spring Boot 命令行参数传递
- 支持 `--enable-native-access=ALL-UNNAMED` 标志，修复 Java 17+ 下 SQLite 原生访问问题
- 修正 Spring Boot 参数格式从 `-D` 系统属性改为 `--` 命令行参数

**2. LLM 配置传递链路**
- `.env` 文件 → `application.properties` 默认值 → `config.json` 用户配置 → `sidecar.rs` 命令行注入 → Spring Boot 运行时
- 首次启动向导引导用户配置 LLM API Key，保存到数据库
- 设置页 LLM 配置管理（CRUD + 连接测试）

**3. 代码质量修复**
- 修复 Vue 组件中未使用参数的 lint 警告（InfoDynamic.vue、Reports.vue）

**4. DMG 重新构建**
- 使用最新代码重新构建 macOS DMG 安装包
- 更新 `latest.json` 版本号和下载地址
- 同步更新 GitHub Release

---

## 2026-07-06 (v6)

### 前端导航与词条显示优化 + 向量索引重建与集成测试

**1. 修复前台页面导航与词条跳转卡顿问题**
- 修复 `PortalLayout.vue` 中 `.tab-nav` 容器层级遮挡导致点击 "智能问答"、"深度研究" 无法响应的 z-index 叠放次序问题
- 修复 `Home.vue` 中点击词条后详情弹窗无法正确展示的问题

**2. 优化词条列表展示逻辑 (Home.vue)**
- 彻底移除对 `image` (图片) 和 `table` (表格) 类型的过滤，实现所有词条类型混合按自然顺序排列展示
- 新增词条类别专属彩色 Badge 标签提示 (概念、实体、表格、图片等)
- 引入奇偶行交替背景色和动态悬浮特效 (translateX + 阴影效果)，增强界面动感与质感
- 将每页词条大小限制由 12 条提升至 20 条，使列表更为紧凑美观

**3. 后端向量索引重构与一键同步**
- 完成项目内各分类文件的二次校验与增量缓存匹配
- 修复后台词条与向量检索引擎的数据关联，确保前台提问可以被语义搜索和引用来源完美捕获

**4. 编写并运行自动化集成测试**
- 编写 `tests/test_platform.py` 集成测试代码，覆盖后端 API 服务、向量搜索精准度、智能问答召回流、以及 Playwright 驱动的端到端页面导航与交互验证
- 清理测试中产生的冗余临时文件，只保留最终的运行测试套件，提供清晰易读的过程输出

---

## 2026-07-04 (v5)

### SmartQA 增强 + DeepResearch + 图片/表格 OCR + 系统配置

**1. 修复 SmartQA 图片显示问题**
- 修复问答回答中引用的图片无法正确显示的问题
- 支持图片内嵌渲染，点击可放大查看
- Markdown 渲染器支持完整的富文本格式（标题、列表、代码块、表格、加粗/斜体等）

**2. 增强引用来源面板（参考 llm_wiki CitedReferencesPanel）**
- 新增 CitedReferencesPanel 组件，展示问答回答中所有引用的资料来源
- 每张引用卡片包含文档标题、相关段落摘要、相似度评分
- 支持点击引用卡片跳转到原始资料详情页
- 引用来源面板让问答结果的可信度透明可见

**3. 批量更新已有文档命名为统一格式**
- 文档命名规则统一为 `{项目名}_{库名}_{序号}` 格式
- 批量更新已有文档的名称以符合新规则
- 示例：`无人集群_研究报告_001`、`通信组网_图表库_003`

**4. 修复 batchUpload 命名逻辑**
- 修复批量上传时文档命名不符合统一格式的问题
- 批量上传自动递增序号，保证命名连续性

**5. 添加问答图片/表格阈值和 Top-K 配置**
- 系统设置页面新增图片相似度阈值配置（范围 0.0~1.0，默认 0.7）
- 系统设置页面新增表格相似度阈值配置
- 系统设置页面新增 Top-K 配置（控制引用来源数量上限，默认 5）
- 参数调整即时生效，无需重启

**6. 新增图片/表格 OCR 功能**
- 支持图片上传后自动 OCR 识别为 Markdown 表格
- 基于 SiliconFlow OCR API 实现
- 支持多张表格图片合并为完整表格

**7. 新增深度研究（DeepResearch）功能**
- 支持多步推理、跨资料综合分析
- 研究过程可视化，推理链路透明可追溯

**8. 技术栈升级**
- 前端迁移至 Vue 3 + Element Plus + TypeScript
- 后端迁移至 Spring Boot 4.1 + MyBatis-Plus

---

## 2026-06-26 (v4)

### 全面修复 + 数据库重建 + 组织架构恢复

**1. 智能问答 - 已完成**
- 新建对话按钮（+ 新建对话）
- 历史对话按钮（📋 历史对话）
- 数据库保存：qa_records 表新增 session_id 字段
- 后端新增 3 个 API：GET /api/qa/sessions, GET /api/qa/session/{id}, DELETE /api/qa/session/{id}

**2. 去掉 (K-value) 显示 - 已完成**
- upload.html、index.html、admin.html、admin-translations.html 全部移除

**3. 修复跳转逻辑 - 已完成**
- 前台页面侧栏只链接前台页面（+ 后台管理入口 admin.html）
- 后台页面侧栏只链接后台页面（+ 切换前台入口 index.html）
- 无交叉跳转

**4. 后台信息库下拉 4 个库 - 已完成**
- 所有 12 个 admin-*.html + admin.html 的信息库下拉均包含：研究报告、动态信息库、译丛译著、图表

**5. 重建前后端 - 已完成**
- 修复 database.py 表结构：analysis_reports 增加 summary/analyst，risk_alerts 增加 reporter，decisions 增加 content/priority/source
- 修复 seed.py：kg_nodes 使用 label 列名，kg_edges 使用 edge_type 列名
- 数据库重建完成，13 条文档、6 份报告、3 条问答、4 份分析报告、4 条风险提示、4 条决策建议、29 个知识图谱节点、28 条边

**6. 文档更新 - 已完成**
- changelog.md 更新

**7. 组织架构库恢复 - 已完成**
- admin-org.html 恢复军事领域组织架构内容
- 三层结构：科研管理部 → 4个研究所 → 4个实验室
- 核心成员列表 + 人员统计面板

---

## 2026-06-26 (v3)

### 7项前端优化

**1. 后台侧栏添加动态信息库**
- 所有12个admin-*.html页面的信息库下拉菜单新增"动态信息库"链接

**2. 优化前端页面布局**
- index.html 从4卡片改为6卡片(3x2)布局：研究报告/分析报告/智能问答/知识图谱/资料上传/后台管理

**3. 上传支持自定义K-value字段**
- upload.html 新增"自定义字段(K-value)"区域
- 支持动态添加/删除字段，如人物传记可添加出生年月、籍贯等
- K-value数据随文件上传一起提交到后端meta_info字段
- 移除所有预填假数据

**4. QA页面清理**
- 移除右侧知识图谱面板，聊天区域全宽显示
- 移除电池/智慧城市/碳捕集/大模型等不相关内容
- 推荐问题更新为军事领域
- 移除硬编码的问答历史

**5. 首页跳转完善**
- 新增"资料上传"和"后台管理"两个卡片入口
- 首页6个卡片与前台侧栏5个选项+后台管理完全对应

**6. 信息库数据清理**
- upload.html 移除所有预填假数据（标题、项目、关键词等）
- 所有表单字段默认为空

---

## 2026-06-26 (v2)

### 清理假数据 + 完善前后端API对接

**清理各信息库假数据**
- `admin-reports.html`: 移除8条硬编码报告数据，改为从 DocumentsAPI 动态加载
- `admin-charts.html`: 移除4个硬编码图表项，改为从 API 动态加载
- `admin-translations.html`: 移除6条硬编码译著数据，改为从 API 动态加载
- `admin-projects.html`: 移除6个硬编码项目卡片，显示空状态提示
- `admin-org.html`: 移除硬编码组织架构和成员数据，显示空状态提示
- `admin-kg.html`: 修复图谱节点中损坏的标签缩写名称

**所有按钮连接API**
- `admin-reports.html`: 搜索/筛选/分页 → DocumentsAPI.list()，查看 → report-detail.html，编辑 → DocumentsAPI.update()，删除 → DocumentsAPI.delete()
- `admin-charts.html`: 上传图表 → fetch('/api/upload') + DocumentsAPI.create()，预览 → 点击放大
- `admin-translations.html`: 添加译著 → DocumentsAPI.create()，搜索/筛选 → DocumentsAPI.list()
- `admin-projects.html`: 新建项目（待后端API支持）

---

## 2026-06-26

### 标签缩写化 + 敏感信息模糊化

**一级标签改为首字母缩写大写**
- 战略规划 → ZLGH, 作战理论 → ZZLL, 装备发展 → ZBFZ, 关键技术 → GJJS
- 作战力量 → ZZLLI, 作战运用 → ZZYY, 典型项目 → DXXM, 原始资料库 → YSZLK

**二级标签改为首字母缩写大写**
- 顶层战略 → DCZL, 发展计划 → FZJH, 作战概念 → ZZGN, 作战构想 → ZZGX, 作战条令 → ZZTL
- 空中无人装备 → KZWRZB, 地面无人装备 → DMWRZB, 水面无人装备 → SMWRZB, 水下无人装备 → SXWRZB
- 平台总体技术 → PTZTJS, 自主技术 → ZZJS, 协同集群技术 → XTJQJS, 通信组网技术 → TXZWJS
- 动力能源技术 → DLNYJS, 任务载荷技术 → RWZHJS, 其他保障技术 → QTBZJS
- 协同编组模式 → XTBZMS, 作战单元编成 → ZZDYBC, 兵力结构体系 → BLJGTX, 人才队伍建设 → RCJSDW
- 作战实验 → ZHSY, 军事演习 → JSYX, 实战运用 → SZYY
- 无人项目 → WRXM, 智能项目 → ZNXXM
- 动态信息库 → DTXXK, 研究报告库 → YJBGK, 译丛译著库 → YCYZK, 图表库 → TBK

**敏感信息模糊化**
- 所有人名替换为角色代号：研究员A-F、教授A-F、分析员A、工程师A
- "情报分析中心" → "分析中心"

**改动范围**
- `数据库有关.docx`：生成 `数据库有关_更新.docx`（原文件被锁定需手动替换）
- `backend/app/seed.py`：category_l1/category_l2 字段使用缩写，标题/描述保持中文原文
- 所有前端HTML页面：<option>标签使用缩写，标题/描述保持中文原文
- 敏感信息在所有前端页面和种子数据中均已模糊化

---

## 2026-06-25

### 后台管理系统全面改造 (11 项需求)

**侧栏与导航改造**
- 后台 `admin.html` 从仪表盘改为简洁入口页，6 个入口卡片 (研究报告/译丛译著/图表/项目库/组织架构库/知识图谱)
- 去掉仪表盘统计卡片、动态信息
- 后台侧栏: 信息库(下拉: 研究报告/译丛译著/图表) + 项目库 + 组织架构库 + 底部知识图谱
- 前台侧栏: 首页 + 研究报告 + 分析报告 + 智能问答 + 知识图谱 + 底部后台管理
- 隐藏底部 5 个菜单项 (智能问答/分析报告/结论冲突/方向建议/系统配置)
- 使用 `fix_sidebar.py` 脚本批量更新 21 个页面的侧栏结构

**页面跳转逻辑修复**
- 前台 5 个页面的"后台管理"链接从 `admin-reports.html` 修正为 `admin.html`
- 后台 6 个页面新增"切换前台"和"返回后台"头部导航链接
- 确保前台→前台、后台→后台的跳转闭环

**前后台系统分离**
- 前台页面 (index/reports/analysis/qa/kg/report-detail/upload) 侧栏链接全部指向前台页面
- 后台页面 (admin/admin-reports/admin-translations/admin-charts/admin-projects/admin-org/admin-kg) 侧栏链接全部指向后台页面
- 两套系统通过同一个 FastAPI 后端 + SQLite 数据库存取数据

**译丛译著 K-value 支持**
- `admin-translations.html` 重写: 军事领域译著数据 + K-value 标签
- 新增添加译著弹窗，支持设置: 标题、作者、译者、领域、出版社、简介
- 数据存储在 documents 表 `doc_type=translation`，meta_info JSON 存储 author/domain/intro
- 支持按领域和关键词筛选

**图表库画廊展示**
- `admin-charts.html` 重写: 从假统计图表改为图表画廊
- 支持上传图表文件 (png/jpg/jpeg/svg/gif/webp)
- 图表以卡片画廊形式展示，点击可全屏预览
- 数据存储在 documents 表 `doc_type=chart`

**项目详情页 (百度百科形式)**
- 新增 `project-detail.html`: 百科风格的项目详情页
- 包含: 项目概述、研究方向、里程碑时间线、关联研究报告
- 右侧信息卡: 项目基本信息、完成进度环形图、参与机构列表
- 从 admin-projects.html 的"查看详情"链接跳转

**其他改动**
- `admin-projects.html`: 项目数据更新为军事领域，"查看详情"链接指向 project-detail.html
- `admin-reports.html`: 新增顶部 header 导航
- `admin-org.html`: 新增顶部 header 导航
- `admin-kg.html`: 新增顶部 header 导航
- `index.html`: 移除卡片中的假统计数据，更新技术栈说明
- 文档同步更新: `docs/frontend-pages.md`, `docs/changelog.md`

---

## 2026-06-24

### 前后端全面对接

- **新增 `js/api.js` 共享 API 客户端模块**
  - 封装所有后端 API 调用: DashboardAPI, DocumentsAPI, ReportsAPI, QAAPI, AnalysisAPI, RisksAPI, DecisionsAPI, KgAPI, SettingsAPI, SearchAPI
  - 提供 `uploadFile()` 文件上传函数
  - 提供 `formatDate()`, `escapeHtml()`, `showToast()` 工具函数

- **前端页面动态数据加载**
  - `admin.html`: 仪表盘统计卡片、最新动态、进行中项目从 API 加载
  - `reports.html`: 报告列表从 DocumentsAPI 加载，支持筛选、分页、删除
  - `upload.html`: 文件上传对接 uploadFile API，显示上传进度和状态
  - `analysis.html`: 分析报告列表从 AnalysisAPI 加载
  - `risk-alerts.html`: 结论冲突列表从 RisksAPI 加载
  - `decision-cards.html`: 方向建议列表从 DecisionsAPI 加载
  - `qa.html`: 问答记录调用 QAAPI.create() 保存到数据库
  - `report-detail.html`: 根据 URL 参数 id 从 DocumentsAPI.get() 加载详情

- **后端路由修复**
  - 修复所有 GET `"/"` 路由导致的 307 重定向问题，改为 `""`
  - 涉及: documents, reports, qa, analysis, risks, decisions, settings 共 7 个路由文件

- **API 端点验证** (全部正常)
  - `/api/documents` → 15 条资料
  - `/api/reports` → 5 条报告
  - `/api/qa` → 4 条问答
  - `/api/analysis` → 6 条分析报告
  - `/api/risks` → 4 条冲突
  - `/api/decisions` → 4 条建议
  - `/api/settings` → 配置项
  - `/api/kg/graph` → 25 节点 44 边
  - `/api/dashboard/stats` → 统计数据

---

## 2026-06-23 (续)

### 知识图谱简化

- **移除社区检测功能**
  - `kg-engine.js`: 移除 `detectCommunities()` 函数、`KG_COMMUNITY_COLORS` 常量、`setCommunities()` 方法
  - `kg-engine.js`: `initKGGraph()` 返回 `{graph}` 而非 `{graph, community, communities}`
  - `kg-engine.js`: `getNodeColor()` 只按节点类型着色，移除 community 模式分支
  - 移除 `kgSetColorMode()` 函数

- **kg.html**: 移除"着色模式"切换按钮和"Louvain 社区"侧栏区块，副标题改为"四信号关联度模型"
- **reports.html**: 图谱标签页移除着色模式切换和社区区块，底部统计条移除社区计数
- **qa.html**: 图谱面板移除着色模式切换和社区区块，底部统计条移除社区计数
- **admin-kg.html**: 移除社区相关 CSS 样式和 JS 代码，图谱洞察只保留孤立节点检测

---

## 2026-06-23

### 前端重构

- **知识图谱引擎模块化** (`js/kg-engine.js`)
  - 提取 `ForceGraph` 类为可复用模块
  - 包含 Louvain 社区检测算法
  - 支持力导向布局、拖拽、缩放
  - 统一 API: `initKGGraph()`, `kgSetColorMode()`

- **kg.html 重构**
  - 使用外部 `kg-engine.js` 模块
  - 代码从 734 行精简到 115 行
  - 保持原有功能: 类型/社区着色切换、图谱洞察

- **reports.html 改进**
  - 新增标签切换: 报告列表 / 知识图谱
  - 知识图谱展示采用 kg.html 风格 (左控制栏 + 右 Canvas + 底部统计)
  - 表格数据更新为军事领域
  - 筛选下拉使用一级标签: 战略规划、作战理论、装备发展、关键技术、作战力量、作战运用、典型项目

- **qa.html 图谱面板改造**
  - 右侧图谱面板改为 kg.html 风格
  - 左控制栏 (着色模式、图例、社区、洞察)
  - 右 Canvas 画布 (力导向图)
  - 底部统计条 (节点/关系/社区数)
  - 保留主题切换功能 (固态电池、智慧城市、碳捕集、大模型)

- **移除模型显示**
  - `index.html` 顶部状态栏移除 "模型: DeepSeek-V3"
  - `qa.html` 顶部移除 "模型: DeepSeek-V3"

### 后端增强

- **新增统一搜索模块** (`routers/search.py`)
  - `GET /api/search`: 跨表统一搜索 (documents/reports/qa/analysis)
  - `GET /api/search/stats/full`: 全面统计 (按类型/分类/状态/严重级别/边类型)
  - `POST /api/search/bulk/documents`: 批量导入资料
  - `GET /api/search/export/documents`: 导出资料 (JSON/CSV)
  - `GET /api/search/export/reports`: 导出报告
  - `GET /api/search/export/kg`: 导出知识图谱

- **路由注册** (`main.py`)
  - 新增 `/api/search` 路由前缀

### 页面数据全面更新为军事领域

- **analysis.html**: 分析报告列表更新 (无人集群评估、空中无人装备对比、外军力量编成研判、水下装备续航评估、有人-无人混合编组风险识别、GPS拒止导航评估)
- **risk-alerts.html**: 结论冲突列表更新 (技术发展评估矛盾、自主能力测试差异、混合编组效能分歧、水下装备发展路径分歧、通信组网评估标准不统一)
- **decision-cards.html**: 方向建议列表更新 (无人集群技术路线对比、混合编组效能评估标准、自主能力测试标准化、水下装备发展预测方法学)
- **report-detail.html**: 报告详情页更新 (无人集群协同作战技术路线评估报告，含自主导航/协同集群/通信组网关键数据对比)
- **settings.html**: 系统配置说明更新 (军事情报分析场景、密级控制、OFD格式支持)

### 文档

- **新增 docs/ 目录**
  - `architecture.md`: 系统架构文档 (技术栈、目录结构、数据库表、分类体系)
  - `api-reference.md`: API 参考文档 (全部端点、参数、响应示例)
  - `frontend-pages.md`: 前端页面说明 (所有页面状态、侧栏结构、JS模块)
  - `changelog.md`: 更新日志 (本文件)

---

## 2026-06-22

### 领域转换

- 从科研领域转换为军事/防务领域
- 更新所有 HTML 页面内容 (侧栏、表格数据、示例文件)
- 侧栏结构: 信息库 (下拉 4 项) → 项目库 → 组织架构库 → 底部知识图谱
- 一级标签: 战略规划、作战理论、装备发展、关键技术、作战力量、作战运用、典型项目

### 两级分类联动

- `upload.html` 实现两级分类下拉
- `L2_MAP` 对象定义一级→二级映射
- `updateL2()` 函数动态更新二级选项

### 项目结构归档

- 整理为工业级结构: `frontend/`, `backend/`, `tests/`, `docs/`
- 后端使用 FastAPI + SQLite + uvicorn
- 包管理使用 uv (`pyproject.toml`)

---

## 2026-06-21

### 初始版本

- 前台 4 卡片入口: 资料获取、分析报告、智能问答、知识图谱
- 后台管理系统: 仪表盘、信息库、项目库、组织架构库、各子模块
- 知识图谱: 25 节点、44 边、4 种边类型、Louvain 社区检测
- 数据库: 11 张表、种子数据
- 27 项 API 测试全部通过
