<template>
  <div class="reports-page">
    <el-card>
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center;">
          <span>研究报告库</span>
          <div>
            <el-input v-model="keyword" placeholder="搜索" style="width: 200px; margin-right: 8px;" clearable @clear="loadData" @keyup.enter="loadData" />
            <el-button @click="loadData">搜索</el-button>
            <el-button type="primary" @click="showUpload = true">上传资料</el-button>
          </div>
        </div>
      </template>
      <el-table :data="items" stripe v-loading="loading" empty-text="暂无文档，请上传资料">
        <el-table-column prop="id" label="ID" width="60" />
        <el-table-column prop="title" label="文件名" min-width="200" />
        <el-table-column prop="docType" label="类型" width="80">
          <template #default="{ row }"><el-tag size="small">{{ row.docType }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 'extracted' ? 'success' : 'info'" size="small">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sourceOrigin" label="来源" width="120" show-overflow-tooltip />
        <el-table-column prop="uploadTime" label="上传时间" width="160" />
        <el-table-column label="操作" width="120">
          <template #default="{ row }">
            <el-button type="primary" size="small" link @click="handleReExtract(row.id)">重新抽取</el-button>
            <el-button type="danger" size="small" link @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 上传对话框 -->
    <el-dialog v-model="showUpload" title="上传研究报告" width="500px">
      <el-form :model="uploadForm" label-width="80px">
        <el-form-item label="来源说明">
          <el-input v-model="uploadForm.sourceOrigin" placeholder="可选：报告出处" clearable />
        </el-form-item>
        <el-form-item label="上传文件">
          <el-upload
            ref="uploadRef"
            action=""
            :auto-upload="false"
            :on-change="handleFileChange"
            :file-list="fileList"
            multiple
            accept=".pdf,.doc,.docx,.ppt,.pptx,.txt,.md,.xlsx,.xls,.csv,.html,.htm"
          >
            <el-button type="primary" size="small">选择文件</el-button>
            <template #tip><div class="el-upload__tip">支持 PDF/Word/PPT/TXT/MD/Excel/HTML</div></template>
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
import { getDocuments, deleteDocument, uploadFile, reExtractDocument } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'

const items = ref<any[]>([])
const keyword = ref('')
const loading = ref(false)
const showUpload = ref(false)
const uploading = ref(false)
const fileList = ref<UploadFile[]>([])
const uploadForm = reactive({ sourceOrigin: '' })

async function loadData() {
  loading.value = true
  try {
    const res = await getDocuments({ docType: 'report', keyword: keyword.value || undefined, page: 1, pageSize: 100 })
    items.value = res.data.items || []
  } catch (e) { console.error(e) }
  loading.value = false
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
        formData.append('docType', 'report')
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
    await ElMessageBox.confirm('确定要重新解析该资料？', '确认重新解析')
    await reExtractDocument(id)
    ElMessage.success('重新解析任务已提交')
    loadData()
  } catch (e: any) {
    if (e !== 'cancel') {
      ElMessage.error('重新解析失败: ' + (e.message || '未知错误'))
    }
  }
}

async function handleDelete(id: number) {
  await ElMessageBox.confirm('确定删除该文档？', '提示', { type: 'warning' })
  try {
    await deleteDocument(id)
    ElMessage.success('删除成功')
    loadData()
  } catch (e: any) {
    ElMessage.error('删除失败: ' + (e.message || ''))
  }
}

onMounted(loadData)
</script>

<style scoped>
.reports-page { padding: 0; }
</style>
