<template>
  <div class="research-report">
    <!-- 顶部：上传按钮 -->
    <div class="sub-header">
      <span class="section-title">研究报告</span>
      <el-button type="primary" size="small" @click="showUpload = true">+ 上传新资料</el-button>
    </div>

    <!-- 4个后台知识库（纵向排列，显示上传资料） -->
    <div class="lib-section-title">知识库</div>
    <div class="lib-list">
      <div v-for="lib in libraries" :key="lib.key" class="lib-row">
        <div class="lib-row-header">
          <span class="lib-row-name">{{ lib.name }}</span>
          <el-tag size="small" type="info">{{ docStats[lib.key] || 0 }} 份资料</el-tag>
        </div>
        <div class="lib-row-body">
          <div v-if="docList[lib.key] && docList[lib.key].length > 0" class="doc-preview">
            <div v-for="doc in docList[lib.key].slice(0, 3)" :key="doc.id" class="doc-item">
              <span class="doc-title">{{ doc.title }}</span>
              <span class="doc-time">{{ doc.upload_time }}</span>
            </div>
          </div>
          <div v-else class="doc-empty">暂无上传资料</div>
        </div>
      </div>
    </div>

    <!-- 上传对话框 -->
    <el-dialog v-model="showUpload" title="上传新资料" width="600px" :close-on-click-modal="!uploading">
      <el-form :model="uploadForm" label-width="100px" v-if="!uploading">
        <el-form-item label="一级分类">
          <el-select v-model="uploadForm.categoryL1" placeholder="选择分类" clearable>
            <el-option label="政治" value="政治" />
            <el-option label="军事" value="军事" />
            <el-option label="经济" value="经济" />
            <el-option label="科技" value="科技" />
            <el-option label="社会" value="社会" />
          </el-select>
        </el-form-item>
        <el-form-item label="资料类型">
          <el-select v-model="uploadForm.docType">
            <el-option label="研究报告" value="report" />
            <el-option label="动态信息" value="dynamic" />
            <el-option label="图书" value="translation" />
            <el-option label="图表" value="chart" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源信息">
          <el-input v-model="uploadForm.sourceOrigin" placeholder="可选：论文名、Blog URL、报告出处" clearable />
        </el-form-item>

        <!-- 图表模式：选择来源文档和页码 -->
        <template v-if="uploadForm.docType === 'chart'">
          <el-form-item label="图表分类">
            <el-radio-group v-model="uploadForm.chartType">
              <el-radio value="image">纯图片</el-radio>
              <el-radio value="table">数据表格 (自动OCR)</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="来源文档">
            <el-select v-model="uploadForm.sourceDocId" placeholder="选择已有文档（可选）" clearable filterable style="width: 100%">
              <el-option-group v-for="lib in libraries" :key="lib.key" :label="lib.name">
                <el-option
                  v-for="doc in docList[lib.key] || []"
                  :key="doc.id"
                  :label="doc.title"
                  :value="doc.id"
                />
              </el-option-group>
            </el-select>
            <div style="font-size: 11px; color: #94A3B8; margin-top: 2px;">
              如来源文档不在列表中，可先上传该文档再关联
            </div>
          </el-form-item>
          <el-form-item label="页码">
            <el-input-number v-model="uploadForm.sourcePage" :min="1" :max="9999" placeholder="来源页码" controls-position="right" />
            <span style="font-size: 11px; color: #94A3B8; margin-left: 8px;">可选：图表在原文件中的页码</span>
          </el-form-item>
        </template>

        <el-form-item label="上传文件">
          <div v-if="isTauriEnv" style="margin-bottom: 8px;">
            <el-button type="primary" plain size="small" @click="pickFiles">
              <el-icon><FolderOpened /></el-icon> 选择文件（系统对话框）
            </el-button>
            <span v-if="files.length > 0" style="margin-left: 8px; color: #67C23A; font-size: 13px;">已选择 {{ files.length }} 个文件</span>
          </div>
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :on-change="handleFileChange"
            :limit="5"
            multiple
            drag
          >
            <el-icon style="font-size: 40px; color: #c0c4cc;"><UploadFilled /></el-icon>
            <div>拖拽或点击上传（Word/PDF/TXT/PPT/图片）</div>
          </el-upload>
        </el-form-item>
      </el-form>

      <!-- 处理进度 -->
      <div v-if="uploading" class="upload-progress-area">
        <div class="progress-header">
          <el-icon class="is-loading" style="font-size: 20px; color: #409EFF;"><Loading /></el-icon>
          <span>正在处理文件... ({{ uploadProgress.current }}/{{ uploadProgress.total }})</span>
        </div>
        <el-progress
          :percentage="uploadProgress.percentage"
          :stroke-width="10"
          style="margin: 16px 0"
        />
        <div class="progress-steps">
          <div :class="['step', { active: uploadProgress.step >= 1, done: uploadProgress.step > 1 }]">
            <span class="step-dot" /> 文件上传
          </div>
          <div :class="['step', { active: uploadProgress.step >= 2, done: uploadProgress.step > 2 }]">
            <span class="step-dot" /> 内容解析
          </div>
          <div :class="['step', { active: uploadProgress.step >= 3, done: uploadProgress.step > 3 }]">
            <span class="step-dot" /> LLM 知识抽取
          </div>
          <div :class="['step', { active: uploadProgress.step >= 4 }]">
            <span class="step-dot" /> 完成
          </div>
        </div>
        <div v-if="uploadProgress.currentFile" class="current-file">
          正在处理：{{ uploadProgress.currentFile }}
        </div>
        <div v-if="uploadProgress.log.length > 0" class="progress-log">
          <div v-for="(line, i) in uploadProgress.log" :key="i" class="log-line">{{ line }}</div>
        </div>
      </div>

      <template #footer>
        <el-button @click="showUpload = false" :disabled="uploading">取消</el-button>
        <el-button type="primary" @click="submitUpload" :loading="uploading" :disabled="uploading">
          {{ uploading ? '处理中...' : '上传并抽取' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, reactive } from 'vue'
import { UploadFilled, Loading, FolderOpened } from '@element-plus/icons-vue'
import { uploadFile, getDocuments } from '../../api'
import { ElMessage } from 'element-plus'
import { isTauri, openFileAsFiles, DOC_FILTERS, sendNotification } from '../../composables/useTauri'
import { getLibraryLabel } from '../../utils/libraryLabels'

const isTauriEnv = isTauri()

async function pickFiles() {
  const selected = await openFileAsFiles({ multiple: true, filters: DOC_FILTERS })
  files.value.push(...selected)
}

const docStats = ref<Record<string, number>>({})
const docList = ref<Record<string, any[]>>({})
const showUpload = ref(false)
const uploading = ref(false)
const files = ref<File[]>([])
const uploadForm = reactive({ categoryL1: '', categoryL2: '', docType: 'report', sourceOrigin: '', sourceDocId: null as number | null, sourcePage: null as number | null, chartType: 'image' })

const uploadProgress = reactive({
  current: 0,
  total: 0,
  percentage: 0,
  step: 0,
  currentFile: '',
  log: [] as string[],
})

const libraries = [
  { key: 'report', name: getLibraryLabel('report') },
  { key: 'dynamic', name: getLibraryLabel('dynamic') },
  { key: 'translation', name: getLibraryLabel('translation') },
  { key: 'chart', name: getLibraryLabel('chart') },
]

// 加载各知识库的文档统计 and 最近上传
async function loadDocStats() {
  for (const lib of libraries) {
    try {
      const res = await getDocuments({ docType: lib.key, page: 1, pageSize: 50 })
      docStats.value[lib.key] = (res.data.items || []).length
      docList.value[lib.key] = res.data.items || []
    } catch (e) {
      console.error(e)
    }
  }
}

function handleFileChange(file: any) {
  files.value.push(file.raw)
}

async function submitUpload() {
  if (files.value.length === 0) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  uploadProgress.total = files.value.length
  uploadProgress.current = 0
  uploadProgress.log = []

  for (const file of files.value) {
    uploadProgress.current++
    uploadProgress.currentFile = file.name
    uploadProgress.step = 1
    uploadProgress.percentage = Math.round(((uploadProgress.current - 1) / uploadProgress.total) * 100)
    uploadProgress.log.push(`[${uploadProgress.current}/${uploadProgress.total}] 正在上传: ${file.name}`)

    const formData = new FormData()
    formData.append('file', file)
    formData.append('categoryL1', uploadForm.categoryL1)
    formData.append('categoryL2', uploadForm.categoryL2)
    formData.append('docType', uploadForm.docType)
    formData.append('sourceOrigin', uploadForm.sourceOrigin)
    if (uploadForm.sourceDocId) formData.append('sourceDocId', String(uploadForm.sourceDocId))
    if (uploadForm.sourcePage) formData.append('sourcePage', String(uploadForm.sourcePage))
    if (uploadForm.docType === 'chart') {
      formData.append('fileType', uploadForm.chartType)
    }
    try {
      uploadProgress.step = 2
      uploadProgress.log.push(`[${uploadProgress.current}/${uploadProgress.total}] 解析内容: ${file.name}`)

      const res = await uploadFile(formData)
      uploadProgress.step = 3
      const entryCount = res.data.entry_count || 0
      uploadProgress.log.push(`[${uploadProgress.current}/${uploadProgress.total}] LLM抽取完成: ${entryCount} 个词条`)
    } catch (e: any) {
      uploadProgress.log.push(`[${uploadProgress.current}/${uploadProgress.total}] 失败: ${e.message || '未知错误'}`)
      ElMessage.error(`上传失败 (${file.name}): ${e.message || '未知错误'}`)
    }
  }

  uploadProgress.step = 4
  uploadProgress.percentage = 100
  uploadProgress.currentFile = ''
  uploadProgress.log.push('全部处理完成')
  ElMessage.success(`上传完成：共${files.value.length}个文件`)
  sendNotification('知识抽取完成', `成功处理 ${files.value.length} 个文件，词条已入库`)

  uploading.value = false
  files.value = []
  showUpload.value = false
  loadDocStats()
}

onMounted(() => {
  loadDocStats()
})
</script>

<style scoped>
.research-report { background: white; border-radius: 8px; padding: 0; }

.sub-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #F1F5F9;
  border: 1px solid #E2E8F0;
  border-radius: 6px;
  margin-bottom: 12px;
}

