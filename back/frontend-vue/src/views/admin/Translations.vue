<template>
  <div class="translations-page">
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>图书</span>
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            :on-change="handleFileSelect"
            accept=".pdf,.doc,.docx,.txt,.epub,.md"
            multiple
          >
            <el-button type="primary" size="small">上传文件</el-button>
          </el-upload>
        </div>
      </template>

      <!-- 上传进度 -->
      <div v-if="uploadingFiles.length > 0" style="margin-bottom: 16px;">
        <div v-for="(f, idx) in uploadingFiles" :key="idx" style="margin-bottom: 8px;">
          <span style="font-size: 13px; color: #666;">{{ f.name }}</span>
          <el-progress :percentage="f.progress" :status="f.status" :stroke-width="4" />
        </div>
      </div>

      <el-table :data="items" stripe v-loading="loading">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="标题" />
        <el-table-column prop="category_l1" label="分类" width="100" />
        <el-table-column prop="keywords" label="关键词" width="200" />
        <el-table-column prop="upload_time" label="上传时间" width="180" />
        <el-table-column label="操作" width="80">
          <template #default="{ row }">
            <el-button type="danger" size="small" link @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无翻译文件，请先上传" :image-size="80" />
        </template>
      </el-table>
      <el-pagination style="margin-top: 16px; justify-content: flex-end;" layout="total, prev, pager, next"
        :total="total" :page-size="pageSize" v-model:current-page="page" @current-change="loadData" />
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getDocuments, deleteDocument, uploadFile } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'

const items = ref<any[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)
const uploadingFiles = ref<{ name: string; progress: number; status: string }[]>([])

async function loadData() {
  loading.value = true
  try {
    const res = await getDocuments({ docType: 'translation', page: page.value, pageSize })
    items.value = res.data.items
    total.value = res.data.total
  } catch (e) { console.error(e) }
  loading.value = false
}

async function handleFileSelect(uploadFileObj: UploadFile) {
  const file = uploadFileObj.raw
  if (!file) return

  const tracker = { name: file.name, progress: 10, status: '' }
  uploadingFiles.value.push(tracker)

  const formData = new FormData()
  formData.append('file', file)
  formData.append('docType', 'translation')
  formData.append('categoryL1', '译著')

  try {
    tracker.progress = 30
    await uploadFile(formData, '/upload/')
    tracker.progress = 100
    tracker.status = 'success'
    ElMessage.success(`已上传: ${file.name}`)
    setTimeout(() => {
      uploadingFiles.value = uploadingFiles.value.filter(f => f !== tracker)
    }, 1500)
    loadData()
  } catch (e: any) {
    tracker.progress = 100
    tracker.status = 'exception'
    ElMessage.error(`上传失败: ${file.name}`)
    setTimeout(() => {
      uploadingFiles.value = uploadingFiles.value.filter(f => f !== tracker)
    }, 3000)
  }
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除？', '提示', { type: 'warning' })
  await deleteDocument(id)
  ElMessage.success('删除成功')
  loadData()
}

onMounted(loadData)
</script>

<style scoped>
.translations-page { padding: 0; }
</style>
