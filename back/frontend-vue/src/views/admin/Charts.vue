<template>
  <div class="charts-page">
    <!-- 筛选标签 + 操作栏 -->
    <el-card style="margin-bottom: 16px;">
      <div style="display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px;">
        <el-radio-group v-model="activeTab" @change="exitSelectMode">
          <el-radio-button value="all">全部 ({{ stats.all }})</el-radio-button>
          <el-radio-button value="image">图片 ({{ stats.image }})</el-radio-button>
          <el-radio-button value="table">表格 ({{ stats.table }})</el-radio-button>
        </el-radio-group>
        <div style="display: flex; gap: 8px; align-items: center;">
          <!-- 上传图片 -->
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            :on-change="(f: any) => handleFileSelect(f, 'image')"
            :accept="imageAccept"
            multiple
          >
            <el-button type="primary" size="small">
              上传图片
            </el-button>
          </el-upload>

          <!-- 上传表格文件 (CSV/Excel) -->
          <el-upload
            :auto-upload="false"
            :show-file-list="false"
            :on-change="(f: any) => handleFileSelect(f, 'table')"
            :accept="tableAccept"
            multiple
          >
            <el-button size="small">
              上传表格
            </el-button>
          </el-upload>

          <el-divider direction="vertical" />

          <!-- 图片Tab下的操作 -->
          <template v-if="activeTab === 'image' || activeTab === 'all'">
            <el-button v-if="!selectMode" size="small" @click="enterSelectMode('image')">
              选择图片 (OCR转表格)
            </el-button>
            <template v-if="selectMode === 'image'">
              <el-button size="small" type="primary" @click="handleOcr" :loading="ocrLoading"
                :disabled="selectedIds.length === 0">
                OCR 识别为表格 ({{ selectedIds.length }})
              </el-button>
              <el-button size="small" @click="exitSelectMode">取消</el-button>
            </template>
          </template>
          <!-- 表格Tab下的操作 -->
          <template v-if="activeTab === 'table' || activeTab === 'all'">
            <el-button v-if="!selectMode" size="small" @click="enterSelectMode('table')">
              选择表格 (合并)
            </el-button>
            <template v-if="selectMode === 'table'">
              <el-button size="small" type="primary" @click="handleMerge" :loading="mergeLoading"
                :disabled="selectedIds.length < 2">
                合并为一个表格 ({{ selectedIds.length }})
              </el-button>
              <el-button size="small" @click="exitSelectMode">取消</el-button>
            </template>
          </template>
        </div>
      </div>
    </el-card>

    <!-- 图片宫格展示 -->
    <div v-if="activeTab === 'all' || activeTab === 'image'" class="section">
      <h3 v-if="imageItems.length" style="margin-bottom: 12px;">图片</h3>
      <div class="image-grid" v-loading="loading">
        <div
          v-for="item in imageItems"
          :key="'img-' + item.id"
          :class="['image-card', { selected: selectedIds.includes(item.id) }]"
          @click="selectMode === 'image' ? toggleSelect(item.id) : openPreview(item)"
        >
          <div class="image-thumb">
            <img :src="getEntryMediaUrl(item)" :alt="item.title" loading="lazy" />
            <!-- 选中标 -->
            <div v-if="selectMode === 'image'" class="select-overlay">
              <el-checkbox :model-value="selectedIds.includes(item.id)" @click.stop @change="toggleSelect(item.id)" />
            </div>
          </div>
          <div class="image-info">
            <span class="image-title" :title="item.title">{{ item.title }}</span>
            <span class="image-source" v-if="item.sourceName">{{ item.sourceName }}</span>
          </div>
        </div>
        <el-empty v-if="!loading && imageItems.length === 0 && activeTab === 'image'" description="暂无图片" />
      </div>
    </div>

    <!-- 表格展示 -->
    <div v-if="activeTab === 'all' || activeTab === 'table'" class="section" style="margin-top: 24px;">
      <h3 v-if="tableItems.length" style="margin-bottom: 12px;">表格</h3>
      <div v-loading="loading">
        <el-card
          v-for="item in tableItems"
          :key="'tbl-' + item.id"
          :class="['table-card', { 'table-selected': selectedIds.includes(item.id) }]"
          style="margin-bottom: 12px;"
        >
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center;">
              <div style="display: flex; align-items: center; gap: 8px;">
                <el-checkbox v-if="selectMode === 'table'"
                  :model-value="selectedIds.includes(item.id)"
                  @change="toggleSelect(item.id)" />
                <span>{{ item.title }}</span>
                <el-tag v-if="item.mediaPath" size="small" type="warning">图片表格</el-tag>
              </div>
              <div v-if="!selectMode" style="display: flex; gap: 4px;">
                <el-button type="primary" size="small" link @click="openEditDialog(item)">编辑</el-button>
                <el-button type="danger" size="small" link @click="handleDelete(item.id)">删除</el-button>
              </div>
            </div>
          </template>
          <div class="table-markdown" v-if="item.tableMarkdown" v-html="renderMarkdown(item.tableMarkdown)"></div>
          <div v-else-if="item.mediaPath" style="text-align: center; padding: 16px;">
            <img :src="getEntryMediaUrl(item)" style="max-width: 100%; max-height: 300px;" />
            <p style="color: #999; margin-top: 8px;">图片表格（未OCR识别）</p>
          </div>
          <el-empty v-else description="无表格数据" />
        </el-card>
        <el-empty v-if="!loading && tableItems.length === 0 && activeTab === 'table'" description="暂无表格" />
      </div>
    </div>

    <!-- 上传处理队列 -->
    <el-card v-if="uploadingFiles.length > 0" style="margin-bottom: 16px;">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span style="font-size: 14px; font-weight: 600;">处理队列 ({{ uploadingFiles.filter(f => f.status !== 'completed' && f.status !== 'failed').length }} 个进行中)</span>
          <el-button size="small" @click="cancelAll" :disabled="uploadingFiles.every(f => f.status === 'completed' || f.status === 'failed')">全部取消</el-button>
        </div>
      </template>
      <div v-for="(f, idx) in uploadingFiles" :key="idx" class="upload-item">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px;">
          <div style="display: flex; align-items: center; gap: 8px;">
            <el-tag :type="f.fileType === 'image' ? 'primary' : 'success'" size="small">{{ f.fileType === 'image' ? '图片' : '表格' }}</el-tag>
            <span style="font-size: 13px;">{{ f.name }}</span>
          </div>
          <div style="display: flex; align-items: center; gap: 8px;">
            <span style="font-size: 12px; color: #999;">{{ f.statusText }}</span>
            <el-button v-if="f.status === 'queued' || f.status === 'processing'" size="small" type="danger" link @click="cancelSingle(f)">取消</el-button>
          </div>
        </div>
        <el-progress :percentage="f.progress" :status="f.status === 'failed' ? 'exception' : f.status === 'completed' ? 'success' : ''" :stroke-width="6" />
      </div>
    </el-card>

    <!-- 空状态 -->
    <el-empty v-if="!loading && imageItems.length === 0 && tableItems.length === 0 && activeTab === 'all'"
      description="图表库暂无数据，请上传图片或表格文件" />

    <!-- 图片预览对话框 -->
    <el-dialog v-model="previewVisible" :title="previewItem?.title" width="fit-content" top="5vh">
      <img
        v-if="previewItem"
        :src="getEntryMediaUrl(previewItem)"
        :alt="previewItem.title"
        style="max-width: 80vw; max-height: 75vh; display: block; margin: 0 auto;"
      />
      <template #footer>
        <p style="color: #999; font-size: 13px;">{{ previewItem?.content }}</p>
      </template>
    </el-dialog>

    <!-- 合并表格标题输入 -->
    <el-dialog v-model="mergeDialogVisible" title="合并表格" width="400px">
      <el-form>
        <el-form-item label="合并后表格标题">
          <el-input v-model="mergeTitle" placeholder="输入合并后的表格标题" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mergeDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="doMerge" :loading="mergeLoading">确认合并</el-button>
      </template>
    </el-dialog>

    <!-- 编辑表格Markdown -->
    <el-dialog v-model="editDialogVisible" :title="'编辑表格 - ' + (editItem?.title || '')" width="700px" top="5vh">
      <el-input
        v-model="editMarkdown"
        type="textarea"
        :rows="16"
        placeholder="输入 Markdown 表格内容，例如：&#10;| 列1 | 列2 |&#10;| --- | --- |&#10;| 数据1 | 数据2 |"
        style="font-family: monospace;"
      />
      <div style="margin-top: 12px;">
        <h4 style="margin-bottom: 8px; color: #666; font-size: 13px;">预览</h4>
        <div class="edit-preview" v-html="renderMarkdown(editMarkdown)"></div>
      </div>
      <template #footer>
        <el-button @click="editDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit" :loading="editSaving">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { getKnowledgeEntries, deleteKnowledgeEntry, getMediaUrl, ocrExistingEntries, mergeTableEntries, uploadFile, getUploadTaskStatus, cancelUploadTask, updateTableMarkdown } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'

