/** Linear-time placement for complete catalogs. No pairwise forces or SVG node objects. */
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

/** Uniform spatial bins keep hit-testing and viewport drawing independent of total node count. */
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
      // When zoomed all the way out, iterating occupied bins is cheaper than a sparse giant rectangle.
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
