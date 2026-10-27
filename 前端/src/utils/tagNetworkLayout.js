const WIDTH = 1100
const HEIGHT = 740
const TAU = Math.PI * 2

const NAMESPACES = {
  female: ['女性', '#e879a4'],
  male: ['男性', '#70a5f4'],
  parody: ['原作', '#a996ef'],
  character: ['角色', '#edbd6a'],
  group: ['团体', '#69c5a5'],
  artist: ['艺术家', '#f39573'],
  language: ['语言', '#72c7da'],
  cosplayer: ['Cosplayer', '#d69be0'],
  mixed: ['混合', '#b7a8d8'],
  other: ['其他', '#a1afc3'],
  reclass: ['重新分类', '#8cb4bb'],
  misc: ['杂项', '#a1afc3']
}
const FALLBACK_COLORS = ['#8bb4ee', '#b4a0e3', '#75c6b4', '#e2b779', '#d98eae']

function hash(value) {
  let result = 2166136261
  for (const char of String(value)) result = Math.imul(result ^ char.charCodeAt(0), 16777619)
  return result >>> 0
}

export function namespaceLabel(namespace) {
  return NAMESPACES[namespace]?.[0] || namespace || '杂项'
}

export function namespaceColor(namespace) {
  return NAMESPACES[namespace]?.[1] || FALLBACK_COLORS[hash(namespace) % FALLBACK_COLORS.length]
}

export function nodeRadius(value, maxValue) {
  const max = Math.max(1, Number(maxValue) || 1)
  return 9 + 15 * Math.sqrt(Math.max(0, Math.min(1, (Number(value) || 0) / max)))
}

function bounds(circles) {
  const left = Math.min(...circles.map(circle => circle.x - circle.r))
  const right = Math.max(...circles.map(circle => circle.x + circle.r))
  const top = Math.min(...circles.map(circle => circle.y - circle.r))
  const bottom = Math.max(...circles.map(circle => circle.y + circle.r))
  return { left, right, top, bottom, width: right - left, height: bottom - top }
}

// Select the tightest valid tangent candidate; unlike random seeding this is repeatable.
function pack(circles, gap = 6, aspect = 1) {
  const placed = []
  for (const circle of circles) {
    let best = null
    let score = Infinity
    if (!placed.length) best = { x: 0, y: 0 }
    for (const anchor of placed) {
      const distance = anchor.r + circle.r + gap
      for (let step = 0; step < 32; step++) {
        const angle = step * TAU / 32
        const x = anchor.x + Math.cos(angle) * distance
        const y = anchor.y + Math.sin(angle) * distance
        if (placed.some(other => Math.hypot(x - other.x, y - other.y) < circle.r + other.r + gap - .01)) continue
        const candidates = [...placed, { ...circle, x, y }]
        const box = bounds(candidates)
        const centerX = (box.left + box.right) / 2
        const centerY = (box.top + box.bottom) / 2
        const candidateScore = Math.max(...candidates.map(candidate =>
          Math.hypot((candidate.x - centerX) / aspect, candidate.y - centerY) + candidate.r))
        if (candidateScore < score) {
          score = candidateScore
          best = { x, y }
        }
      }
    }
    // Outer tangent candidates always exist, including for many equal-size circles.
    placed.push({ ...circle, ...(best || { x: placed.length * (circle.r * 2 + gap), y: 0 }) })
  }
  if (!placed.length) return []
  const box = bounds(placed)
  const centerX = (box.left + box.right) / 2
  const centerY = (box.top + box.bottom) / 2
  return placed.map(circle => ({ ...circle, x: circle.x - centerX, y: circle.y - centerY }))
}

function clusterLayout(nodes, maxValue) {
  const groups = new Map()
  for (const node of nodes) {
    const namespace = node.namespace || 'misc'
    if (!groups.has(namespace)) groups.set(namespace, [])
    groups.get(namespace).push({ name: node.name, r: nodeRadius(node.value, maxValue) })
  }
  const groupsWithChildren = [...groups].map(([namespace, children]) => {
    const packed = pack(children.sort((a, b) => b.r - a.r || a.name.localeCompare(b.name)))
    return { namespace, children: packed, r: Math.max(...packed.map(child => Math.hypot(child.x, child.y) + child.r)) + 12 }
  }).sort((a, b) => b.r - a.r || a.namespace.localeCompare(b.namespace))

  let packedGroups = pack(groupsWithChildren, 12, WIDTH / HEIGHT)
  // Try rotations to favour the landscape canvas rather than assuming a square.
  let bestScale = 0
  let bestGroups = packedGroups
  for (let step = 0; step < 24; step++) {
    const angle = step * Math.PI / 24
    const rotated = packedGroups.map(group => ({
      ...group,
      x: group.x * Math.cos(angle) - group.y * Math.sin(angle),
      y: group.x * Math.sin(angle) + group.y * Math.cos(angle)
    }))
    const box = bounds(rotated)
    const scale = Math.min((WIDTH - 90) / box.width, (HEIGHT - 90) / box.height)
    if (scale > bestScale) {
      bestScale = scale
      bestGroups = rotated
    }
  }
  packedGroups = bestGroups
  const box = bounds(packedGroups)
  const offsetX = WIDTH / 2 - (box.left + box.right) / 2
  const offsetY = HEIGHT / 2 - (box.top + box.bottom) / 2
  const positions = {}
  const clusters = packedGroups.map(group => {
    const x = group.x + offsetX
    const y = group.y + offsetY
    for (const child of group.children) positions[child.name] = { x: x + child.x, y: y + child.y }
    return { namespace: group.namespace, x, y, r: group.r }
  })
  return { positions, clusters }
}

