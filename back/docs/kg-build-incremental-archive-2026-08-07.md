# 知识图谱建图任务化与增量化归档

日期：2026-08-07

## 背景

当前知识图谱已经具备从 `knowledge_entries` 自动生成节点和规则边的能力，包含：

- 词条节点：每个知识词条生成一个 `kg_nodes` 节点。
- 关键词边：共享关键词生成 `keyword_overlap`。
- 显式引用边：正文中的 `[[词条名]]` 生成 `direct_link`。
- 同源稀疏边：同一 `document_id` 或 `source_name` 生成稀疏 `source_overlap`。
- 来源节点：同一来源聚合为 `node_type = source` 的来源节点，并通过 `source_contains` 连接词条。
- Rust sidecar：用于 Louvain 社区划分和 PageRank 重要度计算。

但原始建图流程是一次性同步重建：调用 `/api/kg/build` 后清空当前项目的旧图，再重新生成所有节点和边。这个方式可以工作，但缺少任务状态、构建版本、词条变更记录和失败留痕，后续接入 LLM 语义关系抽取、增量建图和 Rust 接管规则时会难以追踪。

本次改动的目标是先建立“建图过程可记录、可查询、可追溯”的基础设施。

## 本次完成内容

### 1. 新增建图任务表

新增 `kg_build_jobs`，记录每次建图任务：

| 字段 | 作用 |
| --- | --- |
| `id` | 建图任务 ID |
| `project_id` | 当前项目 ID |
| `status` | `running` / `succeeded` / `failed` |
| `build_mode` | 当前为 `full`，后续可扩展 `incremental` |
| `graph_version` | 图谱版本号，当前使用时间戳 |
| `total_entries` | 本次参与建图的词条总数 |
| `processed_entries` | 已处理词条数 |
| `node_count` | 当前/最终节点数 |
| `edge_count` | 当前/最终边数 |
| `error_message` | 失败原因 |
| `started_at` / `finished_at` | 开始和结束时间 |

### 2. 新增词条构建状态表

新增 `kg_entry_build_states`，记录每个词条进入图谱时的状态：

| 字段 | 作用 |
| --- | --- |
| `project_id` | 项目 ID |
| `entry_id` | 词条 ID |
| `entry_hash` | 由标题、内容、关键词、来源等字段计算出的 SHA-256 |
| `graph_version` | 最近一次构建版本 |
| `node_id` | 该词条对应的图节点 ID |
| `status` | 当前为 `clean`，后续可扩展 `dirty` / `failed` |
| `last_built_at` | 最近构建时间 |

这个表是后续增量建图的核心。下一步可以通过比较当前词条 hash 与已保存 hash，判断词条是否变化。

### 3. 新增关系候选表

新增 `kg_relation_candidates`，为后续 LLM 语义关系抽取预留：

| 字段 | 作用 |
| --- | --- |
| `source_entry_id` | 起点词条 |
| `target_entry_id` | 终点词条 |
| `relation_type` | `defines` / `belongs_to` / `causes` / `synonym_of` 等 |
| `confidence` | 置信度 |
| `evidence` | 原文证据 |
| `reason` | 模型解释 |
| `extractor` | `llm` / `rule` / `rust` |
| `status` | `pending` / `accepted` / `rejected` |
| `graph_version` | 所属图谱版本 |

当前已接入 LLM 候选抽取第一阶段。抽取结果默认只进入候选表，不直接污染正式图边。

### 4. 新增构建事件表

新增 `kg_build_events`，记录建图过程事件：

| 事件 | 含义 |
| --- | --- |
| `started` | 建图任务开始 |
| `entries_loaded` | 已加载参与建图的词条 |
| `graph_cleared` | 当前项目旧图已清理，且没有可建图词条 |
| `edges_built` | 规则边已生成并入库 |
| `communities_computed` | 社区划分已完成或降级完成 |
| `completed` | 建图任务成功结束 |
| `failed` | 建图任务失败 |

### 5. 新增后端实体与 Mapper

新增实体：

- `KGBuildJob`
- `KGEntryBuildState`
- `KGRelationCandidate`
- `KGBuildEvent`

新增 Mapper：

- `KGBuildJobMapper`
- `KGEntryBuildStateMapper`
- `KGRelationCandidateMapper`
- `KGBuildEventMapper`

### 6. 更新数据库初始化

同时覆盖两种场景：

- 新数据库：`schema-v2.sql` 和 `back/init-db/01-schema.sql` 已加入新表。
- 旧数据库：`DatabaseInitializer` 启动时执行 `CREATE TABLE IF NOT EXISTS`，自动补齐新表。

### 7. 更新建图接口

原接口仍可用：

```http
POST /api/kg/build
```

现在返回中新增：

