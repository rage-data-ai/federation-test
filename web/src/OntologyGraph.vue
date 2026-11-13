<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

const props = defineProps({
  snapshot: { type: Object, required: true },
  fresh: { type: Array, default: () => [] },
  active: { type: String, default: '' }
})
const emit = defineEmits(['pick'])

const host = ref(null)
const camera = ref({ x: 40, y: 40, k: 1 })
let drag = null
let observer = null
let moved = false

const model = computed(() => layout(props.snapshot))
const freshSet = computed(() => new Set(props.fresh))

const frame = computed(() => {
  const nodes = model.value.nodes
  if (!nodes.length) {
    return { x: 0, y: 0, w: 800, h: 480 }
  }
  const minX = Math.min(...nodes.map((n) => n.x)) - 48
  const minY = Math.min(...nodes.map((n) => n.y)) - 48
  const maxX = Math.max(...nodes.map((n) => n.x + n.w)) + 48
  const maxY = Math.max(...nodes.map((n) => n.y + n.h)) + 48
  return { x: minX, y: minY, w: maxX - minX, h: maxY - minY }
})

function onWheel(event) {
  moved = true
  event.preventDefault()
  const next = camera.value.k * (event.deltaY > 0 ? 0.9 : 1.1)
  camera.value = { ...camera.value, k: Math.min(2.2, Math.max(0.35, next)) }
}

function onPointerDown(event) {
  if (event.target.closest('.node')) {
    return
  }
  drag = { x: event.clientX, y: event.clientY, cam: { ...camera.value } }
}

function onPointerMove(event) {
  if (!drag) {
    return
  }
  moved = true
  camera.value = {
    ...drag.cam,
    x: drag.cam.x + event.clientX - drag.x,
    y: drag.cam.y + event.clientY - drag.y
  }
}

function onPointerUp() {
  drag = null
}

function fit() {
  const el = host.value
  if (moved || !el || el.clientWidth < 80 || el.clientHeight < 80 || !model.value.nodes.length) {
    return
  }
  const pad = 24
  const fitK = Math.min(1, (el.clientWidth - pad * 2) / frame.value.w, (el.clientHeight - pad * 2) / frame.value.h)
  const k = Math.max(fitK, 0.82)
  const wide = frame.value.w * k + pad * 2 <= el.clientWidth
  const tall = frame.value.h * k + pad * 2 <= el.clientHeight
  camera.value = {
    k,
    x: wide ? (el.clientWidth - frame.value.w * k) / 2 : pad,
    y: tall ? Math.max(48, (el.clientHeight - frame.value.h * k) / 2) : 48
  }
}

watch(model, () => {
  moved = false
  nextTick(fit)
})

onMounted(() => {
  window.addEventListener('pointermove', onPointerMove)
  window.addEventListener('pointerup', onPointerUp)
  observer = new ResizeObserver(() => fit())
  observer.observe(host.value)
  fit()
})
onUnmounted(() => {
  observer?.disconnect()
  window.removeEventListener('pointermove', onPointerMove)
  window.removeEventListener('pointerup', onPointerUp)
})

function isFresh(node) {
  if (freshSet.value.has(node.id)) {
    return true
  }
  return (node.attrs || []).some((attr) => freshSet.value.has(attr.name))
}

function edgePath(edge) {
  const nodes = model.value.nodes
  const from = nodes.find((n) => n.id === edge.from)
  const to = nodes.find((n) => n.id === edge.to)
  if (!from || !to) {
    return ''
  }
  const below = to.y > from.y + from.h * 0.6
  const x1 = below ? from.x + from.w / 2 : from.x + from.w
  const y1 = below ? from.y + from.h : from.y + from.h / 2
  const x2 = below ? to.x + to.w / 2 : to.x
  const y2 = below ? to.y : to.y + to.h / 2
  if (below) {
    const bend = Math.max(24, (y2 - y1) / 2)
    return `M ${x1} ${y1} C ${x1} ${y1 + bend}, ${x2} ${y2 - bend}, ${x2} ${y2}`
  }
  const bend = Math.max(36, Math.abs(x2 - x1) / 3)
  return `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`
}