// 上传相关
interface UploadFileItem {
  name: string
  fileType: 'image' | 'table'
  progress: number
  status: 'queued' | 'processing' | 'completed' | 'failed' | 'cancelled'
  statusText: string
  docId?: number
  cancelled: boolean
}

const uploadingFiles = ref<UploadFileItem[]>([])
const imageAccept = '.jpg,.jpeg,.png,.gif,.bmp,.webp,.svg'
const tableAccept = '.csv,.xls,.xlsx,.et,.jpg,.jpeg,.png,.gif,.bmp,.webp,.svg,.tiff,.tif'

const allItems = ref<any[]>([])
const loading = ref(false)
const activeTab = ref('all')
const previewVisible = ref(false)
const previewItem = ref<any>(null)

// 选择模式
const selectMode = ref<'image' | 'table' | null>(null)
const selectedIds = ref<number[]>([])
const ocrLoading = ref(false)
const mergeLoading = ref(false)
const mergeDialogVisible = ref(false)
const mergeTitle = ref('合并表格')

// 编辑表格
const editDialogVisible = ref(false)
const editItem = ref<any>(null)
const editMarkdown = ref('')
const editSaving = ref(false)

const imageItems = computed(() =>
  allItems.value.filter((i: any) => i.mediaType === 'image')
)
const tableItems = computed(() =>
  allItems.value.filter((i: any) => i.mediaType === 'table')
)
const stats = computed(() => ({
  all: allItems.value.length,
  image: imageItems.value.length,
  table: tableItems.value.length,
}))

