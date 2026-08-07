<script setup lang="ts">
import { ref, onErrorCaptured } from 'vue'
import { ElMessage } from 'element-plus'
import { isTauri } from '../composables/useTauri'
import { reportError } from '../utils/errorBus'

const hasError = ref(false)
const errorMessage = ref('')
const errorType = ref<'network' | 'server' | 'unknown'>('unknown')
const renderKey = ref(0)
const autoRetrying = ref(false)
const retryCount = ref(0)

const MAX_AUTO_RETRIES = 3

function classifyError(err: Error): 'network' | 'server' | 'unknown' {
  const msg = err.message?.toLowerCase() || ''
  // axios 网络错误：ECONNABORTED / Network Error / timeout
  if (
    msg.includes('network error') ||
    msg.includes('timeout') ||
    msg.includes('econnaborted') ||
    msg.includes('net::err') ||
    msg.includes('failed to fetch')
  ) {
    return 'network'
  }
  // axios 服务端错误：status >= 500
  const axiosErr = err as any
  if (axiosErr?.response?.status && axiosErr.response.status >= 500) {
    return 'server'
  }
  return 'unknown'
}

function getDisplayMessage(type: 'network' | 'server' | 'unknown'): string {
  switch (type) {
    case 'network':
      return '网络连接失败，请检查网络后重试'
    case 'server':
      return '服务暂时不可用，请稍后重试'
    case 'unknown':
      return '发生了意外错误，请刷新页面重试'
  }
}

function getResultIcon(type: 'network' | 'server' | 'unknown'): 'error' | 'warning' {
  return type === 'unknown' ? 'warning' : 'error'
}

function resetError() {
  hasError.value = false
  errorMessage.value = ''
  errorType.value = 'unknown'
  retryCount.value = 0
  autoRetrying.value = false
  renderKey.value++
}

async function autoRetryNetworkError(_err: Error) {
  if (retryCount.value >= MAX_AUTO_RETRIES) {
    autoRetrying.value = false
    return
  }
  autoRetrying.value = true
  const delay = Math.pow(2, retryCount.value) * 1000 // 1s, 2s, 4s
  retryCount.value++
  await new Promise((resolve) => setTimeout(resolve, delay))
  if (!hasError.value) return // 已被手动重置
  // 重试：重新渲染 slot
  renderKey.value++
  hasError.value = false
  autoRetrying.value = false
}

onErrorCaptured((err: Error) => {
  const type = classifyError(err)
  errorType.value = type
  errorMessage.value = getDisplayMessage(type)
  hasError.value = true

  reportError(err, 'ErrorBoundary')

  if (type === 'network') {
    autoRetryNetworkError(err)
  }

  // 返回 false 阻止错误继续向上传播
  return false
})

async function openLogDir() {
  if (isTauri()) {
    try {
      const { openUrl } = await import('../composables/useTauri')
      // Tauri 应用日志目录通常在 appDataDir 下
      const { appLogDir } = await import('@tauri-apps/api/path')
      const logPath = await appLogDir()
      await openUrl(logPath)
    } catch (e) {
      ElMessage.error('无法打开日志目录')
    }
  } else {
    ElMessage.info('请在浏览器控制台中查看错误日志（F12 → Console）')
  }
}

defineExpose({ resetError })
</script>

<template>
  <div v-if="hasError" class="error-boundary">
    <el-result
      :icon="getResultIcon(errorType)"
      :title="errorMessage"
      :sub-title="autoRetrying ? `正在自动重试（第 ${retryCount} 次）...` : ''"
    >
      <template #extra>
        <el-button type="primary" :loading="autoRetrying" @click="resetError">
          重试
        </el-button>
        <el-button @click="openLogDir">
          查看日志
        </el-button>
      </template>
    </el-result>
  </div>
  <slot v-else :key="renderKey" />
</template>

<style scoped>
.error-boundary {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
  padding: 24px;
}
</style>