function edgeLabel(edge) {
  const nodes = model.value.nodes
  const from = nodes.find((n) => n.id === edge.from)
  const to = nodes.find((n) => n.id === edge.to)
  if (!from || !to) {
    return { x: 0, y: 0 }
  }
  return { x: (from.x + from.w + to.x) / 2, y: (from.y + to.y) / 2 - 8 }
}

function layout(snapshot) {
  const concepts = snapshot.concepts || []
  const defined = snapshot.definedConcepts || []
  const nodes = concepts.map((concept) => {
    const attrs = (snapshot.attributes || []).filter((a) => a.domain === concept.name)
    const shown = attrs.slice(0, 6)
    return {
      id: concept.name,
      kind: 'concept',
      label: concept.labelZh || concept.name,
      sub: concept.name,
      table: concept.table || '未映射表',
      attrs: shown,
      more: Math.max(0, attrs.length - shown.length),
      w: 220,
      h: 78 + shown.length * 18 + (attrs.length > shown.length ? 16 : 0)
    }
  })
  for (const item of defined) {
    const text = (item.conditions || []).map((c) => `${c.attribute} ${c.operator} ${c.value}`).join(' 且 ')
      || item.definitionText
      || ''
    nodes.push({
      id: item.name,
      kind: 'defined',
      label: item.labelZh || item.name,
      sub: item.base ? `派生自 ${item.base}` : '派生概念',
      detail: text,
      w: 200,
      h: text ? 78 : 62
    })
  }
  const edges = []
  for (const concept of concepts) {
    if (concept.parent && nodes.some((n) => n.id === concept.parent)) {
      edges.push({ from: concept.parent, to: concept.name, kind: 'parent', label: '子类' })
    }
  }
  for (const relation of snapshot.relations || []) {
    if (!nodes.some((n) => n.id === relation.domain) || !nodes.some((n) => n.id === relation.range)) {
      continue
    }
    edges.push({
      id: relation.name,
      from: relation.domain,
      to: relation.range,
      kind: 'relation',
      label: relation.labelZh || relation.name,
      join: relation.joinFrom && relation.joinTo ? `${relation.joinFrom} = ${relation.joinTo}` : ''
    })
  }
  for (const item of defined) {
    if (item.base && nodes.some((n) => n.id === item.base)) {
      edges.push({ from: item.base, to: item.name, kind: 'defined', label: '派生' })
    }
  }
  place(nodes, edges)
  return { nodes, edges }
}

function place(nodes, edges) {
  const concepts = nodes.filter((node) => node.kind === 'concept')
  const defined = nodes.filter((node) => node.kind === 'defined')
  const children = new Map()
  for (const edge of edges) {
    if (edge.kind === 'parent') {
      children.set(edge.to, edge.from)
    }
  }
  const roots = concepts.filter((node) => !children.has(node.id))
  const ordered = [...roots, ...concepts.filter((node) => children.has(node.id))]
  let x = 0
  for (const node of ordered) {
    node.x = x
    node.y = 24
    x += node.w + 72
  }
  const slot = {}
  for (const node of defined) {
    const base = concepts.find((item) => edges.some((edge) => edge.kind === 'defined' && edge.from === item.id && edge.to === node.id))
    const index = slot[base?.id] || 0
    slot[base?.id] = index + 1
    node.x = base ? base.x : x
    node.y = base ? base.y + base.h + 36 + index * (node.h + 18) : 24
  }
}
</script>

