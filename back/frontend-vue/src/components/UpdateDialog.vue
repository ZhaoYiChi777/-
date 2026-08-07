<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { isTauri } from '../composables/useTauri'
import { ElMessage, ElMessageBox } from 'element-plus'

const isVisible = ref(false)
const checkLoading = ref(false)
const downloadProgress = ref(0)
const statusText = ref('发现新版本，准备更新')
const isDownloading = ref(false)
const updateFinished = ref(false)

// Update details
const currentVersion = ref('1.0.0')
const newVersion = ref('')
const releaseNotes = ref('')
const hasUpdate = ref(false)

let updateObj: any = null

const checkForUpdates = async (manual = false) => {
  if (!isTauri()) {
    if (manual) ElMessage.info('自动更新仅支持桌面应用')
    return
  }

  checkLoading.value = true
  try {
    const { getVersion } = await import('@tauri-apps/api/app')
    currentVersion.value = await getVersion()

    const { check } = await import('@tauri-apps/plugin-updater')
    const update = await check()
    
    if (update) {
      updateObj = update
      newVersion.value = update.version
      releaseNotes.value = update.body || '无更新说明'
      hasUpdate.value = true
      isVisible.value = true
    } else {
      hasUpdate.value = false
      if (manual) {
        ElMessage.success('当前已是最新版本')
      }
    }
  } catch (e: any) {
    console.error('[Update] Check for updates failed:', e)
    if (manual) {
      ElMessage.error('检查更新失败: ' + e.message)
    }
  } finally {
    checkLoading.value = false
  }
}

const handleStartUpdate = async () => {
  if (!updateObj) return
  
  isDownloading.value = true
  statusText.value = '正在下载更新程序...'
  downloadProgress.value = 0
  
  try {
    let downloaded = 0
    let contentLength = 0

    await updateObj.downloadAndInstall((event: any) => {
      switch (event.event) {
        case 'Started':
          contentLength = event.data.contentLength || 0
          statusText.value = '开始下载更新...'
          break
        case 'Progress':
          downloaded += event.data.chunkLength
          if (contentLength > 0) {
            downloadProgress.value = Math.round((downloaded / contentLength) * 100)
          } else {
            downloadProgress.value = 50 // fallback if no content length
          }
          statusText.value = `正在下载: ${downloadProgress.value}%`
          break
        case 'Finished':
          statusText.value = '下载完成，正在解压安装...'
          downloadProgress.value = 100
          break
      }
    })

    updateFinished.value = true
    statusText.value = '安装成功！应用需要重启以应用更新。'
    
    ElMessageBox.confirm('更新安装成功，是否立即重启应用？', '更新就绪', {
      confirmButtonText: '立即重启',
      cancelButtonText: '稍后重启',
      type: 'success'
    }).then(async () => {
      await handleRelaunch()
    }).catch(() => {
      isVisible.value = false
    })

  } catch (e: any) {
    console.error('[Update] Installation failed:', e)
    ElMessage.error('下载安装失败: ' + e.message)
    isDownloading.value = false
  }
}

const handleRelaunch = async () => {
  try {
    const { relaunch } = await import('@tauri-apps/plugin-process')
    await relaunch()
  } catch (e: any) {
    console.error('[Update] Relaunch failed:', e)
    ElMessage.error('重启应用失败，请手动重启。')
  }
}

const handleCheckEvent = (e: any) => {
  checkForUpdates(e.detail?.manual ?? false)
}

onMounted(() => {
  window.addEventListener('tauri-check-update', handleCheckEvent)

  // Check settings if user wants to auto check update on startup
  const saved = localStorage.getItem('desktopSettings')
  if (saved) {
    try {
      const parsed = JSON.parse(saved)
      if (parsed.checkUpdateFrequency === 'startup') {
        // Delay checking slightly to prioritize main app loading
        setTimeout(() => checkForUpdates(false), 5000)
      }
    } catch {}
  } else {
    // Default: check on startup
    setTimeout(() => checkForUpdates(false), 5000)
  }
})

onUnmounted(() => {
  window.removeEventListener('tauri-check-update', handleCheckEvent)
})

// Expose checks
defineExpose({
  checkForUpdates,
  checkLoading,
  hasUpdate
})
</script>

<template>
  <el-dialog
    v-model="isVisible"
    title="软件更新提示"
    width="500px"
    class="update-dialog"
    :close-on-click-modal="!isDownloading"
    :close-on-press-escape="!isDownloading"
    :show-close="!isDownloading"
    append-to-body
  >
    <div class="update-body">
      <div class="versions">
        <div class="v-box">
          <span class="v-label">当前版本</span>
          <span class="v-num">{{ currentVersion }}</span>
        </div>
        <div class="arrow">➔</div>
        <div class="v-box">
          <span class="v-label">最新版本</span>
          <span class="v-num highlight">{{ newVersion }}</span>
        </div>
      </div>

      <div class="changelog-section">
        <h4>更新内容说明：</h4>
        <div class="changelog-content">
          <pre>{{ releaseNotes }}</pre>
        </div>
      </div>

      <div v-if="isDownloading" class="progress-section">
        <div class="progress-status">{{ statusText }}</div>
        <el-progress
          :percentage="downloadProgress"
          :stroke-width="12"
          color="#00f2fe"
          striped
          striped-flow
        />
      </div>
    </div>

    <template #footer>
      <div class="dialog-footer">
        <template v-if="!isDownloading">
          <el-button @click="isVisible = false">稍后提醒</el-button>
          <el-button type="primary" class="update-btn" @click="handleStartUpdate">立即更新</el-button>
        </template>
        <template v-else>
          <el-button v-if="updateFinished" type="success" @click="handleRelaunch">立即重启</el-button>
          <el-button v-else disabled>正在升级，请勿关闭...</el-button>
        </template>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
:deep(.update-dialog) {
  background: #151720 !important;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
}
:deep(.update-dialog .el-dialog__title) {
  color: #ffffff;
  font-weight: 600;
}

.update-body {
  display: flex;
  flex-direction: column;
  gap: 20px;
  color: #b0b5c1;
}

.versions {
  display: flex;
  justify-content: space-around;
  align-items: center;
  background: rgba(255, 255, 255, 0.03);
  padding: 16px;
  border-radius: 8px;
  border: 1px solid rgba(255, 255, 255, 0.05);
}

.v-box {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.v-label {
  font-size: 12px;
  color: #8a8f98;
}

.v-num {
  font-size: 20px;
  font-weight: bold;
  color: #ffffff;
}

.v-num.highlight {
  color: #00f2fe;
}

.arrow {
  font-size: 24px;
  color: #8a8f98;
}

.changelog-section h4 {
  margin: 0 0 8px 0;
  font-size: 14px;
  color: #ffffff;
}

.changelog-content {
  background: rgba(0, 0, 0, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.05);
  border-radius: 6px;
  padding: 12px;
  max-height: 180px;
  overflow-y: auto;
}

.changelog-content pre {
  margin: 0;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.progress-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 10px;
}

.progress-status {
  font-size: 13px;
  color: #00f2fe;
  font-weight: 500;
}

.update-btn {
  background: linear-gradient(135deg, #00f2fe 0%, #0072ff 100%);
  border: none;
  font-weight: 600;
}

.update-btn:hover {
  box-shadow: 0 0 12px rgba(0, 242, 254, 0.4);
}

:deep(.el-progress-bar__outer) {
  background-color: rgba(255, 255, 255, 0.1) !important;
}
</style>
