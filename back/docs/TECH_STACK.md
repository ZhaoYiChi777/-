# 技术栈

## 前端
| 技术 | 版本 | 用途 |
|------|------|------|
| Vue 3 | 3.x | 前端框架（Composition API + `<script setup>`） |
| TypeScript | 5.x | 类型安全 |
| Vite | 5.x | 构建工具 |
| Element Plus | 2.x | UI 组件库 |
| ECharts | 5.x | 图表与知识图谱可视化 |
| marked | latest | Markdown 渲染 |

## 后端
| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.x | 应用框架 |
| MyBatis-Plus | 3.x | ORM |
| MySQL | 8.x | 关系数据库 |
| Flyway | - | 数据库迁移 |

## LLM 集成
- 支持 OpenAI API 兼容接口（OpenAI / DeepSeek / 自定义 LLM）
- 流式回答（SSE, Server-Sent Events）
- 可配置多 Provider，每个 Provider 支持多 Model

## 存储
- 本地文件系统（上传文件、解析产物）
- 向量检索（PostgreSQL pgvector / 内置向量存储）

## 构建与部署
| 技术 | 用途 |
|------|------|
| Maven | 后端构建 |
| npm + Vite | 前端构建 |
| Shell 脚本 | 一键启动 (`start.sh`) |
| Tauri | 桌面应用（可选） |

## 选型理由
- **Vue 3 + Element Plus**：成熟的中后台 UI 生态，低学习成本
- **Spring Boot + MyBatis-Plus**：Java 生态最流行的微服务框架组合
- **MySQL**：稳定可靠，满足当前数据规模
- **ECharts**：功能最全的 JS 可视化库，原生支持力导向图
