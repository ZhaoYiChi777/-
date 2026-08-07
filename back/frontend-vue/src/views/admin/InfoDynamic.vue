<template>
  <div class="info-dynamic-page">
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>动态信息库</span>
          <div>
            <el-button @click="showUrlDialog = true">添加网址</el-button>
            <el-button type="primary" @click="showUpload = true">上传文件</el-button>
          </div>
        </div>
      </template>
      <el-table :data="items" stripe v-loading="loading" empty-text="暂无动态信息">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="标题" min-width="200" />
        <el-table-column prop="url" label="网址" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <a v-if="row.url" :href="row.url" target="_blank" style="color: #409eff;">{{ row.url }}</a>
            <span v-else style="color: #999;">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'extracted' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="uploadTime" label="上传时间" width="160" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button type="primary" size="small" link @click="handleReExtract(row.id)">重新抽取</el-button>
            <el-button type="danger" size="small" link @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- URL上传对话框 -->
    <el-dialog v-model="showUrlDialog" title="添加网页/博客URL" width="500px">
      <el-form :model="urlForm" label-width="80px">
        <el-form-item label="URL">
          <el-input v-model="urlForm.url" placeholder="https://example.com/blog/article" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="urlForm.title" placeholder="可选：自定义标题" />
        </el-form-item>
        <el-form-item label="来源说明">
          <el-input v-model="urlForm.sourceOrigin" placeholder="可选" clearable />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUrlDialog = false">取消</el-button>
        <el-button type="primary" @click="handleUrlUpload" :loading="urlUploading">抓取并入库</el-button>
      </template>
    </el-dialog>

    <!-- 文件上传对话框 -->
    <el-dialog v-model="showUpload" title="上传动态信息文件" width="500px">
      <el-form :model="uploadForm" label-width="80px">
        <el-form-item label="来源说明">
          <el-input v-model="uploadForm.sourceOrigin" placeholder="可选" clearable />
        </el-form-item>
        <el-form-item label="上传文件">
          <el-upload ref="uploadRef" action="" :auto-upload="false" :on-change="handleFileChange" :file-list="fileList" multiple
            accept=".pdf,.doc,.docx,.txt,.md,.html,.htm,.csv">
            <el-button type="primary" size="small">选择文件</el-button>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUpload = false">取消</el-button>
        <el-button type="primary" @click="handleUpload" :loading="uploading">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { getDocuments, deleteDocument, uploadFile, uploadFromUrl, reExtractDocument } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'

const items = ref<any[]>([])
const loading = ref(false)
const showUrlDialog = ref(false)
const showUpload = ref(false)
const urlUploading = ref(false)
const uploading = ref(false)
const fileList = ref<UploadFile[]>([])
const urlForm = reactive({ url: '', title: '', sourceOrigin: '' })
const uploadForm = reactive({ sourceOrigin: '' })

async function loadData() {
  loading.value = true
  try {
    const res = await getDocuments({ docType: 'dynamic', page: 1, pageSize: 100 })
    items.value = res.data.items || []
  } catch (e) { console.error(e) }
  loading.value = false
}

async function handleUrlUpload() {
  if (!urlForm.url.trim()) {
    ElMessage.warning('请输入URL')
    return
  }
  urlUploading.value = true
  try {
    const res = await uploadFromUrl({
      url: urlForm.url,
      title: urlForm.title || undefined,
      docType: 'dynamic',
      sourceOrigin: urlForm.sourceOrigin || undefined,
    })
    ElMessage.success(res.data.message || 'URL已抓取入库')
    showUrlDialog.value = false
    urlForm.url = ''
    urlForm.title = ''
    urlForm.sourceOrigin = ''
    loadData()
  } catch (e: any) {
    ElMessage.error('抓取失败: ' + (e.response?.data?.message || e.message || ''))
  }
  urlUploading.value = false
}

function handleFileChange(_file: UploadFile, fileListNew: UploadFile[]) {
  fileList.value = fileListNew
}

async function handleUpload() {
  if (fileList.value.length === 0) {
    ElMessage.warning('请选择文件')
    return
  }
  uploading.value = true
  let successCount = 0
  let failCount = 0
  try {
    for (const fileItem of fileList.value) {
      try {
        const formData = new FormData()
        formData.append('file', fileItem.raw!)
        formData.append('docType', 'dynamic')
        formData.append('sourceOrigin', uploadForm.sourceOrigin)
        await uploadFile(formData)
        successCount++
      } catch (e: any) {
        failCount++
        console.error('文件上传失败:', fileItem.name, e)
      }
    }
    if (failCount === 0) {
      ElMessage.success(`全部 ${successCount} 个文件上传成功`)
    } else {
      ElMessage.warning(`${successCount} 个成功, ${failCount} 个失败`)
    }
    showUpload.value = false
    fileList.value = []
    uploadForm.sourceOrigin = ''
    loadData()
  } catch (e: any) {
    ElMessage.error('上传异常: ' + (e.message || ''))
  }
  uploading.value = false
}

async function handleReExtract(id: number) {
  try {
    await ElMessageBox.confirm('确定要重新抽取该资料？', '确认重新抽取')
    await reExtractDocument(id)
    ElMessage.success('重新抽取任务已提交')
    loadData()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('重新抽取失败: ' + (e.message || '未知错误'))
    }
  }
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除？', '提示', { type: 'warning' })
  try {
    await deleteDocument(id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e: any) {
    ElMessage.error('删除失败')
  }
}

onMounted(loadData)
</script>

<style scoped>
.info-dynamic-page { padding: 0; }
</style>