```json
{
  "job_id": 1,
  "status": "succeeded",
  "node_count": 12,
  "edge_count": 37
}
```

新增查询接口：

```http
GET /api/kg/build-jobs/latest
GET /api/kg/build-jobs/{jobId}
```

返回任务状态、任务详情和事件列表，用于前端展示进度或排查失败原因。

## 当前建图流程

```text
POST /api/kg/build
  -> 创建 kg_build_jobs running 任务
  -> 写入 started 事件
  -> 读取当前项目 knowledge_entries
  -> 写入 entries_loaded 事件
  -> 清理当前项目旧 kg_edges / kg_nodes
  -> 清理已删除词条对应的 kg_entry_build_states
  -> 为每个词条创建 KGNode
  -> 计算 entry_hash
  -> upsert kg_entry_build_states
  -> 构建 keyword_overlap / direct_link / source_overlap / source_contains
  -> 写入 kg_edges
  -> 写入 edges_built 事件
  -> 调用 Rust sidecar 计算 Louvain 社区
  -> 失败则降级为简单社区分组
  -> 写入 communities_computed 事件
  -> 更新 kg_build_jobs 为 succeeded
  -> 写入 completed 事件
```

## 当前完成度

已完成：

- 建图任务落库。
- 建图事件留痕。
- 词条 hash 状态落库。
- 当前全量建图仍保持兼容。
- 查询最新/指定建图任务。
- 新旧数据库自动补表。
- 为后续 LLM 语义关系抽取预留关系候选表。

新增进展：2026-08-11 已实现伪增量建图扫描。

伪增量当前行为：

- `/api/kg/build` 的 `build_mode` 改为 `pseudo_incremental`。
- 每次构建前先读取 `kg_entry_build_states` 中已有的 `entry_hash`。
- 对当前参与建图的 `knowledge_entries` 重新计算 hash。
- 统计 `newEntries`、`changedEntries`、`unchangedEntries`、`deletedEntries`、`dirtyEntries`。
- 写入 `kg_build_events`，事件类型为 `incremental_scan`。
- 本轮仍执行全量图谱重建，保证最终图谱结果与原全量构建兼容。
- 构建完成后，本轮参与词条状态重新写为 `clean`，并更新 `graph_version`、`node_id`、`last_built_at`。

`incremental_scan` 事件 payload 示例：

```json
{
  "totalEntries": 11,
  "newEntries": 0,
  "changedEntries": 0,
  "unchangedEntries": 11,
  "deletedEntries": 0,
  "dirtyEntries": 0
}
```

仍未完成：

- 真正的局部增量建图尚未启用，目前仍是“先扫描 dirty，再全量重建”。
- `kg_relation_candidates` 已接入 LLM 候选抽取第一阶段，但默认关闭，尚未接入自动异步建图链路。
- 前端尚未展示建图任务进度。
- Rust 尚未接管规则边生成。
- 建图质量指标尚未自动计算入库。

## LLM 语义关系抽取第一阶段

日期：2026-08-11

本阶段新增 `SemanticRelationService`，目标是先完成“候选关系抽取 + 幻觉控制 + 阀门 + 保底机制”的闭环。默认不影响现有建图流程。

### 开关与接口

默认关闭：

```properties
semantic.extract.enabled=false
semantic.extract.auto-promote=false
semantic.extract.reject-threshold=0.65
semantic.extract.auto-accept-threshold=0.82
semantic.extract.max-dirty-entries-per-job=20
semantic.extract.max-targets-per-entry=12
semantic.extract.max-content-chars=3000
```

手动触发：

```http
POST /api/kg/semantic-relations/extract?includeClean=false&limit=20
```

候选查询：

```http
GET /api/kg/semantic-relations/candidates?status=pending&page=1&pageSize=50
```

### 防幻觉与阀门

第一版不让 LLM 直接写入正式图边。流程为：

```text
dirty/new 词条
  -> 选择同文档/同来源/关键词重合的候选 target
  -> LLM 只能在候选 target 中抽关系
  -> 解析 JSON
  -> 校验 sourceTitle 必须等于当前 source 词条
  -> 校验 targetTitle 必须映射到已有词条
  -> 校验 relationType 必须在白名单内
  -> 校验 evidence 必须出现在 source 或 target 原文中
  -> 按 confidence 和关系类型分流 accepted/pending/rejected
  -> 幂等写入 kg_relation_candidates，重复候选更新原记录
```

状态阀门：

```text
evidence 缺失或原文不命中 -> rejected
source/target 不合法 -> rejected
relationType 不在白名单 -> rejected
confidence < 0.65 -> rejected
confidence >= 0.82 且类型属于高稳白名单 -> accepted
其他通过基础校验的关系 -> pending
```

