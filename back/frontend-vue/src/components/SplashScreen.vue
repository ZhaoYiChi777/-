<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useBackend } from '../composables/useBackend'

const emit = defineEmits(['ready'])

const { backendStatus, startBackend, checkBackendStatus } = useBackend()

const isVisible = ref(true)
const statusText = ref('正在启动后端服务')
const showError = ref(false)
const errorMessage = ref('')
const dots = ref('.')

// 启动时间戳，用于超时计算
const startTime = ref(0)
const TIMEOUT_MS = 30000 // 30 秒

let dotsInterval: ReturnType<typeof setInterval> | undefined = undefined
let timeoutTimer: ReturnType<typeof setTimeout> | undefined = undefined

// 动态加载点动画
const startDotsAnimation = () => {
  dotsInterval = setInterval(() => {
    dots.value = dots.value.length >= 3 ? '.' : dots.value + '.'
  }, 500)
}

const clearDotsAnimation = () => {
  if (dotsInterval) {
    clearInterval(dotsInterval)
    dotsInterval = undefined
  }
}

// 显示超时错误
const showTimeoutError = () => {
  showError.value = true
  statusText.value = '启动超时'
  errorMessage.value =
    '本地后台服务在30秒内未响应。请检查系统是否安装了 Java 17/21+ 运行环境，并确保没有其他进程占用端口 8080。'
  clearDotsAnimation()
}

// 显示启动失败错误
const showStoppedError = () => {
  showError.value = true
  statusText.value = '后端服务未启动'
  errorMessage.value =
    '后台服务未运行或已异常退出。请检查系统是否安装了 Java 17/21+ 运行环境，并确保没有其他进程占用端口 8080。点击下方按钮尝试重新启动。'
  clearDotsAnimation()
}

// 超时检查：根据已过时间判断是否超时
const startTimeout = () => {
  clearTimeout(timeoutTimer)
  const elapsed = startTime.value > 0 ? Date.now() - startTime.value : 0
  const remaining = Math.max(0, TIMEOUT_MS - elapsed)

  timeoutTimer = setTimeout(() => {
    if (backendStatus.value !== 'running' && !showError.value) {
      showTimeoutError()
    }
  }, remaining)
}

// 重置超时状态（重试时调用）
const resetTimeout = () => {
  showError.value = false
  errorMessage.value = ''
  statusText.value = '正在重新启动后端服务'
  startTime.value = Date.now()
  startDotsAnimation()
  startTimeout()
}

// 跳过启动等待，直接进入系统
const handleSkip = () => {
  clearDotsAnimation()
  clearTimeout(timeoutTimer)
  isVisible.value = false
  emit('ready')
}

// 重试按钮处理
const handleRetry = async () => {
  resetTimeout()
  const started = await startBackend()
  if (!started) {
    errorMessage.value = '发送启动命令失败。请手动检查 Spring Boot 后端是否在运行。'
    showError.value = true
    clearDotsAnimation()
  }
}

// 监听后端状态变化
watch(
  backendStatus,
  (newStatus) => {
    if (newStatus === 'running') {
      statusText.value = '引擎就绪，正在载入数据'
      clearDotsAnimation()
      clearTimeout(timeoutTimer)
      // 平滑过渡后隐藏
      setTimeout(() => {
        isVisible.value = false
        emit('ready')
      }, 800)
    } else if (newStatus === 'stopped') {
      // 后端停止：如果已超时则显示超时错误，否则显示停止错误
      if (!showError.value) {
        const elapsed = startTime.value > 0 ? Date.now() - startTime.value : 0
        if (elapsed >= TIMEOUT_MS) {
          showTimeoutError()
        } else {
          showStoppedError()
        }
      }
    }
    // unknown 状态保持加载动画，由超时机制兜底
  },
  { immediate: true },
)

onMounted(() => {
  startTime.value = Date.now()
  startDotsAnimation()
  startTimeout()
  // 初始检查并尝试启动后端
  checkBackendStatus().then(async (status) => {
    if (status !== 'running') {
      statusText.value = '正在启动后端服务'
      await startBackend()
    }
  })
})