function forceLayout(nodes, links, maxValue, seed) {
  const particles = nodes.map(node => ({
    name: node.name,
    namespace: node.namespace || 'misc',
    r: nodeRadius(node.value, maxValue),
    x: seed[node.name].x,
    y: seed[node.name].y,
    vx: 0,
    vy: 0
  }))
  const lookup = new Map(particles.map((particle, index) => [particle.name, index]))
  const edges = links.map(link => ({
    source: lookup.get(link.source),
    target: lookup.get(link.target),
    weight: Math.max(1, Number(link.value) || 1)
  })).filter(link => link.source !== undefined && link.target !== undefined)

  for (let iteration = 0; iteration < 220; iteration++) {
    const cooling = 1 - iteration / 250
    for (const particle of particles) {
      particle.vx += (WIDTH / 2 - particle.x) * .002
      particle.vy += (HEIGHT / 2 - particle.y) * .002
    }
    for (let first = 0; first < particles.length; first++) {
      for (let second = first + 1; second < particles.length; second++) {
        const a = particles[first]
        const b = particles[second]
        let dx = b.x - a.x
        let dy = b.y - a.y
        let distance = Math.hypot(dx, dy)
        if (distance < .01) {
          const angle = hash(a.name + b.name) / 4294967296 * TAU
          dx = Math.cos(angle)
          dy = Math.sin(angle)
          distance = 1
        }
        const minimum = a.r + b.r + 16
        const repulsion = 1300 / (distance * distance) + Math.max(0, minimum - distance) * .11
        const forceX = dx / distance * repulsion
        const forceY = dy / distance * repulsion
        a.vx -= forceX
        a.vy -= forceY
        b.vx += forceX
        b.vy += forceY
      }
    }
    for (const edge of edges) {
      const a = particles[edge.source]
      const b = particles[edge.target]
      const dx = b.x - a.x
      const dy = b.y - a.y
      const distance = Math.max(1, Math.hypot(dx, dy))
      const desired = (a.namespace === b.namespace ? 100 : 165) + a.r + b.r
      const attraction = (distance - desired) * .006 * Math.min(2, 1 + Math.log1p(edge.weight) / 8)
      const forceX = dx / distance * attraction
      const forceY = dy / distance * attraction
      a.vx += forceX
      a.vy += forceY
      b.vx -= forceX
      b.vy -= forceY
    }
    for (const particle of particles) {
      particle.vx *= .72
      particle.vy *= .72
      particle.x = Math.max(particle.r + 45, Math.min(WIDTH - particle.r - 45, particle.x + particle.vx * cooling))
      particle.y = Math.max(particle.r + 45, Math.min(HEIGHT - particle.r - 45, particle.y + particle.vy * cooling))
    }
  }
  // Finish with collision relaxation so dense high-degree nodes remain clickable.
  for (let pass = 0; pass < 30; pass++) {
    for (let i = 0; i < particles.length; i++) {
      for (let j = i + 1; j < particles.length; j++) {
        const a = particles[i], b = particles[j]
        const dx = b.x - a.x, dy = b.y - a.y
        const distance = Math.max(.001, Math.hypot(dx, dy))
        const overlap = a.r + b.r + 9 - distance
        if (overlap <= 0) continue
        const amount = overlap / distance * .51
        a.x -= dx * amount
        a.y -= dy * amount
        b.x += dx * amount
        b.y += dy * amount
      }
    }
  }
  return Object.fromEntries(particles.map(particle => [particle.name, { x: particle.x, y: particle.y }]))
}

export function layoutNetwork(nodes, links = [], mode = 'force') {
  if (!nodes.length) return { positions: {}, clusters: [], width: WIDTH, height: HEIGHT }
  const maxValue = Math.max(1, ...nodes.map(node => Number(node.value) || 0))
  const packed = clusterLayout(nodes, maxValue)
  return {
    positions: mode === 'clusters' ? packed.positions : forceLayout(nodes, links, maxValue, packed.positions),
    clusters: mode === 'clusters' ? packed.clusters : [],
    width: WIDTH,
    height: HEIGHT
  }
}
