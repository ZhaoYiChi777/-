<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed } from 'vue'
import { useBackend } from '../composables/useBackend'

const { backendStatus, checkBackendStatus, startBackend } = useBackend()
const isDialogVisible = ref(false)
const isRefreshing = ref(false)

// Detailed health data from /api/health
const healthData = ref<any>(null)

const statusColor = computed(() => {
  if (backendStatus.value === 'running') return '#67c23a' // green
  if (backendStatus.value === 'stopped') return '#f56c6c' // red
  return '#e6a23c' // yellow (unknown/connecting)
})

const statusText = computed(() => {
  if (backendStatus.value === 'running') return '已连接'
  if (backendStatus.value === 'stopped') return '已断开'
  return '连接中'
})

const fetchHealthDetails = async () => {
  if (backendStatus.value !== 'running') {
    healthData.value = null
    return
  }
  
  isRefreshing.value = true
  try {
    const resp = await fetch('/api/health')
    if (resp.ok) {
      const json = await resp.json()
      if (json.code === 200) {
        healthData.value = json.data
      }
    }
  } catch (e) {
    console.error('[BackendStatus] Failed to fetch health details:', e)
  } finally {
    isRefreshing.value = false
  }
}

const formatMemory = (bytes: number) => {
  if (!bytes) return '0 MB'
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB'
}

const formatUptime = (ms: number) => {
  if (!ms) return '0秒'
  const seconds = Math.floor(ms / 1000)
  const minutes = Math.floor(seconds / 60)
  const hours = Math.floor(minutes / 60)
  
  if (hours > 0) {
    return `${hours}小时 ${minutes % 60}分钟`
  } else if (minutes > 0) {
    return `${minutes}分钟 ${seconds % 60}秒`
  }
  return `${seconds}秒`
}

const handleReconnect = async () => {
  isRefreshing.value = true
  await checkBackendStatus()
  if (backendStatus.value !== 'running') {
    await startBackend()
  }
  await fetchHealthDetails()
  isRefreshing.value = false
}

// Open details and refresh
const showDetails = async () => {
  isDialogVisible.value = true
  await fetchHealthDetails()
}

// Polling for details if dialog is open
let detailTimer: any = null

onMounted(() => {
  detailTimer = setInterval(() => {
    if (isDialogVisible.value && backendStatus.value === 'running') {
      fetchHealthDetails()
    }
  }, 5000)
})

onUnmounted(() => {
  if (detailTimer) clearInterval(detailTimer)
})
</script>