async function loadData() {
  loading.value = true
  try {
    const params: Record<string, any> = { library: 'chart', pageSize: 200, includeImage: true }
    const res = await getKnowledgeEntries(params)
    // 图表库只显示图片和表格，过滤掉文本条目
    allItems.value = (res.data.items || []).filter(
      (i: any) => i.mediaType === 'image' || i.mediaType === 'table'
    )
  } catch (e) {
    console.error(e)
  }
  loading.value = false
}

function getEntryMediaUrl(item: any): string {
  return getMediaUrl(item.mediaPath || '')
}

function enterSelectMode(mode: 'image' | 'table') {
  selectMode.value = mode
  selectedIds.value = []
}

function exitSelectMode() {
  selectMode.value = null
  selectedIds.value = []
}

function toggleSelect(id: number) {
  const idx = selectedIds.value.indexOf(id)
  if (idx >= 0) {
    selectedIds.value.splice(idx, 1)
  } else {
    selectedIds.value.push(id)
  }
}

/** OCR: 将选中的图片条目识别为表格 */
async function handleOcr() {
  if (selectedIds.value.length === 0) return
  await ElMessageBox.confirm(
    `将对选中的 ${selectedIds.value.length} 张图片进行OCR表格识别，识别后图片将转为表格类型。`,
    'OCR 识别',
    { type: 'info' }
  )
  ocrLoading.value = true
  try {
    const res = await ocrExistingEntries({ entryIds: selectedIds.value })
    if (res.data.status === 'success') {
      ElMessage.success(res.data.message)
      exitSelectMode()
      loadData()
    } else {
      ElMessage.error(res.data.message)
    }
  } catch (e: any) {
    ElMessage.error('OCR失败: ' + (e.message || '未知错误'))
  }
  ocrLoading.value = false
}