<template>
  <div
    ref="host"
    class="graph"
    @wheel="onWheel"
    @pointerdown="onPointerDown"
  >
    <svg v-if="model.nodes.length" class="scene">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
          <path d="M 0 1 L 10 5 L 0 9 z" />
        </marker>
      </defs>
      <g :transform="`translate(${camera.x} ${camera.y}) scale(${camera.k}) translate(${-frame.x} ${-frame.y})`">
        <g v-for="(edge, index) in model.edges" :key="`${edge.kind}-${edge.from}-${edge.to}-${index}`">
          <path :d="edgePath(edge)" class="edge" :class="edge.kind" marker-end="url(#arrow)" />
          <text class="edge-label" :class="edge.kind" :x="edgeLabel(edge).x" :y="edgeLabel(edge).y">
            {{ edge.label }}<tspan v-if="edge.join" class="join"> {{ edge.join }}</tspan>
          </text>
        </g>
        <g
          v-for="node in model.nodes"
          :key="node.id"
          class="node"
          :class="[node.kind, { fresh: isFresh(node), active: active === node.id }]"
          :transform="`translate(${node.x} ${node.y})`"
          @click="emit('pick', { kind: node.kind === 'defined' ? 'defined' : 'concept', name: node.id })"
        >
          <rect :width="node.w" :height="node.h" rx="10" />
          <text class="title" x="14" y="24">{{ node.label }}</text>
          <text class="sub" x="14" y="42">{{ node.sub }}</text>
          <text v-if="node.table" class="table" x="14" y="60">{{ node.table }}</text>
          <text v-if="node.detail" class="detail" x="14" y="62">{{ node.detail }}</text>
          <g v-for="(attr, aidx) in node.attrs || []" :key="attr.name" @click.stop="emit('pick', { kind: 'attribute', name: attr.name })">
            <text class="attr" x="14" :y="84 + aidx * 18">
              {{ attr.labelZh || attr.name }}
              <tspan class="col"> {{ attr.column || '—' }}</tspan>
              <tspan v-if="attr.codeList" class="code"> {{ attr.codeList }}</tspan>
            </text>
          </g>
          <text v-if="node.more" class="more" x="14" :y="84 + (node.attrs.length) * 18">还有 {{ node.more }} 个属性</text>
        </g>
      </g>
    </svg>
    <p v-if="model.nodes.length" class="hint-bar">拖动平移，滚轮缩放。琥珀色描边是刚起草写入的实体。</p>
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

.edge.relation {
  stroke: var(--accent-2);
}

.edge.parent {
  stroke-dasharray: 5 4;
}

.edge.defined {
  stroke: var(--warn);
  stroke-dasharray: 2 3;
}

marker path {
  fill: var(--muted);
}

.edge-label {
  font-size: 11px;
  fill: var(--muted);
}

.edge-label.relation {
  fill: var(--accent-2);
}

.edge-label .join {
  fill: var(--muted);
}

.node {
  cursor: pointer;
}

.node rect {
  fill: var(--panel);
  stroke: var(--line);
  stroke-width: 1.5;
}

.node.defined rect {
  fill: var(--panel-2);
  stroke-dasharray: 5 3;
}

.node.fresh rect {
  stroke: var(--warn);
  stroke-width: 2.5;
}

.node.active rect {
  stroke: var(--accent);
  stroke-width: 2.5;
}

.node.fresh.active rect {
  stroke: var(--accent);
}

.title {
  font-size: 14px;
  font-weight: 650;
  fill: var(--text);
}

.sub,
.table,
.detail,
.more {
  font-size: 11px;
  fill: var(--muted);
}

.attr {
  font-size: 12px;
  fill: var(--text);
}

.attr .col,
.attr .code {
  fill: var(--muted);
  font-size: 11px;
}

.hint-bar {
  position: absolute;
  left: 12px;
  top: 10px;
  margin: 0;
  padding: 4px 8px;
  border-radius: 6px;
  background: color-mix(in srgb, var(--panel) 88%, transparent);
  font-size: 12px;
}
</style>
