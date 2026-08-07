<template>
  <div
    class="upload-page"
    @dragenter.prevent="onDragEnter"
    @dragover.prevent="onDragOver"
    @dragleave.prevent="onDragLeave"
    @drop.prevent="onDrop"
  >
    <!-- 拖拽覆盖层 -->
    <Transition name="drop-overlay">
      <div v-if="isDragging" class="drop-zone-overlay">
        <div class="drop-zone-content">
          <el-icon :size="72" color="#409EFF"><UploadFilled /></el-icon>
          <h2>释放文件以上传</h2>
          <p>支持 PDF、Word、Excel、PPT、图片、TXT、Markdown 等格式</p>
        </div>
      </div>
    </Transition>

    <el-card>
      <template #header><span>资料上传</span></template>
      <el-form :model="form" label-width="120px">
        <el-form-item label="目标库">
          <el-select v-model="form.library" placeholder="选择目标库" style="width: 100%">
            <el-option label="研究报告库" value="report" />
            <el-option label="动态信息库" value="dynamic" />
            <el-option label="译丛译著库" value="translation" />
            <el-option label="图表库" value="chart" />
            <el-option label="政策文件库" value="policy" />
            <el-option label="新闻资讯库" value="news" />
          </el-select>
        </el-form-item>
        <el-form-item label="一级分类">
          <el-select v-model="form.categoryL1" placeholder="选择分类" clearable style="width: 100%">
            <el-option label="政治" value="政治" />
            <el-option label="军事" value="军事" />
            <el-option label="经济" value="经济" />
            <el-option label="科技" value="科技" />
            <el-option label="社会" value="社会" />
            <el-option label="文化" value="文化" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item label="资料类型">
          <el-select v-model="form.docType" placeholder="选择类型" style="width: 100%">
            <el-option label="研究报告" value="report" />
            <el-option label="动态信息" value="dynamic" />
            <el-option label="图书" value="translation" />
            <el-option label="图表数据" value="chart" />
            <el-option label="政策文件" value="policy" />
            <el-option label="新闻资讯" value="news" />
          </el-select>
        </el-form-item>
        <el-form-item label="AI智能分类">
          <el-switch v-model="form.autoClassify" />
          <span style="margin-left: 12px; color: #909399; font-size: 12px;">
            启用后AI将自动分析文档内容并推荐分类
          </span>
        </el-form-item>
        <el-form-item label="来源信息">
          <el-input v-model="form.sourceOrigin" placeholder="可选：填写来源信息，如论文名、Blog URL、报告出处等" clearable />
          <div style="font-size: 12px; color: #909399; margin-top: 4px;">
            若来自论文或Blog，请注明出处。留空则系统自动从文件名提取。
          </div>
        </el-form-item>
        <el-form-item label="上传文件">
          <div v-if="isTauriEnv" style="margin-bottom: 8px;">
            <el-button type="primary" plain @click="pickFiles">
              <el-icon><FolderOpened /></el-icon> 选择文件（系统对话框）
            </el-button>
            <span v-if="files.length > 0" style="margin-left: 8px; color: #67C23A; font-size: 13px;">已选择 {{ files.length }} 个文件</span>
          </div>
          <div
            class="custom-drop-zone"
            :class="{ 'is-drag-hover': dragHoverZone }"
            @dragenter.prevent="dragHoverZone = true"
            @dragleave.prevent="dragHoverZone = false"
            @drop.prevent.stop
          >
            <el-upload
              ref="uploadRef"
              :auto-upload="false"
              :on-change="handleFileChange"
              :on-remove="handleFileRemove"
              :limit="50"
              multiple
              drag
              :accept="acceptTypes"
              class="full-width-upload"
            >
              <div class="upload-trigger-content">
                <el-icon :size="52" :color="dragHoverZone ? '#409EFF' : '#c0c4cc'"><UploadFilled /></el-icon>
                <div class="upload-text">
                  <span>拖拽文件到此处或</span>
                  <em>点击上传</em>
                </div>
                <div class="upload-subtext">支持批量上传，单文件最大 100MB</div>
              </div>
            </el-upload>
          </div>
          <!-- 已选文件列表 -->
          <div v-if="files.length > 0" class="file-list">
            <el-tag
              v-for="(f, i) in files"
              :key="i"
              closable
              :type="getFileTagType(f.name)"
              @close="removeFile(i)"
              style="margin: 4px;"
            >
              {{ f.name }}
            </el-tag>
          </div>
          <div class="el-upload__tip">
            支持格式：PDF、Word(.doc/.docx)、WPS文字(.wps)、Excel(.xls/.xlsx)、WPS表格(.et)、PPT(.ppt/.pptx)、WPS演示(.dps)、TXT、Markdown、图片（JPG/PNG/GIF/TIFF/WebP/SVG/HEIC等），单个文件不超过100MB
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="submitUpload" :loading="uploading" size="large">
            <el-icon><Upload /></el-icon> 开始上传（{{ files.length }} 个文件）
          </el-button>
          <el-button @click="resetForm" size="large">重置</el-button>
        </el-form-item>
      </el-form>
      <el-divider />
      <div v-if="results.length > 0">
        <h4>上传结果</h4>
        <el-table :data="results" stripe style="width: 100%">
          <el-table-column prop="filename" label="文件名" width="180" />
          <el-table-column prop="status" label="状态" width="90">
            <template #default="scope">
              <el-tag :type="getStatusType(scope.row.status)">{{ getStatusText(scope.row.status) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="library" label="目标库" width="110" />
          <el-table-column prop="category" label="分类" width="80" />
          <el-table-column prop="entry_count" label="词条数" width="80">
            <template #default="scope">
              <span v-if="scope.row.entry_count >= 0">{{ scope.row.entry_count }}</span>
              <el-tag v-else type="danger" size="small">失败</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="信息" min-width="140">
            <template #default="scope">
              <span v-if="scope.row.message" style="color: #f56c6c; font-size: 12px;">{{ scope.row.message }}</span>
              <span v-else style="color: #909399;">-</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="140" fixed="right">
            <template #default="scope">
              <el-button
                v-if="scope.row.status === 'error' || scope.row.entry_count < 0"
                type="warning"
                size="small"
                link
                :loading="scope.row._retrying"
                @click="handleRetry(scope.row, scope.$index)"
              >
                重试
              </el-button>
              <el-button
                type="danger"
                size="small"
                link
                :loading="scope.row._deleting"
                @click="handleDelete(scope.row, scope.$index)"
              >
                删除
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { UploadFilled, FolderOpened, Upload } from '@element-plus/icons-vue'
import { uploadFile, deleteDocument, reExtractDocument } from '../../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { UploadFile } from 'element-plus'
import { isTauri, openFileAsFiles, DOC_FILTERS, sendNotification } from '../../composables/useTauri'

const isTauriEnv = isTauri()

async function pickFiles() {
  const selected = await openFileAsFiles({ multiple: true, filters: DOC_FILTERS })
  files.value.push(...selected)
}

const form = reactive({
  categoryL1: '',
  categoryL2: '',
  docType: 'report',
  library: 'report',
  autoClassify: false,
  sourceOrigin: ''
})

// 自动同步 docType 和 library（用户选图表库时docType也设为chart）
watch(() => form.library, (val) => { form.docType = val })

const files = ref<File[]>([])
const uploading = ref(false)
const results = ref<any[]>([])

// 拖拽状态
const isDragging = ref(false)
const dragHoverZone = ref(false)
let dragCounter = 0

// 支持的文件类型（含WPS格式和更多图片格式）
const acceptTypes = '.pdf,.doc,.docx,.wps,.xls,.xlsx,.et,.ppt,.pptx,.dps,.odt,.ods,.odp,.txt,.md,.markdown,.rtf,.csv,.jpg,.jpeg,.png,.gif,.bmp,.webp,.tiff,.tif,.svg,.ico,.heic,.heif,.avif'

const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'tiff', 'tif', 'svg', 'ico', 'heic', 'heif', 'avif']

function isImageFile(filename: string): boolean {
  const ext = filename.split('.').pop()?.toLowerCase() || ''
  return IMAGE_EXTENSIONS.includes(ext)
}

function getFileTagType(filename: string): string {
  return isImageFile(filename) ? 'success' : ''
}

function removeFile(index: number) {
  files.value.splice(index, 1)
}

// 全页面拖拽支持
function onDragEnter(e: DragEvent) {
  dragCounter++
  if (e.dataTransfer?.types.includes('Files')) {
    isDragging.value = true
  }
}

function onDragOver(e: DragEvent) {
  if (e.dataTransfer) {
    e.dataTransfer.dropEffect = 'copy'
  }
}

function onDragLeave(_e: DragEvent) {
  dragCounter--
  if (dragCounter <= 0) {
    dragCounter = 0
    isDragging.value = false
  }
}

function onDrop(e: DragEvent) {
  isDragging.value = false
  dragCounter = 0
  dragHoverZone.value = false

  const droppedFiles = e.dataTransfer?.files
  if (!droppedFiles || droppedFiles.length === 0) return

  // 智能检测：如果全是图片文件且当前库不是图表库，自动切换
  const allImages = Array.from(droppedFiles).every(f => isImageFile(f.name))
  if (allImages && form.library !== 'chart') {
    // 提示用户是否切换到图表库
    ElMessage.info('检测到图片文件，已自动切换到图表库')
    form.library = 'chart'
  }

  // 将拖拽的文件添加到列表
  for (let i = 0; i < droppedFiles.length; i++) {
    const file = droppedFiles[i]
    // 检查文件扩展名
    const ext = file.name.split('.').pop()?.toLowerCase() || ''
    const allExtensions = acceptTypes.split(',').map(t => t.replace('.', '').trim()).filter(Boolean)
    if (allExtensions.includes(ext)) {
      // 避免重复添加
      if (!files.value.some(f => f.name === file.name && f.size === file.size)) {
        files.value.push(file)
      }
    } else {
      ElMessage.warning(`不支持的文件类型: ${file.name}`)
    }
  }

  ElMessage.success(`已添加 ${droppedFiles.length} 个文件`)
}

function handleFileChange(file: UploadFile) {
  if (file.raw) {
    // 避免重复添加
    if (!files.value.some(f => f.name === file.raw!.name && f.size === file.raw!.size)) {
      files.value.push(file.raw)
    }
  }
}

function handleFileRemove(file: UploadFile) {
  const index = files.value.findIndex(f => f.name === file.name)
  if (index > -1) {
    files.value.splice(index, 1)
  }
}

function getStatusType(status: string) {
  switch (status) {
    case 'success': return 'success'
    case 'skipped': return 'warning'
    case 'error': return 'danger'
    default: return 'info'
  }
}

function getStatusText(status: string) {
  switch (status) {
    case 'success': return '成功'
    case 'skipped': return '跳过'
    case 'error': return '失败'
    default: return status
  }
}

async function handleRetry(row: any, _index: number) {
  if (!row.id) {
    ElMessage.warning('无法重试：缺少文档ID')
    return
  }
  row._retrying = true
  try {
    const res = await reExtractDocument(row.id)
    row.status = 'success'
    row.entry_count = res.data.entry_count || 0
    row.message = '重试成功'
    ElMessage.success(`重试成功：${row.filename}，提取 ${row.entry_count} 个词条`)
  } catch (e: any) {
    row.message = '重试失败: ' + (e.response?.data?.message || e.message)
    ElMessage.error(`重试失败: ${row.filename}`)
  }
  row._retrying = false
}

async function handleDelete(row: any, index: number) {
  if (!row.id) {
    results.value.splice(index, 1)
    return
  }
  try {
    await ElMessageBox.confirm(`确定删除「${row.filename}」吗？相关词条也将被删除。`, '确认删除', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消'
    })
  } catch {
    return // 用户取消
  }
  row._deleting = true
  try {
    await deleteDocument(row.id)
    results.value.splice(index, 1)
    ElMessage.success(`已删除: ${row.filename}`)
  } catch (e: any) {
    ElMessage.error(`删除失败: ${row.filename} - ${e.response?.data?.message || e.message}`)
  }
  row._deleting = false
}