/** 合并: 弹出标题输入框 */
function handleMerge() {
  if (selectedIds.value.length < 2) {
    ElMessage.warning('请至少选择2个表格进行合并')
    return
  }
  mergeTitle.value = '合并表格'
  mergeDialogVisible.value = true
}

/** 执行合并 */
async function doMerge() {
  mergeLoading.value = true
  try {
    const res = await mergeTableEntries({ entryIds: selectedIds.value, title: mergeTitle.value })
    if (res.data.status === 'success') {
      ElMessage.success(res.data.message)
      mergeDialogVisible.value = false
      exitSelectMode()
      loadData()
    } else {
      ElMessage.error(res.data.message)
    }
  } catch (e: any) {
    ElMessage.error('合并失败: ' + (e.message || '未知错误'))
  }
  mergeLoading.value = false
}

function renderMarkdown(md: string): string {
  if (!md) return ''
  const lines = md.split('\n').filter((l: string) => l.trim())
  if (lines.length < 2) return `<pre>${md}</pre>`

  let html = '<table class="md-table"><thead><tr>'
  const headers = parseTableRow(lines[0])
  headers.forEach((h: string) => { html += `<th>${h}</th>` })
  html += '</tr></thead><tbody>'

  for (let i = 2; i < lines.length; i++) {
    if (lines[i].match(/^\|[\s-|]+\|$/)) continue
    const cells = parseTableRow(lines[i])
    html += '<tr>'
    cells.forEach((c: string) => { html += `<td>${c}</td>` })
    html += '</tr>'
  }
  html += '</tbody></table>'
  return html
}

function parseTableRow(line: string): string[] {
  return line
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c: string) => c.trim())
}

function openPreview(item: any) {
  previewItem.value = item
  previewVisible.value = true
}

function openEditDialog(item: any) {
  editItem.value = item
  editMarkdown.value = item.tableMarkdown || ''
  editDialogVisible.value = true
}

async function saveEdit() {
  if (!editItem.value) return
  editSaving.value = true
  try {
    await updateTableMarkdown(editItem.value.id, editMarkdown.value)
    ElMessage.success('表格已保存')
    editDialogVisible.value = false
    // 更新本地数据
    editItem.value.tableMarkdown = editMarkdown.value
  } catch (e: any) {
    ElMessage.error('保存失败: ' + (e.message || '未知错误'))
  }
  editSaving.value = false
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除？', '提示', { type: 'warning' })
  await deleteKnowledgeEntry(id)
  ElMessage.success('删除成功')
  loadData()
}

/** 文件选择后自动上传到图表库 */
async function handleFileSelect(uploadFileObj: UploadFile, fileType: 'image' | 'table') {
  const file = uploadFileObj.raw
  if (!file) return

  const tracker: UploadFileItem = {
    name: file.name,
    fileType,
    progress: 0,
    status: 'queued',
    statusText: '等待上传...',
    cancelled: false,
  }
  uploadingFiles.value.push(tracker)

  // Step 1: Upload file
  tracker.progress = 10
  tracker.statusText = '上传中...'

  const formData = new FormData()
  formData.append('file', file)
  formData.append('docType', 'chart')
  formData.append('categoryL1', fileType === 'image' ? '图片' : '表格')
  formData.append('fileType', fileType)

  try {
    const res = await uploadFile(formData, '/upload/')
    if (tracker.cancelled) return

    if (res.data?.status === 'success' || res.data?.status === 'skipped') {
      tracker.docId = res.data.id
      tracker.progress = 30
      tracker.statusText = '后端处理中...'

      // Step 2: Poll task status
      await pollTaskStatus(tracker)
    } else {
      tracker.status = 'failed'
      tracker.statusText = res.data?.message || '上传失败'
      tracker.progress = 100
    }
  } catch (e: any) {
    if (!tracker.cancelled) {
      tracker.status = 'failed'
      tracker.statusText = `上传失败: ${e.message || '未知错误'}`
      tracker.progress = 100
    }
  }
}