onUnmounted(() => {
  clearDotsAnimation()
  clearTimeout(timeoutTimer)
})
</script>

<template>
  <Transition name="fade">
    <div v-if="isVisible" class="splash-container">
      <div class="splash-content">
        <!-- 科技感 Logo -->
        <div class="logo-wrapper">
          <svg viewBox="0 0 100 100" class="logo-svg">
            <!-- 背景圆环 -->
            <circle cx="50" cy="50" r="45" class="circle-bg" />
            <circle cx="50" cy="50" r="35" class="circle-glow" />

            <!-- 脑网络路径 -->
            <path d="M 30,50 L 50,30 L 70,50 L 50,70 Z" class="network-path" />
            <path d="M 35,35 L 65,65" class="network-path" />
            <path d="M 35,65 L 65,35" class="network-path" />

            <!-- 节点 -->
            <circle cx="50" cy="30" r="4" class="node cyan" />
            <circle cx="70" cy="50" r="4" class="node blue" />
            <circle cx="50" cy="70" r="4" class="node purple" />
            <circle cx="30" cy="50" r="4" class="node pink" />
            <circle cx="50" cy="50" r="6" class="node center-glow" />
          </svg>
        </div>

        <h1 class="app-title">智能情报分析平台</h1>
        <p class="app-subtitle">无人信息领域智能决策支撑系统</p>

        <!-- 加载动画区域 -->
        <div v-if="!showError" class="loader-section">
          <div class="spinner"></div>
          <div class="status-msg">
            {{ statusText }}{{ dots }}
          </div>
          <el-button class="skip-waiting-btn" text size="small" @click="handleSkip">
            跳过等待，直接进入
          </el-button>
        </div>

        <!-- 错误/超时区域 -->
        <Transition name="slide-up">
          <div v-if="showError" class="error-section">
            <el-icon class="error-icon" :size="40">
              <WarningFilled />
            </el-icon>
            <h3 class="error-title">启动失败</h3>
            <p class="error-desc">{{ errorMessage }}</p>

            <div class="action-buttons">
              <el-button type="primary" class="retry-btn" @click="handleRetry">
                重试
              </el-button>
              <el-button class="skip-btn" @click="handleSkip">
                跳过，直接进入系统
              </el-button>
            </div>
          </div>
        </Transition>
      </div>

      <!-- 科技网格背景 -->
      <div class="grid-bg"></div>
    </div>
  </Transition>
</template>

