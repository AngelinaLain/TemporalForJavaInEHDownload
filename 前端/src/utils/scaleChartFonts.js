/* 按系统字号调整图表中的文字，保持图形坐标和浏览器缩放不变。 */
/** 递归调整图表配置中的文字大小，不改变图形坐标和浏览器缩放。 */
export function scaleChartFonts(option) {
  const scale = Number.parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--app-font-scale')) || 1
  const visit = value => {
    if (Array.isArray(value)) return value.map(visit)
    if (!value || typeof value !== 'object' || Object.getPrototypeOf(value) !== Object.prototype) return value
    return Object.fromEntries(Object.entries(value).map(([key, item]) => [
      key, key === 'fontSize' && typeof item === 'number' ? item * scale : visit(item)
    ]))
  }
  return visit(option)
}