/** Poll task status until completed/failed/cancelled */
async function pollTaskStatus(tracker: UploadFileItem) {
  const pollInterval = setInterval(async () => {
    if (tracker.cancelled || tracker.status === 'completed' || tracker.status === 'failed') {
      clearInterval(pollInterval)
      return
    }
    try {
      const res = await getUploadTaskStatus(tracker.docId!)
      const task = res.data
      if (!task) {
        tracker.progress = 100
        tracker.status = 'completed'
        tracker.statusText = '处理完成'
        clearInterval(pollInterval)
        loadData()
        return
      }
      tracker.progress = Math.max(tracker.progress, 30 + (task.progress || 0) * 0.7)
      tracker.statusText = task.message || getStatusText(task.status)
      if (task.status === 'completed') {
        tracker.status = 'completed'
        tracker.progress = 100
        const count = task.entryCount || 0
        tracker.statusText = count > 0 ? `处理完成，${count} 个条目` : '处理完成'
        clearInterval(pollInterval)
        loadData()
      } else if (task.status === 'failed') {
        tracker.status = 'failed'
        tracker.progress = 100
        tracker.statusText = task.message || '处理失败'
        clearInterval(pollInterval)
      } else if (task.status === 'cancelled') {
        tracker.status = 'cancelled'
        tracker.progress = 100
        tracker.statusText = '已取消'
        clearInterval(pollInterval)
      }
    } catch (e) {
      // Ignore poll errors
    }
  }, 2000)

  // Auto-cleanup after 5 minutes
  setTimeout(() => {
    clearInterval(pollInterval)
  }, 300000)
}

function getStatusText(status: string): string {
  const map: Record<string, string> = {
    queued: '排队中...',
    processing: '处理中...',
    completed: '处理完成',
    failed: '处理失败',
    cancelled: '已取消',
  }
  return map[status] || status
}

function cancelSingle(tracker: UploadFileItem) {
  tracker.cancelled = true
  tracker.status = 'cancelled'
  tracker.statusText = '已取消'
  tracker.progress = 100
  if (tracker.docId) {
    cancelUploadTask(tracker.docId!).catch(() => {})
  }
}

function cancelAll() {
  uploadingFiles.value.forEach(f => {
    if (f.status === 'queued' || f.status === 'processing') {
      cancelSingle(f)
    }
  })
}

onMounted(loadData)
</script>

<style scoped>
.charts-page {
  padding: 0;
}
.section h3 {
  font-size: 15px;
  color: #333;
  font-weight: 600;
}
.image-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 16px;
}
.image-card {
  border: 2px solid #e8e8e8;
  border-radius: 8px;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s, border-color 0.2s;
  background: #fff;
  position: relative;
}
.image-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.12);
  transform: translateY(-2px);
}
.image-card.selected {
  border-color: #409eff;
  box-shadow: 0 0 0 2px rgba(64, 158, 255, 0.3);
}
.image-thumb {
  width: 100%;
  aspect-ratio: 1;
  overflow: hidden;
  background: #f5f5f5;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}
.image-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.select-overlay {
  position: absolute;
  top: 6px;
  left: 6px;
  background: rgba(255, 255, 255, 0.9);
  border-radius: 4px;
  padding: 2px 4px;
}
.image-info {
  padding: 8px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.image-title {
  font-size: 13px;
  color: #333;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.image-source {
  font-size: 11px;
  color: #999;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.table-card.table-selected {
  border-color: #409eff;
}
.upload-item {
  margin-bottom: 12px;
  padding: 8px 12px;
  background: #fafafa;
  border-radius: 6px;
}
.upload-item:last-child {
  margin-bottom: 0;
}
.edit-preview {
  border: 1px solid #e8e8e8;
  border-radius: 6px;
  padding: 12px;
  max-height: 300px;
  overflow: auto;
  background: #fafafa;
}
</style>

<style>
/* 全局样式 - markdown 表格 */
.md-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
.md-table th,
.md-table td {
  border: 1px solid #e8e8e8;
  padding: 8px 12px;
  text-align: left;
}
.md-table th {
  background: #fafafa;
  font-weight: 600;
}
.md-table tr:hover td {
  background: #f5f7fa;
}
</style>
