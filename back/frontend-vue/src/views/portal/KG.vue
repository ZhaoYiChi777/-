<template>
  <div class="kg-page">
    <el-row :gutter="16">
      <!-- 图谱可视化区 -->
      <el-col :span="18">
        <div class="graph-card">
          <div class="graph-toolbar">
            <div class="toolbar-left">
              <span class="toolbar-title">知识图谱</span>
              <el-tag size="small">{{ totalNodes }} 节点</el-tag>
              <el-tag type="success" size="small">{{ allEdges.length }} 关系</el-tag>
              <el-tag type="warning" size="small">{{ currentCommunities.length }} 社区</el-tag>
            </div>
            <div class="toolbar-right">
              <el-radio-group v-model="colorMode" size="small">
                <el-radio-button value="type">按类型</el-radio-button>
                <el-radio-button value="community">按社区</el-radio-button>
              </el-radio-group>
              <el-button size="small" @click="resetZoom">重置</el-button>
            </div>
          </div>
          <div class="graph-container" v-loading="loading">
            <v-chart
              ref="chartRef"
              :option="chartOption"
              autoresize
              style="height: 520px; width: 100%;"
              @click="onNodeClick"
            />
          </div>
          <div class="graph-legend">
            <span v-for="(color, type) in typeColors" :key="type" class="legend-item">
              <span class="legend-dot" :style="{ backgroundColor: color }" />
              {{ typeLabels[type] || type }}
            </span>
          </div>
          <div class="graph-pagination" v-if="totalNodes > pageSize">
            <el-pagination
              v-model:current-page="currentPage"
              :page-size="pageSize"
              :total="totalNodes"
              layout="prev, pager, next, total"
              size="small"
              @current-change="loadPage"
            />
          </div>
        </div>
      </el-col>

      <!-- 右侧：详情 + 洞察 -->
      <el-col :span="6">
        <!-- 选中节点详情 -->
        <div class="detail-card" v-if="selectedNode">
          <div class="detail-header">
            <span class="detail-title">{{ selectedNode.label }}</span>
            <el-button size="small" text @click="selectedNode = null">✕</el-button>
          </div>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="类型">
              <el-tag size="small">{{ typeLabels[selectedNode.nodeType] || selectedNode.nodeType }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="社区">社区 {{ selectedNode.communityId }}</el-descriptions-item>
            <el-descriptions-item label="连接数">{{ selectedNode.linkCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="重要度">{{ formatImportance(selectedNode) }}%</el-descriptions-item>
            <el-descriptions-item label="PageRank">{{ selectedNode.pageRank ?? '-' }}</el-descriptions-item>
            <el-descriptions-item label="描述">{{ selectedNode.description || '-' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <!-- 图谱洞察 -->
        <div class="insights-card">
          <div class="insights-header">图谱洞察</div>
          <div v-for="(insight, i) in insights" :key="i" class="insight-item">
            <el-tag :type="insight.type === 'isolated' ? 'warning' : insight.type === 'surprise' ? 'success' : 'info'" size="small">
              {{ insight.type === 'isolated' ? '孤立' : insight.type === 'surprise' ? '惊奇' : '缺口' }}
            </el-tag>
            <div class="insight-title">{{ insight.title }}</div>
            <div class="insight-desc">{{ insight.desc }}</div>
          </div>
          <el-empty v-if="insights.length === 0" description="暂无洞察" :image-size="50" />
        </div>

        <!-- 社区列表 -->
        <div class="community-card">
          <div class="community-header">社区发现（Louvain）</div>
          <div v-for="comm in currentCommunities.slice(0, 8)" :key="comm.id" class="community-item">
            <span class="comm-dot" :style="{ backgroundColor: communityColors[comm.id % communityColors.length] }" />
            <span class="comm-name">社区 {{ comm.id }}</span>
            <el-tag size="small" type="info">{{ comm.members?.length || 0 }}节点</el-tag>
          </div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { GraphChart } from 'echarts/charts'
import { TooltipComponent, LegendComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { getKGNodes, getKGEdges, getKGInsights } from '../../api'

use([GraphChart, TooltipComponent, LegendComponent, CanvasRenderer])

const chartRef = ref<InstanceType<typeof VChart> | null>(null)
const currentNodes = ref<any[]>([])
const allEdges = ref<any[]>([])
const insights = ref<any[]>([])
const loading = ref(false)
const selectedNode = ref<any>(null)
const colorMode = ref<'type' | 'community'>('community')
const currentPage = ref(1)
const pageSize = ref(50)
const totalNodes = ref(0)

const typeColors: Record<string, string> = {
  entity: '#60a5fa',
  concept: '#c084fc',
  source: '#fb923c',
  query: '#4ade80',
  synthesis: '#f87171',
  overview: '#facc15',
  other: '#94a3b8',
}

const typeLabels: Record<string, string> = {
  entity: '实体', concept: '概念', source: '来源',
  query: '查询', synthesis: '综合', overview: '概述', other: '其他',
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

// 计算连接数
function computeLinkCounts(nodes: any[], edges: any[]): Map<number, number> {
  const counts = new Map<number, number>()
  for (const n of nodes) counts.set(n.id, 0)
  for (const e of edges) {
    counts.set(e.sourceId, (counts.get(e.sourceId) || 0) + 1)
    counts.set(e.targetId, (counts.get(e.targetId) || 0) + 1)
  }
  return counts
}

const currentEdges = computed(() => {
  const nodeIds = new Set(currentNodes.value.map((n: any) => String(n.id)))
  return allEdges.value.filter(
    (e: any) => nodeIds.has(String(e.sourceId)) && nodeIds.has(String(e.targetId))
  )
})

const currentCommunities = computed(() => {
  const map = new Map<number, any[]>()
  for (const n of currentNodes.value) {
    const cid = n.communityId ?? 0
    if (!map.has(cid)) map.set(cid, [])
    map.get(cid)!.push(n)
  }
  return Array.from(map.entries()).map(([id, members]) => ({ id, members, cohesion: 0 }))
})

const chartOption = computed((): any => {
  const nodes = currentNodes.value
  const edges = currentEdges.value
  const linkCounts = computeLinkCounts(nodes, edges)
  const maxLinks = Math.max(1, ...linkCounts.values())

  const chartNodes = nodes.map((n: any) => {
    const linkCount = n.linkCount ?? linkCounts.get(n.id) ?? 0
    const rankSize = Number(n.importanceScore ?? 0)
    const linkSize = Math.sqrt(linkCount / maxLinks)
    const sizeFactor = Math.max(rankSize, linkSize)
    return {
      id: String(n.id),
      name: n.label,
      symbolSize: 12 + sizeFactor * 32,
      category: colorMode.value === 'community' ? (n.communityId || 0) : undefined,
      itemStyle: { color: getNodeColor(n) },
      label: { show: linkCount >= 3 || formatImportance(n) >= 70 || totalNodes.value <= 100 },
      // store original data for click
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
    animationEasingUpdate: 'quinticInOut',
    series: [{
      type: 'graph',
      layout: 'force',
      data: chartNodes,
      links: chartEdges,
      roam: true,
      draggable: true,
      force: {
        repulsion: 180,
        edgeLength: [60, 200],
        gravity: 0.08,
        friction: 0.6,
      },
      emphasis: {
        focus: 'adjacency',
        lineStyle: { width: 3 },
      },
      label: {
        position: 'right',
        fontSize: 11,
        color: '#334155',
      },
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

async function loadPage(page: number) {
  loading.value = true
  try {
    const res = await getKGNodes({ page, pageSize: pageSize.value })
    currentNodes.value = res.data.items || []
    totalNodes.value = res.data.total || 0
    currentPage.value = page
  } catch (e) { console.error(e) }
  loading.value = false
}

onMounted(async () => {
  loading.value = true
  try {
    const [nodeRes, edgeRes, insightsRes] = await Promise.all([
      getKGNodes({ page: 1, pageSize: pageSize.value }),
      getKGEdges({ page: 1, pageSize: 10000 }),
      getKGInsights(),
    ])
    currentNodes.value = nodeRes.data.items || []
    totalNodes.value = nodeRes.data.total || 0
    allEdges.value = edgeRes.data.items || []
    insights.value = insightsRes.data || []
  } catch (e) { console.error(e) }
  loading.value = false
})
</script>

<style scoped>
.kg-page { padding: 0; }

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

.graph-container { min-height: 520px; }

.graph-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  padding: 8px 16px;
  border-top: 1px solid #E2E8F0;
  background: #F8FAFC;
}

.legend-item { display: flex; align-items: center; gap: 4px; font-size: 11px; color: #64748B; }
.legend-dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }

.graph-pagination {
  display: flex;
  justify-content: center;
  padding: 10px 16px;
  border-top: 1px solid #E2E8F0;
  background: #F8FAFC;
}

.detail-card, .insights-card, .community-card {
  background: white;
  border: 1px solid #E2E8F0;
  border-radius: 8px;
  margin-bottom: 12px;
  padding: 12px;
}

.detail-header, .insights-header, .community-header {
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

.community-item {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 0;
  font-size: 12px;
}

.comm-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.comm-name { flex: 1; color: #334155; }
</style>