<template>
  <div class="backend-status-container">
    <!-- Status indicator pill in header -->
    <div class="status-pill" @click="showDetails">
      <span class="status-dot" :style="{ backgroundColor: statusColor }"></span>
      <span class="status-label">{{ statusText }}</span>
    </div>

    <!-- Health detail popover dialog -->
    <el-dialog
      v-model="isDialogVisible"
      title="本地引擎状态监控"
      width="440px"
      append-to-body
      class="health-dialog"
    >
      <div v-loading="isRefreshing" class="health-details">
        <div class="status-header">
          <div class="status-indicator">
            <span class="large-dot" :style="{ backgroundColor: statusColor }"></span>
            <div>
              <div class="status-main">{{ backendStatus === 'running' ? '后台服务运行正常' : '后台服务未运行' }}</div>
              <div class="status-sub">端口: 8080 | 协议: HTTP REST</div>
            </div>
          </div>
        </div>

        <el-divider />

        <!-- Info Grid -->
        <div class="info-grid">
          <div class="info-row">
            <span class="info-label">系统版本</span>
            <span class="info-val">{{ healthData?.version || '1.0.0' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">数据库连接</span>
            <span class="info-val" :class="{ 'text-success': healthData?.database === 'CONNECTED', 'text-error': healthData?.database !== 'CONNECTED' }">
              {{ healthData?.database || (backendStatus === 'running' ? '测试中' : '未连接') }}
            </span>
          </div>
          <div class="info-row">
            <span class="info-label">运行时间</span>
            <span class="info-val">{{ healthData?.jvm?.uptimeMs ? formatUptime(healthData.jvm.uptimeMs) : '0秒' }}</span>
          </div>
        </div>

        <el-divider v-if="healthData?.jvm" />

        <!-- JVM Memory Details -->
        <div v-if="healthData?.jvm" class="memory-section">
          <h4 class="section-title">JVM 内存占用</h4>
          <div class="mem-progress-container">
            <el-progress
              :percentage="Math.round(((healthData.jvm.totalMemoryBytes - healthData.jvm.freeMemoryBytes) / healthData.jvm.maxMemoryBytes) * 100)"
              :stroke-width="12"
              color="#00f2fe"
            />
          </div>
          <div class="mem-details">
            <div>
              <span>已用:</span> <strong>{{ formatMemory(healthData.jvm.totalMemoryBytes - healthData.jvm.freeMemoryBytes) }}</strong>
            </div>
            <div>
              <span>分配:</span> <strong>{{ formatMemory(healthData.jvm.totalMemoryBytes) }}</strong>
            </div>
            <div>
              <span>最大限制:</span> <strong>{{ formatMemory(healthData.jvm.maxMemoryBytes) }}</strong>
            </div>
          </div>
        </div>

        <!-- Stopped status placeholder -->
        <div v-else-if="backendStatus !== 'running'" class="stopped-placeholder">
          <el-icon :size="40" class="warning-icon"><Warning /></el-icon>
          <p>未能拉取详细监控指标。服务可能被关闭、崩溃或正被占用端口。</p>
        </div>
      </div>

      <template #footer>
        <span class="dialog-footer">
          <el-button @click="isDialogVisible = false">关闭</el-button>
          <el-button type="primary" :loading="isRefreshing" @click="handleReconnect">
            重新连接
          </el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.backend-status-container {
  display: inline-block;
}

.status-pill {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid rgba(255, 255, 255, 0.1);
  padding: 4px 12px;
  border-radius: 20px;
  cursor: pointer;
  transition: all 0.3s;
}

.status-pill:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(0, 242, 254, 0.4);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  box-shadow: 0 0 6px currentColor;
}

.status-label {
  font-size: 13px;
  font-weight: 500;
  color: #a0a6b5;
}

.status-pill:hover .status-label {
  color: #ffffff;
}

/* Dialog styles */
:deep(.health-dialog) {
  background: #151720 !important;
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
}
:deep(.health-dialog .el-dialog__title) {
  color: #ffffff;
  font-weight: 600;
}
:deep(.health-dialog .el-dialog__body) {
  color: #b0b5c1;
}

.status-header {
  padding-top: 8px;
}

.status-indicator {
  display: flex;
  align-items: center;
  gap: 16px;
}

.large-dot {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  box-shadow: 0 0 10px currentColor;
}

.status-main {
  font-size: 16px;
  font-weight: 600;
  color: #ffffff;
}

.status-sub {
  font-size: 13px;
  color: #8a8f98;
  margin-top: 2px;
}

.info-grid {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.info-row {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
}

.info-label {
  color: #8a8f98;
}

.info-val {
  color: #ffffff;
  font-weight: 500;
}

.text-success {
  color: #67c23a !important;
}

.text-error {
  color: #f56c6c !important;
}

.memory-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.section-title {
  margin: 0 0 4px 0;
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
}

.mem-progress-container {
  margin: 4px 0;
}

.mem-details {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #8a8f98;
  margin-top: 4px;
}

.mem-details strong {
  color: #ffffff;
}

.stopped-placeholder {
  text-align: center;
  padding: 20px 0;
}

.warning-icon {
  color: #e6a23c;
  margin-bottom: 12px;
}

.stopped-placeholder p {
  font-size: 13px;
  color: #8a8f98;
  line-height: 1.6;
}

:deep(.el-divider) {
  border-top-color: rgba(255, 255, 255, 0.08);
  margin: 16px 0;
}

:deep(.el-dialog__footer) {
  border-top: 1px solid rgba(255, 255, 255, 0.08);
  padding: 12px 20px;
}
</style>
