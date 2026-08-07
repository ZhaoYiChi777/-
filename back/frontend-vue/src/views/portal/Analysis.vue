<template>
  <div class="analysis-page">
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>分析报告</span>
          <el-input v-model="keyword" placeholder="搜索报告" style="width: 250px;" clearable @clear="loadData" @keyup.enter="loadData">
            <template #append><el-button @click="loadData">搜索</el-button></template>
          </el-input>
        </div>
      </template>
      <el-table :data="items" stripe v-loading="loading">
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="analysis_type" label="类型" width="120" />
        <el-table-column prop="category_l1" label="分类" width="100" />
        <el-table-column prop="source_count" label="来源数" width="80" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'completed' ? 'success' : 'info'">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_at" label="创建时间" width="180" />
        <template #empty>
          <el-empty description="暂无分析报告" :image-size="80" />
        </template>
      </el-table>
      <el-pagination
        v-if="total > 0"
        style="margin-top: 16px; justify-content: flex-end;"
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        v-model:current-page="page"
        @current-change="loadData"
      />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getAnalysisList } from '../../api'

const items = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const keyword = ref('')
const loading = ref(false)

async function loadData() {
  loading.value = true
  try {
    const res = await getAnalysisList({ page: page.value, pageSize, keyword: keyword.value || undefined })
    items.value = res.data.items
    total.value = res.data.total
  } catch (e) { console.error(e) }
  loading.value = false
}

onMounted(loadData)
</script>

<style scoped>
.analysis-page { padding: 20px; }
</style>