<style scoped>
.splash-container {
  position: fixed;
  top: 0;
  left: 0;
  width: 100vw;
  height: 100vh;
  background: radial-gradient(circle at center, #1b1d28 0%, #0d0f15 100%);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 99999;
  overflow: hidden;
  color: #ffffff;
  font-family: 'Outfit', 'Inter', -apple-system, BlinkMacSystemFont, 'Segoe UI',
    Roboto, sans-serif;
}

.grid-bg {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-image: linear-gradient(rgba(0, 242, 254, 0.03) 1px, transparent 1px),
    linear-gradient(90deg, rgba(0, 242, 254, 0.03) 1px, transparent 1px);
  background-size: 40px 40px;
  background-position: center;
  pointer-events: none;
  z-index: 1;
}

.splash-content {
  position: relative;
  z-index: 10;
  text-align: center;
  max-width: 480px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
}

.logo-wrapper {
  width: 120px;
  height: 120px;
  margin-bottom: 24px;
  filter: drop-shadow(0 0 20px rgba(0, 242, 254, 0.3));
}

.logo-svg {
  width: 100%;
  height: 100%;
}

.circle-bg {
  fill: none;
  stroke: rgba(0, 242, 254, 0.1);
  stroke-width: 1.5;
}

.circle-glow {
  fill: none;
  stroke: rgba(0, 242, 254, 0.2);
  stroke-width: 1;
  stroke-dasharray: 4 4;
  animation: rotate 20s linear infinite;
  transform-origin: center;
}

.network-path {
  fill: none;
  stroke: rgba(255, 255, 255, 0.15);
  stroke-width: 1.5;
}

.node {
  animation: pulse-node 2s infinite ease-in-out;
}

.node.cyan {
  fill: #00f2fe;
  animation-delay: 0s;
}
.node.blue {
  fill: #0072ff;
  animation-delay: 0.5s;
}
.node.purple {
  fill: #b92b27;
  animation-delay: 1s;
}
.node.pink {
  fill: #ff007f;
  animation-delay: 1.5s;
}
.node.center-glow {
  fill: #00f2fe;
  filter: drop-shadow(0 0 8px #00f2fe);
}

.app-title {
  font-size: 28px;
  font-weight: 700;
  margin: 0 0 8px 0;
  letter-spacing: 2px;
  background: linear-gradient(135deg, #ffffff 30%, #a5b4fc 100%);
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
}

.app-subtitle {
  font-size: 14px;
  color: #8a8f98;
  margin: 0 0 40px 0;
  letter-spacing: 4px;
  text-transform: uppercase;
}

.loader-section {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.spinner {
  width: 36px;
  height: 36px;
  border: 3px solid rgba(0, 242, 254, 0.1);
  border-radius: 50%;
  border-top-color: #00f2fe;
  animation: spin 1s ease-in-out infinite;
  margin-bottom: 16px;
}

.status-msg {
  font-size: 14px;
  color: #a0a6b5;
  font-weight: 500;
  min-height: 20px;
}

/* 错误区域样式 */
.error-section {
  background: rgba(25, 15, 20, 0.6);
  border: 1px solid rgba(245, 108, 108, 0.2);
  border-radius: 12px;
  padding: 24px;
  margin-top: 10px;
  backdrop-filter: blur(8px);
  width: 100%;
}

.error-icon {
  color: #f56c6c;
  margin-bottom: 12px;
}

.error-title {
  color: #f56c6c;
  font-size: 18px;
  font-weight: 600;
  margin: 0 0 10px 0;
}

.error-desc {
  color: #b0b5c1;
  font-size: 13px;
  line-height: 1.6;
  margin: 0 0 20px 0;
  text-align: left;
}

.retry-btn {
  background: linear-gradient(135deg, #409eff 0%, #0072ff 100%);
  border: none;
  font-weight: 600;
  padding: 10px 24px;
  border-radius: 8px;
  transition: all 0.3s;
}
.retry-btn:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 114, 255, 0.4);
}

.skip-btn {
  background: transparent;
  border: 1px solid rgba(160, 166, 181, 0.3);
  color: #a0a6b5;
  font-weight: 500;
  padding: 10px 24px;
  border-radius: 8px;
  transition: all 0.3s;
  margin-left: 12px;
}
.skip-btn:hover {
  border-color: rgba(160, 166, 181, 0.6);
  color: #d0d5e0;
}

.skip-waiting-btn {
  margin-top: 16px;
  color: rgba(160, 166, 181, 0.5) !important;
  font-size: 12px;
  transition: color 0.3s;
}
.skip-waiting-btn:hover {
  color: rgba(160, 166, 181, 0.9) !important;
}

.action-buttons {
  display: flex;
  justify-content: center;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

/* 过渡动画 */
.fade-leave-active {
  transition: opacity 0.8s cubic-bezier(0.25, 1, 0.5, 1);
}
.fade-leave-to {
  opacity: 0;
}

.slide-up-enter-active {
  transition: all 0.5s cubic-bezier(0.25, 1, 0.5, 1);
}
.slide-up-enter-from {
  opacity: 0;
  transform: translateY(20px);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes rotate {
  100% {
    transform: rotate(360deg);
  }
}

@keyframes pulse-node {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.7;
  }
  50% {
    transform: scale(1.3);
    opacity: 1;
    filter: drop-shadow(0 0 6px currentColor);
  }
}
</style>
