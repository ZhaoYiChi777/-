export const LIBRARY_LABELS: Record<string, string> = {
  report: '研究报告库',
  dynamic: '动态信息库',
  translation: '译丛译著库',
  chart: '图表库',
  policy: '政策文件库',
  news: '新闻资讯库',
}

export function getLibraryLabel(key: string): string {
  return LIBRARY_LABELS[key] || key
}