高稳自动 accepted 类型：

```text
defines
belongs_to
references
synonym_of
```

复杂类型先进入 pending：

```text
causes
parent_of
child_of
applies_when
depends_on
conflicts_with
```

自动晋升正式图边默认关闭。只有显式设置：

```properties
semantic.extract.auto-promote=true
```

accepted 候选才会转成 `kg_edges`，边类型为：

```text
semantic_{relationType}
```

候选写入具备幂等保护：同一项目、同一 source/target、同一 relationType、同一 evidence、同一 extractor 的候选会复用已有记录，并刷新 `confidence`、`reason`、`status` 和 `graph_version`，避免重复触发抽取导致审核列表膨胀。

## 验证记录

已运行：

```bash
mvn -Dtest=KGServiceSourceOverlapTest test
```

结果：

```text
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖点：

- 大同源组不会再全连接。
- 同源多信号会合并权重。
- 无来源词条不会生成同源边。
- 来源节点 membership 逻辑正确。
- 无来源词条不会生成来源 membership。
- `entry_hash` 对同等数据稳定。
- `entry_hash` 在内容变化后会变化。
- 伪增量扫描可区分新增、变更、未变、删除词条。
- LLM 响应 JSON fenced code block 可解析。
- evidence 必须命中原文，否则高置信关系也会 rejected。
- 高置信高稳关系可 accepted，复杂关系先 pending。

## 下一步建议

### 第一阶段：启用伪增量

目标：先做到“识别 dirty 词条”，但图算法仍然全图重算。

建议实现：

```text
1. 查询 knowledge_entries 当前 hash。
2. 与 kg_entry_build_states.entry_hash 对比。
3. hash 未变化的词条标记 clean。
4. hash 变化或不存在的词条标记 dirty。
5. 只重建 dirty 词条相关节点/边。
6. 最后全图重新跑 PageRank/community。
```

### 第二阶段：接入 LLM 语义关系

建议支持关系：

- `defines`
- `belongs_to`
- `references`
- `causes`
- `parent_of`
- `child_of`
- `synonym_of`
- `applies_when`
- `conflicts_with`
- `depends_on`

LLM 抽取结果先进入 `kg_relation_candidates`，人工或阈值审核通过后再转成 `kg_edges`。

### 第三阶段：Rust 接管规则边

建议迁移顺序：

```text
1. keyword_overlap
2. source_overlap
3. direct_link
4. source_contains
5. 边去重、边权归一化、稠密图裁剪
```

Java 继续负责：

- 数据库读写。
- 项目隔离。
- 建图任务编排。
- LLM 调用和审核流程。

Rust 负责：

- 确定性图规则。
- 图算法。
- 大图性能敏感计算。
- 图质量指标。

## 建图质量检测建议

### 结构指标

每次建图后统计：

- `node_count`
- `edge_count`
- `avg_degree = 2 * edge_count / node_count`
- `isolated_node_rate`
- `max_degree`
- `edge_type_distribution`
- `community_count`
- `community_size_distribution`
- `source_overlap_ratio`

建议阈值：

```text
avg_degree < 1.0       图过稀
avg_degree 2 - 8       较健康
avg_degree > 15        可能过密
source_overlap > 50%   同源关系影响过大
isolated_node > 30%    关系召回不足
```

### 边抽样评估

每次抽样 30-50 条边，人工标注：

```text
correct
weak_but_acceptable
wrong
unknown
```

重点看：

- `direct_link` 是否解析正确。
- `source_contains` 来源是否正确。
- `source_overlap` 是否只是噪声。
- `keyword_overlap` 是否存在弱关键词误连。
- 后续 `semantic_relation` 是否有 evidence 支撑。

### 查询点测

固定 10 个问题：

```text
1. 某概念的定义是什么？
2. 某概念属于哪个分类？
3. 某概念的上位概念是什么？
4. 某概念的下位概念是什么？
5. 某概念引用了哪些词条？
6. 某概念被哪些词条引用？
7. 某概念适用于什么条件？
8. 某两个概念是否同义或近义？
9. 某来源文档贡献了哪些词条？
10. 某个结论由哪些证据支持？
```

每个问题记录：

- 是否召回正确节点。
- 是否召回正确边。
- 是否有证据。
- 噪声是否可接受。

## 相关代码位置

- `KGService.java`：建图任务编排、entry hash、事件记录、规则边生成。
- `KGController.java`：建图接口和任务查询接口。
- `DatabaseInitializer.java`：旧数据库自动补表。
- `schema-v2.sql`：新数据库表结构。
- `back/init-db/01-schema.sql`：Docker 初始化表结构。
- `kg-compute/src/main.rs`：Rust 图算法 sidecar。
