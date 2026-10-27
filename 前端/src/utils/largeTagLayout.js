/* 为全部标签生成开销较小的位置和空间索引，供画布按可见范围绘制与点击定位。 */
/** 按分类把全部标签排成分散的圆盘，不计算两两作用力，也不创建大量页面元素。 */
export function layoutLargeCatalog(nodes) {
  const groups = new Map()
  for (const node of nodes) {
    const name = node.namespace || 'misc'
    if (!groups.has(name)) groups.set(name, [])
    groups.get(name).push(node)
  }
  const columns = Math.max(1, Math.ceil(Math.sqrt(groups.size * 1.4)))
  const descriptors = [...groups].map(([namespace, children]) => ({
    namespace, children, r: Math.max(70, Math.sqrt(children.length) * 34 + 40)
  }))
  const cell = descriptors.reduce((max, group) => Math.max(max, group.r * 2 + 55), 180)
  const points = [], clusters = [], positions = new Map()
  descriptors.forEach((group, index) => {
    const x = (index % columns + .5) * cell, y = (Math.floor(index / columns) + .5) * cell
    clusters.push({ namespace: group.namespace, x, y, r: group.r })
    // 固定角度配合逐渐增加的半径，将同类节点均匀散开；同一份数据的位置保持稳定。
    group.children.forEach((node, offset) => {
      const angle = offset * 2.399963229728653
      const distance = 34 * Math.sqrt(offset)
      const point = { node, x: x + Math.cos(angle) * distance, y: y + Math.sin(angle) * distance }
      points.push(point)
      positions.set(node.name, point)
    })
  })
  return { points, positions, clusters, width: columns * cell, height: Math.max(1, Math.ceil(groups.size / columns)) * cell }
}

/** 按位置把节点放入网格，绘图和点击时只查目标区域，减少遍历全部节点的开销。 */
export function indexCatalog(points, cellSize = 100) {
  const cells = new Map()
  for (const point of points) {
    const key = Math.floor(point.x / cellSize) + ':' + Math.floor(point.y / cellSize)
    if (!cells.has(key)) cells.set(key, [])
    cells.get(key).push(point)
  }
  return {
    query(left, top, right, bottom) {
      const result = []
      const firstX = Math.floor(left / cellSize), lastX = Math.floor(right / cellSize)
      const firstY = Math.floor(top / cellSize), lastY = Math.floor(bottom / cellSize)
      // 缩得很小时查询区域很大，改为遍历有节点的格子，避免检查大量空格子。
      if ((lastX - firstX + 1) * (lastY - firstY + 1) > cells.size * 2) {
        for (const values of cells.values()) for (const point of values) {
          if (point.x >= left && point.x <= right && point.y >= top && point.y <= bottom) result.push(point)
        }
      } else {
        for (let x = firstX; x <= lastX; x++) for (let y = firstY; y <= lastY; y++) {
          const values = cells.get(x + ':' + y)
          if (values) for (const point of values) if (point.x >= left && point.x <= right && point.y >= top && point.y <= bottom) result.push(point)
        }
      }
      return result
    }
  }
}