async function submitUpload() {
  if (files.value.length === 0) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  results.value = []

  // 使用批量上传API
  const formData = new FormData()
  files.value.forEach(file => {
    formData.append('files', file)
  })
  formData.append('categoryL1', form.categoryL1)
  formData.append('categoryL2', form.categoryL2)
  formData.append('docType', form.docType)
  formData.append('library', form.library)
  formData.append('autoClassify', String(form.autoClassify))
  formData.append('sourceOrigin', form.sourceOrigin)

  try {
    const res = await uploadFile(formData, '/upload/batch')
    results.value = res.data.results || []
    ElMessage.success(`上传完成：共${res.data.total}个文件`)
    sendNotification('文件上传完成', `成功处理 ${res.data.total} 个文件`)
  } catch (e: any) {
    ElMessage.error('上传失败: ' + e.message)
  }

  uploading.value = false
}

function resetForm() {
  files.value = []
  results.value = []
  form.categoryL1 = ''
  form.categoryL2 = ''
  form.docType = 'report'
  form.library = 'report'
  form.autoClassify = false
}
</script>

<style scoped>
.upload-page {
  padding: 20px;
  max-width: 900px;
  margin: 0 auto;
  position: relative;
}

/* ========== 全页面拖拽覆盖层 ========== */
.drop-zone-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(64, 158, 255, 0.08);
  backdrop-filter: blur(4px);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 3px dashed #409EFF;
  margin: 8px;
  border-radius: 16px;
}

