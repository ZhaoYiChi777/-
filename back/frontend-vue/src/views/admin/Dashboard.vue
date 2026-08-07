<template>
  <div class="dashboard-page">
    <el-row :gutter="20" class="stats-row">
      <el-col :span="4" v-for="item in statCards" :key="item.label">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-icon" :style="{ backgroundColor: item.color + '1A', color: item.color }">
            <el-icon><component :is="item.icon" /></el-icon>
          </div>
          <div class="stat-info">
            <div class="stat-value">{{ item.value }}</div>
            <div class="stat-label">{{ item.label }}</div>
          </div>
        </el-card>
      </el-col>
    </el-row>
    <el-row :gutter="20">
      <el-col :span="12">
        <el-card>
          <template #header>最新资料</template>
          <el-table :data="latestDocs" stripe size="small">
            <el-table-column prop="title" label="标题" show-overflow-tooltip />
            <el-table-column prop="doc_type" label="类型" width="80" />
            <el-table-column prop="uploaded_at" label="上传时间" width="100">
              <template #default="{ row }">{{ row.uploaded_at?.slice(0, 10) }}</template>
            </el-table-column>
            <template #empty>
              <el-empty description="暂无最新资料" :image-size="60" />
            </template>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="12">
        <el-card>
          <template #header>活跃项目</template>
          <el-table :data="activeProjects" stripe size="small">
            <el-table-column prop="name" label="项目名称" />
            <el-table-column prop="leader" label="负责人" width="80" />
            <el-table-column prop="priority" label="优先级" width="80">
              <template #default="{ row }">
                <el-tag :type="row.priority === 'high' ? 'danger' : row.priority === 'medium' ? 'warning' : 'info'" size="small">{{ row.priority }}</el-tag>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getDashboardStats, getLatestDocuments, getActiveProjects } from '../../api'

const stats = ref<any>({})
const latestDocs = ref<any[]>([])
const activeProjects = ref<any[]>([])
const statCards = ref<any[]>([])

onMounted(async () => {
  try {
    const [s, d, p] = await Promise.all([getDashboardStats(), getLatestDocuments(8), getActiveProjects(8)])
    stats.value = s.data
    latestDocs.value = d.data
    activeProjects.value = p.data
    statCards.value = [
      { label: '资料', value: s.data.doc_count || 0, icon: 'Document', color: '#3b82f6' },
      { label: '报告', value: s.data.report_count || 0, icon: 'Reading', color: '#8b5cf6' },
      { label: '问答', value: s.data.qa_count || 0, icon: 'ChatDotRound', color: '#ef4444' },
      { label: '项目', value: s.data.active_projects || 0, icon: 'Bell', color: '#10b981' },
      { label: '组织', value: s.data.organization_count || 0, icon: 'Picture', color: '#f59e0b' },
      { label: '知识节点', value: s.data.kg_node_count || 0, icon: 'Search', color: '#6366f1' },
    ]
  } catch (e) { console.error(e) }
})
</script>

<style scoped>
.dashboard-page { padding: 0; }
.stats-row { margin-bottom: 20px; }
.stat-card {
  display: flex;
  align-items: center;
  padding: 16px;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-right: 14px;
  font-size: 24px;
  flex-shrink: 0;
}
.stat-info {
  flex: 1;
  min-width: 0;
}
.stat-value {
  font-size: 24px;
  font-weight: bold;
  color: #303133;
  line-height: 1.2;
}
.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 4px;
}
</style>
