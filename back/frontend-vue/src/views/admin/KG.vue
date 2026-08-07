<template>
  <div class="admin-kg-page">
    <el-row :gutter="16">
      <!-- 图谱可视化 -->
      <el-col :span="17">
        <div class="graph-card">
          <div class="graph-toolbar">
            <div class="toolbar-left">
              <span class="toolbar-title">知识图谱管理</span>
              <el-input v-model="searchQuery" placeholder="搜索节点..." size="small" clearable style="width: 160px;" />
              <el-select v-model="typeFilter" placeholder="类型筛选" size="small" clearable style="width: 110px;">
                <el-option v-for="(label, value) in typeLabels" :key="value" :label="label" :value="value" />
              </el-select>
              <el-tag size="small">{{ graphData.nodes?.length || 0 }} 节点</el-tag>
              <el-tag type="success" size="small">{{ graphData.edges?.length || 0 }} 关系</el-tag>
              <el-tag type="warning" size="small">{{ graphData.communities?.length || 0 }} 社区</el-tag>
            </div>
            <div class="toolbar-right">
              <el-radio-group v-model="colorMode" size="small">
                <el-radio-button value="type">按类型</el-radio-button>
                <el-radio-button value="community">按社区</el-radio-button>
              </el-radio-group>
              <el-button size="small" @click="resetZoom">重置视图</el-button>
            </div>
          </div>
          <div class="graph-area" v-loading="loading">
            <v-chart
              ref="chartRef"
              :option="chartOption"
              autoresize
              style="height: 500px; width: 100%;"
              @click="onNodeClick"
            />
          </div>
          <!-- 数据表格 Tab -->
          <div class="data-section">
            <el-tabs v-model="dataTab" type="card" size="small">
              <el-tab-pane label="节点列表" name="nodes">
                <el-table :data="paginatedNodes" stripe size="small" max-height="250">
                  <el-table-column prop="id" label="ID" width="60" />
                  <el-table-column prop="label" label="标签" />
                  <el-table-column prop="nodeType" label="类型" width="80">
                    <template #default="{ row }">
                      <span class="type-badge" :style="{ backgroundColor: typeColors[row.nodeType] || typeColors.other }" />
                      {{ typeLabels[row.nodeType] || row.nodeType }}
                    </template>
                  </el-table-column>
                  <el-table-column label="关键词" width="140">
                    <template #default="{ row }">
                      <el-tag v-for="kw in (row.keywords || []).slice(0, 3)" :key="kw" size="small" style="margin: 1px;" type="info">{{ kw }}</el-tag>
                      <span v-if="(row.keywords || []).length === 0" style="color: #94A3B8;">-</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="资料库" width="100">
                    <template #default="{ row }">
                      {{ entryTypeLabels[row.entryType] || row.entryType || '-' }}
                    </template>
                  </el-table-column>
                  <el-table-column prop="communityId" label="社区" width="60" />
                  <el-table-column label="重要度" width="110">
                    <template #default="{ row }">
                      <el-progress :percentage="formatImportance(row)" :stroke-width="10" :text-inside="true" />
                    </template>
                  </el-table-column>
                  <el-table-column prop="description" label="描述" show-overflow-tooltip />
                </el-table>
                <div class="table-pagination" v-if="filteredNodes.length > nodePageSize">
                  <el-pagination
                    v-model:current-page="nodePage"
                    :page-size="nodePageSize"
                    :total="filteredNodes.length"
                    layout="prev, pager, next"
                    size="small"
                  />
                </div>
              </el-tab-pane>
              <el-tab-pane label="关系列表" name="edges">
                <el-table :data="paginatedEdges" stripe size="small" max-height="250">
                  <el-table-column prop="sourceId" label="源节点" width="80" />
                  <el-table-column prop="targetId" label="目标节点" width="80" />
                  <el-table-column prop="edgeType" label="关系类型" />
                  <el-table-column prop="weight" label="权重" width="80" />
                </el-table>
                <div class="table-pagination" v-if="(graphData.edges || []).length > edgePageSize">
                  <el-pagination
                    v-model:current-page="edgePage"
                    :page-size="edgePageSize"
                    :total="(graphData.edges || []).length"
                    layout="prev, pager, next"
                    size="small"
                  />
                </div>
              </el-tab-pane>
              <el-tab-pane label="社区分析" name="communities">
                <el-table :data="paginatedCommunities" stripe size="small" max-height="250">
                  <el-table-column prop="id" label="社区ID" width="80">
                    <template #default="{ row }">
                      <span class="comm-dot-sm" :style="{ backgroundColor: communityColors[row.id % communityColors.length] }" />
                      {{ row.id }}
                    </template>
                  </el-table-column>
                  <el-table-column label="成员数" width="80">
                    <template #default="{ row }">{{ row.members?.length || 0 }}</template>
                  </el-table-column>
                  <el-table-column prop="cohesion" label="内聚度" width="100">
                    <template #default="{ row }">
                      <el-progress :percentage="Math.round((row.cohesion || 0) * 100)" :stroke-width="10" :text-inside="true" />
                    </template>
                  </el-table-column>
                  <el-table-column label="核心主题词" width="160">
                    <template #default="{ row }">
                      <el-tag v-for="kw in getTopKeywords(row.members || [])" :key="kw" size="small" style="margin: 1px;" type="info">{{ kw }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="核心节点">
                    <template #default="{ row }">
                      <el-tag v-for="m in (row.members || []).slice(0, 4)" :key="m.id" size="small" style="margin: 2px;">{{ m.label }}</el-tag>
                      <span v-if="row.members?.length > 4" style="color: #94A3B8; font-size: 11px;">+{{ row.members.length - 4 }}</span>
                    </template>
                  </el-table-column>
                </el-table>
                <div class="table-pagination" v-if="(graphData.communities || []).length > commPageSize">
                  <el-pagination
                    v-model:current-page="commPage"
                    :page-size="commPageSize"
                    :total="(graphData.communities || []).length"
                    layout="prev, pager, next"
                    size="small"
                  />
                </div>
              </el-tab-pane>
            </el-tabs>
          </div>
        </div>
      </el-col>

      <!-- 右侧面板 -->
      <el-col :span="7">
        <!-- 选中节点 -->
        <div class="panel-card" v-if="selectedNode">
          <div class="panel-header">
            <span>节点详情</span>
            <el-button size="small" text @click="selectedNode = null">✕</el-button>
          </div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="标签">{{ selectedNode.label }}</el-descriptions-item>
            <el-descriptions-item label="类型">
              <el-tag size="small">{{ typeLabels[selectedNode.nodeType] || selectedNode.nodeType }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="资料库">
              {{ entryTypeLabels[selectedNode.entryType] || selectedNode.entryType || '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="关键词">
              <template v-if="selectedNode.keywords && selectedNode.keywords.length">
                <el-tag v-for="kw in selectedNode.keywords" :key="kw" size="small" style="margin: 1px;" type="info">{{ kw }}</el-tag>
              </template>
              <span v-else>-</span>
            </el-descriptions-item>
            <el-descriptions-item label="社区">{{ selectedNode.communityId }}</el-descriptions-item>
            <el-descriptions-item label="连接数">{{ selectedNode.linkCount }}</el-descriptions-item>
            <el-descriptions-item label="重要度">{{ formatImportance(selectedNode) }}%</el-descriptions-item>
            <el-descriptions-item label="PageRank">{{ selectedNode.pageRank ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="描述">{{ selectedNode.description || '-' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 图谱洞察 -->
        <div class="panel-card">
          <div class="panel-header">图谱洞察</div>
          <div v-for="(insight, i) in insights" :key="i" class="insight-item">
            <el-tag :type="insight.type === 'isolated' ? 'warning' : 'success'" size="small">
              {{ insight.type === 'isolated' ? '孤立' : '惊奇' }}
            </el-tag>
            <div class="insight-title">{{ insight.title }}</div>
            <div class="insight-desc">{{ insight.desc }}</div>
            <div v-if="insight.type === 'isolated'" class="insight-suggestion">建议: 补充该节点的相关文档以建立更多连接</div>
          </div>
          <el-empty v-if="insights.length === 0" description="暂无洞察" :image-size="40" />
        </div>

        <!-- 边类型统计 -->
        <div class="panel-card">
          <div class="panel-header">关系类型分布</div>
          <div v-for="(count, type) in graphData.stats?.edge_types" :key="type" class="stat-row">
            <span class="stat-label">{{ type }}</span>
            <el-progress :percentage="Math.round(count / (graphData.stats?.edge_count || 1) * 100)" :stroke-width="8" :text-inside="true" />
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getKGGraph, getKGInsights } from '../../api'

use([GraphChart, TooltipComponent, CanvasRenderer])

const chartRef = ref<InstanceType<typeof VChart> | null>(null)
const graphData = ref<any>({})
const insights = ref<any[]>([])
const loading = ref(false)
const selectedNode = ref<any>(null)
const colorMode = ref<'type' | 'community'>('community')
const dataTab = ref('nodes')
const searchQuery = ref('')
const typeFilter = ref('')

// 表格分页
const nodePage = ref(1)
const nodePageSize = ref(20)
const edgePage = ref(1)
const edgePageSize = ref(50)
const commPage = ref(1)
const commPageSize = ref(50)

const typeColors: Record<string, string> = {
  entity: '#60a5fa', concept: '#c084fc', source: '#fb923c',
  query: '#4ade80', synthesis: '#f87171', overview: '#facc15',
  finding: '#f472b6', methodology: '#38bdf8', data: '#a78bfa', event: '#34d399',
  other: '#94a3b8',
}
const typeLabels: Record<string, string> = {
  entity: '实体', concept: '概念', source: '来源',
  query: '查询', synthesis: '综合', overview: '概述',
  finding: '发现', methodology: '方法', data: '数据', event: '事件',
  other: '其他',
}
const entryTypeLabels: Record<string, string> = {
  research_report: '研究报告', dynamic_info: '动态信息',
  translation: '图书', chart: '图表',
}
const communityColors = [
  '#60a5fa', '#4ade80', '#fb923c', '#c084fc', '#f87171',
  '#2dd4bf', '#facc15', '#f472b6', '#a78bfa', '#38bdf8',
]

function getNodeColor(node: any): string {
  if (colorMode.value === 'community') {
    return communityColors[(node.communityId || 0) % communityColors.length]
  }
  return typeColors[node.nodeType] || typeColors.other
}

function formatImportance(node: any): number {
  const value = Number(node?.importanceScore ?? 0)
  if (!Number.isFinite(value) || value <= 0) return 0
  return Math.min(100, Math.round(value * 100))
}

function computeLinkCounts(nodes: any[], edges: any[]): Map<number, number> {
  const counts = new Map<number, number>()
  for (const n of nodes) counts.set(n.id, 0)
  for (const e of edges) {
    counts.set(e.sourceId, (counts.get(e.sourceId) || 0) + 1)
    counts.set(e.targetId, (counts.get(e.targetId) || 0) + 1)
  }
  return counts
}

const filteredNodes = computed(() => {
  const nodes = graphData.value.nodes || []
  let result = nodes
  if (searchQuery.value) {
    const q = searchQuery.value.toLowerCase()
    result = result.filter((n: any) =>
      (n.label || '').toLowerCase().includes(q) ||
      (n.description || '').toLowerCase().includes(q)
    )
  }
  if (typeFilter.value) {
    result = result.filter((n: any) => n.nodeType === typeFilter.value)
  }
  return result
})

const paginatedNodes = computed(() => {
  const nodes = filteredNodes.value || []
  const start = (nodePage.value - 1) * nodePageSize.value
  return nodes.slice(start, start + nodePageSize.value)
})

const paginatedEdges = computed(() => {
  const edges = graphData.value.edges || []
  const start = (edgePage.value - 1) * edgePageSize.value
  return edges.slice(start, start + edgePageSize.value)
})

const paginatedCommunities = computed(() => {
  const comms = graphData.value.communities || []
  const start = (commPage.value - 1) * commPageSize.value
  return comms.slice(start, start + commPageSize.value)
})

// 搜索/筛选变化时重置节点分页
watch([searchQuery, typeFilter], () => {
  nodePage.value = 1
})

const chartOption = computed(() => {
  const nodes = graphData.value.nodes || []
  const edges = graphData.value.edges || []
  const linkCounts = computeLinkCounts(nodes, edges)
  const maxLinks = Math.max(1, ...linkCounts.values())
  const q = searchQuery.value.toLowerCase()
  const tf = typeFilter.value

  const chartNodes = nodes.map((n: any) => {
    const matchesSearch = !q || (n.label || '').toLowerCase().includes(q) || (n.description || '').toLowerCase().includes(q)
    const matchesType = !tf || n.nodeType === tf
    const dimmed = (q || tf) && !(matchesSearch && matchesType)
    const linkCount = n.linkCount ?? linkCounts.get(n.id) ?? 0
    const rankSize = Number(n.importanceScore ?? 0)
    const linkSize = Math.sqrt(linkCount / maxLinks)
    const sizeFactor = Math.max(rankSize, linkSize)
    return {
      id: String(n.id),
      name: n.label,
      symbolSize: 12 + sizeFactor * 32,
      itemStyle: { color: getNodeColor(n), opacity: dimmed ? 0.15 : 1 },
      label: { show: linkCount >= 2 || formatImportance(n) >= 70 || nodes.length <= 30 },
      _raw: n,
      linkCount,
    }
  })

  const nodeIds = new Set(chartNodes.map((n: any) => n.id))
  const chartEdges = edges
    .filter((e: any) => nodeIds.has(String(e.sourceId)) && nodeIds.has(String(e.targetId)))
    .map((e: any) => ({
      source: String(e.sourceId),
      target: String(e.targetId),
      lineStyle: { color: '#cbd5e1', width: 1, curveness: 0.1 },
    }))

  return {
    tooltip: {
      formatter: (params: any) => {
        if (params.dataType === 'node') {
          const raw = params.data._raw
          return `<b>${raw.label}</b><br/>类型: ${typeLabels[raw.nodeType] || raw.nodeType}<br/>社区: ${raw.communityId ?? '-'}<br/>连接: ${params.data.linkCount}<br/>重要度: ${formatImportance(raw)}%`
        }
        return ''
      }
    },
    animationDuration: 800,
    series: [{
      type: 'graph',
      layout: 'force',
      data: chartNodes,
      links: chartEdges,
      roam: true,
      draggable: true,
      force: { repulsion: 180, edgeLength: [60, 200], gravity: 0.08, friction: 0.6 },
      emphasis: { focus: 'adjacency', lineStyle: { width: 3 } },
      label: { position: 'right', fontSize: 11, color: '#334155' },
      lineStyle: { opacity: 0.5 },
    }],
  }
})

function onNodeClick(params: any) {
  if (params.dataType === 'node' && params.data._raw) {
    selectedNode.value = { ...params.data._raw, linkCount: params.data.linkCount }
  }
}

function resetZoom() {
  chartRef.value?.dispatchAction({ type: 'restore' })
}

function getTopKeywords(members: any[]): string[] {
  const keywordCounts: Record<string, number> = {}
  for (const m of members) {
    if (m.keywords && Array.isArray(m.keywords)) {
      for (const kw of m.keywords) {
        keywordCounts[kw] = (keywordCounts[kw] || 0) + 1
      }
    }
  }
  const entries = Object.entries(keywordCounts).sort((a, b) => b[1] - a[1])
  if (entries.length > 0) return entries.slice(0, 3).map(e => e[0])
  return members.slice(0, 3).map(m => m.label)
}

onMounted(async () => {
  loading.value = true
  try {
    const [graphRes, insightsRes] = await Promise.all([getKGGraph(), getKGInsights()])
    graphData.value = graphRes.data
    insights.value = [...new Map((insightsRes.data || []).map((i: any) => [i.title, i])).values()]
  } catch (e) { console.error(e) }
  loading.value = false
})
</script>

<style scoped>
.admin-kg-page { padding: 0; }

.graph-card {
  background: white;
  border-radius: 8px;
  border: 1px solid #E2E8F0;
  overflow: hidden;
}

.graph-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 16px;
  border-bottom: 1px solid #E2E8F0;
  background: #F8FAFC;
}

.toolbar-left { display: flex; align-items: center; gap: 8px; }
.toolbar-title { font-weight: bold; font-size: 14px; color: #334155; margin-right: 8px; }
.toolbar-right { display: flex; align-items: center; gap: 8px; }
.graph-area { min-height: 500px; }

.data-section { padding: 0 16px 16px; border-top: 1px solid #E2E8F0; }

.table-pagination {
  display: flex;
  justify-content: center;
  padding-top: 8px;
}

.panel-card {
  background: white;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  margin-bottom: 12px;
  padding: 12px;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: bold;
  font-size: 13px;
  color: #334155;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid #F1F5F9;
}

.insight-item { margin-bottom: 10px; padding-bottom: 10px; border-bottom: 1px solid #F8FAFC; }
.insight-title { font-size: 12px; font-weight: 600; margin: 4px 0 2px; color: #334155; }
.insight-desc { font-size: 11px; color: #94A3B8; }
.insight-suggestion { font-size: 11px; color: #f59e0b; margin-top: 2px; font-style: italic; }

.type-badge { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; }
.comm-dot-sm { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 4px; }

.stat-row { display: flex; align-items: center; gap: 8px; margin-bottom: 6px; }
.stat-label { font-size: 11px; color: #64748B; width: 80px; flex-shrink: 0; }
</style>