.section-title {
  font-size: 14px;
  font-weight: bold;
  color: #334155;
}

.filter-bar {
  display: flex;
  gap: 8px;
  padding: 8px 0;
  margin-bottom: 8px;
  align-items: center;
}

.entry-table-wrap {
  border: 1px solid #E2E8F0;
  border-radius: 6px;
  overflow: hidden;
}

.table-header {
  display: grid;
  grid-template-columns: 2fr 80px 100px 1.5fr 80px 120px;
  background: #E8F0FE;
  padding: 8px 12px;
  font-size: 12px;
  font-weight: bold;
  color: #4A90D9;
}

.table-row {
  display: grid;
  grid-template-columns: 2fr 80px 100px 1.5fr 80px 120px;
  padding: 10px 12px;
  font-size: 12px;
  color: #334155;
  border-bottom: 1px solid #F1F5F9;
  align-items: center;
}

.table-row.odd { background: #FAFBFC; }
.table-row:hover { background: #F0F6FF; }

.col-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.col-action { display: flex; gap: 4px; }

.stats-bar {
  display: flex;
  justify-content: space-between;
  padding: 8px 12px;
  background: #F1F5F9;
  font-size: 12px;
  color: #64748B;
  border-radius: 0 0 6px 6px;
}

.empty-tip { padding: 30px 0; }

.divider {
  border-top: 1.5px solid #CBD5E1;
  margin: 20px 0;
}

.lib-section-title {
  font-size: 14px;
  font-weight: bold;
  color: #334155;
  margin-bottom: 12px;
}

.lib-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.lib-row {
  border: 1.2px solid #E2E8F0;
  border-radius: 8px;
  overflow: hidden;
}

.lib-row-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: linear-gradient(180deg, #E8F4FD, #D6E8F7);
  padding: 10px 16px;
}

.lib-row-name {
  font-size: 14px;
  font-weight: bold;
  color: #2F5496;
}

.lib-row-body {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background: #F8FAFC;
}

.doc-preview {
  display: flex;
  flex-direction: column;
  gap: 4px;
  flex: 1;
}

.doc-item {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #475569;
  padding: 2px 0;
}

.doc-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 300px;
}

.doc-time {
  color: #94A3B8;
  font-size: 11px;
  margin-left: 12px;
}

.doc-empty {
  font-size: 12px;
  color: #94A3B8;
  flex: 1;
}

/* Upload progress */
.upload-progress-area {
  padding: 16px 0;
}
.progress-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 15px;
  font-weight: 600;
  color: #1F2937;
  margin-bottom: 4px;
}
.progress-steps {
  display: flex;
  justify-content: space-between;
  margin: 12px 0;
  padding: 0 8px;
}
.step {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #CBD5E1;
  transition: color 0.3s;
}
.step.active { color: #409EFF; font-weight: 500; }
.step.done { color: #10B981; }
.step-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #E2E8F0;
  display: inline-block;
}
.step.active .step-dot { background: #409EFF; }
.step.done .step-dot { background: #10B981; }
.current-file {
  font-size: 12px;
  color: #64748B;
  margin-top: 8px;
  padding: 6px 10px;
  background: #F8FAFC;
  border-radius: 4px;
}
.progress-log {
  max-height: 120px;
  overflow-y: auto;
  margin-top: 8px;
  padding: 8px;
  background: #1E293B;
  border-radius: 6px;
}
.log-line {
  font-size: 11px;
  font-family: 'SF Mono', Menlo, monospace;
  color: #94A3B8;
  line-height: 1.8;
}
</style>
