<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps({
  snapshot: { type: Object, required: true },
  fresh: { type: Array, default: () => [] },
  active: { type: String, default: '' }
})
const emit = defineEmits(['pick'])

const CARD = { w: 240, h: 74 }
const ATTR = { w: 230, h: 32, gap: 8 }
const CODE = { w: 220, h: 26, gap: 6 }
const CODE_LIMIT = 40
const GAP_X = 150
const GAP_Y = 120

const host = ref(null)
const hostWidth = ref(1200)
const camera = ref({ x: 40, y: 40, k: 1 })
const expanded = ref(new Set())
let drag = null
let observer = null
let fitted = false

const rowWidth = computed(() => Math.max(900, Math.round(hostWidth.value / 0.75 / 100) * 100))
const model = computed(() => layout(props.snapshot, expanded.value, rowWidth.value))
const freshSet = computed(() => new Set(props.fresh))

function toggle(key) {
  const next = new Set(expanded.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  expanded.value = next
}

function expandAll() {
  expanded.value = new Set((props.snapshot.concepts || []).map((concept) => `c:${concept.name}`))
  nextTick(() => fit(true))
}

function collapseAll() {
  expanded.value = new Set()
  nextTick(() => fit(true))
}

function groupBy(items, key) {
  const map = new Map()
  for (const item of items || []) {
    const k = key(item)
    if (!map.has(k)) {
      map.set(k, [])
    }
    map.get(k).push(item)
  }
  return map
}

function clip(text, max) {
  if (!text) {
    return ''
  }
  return text.length > max ? `${text.slice(0, max - 1)}…` : text
}

/** 关系相连的概念排在一起，便于连线变短。 */
function orderConcepts(concepts, relations) {
  const names = concepts.map((concept) => concept.name)
  const links = new Map(names.map((name) => [name, new Set()]))
  const link = (a, b) => {
    if (links.has(a) && links.has(b) && a !== b) {
      links.get(a).add(b)
      links.get(b).add(a)
    }
  }
  for (const relation of relations || []) {
    link(relation.domain, relation.range)
  }
  for (const concept of concepts) {
    link(concept.parent, concept.name)
  }
  const seen = new Set()
  const order = []
  for (const start of names) {
    if (seen.has(start)) {
      continue
    }
    const queue = [start]
    seen.add(start)
    while (queue.length) {
      const name = queue.shift()
      order.push(name)
      for (const next of links.get(name)) {
        if (!seen.has(next)) {
          seen.add(next)
          queue.push(next)
        }
      }
    }
  }
  return order.map((name) => concepts.find((concept) => concept.name === name))
}

function buildCluster(concept, s, open) {
  const attrs = s.attrsOf.get(concept.name) || []
  const nodes = []
  const edges = []
  const card = {
    id: concept.name,
    name: concept.name,
    kind: 'concept',
    x: 0,
    y: 0,
    w: CARD.w,
    h: CARD.h,
    label: clip(concept.labelZh || concept.name, 11),
    sub: concept.name,
    table: concept.table || '未映射表',
    count: attrs.length,
    toggleKey: attrs.length ? `c:${concept.name}` : null,
    open: open.has(`c:${concept.name}`)
  }
  nodes.push(card)

  let left = CARD.h
  for (const item of s.definedOf.get(concept.name) || []) {
    const detail = (item.conditions || []).map((c) => `${c.attribute} ${c.operator} ${c.value}`).join(' 且 ')
      || item.definitionText || ''
    const node = {
      id: item.name,
      name: item.name,
      kind: 'defined',
      x: 28,
      y: left + 26,
      w: 212,
      h: detail ? 56 : 40,
      label: clip(item.labelZh || item.name, 12),
      detail: clip(detail, 30)
    }
    nodes.push(node)
    edges.push({ kind: 'defined', from: card, to: node })
    left = node.y + node.h
  }

  let right = 0
  if (card.open) {
    const ax = CARD.w + 70
    let y = 0
    for (const attribute of attrs) {
      const codes = attribute.codeList ? s.codesOf.get(attribute.codeList) || [] : []
      const key = `a:${attribute.name}`
      const node = {
        id: attribute.name,
        name: attribute.name,
        kind: 'attribute',
        x: ax,
        y,
        w: ATTR.w,
        h: ATTR.h,
        label: clip(attribute.labelZh || attribute.name, 8),
        column: clip(attribute.column || '—', 16),
        identifier: attribute.identifier,
        count: codes.length,
        toggleKey: codes.length ? key : null,
        open: open.has(key)
      }
      nodes.push(node)
      edges.push({ kind: 'has', from: card, to: node })
      let block = ATTR.h
      if (node.open) {
        const cx = ax + ATTR.w + 56
        let cy = y
        for (const code of codes.slice(0, CODE_LIMIT)) {
          const leaf = {
            id: `code:${code.name}`,
            name: code.name,
            kind: 'code',
            x: cx,
            y: cy,
            w: CODE.w,
            h: CODE.h,
            value: clip(code.value, 12),
            label: clip(code.labelZh || '', 10)
          }
          nodes.push(leaf)
          edges.push({ kind: 'has', from: node, to: leaf })
          cy += CODE.h + CODE.gap
        }
        if (codes.length > CODE_LIMIT) {
          nodes.push({
            id: `more:${attribute.name}`,
            kind: 'more',
            x: cx,
            y: cy,
            w: CODE.w,
            h: CODE.h,
            label: `还有 ${codes.length - CODE_LIMIT} 个，在左侧列表查看`
          })
          cy += CODE.h + CODE.gap
        }
        block = Math.max(ATTR.h, cy - y - CODE.gap)
      }
      y += block + ATTR.gap
    }
    right = y - ATTR.gap
  }
  const width = Math.max(...nodes.map((node) => node.x + node.w))
  return { card, nodes, edges, width, height: Math.max(left, right) }
}

function layout(snapshot, open, rowLimit) {
  const concepts = snapshot.concepts || []
  const s = {
    attrsOf: groupBy(snapshot.attributes, (a) => a.domain),
    codesOf: groupBy(snapshot.codes, (c) => c.codeList),
    definedOf: groupBy(snapshot.definedConcepts, (d) => d.base)
  }
  const clusters = orderConcepts(concepts, snapshot.relations).map((concept) => buildCluster(concept, s, open))
  const limit = Math.max(rowLimit, ...clusters.map((cluster) => cluster.width))
  let x = 0
  let y = 0
  let rowHeight = 0
  for (const cluster of clusters) {
    if (x > 0 && x + cluster.width > limit) {
      x = 0
      y += rowHeight + GAP_Y
      rowHeight = 0
    }
    for (const node of cluster.nodes) {
      node.x += x
      node.y += y
    }
    x += cluster.width + GAP_X
    rowHeight = Math.max(rowHeight, cluster.height)
  }

  const nodes = clusters.flatMap((cluster) => cluster.nodes)
  const edges = clusters.flatMap((cluster) => cluster.edges)
  const cards = new Map(clusters.map((cluster) => [cluster.card.id, cluster.card]))
  const pairs = new Map()
  const between = (kind, from, to, extra) => {
    const a = cards.get(from)
    const b = cards.get(to)
    if (!a || !b) {
      return
    }
    const pair = [from, to].sort().join('|')
    const index = pairs.get(pair) || 0
    pairs.set(pair, index + 1)
    edges.push({ kind, from: a, to: b, index, ...extra })
  }
  for (const concept of concepts) {
    if (concept.parent) {
      between('parent', concept.parent, concept.name, { label: '子类' })
    }
  }
  for (const relation of snapshot.relations || []) {
    between('relation', relation.domain, relation.range, {
      id: relation.name,
      label: clip(relation.labelZh || relation.name, 12),
      join: relation.joinFrom && relation.joinTo ? `${relation.joinFrom} = ${relation.joinTo}` : ''
    })
  }
  for (const edge of edges) {
    Object.assign(edge, route(edge))
  }
  return { nodes, edges }
}

function route(edge) {
  const a = edge.from
  const b = edge.to
  if (edge.kind === 'has') {
    const x1 = a.x + a.w
    const y1 = a.kind === 'concept' ? a.y + 37 : a.y + a.h / 2
    const x2 = b.x
    const y2 = b.y + b.h / 2
    const bend = (x2 - x1) / 2
    return { d: `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}` }
  }
  if (edge.kind === 'defined') {
    const x = a.x + 14
    return { d: `M ${x} ${a.y + a.h} V ${b.y + b.h / 2} H ${b.x}` }
  }
  const lift = 54 + edge.index * 26
  if (a === b) {
    const x = a.x + 24 + edge.index * 34
    const d = `M ${x} ${a.y} C ${x - 10} ${a.y - lift}, ${x + 70} ${a.y - lift}, ${x + 40} ${a.y}`
    return { d, lx: x + 20, ly: a.y - lift * 0.78 }
  }
  if (Math.abs(a.y - b.y) < 4) {
    const x1 = a.x + a.w / 2 + (a.x < b.x ? 50 : -50)
    const x2 = b.x + b.w / 2 + (a.x < b.x ? -30 : 30)
    const h = lift + Math.min(80, Math.abs(x2 - x1) * 0.06)
    return {
      d: `M ${x1} ${a.y} C ${x1} ${a.y - h}, ${x2} ${b.y - h}, ${x2} ${b.y}`,
      lx: (x1 + x2) / 2,
      ly: a.y - h * 0.75
    }
  }
  const y1 = a.y + 26 + edge.index * 10
  const y2 = b.y + 26 + edge.index * 10
  const bend = 70 + edge.index * 28 + Math.min(120, Math.abs(y2 - y1) * 0.12)
  return {
    d: `M ${a.x} ${y1} C ${a.x - bend} ${y1}, ${b.x - bend} ${y2}, ${b.x} ${y2}`,
    lx: Math.min(a.x, b.x) - bend * 0.75 - 8,
    ly: (y1 + y2) / 2,
    anchor: 'end'
  }
}

const bounds = computed(() => {
  const nodes = model.value.nodes
  if (!nodes.length) {
    return { x: 0, y: 0, w: 800, h: 480 }
  }
  const labels = model.value.edges.filter((e) => e.anchor === 'end').map((e) => e.lx - 220)
  const minX = Math.min(...nodes.map((n) => n.x), ...labels) - 40
  const minY = Math.min(...nodes.map((n) => n.y)) - 110
  const maxX = Math.max(...nodes.map((n) => n.x + n.w)) + 40
  const maxY = Math.max(...nodes.map((n) => n.y + n.h)) + 40
  return { x: minX, y: minY, w: maxX - minX, h: maxY - minY }
})

function fit(force) {
  const el = host.value
  if (!el || el.clientWidth < 80 || el.clientHeight < 80 || !model.value.nodes.length) {
    return
  }
  if (fitted && !force) {
    return
  }
  fitted = true
  const b = bounds.value
  const top = 44
  const k = Math.max(0.45, Math.min(1, el.clientWidth / b.w, (el.clientHeight - top) / b.h))
  camera.value = {
    k,
    x: Math.max(0, (el.clientWidth - b.w * k) / 2) - b.x * k,
    y: top + Math.max(0, (el.clientHeight - top - b.h * k) / 2) - b.y * k
  }
}

function centerOn(id) {
  const el = host.value
  const node = model.value.nodes.find((item) => item.id === id || item.id === `code:${id}`)
  if (!el || !node) {
    return
  }
  const { x, y, k } = camera.value
  const sx = x + node.x * k
  const sy = y + node.y * k
  if (sx > 0 && sy > 0 && sx + node.w * k < el.clientWidth && sy + node.h * k < el.clientHeight) {
    return
  }
  camera.value = {
    k,
    x: el.clientWidth / 2 - (node.x + node.w / 2) * k,
    y: el.clientHeight / 2 - (node.y + node.h / 2) * k
  }
}

function onWheel(event) {
  event.preventDefault()
  const rect = host.value.getBoundingClientRect()
  const px = event.clientX - rect.left
  const py = event.clientY - rect.top
  const { x, y, k } = camera.value
  const next = Math.min(2.2, Math.max(0.2, k * (event.deltaY > 0 ? 0.9 : 1.1)))
  camera.value = {
    k: next,
    x: px - ((px - x) / k) * next,
    y: py - ((py - y) / k) * next
  }
}

function onPointerDown(event) {
  if (event.target.closest('.node, .toolbar')) {
    return
  }
  drag = { x: event.clientX, y: event.clientY, cam: { ...camera.value } }
}

function onPointerMove(event) {
  if (!drag) {
    return
  }
  camera.value = {
    ...drag.cam,
    x: drag.cam.x + event.clientX - drag.x,
    y: drag.cam.y + event.clientY - drag.y
  }
}

function onPointerUp() {
  drag = null
}

function pick(node) {
  if (node.kind === 'more') {
    return
  }
  emit('pick', { kind: node.kind, name: node.name })
}

/** 左侧或别处选中属性、码值时，把它所在的概念和属性展开。 */
function reveal(name) {
  const s = props.snapshot
  const next = new Set(expanded.value)
  const attribute = (s.attributes || []).find((item) => item.name === name)
  const code = (s.codes || []).find((item) => item.name === name)
  if (attribute) {
    next.add(`c:${attribute.domain}`)
  }
  if (code) {
    const owner = (s.attributes || []).find((item) => item.codeList && item.codeList === code.codeList)
    if (owner) {
      next.add(`c:${owner.domain}`)
      next.add(`a:${owner.name}`)
    }
  }
  if (next.size !== expanded.value.size) {
    expanded.value = next
  }
  nextTick(() => centerOn(name))
}

watch(() => props.active, (name) => {
  if (name) {
    reveal(name)
  }
})

watch(() => props.snapshot, (snapshot, previous) => {
  const concepts = snapshot.concepts || []
  if (!(previous?.concepts || []).length && concepts.length && concepts.length <= 4) {
    expanded.value = new Set(concepts.map((concept) => `c:${concept.name}`))
  }
  if (!(previous?.concepts || []).length) {
    fitted = false
  }
  nextTick(() => fit())
}, { immediate: true })

onMounted(() => {
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  observer = new ResizeObserver(() => {
    hostWidth.value = host.value?.clientWidth || hostWidth.value
    fit()
  })
  observer.observe(host.value)
  hostWidth.value = host.value.clientWidth || hostWidth.value
  fit()
})
onUnmounted(() => {
  observer?.disconnect()
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
})

function isFresh(node) {
  return freshSet.value.has(node.name)
}
</script>

<template>
  <div ref="host" class="graph" @wheel="onWheel" @pointerdown="onPointerDown">
    <svg v-if="model.nodes.length" class="scene">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 1 L 10 5 L 0 9 z" />
        </marker>
      </defs>
      <g :transform="`translate(${camera.x} ${camera.y}) scale(${camera.k})`">
        <path
          v-for="(edge, index) in model.edges"
          :key="`e-${index}`"
          :d="edge.d"
          class="edge"
          :class="edge.kind"
          :marker-end="edge.kind === 'relation' || edge.kind === 'parent' ? 'url(#arrow)' : null"
        />
        <g
          v-for="node in model.nodes"
          :key="node.id"
          class="node"
          :class="[node.kind, { fresh: isFresh(node), active: active && active === node.name }]"
          :transform="`translate(${node.x} ${node.y})`"
          @click="pick(node)"
          @dblclick.stop="node.toggleKey && toggle(node.toggleKey)"
        >
          <rect :width="node.w" :height="node.h" :rx="node.kind === 'concept' ? 10 : 6" />
          <template v-if="node.kind === 'concept'">
            <text class="title" x="14" y="25">{{ node.label }}</text>
            <text class="sub" x="14" y="44">{{ node.sub }}</text>
            <text class="table" x="14" y="62">{{ node.table }}</text>
          </template>
          <template v-else-if="node.kind === 'attribute'">
            <text class="attr" x="12" y="21">
              {{ node.label }}<tspan class="muted" dx="8">{{ node.column }}</tspan><tspan v-if="node.identifier" class="muted" dx="6">主键</tspan>
            </text>
          </template>
          <template v-else-if="node.kind === 'code'">
            <text class="code-text" x="10" y="17">
              <tspan class="value">{{ node.value }}</tspan><tspan dx="8">{{ node.label }}</tspan>
            </text>
          </template>
          <template v-else-if="node.kind === 'defined'">
            <text class="title small" x="12" y="22">{{ node.label }}</text>
            <text v-if="node.detail" class="sub" x="12" y="42">{{ node.detail }}</text>
          </template>
          <text v-else class="sub" x="10" y="17">{{ node.label }}</text>
          <g
            v-if="node.toggleKey"
            class="badge"
            :class="{ open: node.open }"
            :transform="`translate(${node.w - 76} ${node.kind === 'concept' ? 10 : 6})`"
            @click.stop="toggle(node.toggleKey)"
          >
            <rect width="68" height="20" rx="10" />
            <text x="34" y="14">{{ node.kind === 'concept' ? '属性' : '码值' }} {{ node.count }} {{ node.open ? '−' : '+' }}</text>
          </g>
        </g>
        <g v-for="(edge, index) in model.edges" :key="`l-${index}`">
          <text v-if="edge.label" class="edge-label" :class="edge.kind" :x="edge.lx" :y="edge.ly" :text-anchor="edge.anchor || 'middle'">
            {{ edge.label }}<tspan v-if="edge.join" class="join" dx="6">{{ edge.join }}</tspan>
          </text>
        </g>
      </g>
    </svg>
    <div v-if="model.nodes.length" class="toolbar">
      <span class="tip">拖动平移，滚轮缩放，点 + 展开下一级</span>
      <button type="button" @click="expandAll">展开全部属性</button>
      <button type="button" @click="collapseAll">全部收起</button>
      <button type="button" @click="fit(true)">适应画布</button>
    </div>
  </div>
</template>

<style scoped>
.graph {
  position: relative;
  min-height: 0;
  height: 100%;
  overflow: hidden;
  background:
    radial-gradient(circle at 1px 1px, var(--line) 1px, transparent 0) 0 0 / 22px 22px,
    var(--bg);
  cursor: grab;
}

.graph:active {
  cursor: grabbing;
}

.scene {
  width: 100%;
  height: 100%;
  display: block;
}

.edge {
  fill: none;
  stroke: var(--muted);
  stroke-width: 1.4;
}

.edge.has {
  stroke: var(--line);
  stroke-width: 1.6;
}

.edge.relation {
  stroke: var(--accent-2);
  stroke-width: 1.8;
}

.edge.parent {
  stroke-dasharray: 5 4;
}

.edge.defined {
  stroke: var(--warn);
  stroke-dasharray: 2 3;
}

marker path {
  fill: var(--accent-2);
}

.edge-label {
  font-size: 12px;
  fill: var(--muted);
  paint-order: stroke;
  stroke: var(--bg);
  stroke-width: 5px;
  stroke-linejoin: round;
}

.edge-label.relation {
  fill: var(--accent-2);
  font-weight: 600;
}

.edge-label .join {
  fill: var(--muted);
  font-weight: 400;
  font-size: 11px;
}

.node {
  cursor: pointer;
}

.node rect {
  fill: var(--panel);
  stroke: var(--line);
  stroke-width: 1.5;
}

.node.concept > rect {
  stroke: var(--accent-2);
  stroke-width: 1.8;
}

.node.attribute > rect {
  fill: var(--panel);
}

.node.code > rect {
  fill: var(--panel-2);
  stroke-width: 1;
}

.node.more > rect {
  fill: transparent;
  stroke-dasharray: 3 3;
}

.node.defined > rect {
  fill: var(--panel-2);
  stroke: var(--warn);
  stroke-dasharray: 5 3;
}

.node:hover > rect {
  stroke: var(--accent);
}

.node.fresh > rect {
  stroke: var(--warn);
  stroke-width: 2.5;
}

.node.active > rect {
  stroke: var(--accent);
  stroke-width: 2.6;
}

.title {
  font-size: 15px;
  font-weight: 650;
  fill: var(--text);
}

.title.small {
  font-size: 13px;
}

.sub,
.table {
  font-size: 11px;
  fill: var(--muted);
}

.attr {
  font-size: 13px;
  fill: var(--text);
}

.muted {
  fill: var(--muted);
  font-size: 11px;
}

.code-text {
  font-size: 12px;
  fill: var(--text);
}

.code-text .value {
  font-weight: 650;
}

.badge rect {
  fill: var(--panel-2);
  stroke: var(--line);
  stroke-width: 1;
}

.badge text {
  font-size: 11px;
  text-anchor: middle;
  fill: var(--muted);
}

.badge:hover rect,
.badge.open rect {
  stroke: var(--accent);
}

.badge.open text {
  fill: var(--accent);
}

.toolbar {
  position: absolute;
  left: 12px;
  top: 10px;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px 4px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: color-mix(in srgb, var(--panel) 92%, transparent);
  font-size: 12px;
  cursor: default;
}

.toolbar .tip {
  color: var(--muted);
  margin-right: 4px;
  white-space: nowrap;
}

.toolbar button {
  padding: 2px 8px;
  font-size: 12px;
  white-space: nowrap;
}

@container (max-width: 640px) {
  .toolbar .tip {
    display: none;
  }
}

.graph {
  container-type: inline-size;
}
</style>
