/** Scale chart typography without changing the plot geometry or the user's browser zoom. */
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