.drop-zone-content {
  text-align: center;
  pointer-events: none;
  user-select: none;
}

.drop-zone-content h2 {
  font-size: 28px;
  color: #409EFF;
  margin: 16px 0 8px;
  font-weight: 600;
}

.drop-zone-content p {
  font-size: 15px;
  color: #606266;
}

/* 过渡动画 */
.drop-overlay-enter-active {
  transition: opacity 0.15s ease, backdrop-filter 0.15s ease;
}
.drop-overlay-leave-active {
  transition: opacity 0.2s ease, backdrop-filter 0.2s ease;
}
.drop-overlay-enter-from,
.drop-overlay-leave-to {
  opacity: 0;
}

/* ========== 上传区域增强 ========== */
.custom-drop-zone {
  width: 100%;
  border-radius: 8px;
  transition: all 0.2s ease;
}

.custom-drop-zone.is-drag-hover {
  transform: scale(1.01);
}

.full-width-upload {
  width: 100%;
}

.full-width-upload :deep(.el-upload-dragger) {
  padding: 36px 0;
  width: 100%;
  min-height: 160px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: 2px dashed var(--el-border-color);
  border-radius: 8px;
  transition: all 0.25s ease;
  cursor: pointer;
}

.full-width-upload :deep(.el-upload-dragger):hover {
  border-color: #409EFF;
  background: rgba(64, 158, 255, 0.03);
}

.custom-drop-zone.is-drag-hover :deep(.el-upload-dragger) {
  border-color: #409EFF;
  background: rgba(64, 158, 255, 0.06);
  box-shadow: 0 0 0 4px rgba(64, 158, 255, 0.1);
}

.upload-trigger-content {
  text-align: center;
}

.upload-text {
  margin-top: 12px;
  font-size: 15px;
  color: #606266;
}

.upload-text em {
  color: #409EFF;
  font-style: normal;
  font-weight: 500;
}

.upload-subtext {
  margin-top: 6px;
  font-size: 13px;
  color: #909399;
}

/* ========== 文件列表 ========== */
.file-list {
  margin-top: 12px;
  padding: 8px;
  background: #fafafa;
  border-radius: 6px;
  min-height: 20px;
}

/* ========== 提示文字 ========== */
.el-upload__tip {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
