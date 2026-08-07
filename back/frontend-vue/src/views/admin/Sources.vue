<template>
  <div class="sources-page">
    <div class="page-header">
      <div class="header-left">
        <h2>来源管理</h2>
        <el-tag type="info" size="small">共 {{ total }} 份资料</el-tag>
      </div>
      <div class="header-center">
        <el-select
          v-model="filterDocType"
          placeholder="信息库筛选"
          clearable
          size="small"
          style="width: 160px"
          @change="handleFilterChange"
        >
          <el-option label="全部" value="" />
          <el-option label="研究报告" value="report" />
          <el-option label="动态信息" value="dynamic" />
          <el-option label="图书" value="translation" />
          <el-option label="图表" value="chart" />
        </el-select>
      </div>
      <div class="header-right">
        <el-button size="small" @click="loadDocuments" :loading="loading">
          <el-icon><Refresh /></el-icon> 刷新
        </el-button>
        <el-button size="small" type="primary" @click="showImportDialog = true">
          <el-icon><Upload /></el-icon> 导入文件夹
        </el-button>
      </div>
    </div>

    <!-- 来源文档表格 -->
    <el-table
      :data="documents"
      stripe
      style="width: 100%"
      empty-text="暂无来源资料，请上传或导入文件"
      v-loading="loading"
      @sort-change="handleSortChange"
    >
      <el-table-column prop="title" label="文件名称" min-width="220" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="file-name-cell">
            <span class="file-icon">{{ getFileIcon(row.title) }}</span>
            <span class="file-name">{{ row.title }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="file_type" label="文件类型" width="100" align="center">
        <template #default="{ row }">
          <el-tag size="small" :type="getTypeColor(row.title)">{{ getExtension(row.title) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="file_size" label="文件大小" width="120" sortable="custom">
        <template #default="{ row }">
          {{ formatFileSize(row.file_size) }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="getStatusColor(row.status)" size="small">
            {{ getStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="upload_time" label="上传时间" width="170" sortable="custom">
        <template #default="{ row }">
          {{ formatTime(row.upload_time) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" align="center" fixed="right">
        <template #default="{ row }">
          <el-popconfirm title="确定删除此来源文件？" @confirm="handleDelete(row.id)">
            <template #reference>
              <el-button size="small" type="danger" text>删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <div class="pagination-wrapper">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="handlePageChange"
        @current-change="handlePageChange"
      />
    </div>

    <!-- 导入文件夹对话框 -->
    <el-dialog v-model="showImportDialog" title="导入文件夹" width="520px">
      <el-form :model="importForm" label-width="100px">
        <el-form-item label="文件夹路径">
          <el-input v-model="importForm.folderPath" placeholder="输入文件夹的绝对路径" />
        </el-form-item>
        <el-form-item label="一级分类">
          <el-select v-model="importForm.categoryL1" placeholder="选择分类" clearable style="width: 100%">
            <el-option label="政治" value="政治" />
            <el-option label="军事" value="军事" />
            <el-option label="经济" value="经济" />
            <el-option label="科技" value="科技" />
            <el-option label="社会" value="社会" />
            <el-option label="文化" value="文化" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源说明">
          <el-input v-model="importForm.sourceOrigin" placeholder="可选：来源出处说明" clearable />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showImportDialog = false">取消</el-button>
        <el-button type="primary" @click="handleImportFolder" :loading="importing">开始导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { Refresh, Upload } from '@element-plus/icons-vue'
import { getDocuments, deleteSource, importSourceFolder } from '../../api'
import { ElMessage } from 'element-plus'

const documents = ref<any[]>([])
const loading = ref(false)
const importing = ref(false)
const showImportDialog = ref(false)
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const filterDocType = ref('')
const sortField = ref('')
const sortOrder = ref('')

const importForm = reactive({
  folderPath: '',
  categoryL1: '',
  sourceOrigin: '',
})

async function loadDocuments() {
  loading.value = true
  try {
    const params: Record<string, any> = {
      page: currentPage.value,
      pageSize: pageSize.value,
    }
    if (filterDocType.value) params.docType = filterDocType.value
    if (sortField.value) {
      params.sortField = sortField.value
      params.sortOrder = sortOrder.value
    }
    const res = await getDocuments(params)
    documents.value = (res.data.items || []).map((doc: any) => ({
      ...doc,
      source_path: doc.source_path?.replace(/^raw\/sources\//, '') || '',
      folder_context: doc.folder_context || '',
    }))
    total.value = res.data.total || documents.value.length
  } catch (e: any) {
    ElMessage.error('加载来源列表失败: ' + (e.message || ''))
  }
  loading.value = false
}

function handleFilterChange() {
  currentPage.value = 1
  loadDocuments()
}

function handleSortChange({ prop, order }: { prop: string; order: string }) {
  sortField.value = prop || ''
  sortOrder.value = order === 'ascending' ? 'asc' : order === 'descending' ? 'desc' : ''
  loadDocuments()
}

function handlePageChange() {
  loadDocuments()
}

function formatFileSize(bytes: number): string {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

async function handleDelete(id: number) {
  try {
    await deleteSource(id)
    ElMessage.success('已删除')
    await loadDocuments()
  } catch (e: any) {
    ElMessage.error('删除失败: ' + (e.message || ''))
  }
}

async function handleImportFolder() {
  if (!importForm.folderPath) {
    ElMessage.warning('请输入文件夹路径')
    return
  }
  importing.value = true
  try {
    const res = await importSourceFolder({
      folderPath: importForm.folderPath,
      categoryL1: importForm.categoryL1,
      sourceOrigin: importForm.sourceOrigin,
    })
    ElMessage.success(`导入完成：${res.data.imported || 0} 个文件`)
    showImportDialog.value = false
    await loadDocuments()
  } catch (e: any) {
    ElMessage.error('导入失败: ' + (e.message || ''))
  }
  importing.value = false
}

function getExtension(filename: string): string {
  if (!filename) return '?'
  const ext = filename.split('.').pop()?.toUpperCase() || '?'
  return ext.length > 5 ? ext.slice(0, 5) : ext
}

function getFileIcon(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || ''
  if (['pdf'].includes(ext)) return '📕'
  if (['doc', 'docx', 'wps'].includes(ext)) return '📘'
  if (['xls', 'xlsx', 'et', 'csv'].includes(ext)) return '📗'
  if (['ppt', 'pptx', 'dps'].includes(ext)) return '📙'
  if (['jpg', 'jpeg', 'png', 'gif', 'webp', 'svg', 'bmp'].includes(ext)) return '🖼'
  if (['txt', 'md', 'markdown'].includes(ext)) return '📄'
  return '📄'
}

function getTypeColor(filename: string): string {
  const ext = filename.split('.').pop()?.toLowerCase() || ''
  if (['pdf'].includes(ext)) return 'danger'
  if (['doc', 'docx', 'wps'].includes(ext)) return ''
  if (['xls', 'xlsx', 'et', 'csv'].includes(ext)) return 'success'
  if (['ppt', 'pptx', 'dps'].includes(ext)) return 'warning'
  if (['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(ext)) return 'info'
  return 'info'
}

function getStatusColor(status: string): string {
  switch (status) {
    case 'parsed': return 'success'
    case 'extracted': return 'success'
    case 'error': return 'danger'
    default: return 'info'
  }
}

function getStatusText(status: string): string {
  switch (status) {
    case 'parsed': return '已解析'
    case 'extracted': return '已抽取'
    case 'error': return '异常'
    default: return status
  }
}

function formatTime(time: string): string {
  if (!time) return '—'
  // 格式化为 YYYY-MM-DD HH:mm
  return time.replace('T', ' ').slice(0, 16)
}

onMounted(loadDocuments)
</script>

<style scoped>
.sources-page {
  padding: 20px;
  background: white;
  border-radius: 8px;
  min-height: calc(100vh - 120px);
}

.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.page-header h2 {
  margin: 0;
  font-size: 18px;
  color: #1F2937;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.header-center {
  display: flex;
  align-items: center;
}

.header-right {
  display: flex;
  gap: 8px;
}

.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

.file-name-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}

.file-icon {
  font-size: 16px;
  flex-shrink: 0;
}

.file-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: #1F2937;
  font-weight: 500;
}
</style>
