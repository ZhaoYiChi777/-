/**
 * 平台检测工具
 * 检测当前运行环境是 Web 模式还是 Tauri 桌面模式
 */

// 检测是否在 Tauri 环境中运行
export function isTauri(): boolean {
  return typeof window !== 'undefined' && '__TAURI__' in window;
}

// 检测是否在 Web 模式中运行
export function isWeb(): boolean {
  return !isTauri();
}

// 获取 API 基础 URL
export function getApiBaseUrl(): string {
  // Web 模式下通过 Vite 代理访问（相对路径，避免跨域）
  if (isWeb()) {
    return '';
  }
  // Tauri 模式下直接访问后端
  return 'http://localhost:8080';
}

// 平台信息
export const platform = {
  isTauri: isTauri(),
  isWeb: isWeb(),
  apiBaseUrl: getApiBaseUrl()
};
