<script setup lang="ts">
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import {
  VueFlow,
  type Connection,
  type NodeTypesObject
} from '@vue-flow/core'
import { computed, markRaw, nextTick, onMounted, ref, watch } from 'vue'
import { getFlowNodePorts } from '../../api'
import type { FlowGraph } from '../../types'
import {
  graphToVueFlow,
  newNodeId,
  upstreamNodeIds,
  vueFlowToGraph,
  withParallelEdgeLayout,
  type DagNodeData
} from '../../flow/graphAdapter'
import {
  NODE_CATALOG,
  catalogItem,
  edgeWhenLabel,
  edgeWhenOptionsFor,
  nextEdgeWhen
} from '../../flow/nodeCatalog'
import { asRef, parseRef, portsFor, setPortsCatalog, sysVars } from '../../flow/nodePorts'
import { skillsBindableOn, skillsForNode, type AskSkillId } from '../../flow/skillCatalog'
import FlowNodeCard from './FlowNodeCard.vue'

import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import '@vue-flow/minimap/dist/style.css'

const props = defineProps<{
  modelValue: FlowGraph
  readonly?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [graph: FlowGraph]
  change: [graph: FlowGraph]
}>()

/** any[] avoids Vue Flow Node generics (TS2589) while still driving the canvas. */
const nodes = ref<any[]>([])
const edges = ref<any[]>([])
/** 单击节点/连线即打开右侧配置（类 Dify）。 */
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
const editingNodeId = ref('')
const editingEdgeId = ref('')
const localError = ref('')
const syncing = ref(false)
const lastEmitted = ref('')
/** 后端端口目录加载世代，驱动 portsFor 相关 UI 刷新。 */
const portsEpoch = ref(0)
const promptTextareaRef = ref<HTMLTextAreaElement | null>(null)
const answerTextareaRef = ref<HTMLTextAreaElement | null>(null)
const promptCaret = ref({ systemPrompt: { start: 0, end: 0 }, text: { start: 0, end: 0 } })
const promptCaretTouched = ref({ systemPrompt: false, text: false })
/** Dify 式 {x} 变量选择器。 */
const varPicker = ref<{ field: 'systemPrompt' | 'text' | ''; filter: string }>({
  field: '',
  filter: ''
})

const nodeTypes = { flow: markRaw(FlowNodeCard) } as unknown as NodeTypesObject

onMounted(async () => {
  try {
    const catalog = await getFlowNodePorts()
    setPortsCatalog(catalog)
    portsEpoch.value += 1
  } catch (e) {
    localError.value = e instanceof Error ? e.message : String(e)
  }
})

const selectedNode = computed(
  () => nodes.value.find((node) => node.id === selectedNodeId.value) || null
)
const selectedEdge = computed(
  () => edges.value.find((edge) => edge.id === selectedEdgeId.value) || null
)
const editingNode = computed(
  () => nodes.value.find((node) => node.id === editingNodeId.value) || null
)
const editingEdge = computed(
  () => edges.value.find((edge) => edge.id === editingEdgeId.value) || null
)
const addable = computed(() => NODE_CATALOG.filter((item) => item.addable))

const IF_ELSE_OPS = [
  { value: '==', label: '==' },
  { value: '!=', label: '!=' },
  { value: '>', label: '>' },
  { value: '>=', label: '>=' },
  { value: '<', label: '<' },
  { value: '<=', label: '<=' },
  { value: 'empty', label: '为空' },
  { value: 'not_empty', label: '非空' }
] as const

function nodeData(node: { id?: string; data?: Partial<DagNodeData> } | null | undefined): DagNodeData {
  const data = node?.data || {}
  return {
    flowType: data.flowType || 'llm_chat',
    title: data.title || node?.id || '',
    config: data.config || {}
  }
}

watch(
  () => props.modelValue,
  (graph) => {
    const fingerprint = JSON.stringify(graph)
    if (fingerprint === lastEmitted.value) {
      return
    }
    syncing.value = true
    const mapped = graphToVueFlow(graph)
    nodes.value = mapped.nodes
    edges.value = mapped.edges
    selectedNodeId.value = ''
    selectedEdgeId.value = ''
    editingNodeId.value = ''
    editingEdgeId.value = ''
    localError.value = ''
    lastEmitted.value = fingerprint
    nextTick(() => {
      syncing.value = false
    })
  },
  { immediate: true, deep: true }
)

function commit() {
  if (props.readonly || syncing.value) {
    return
  }
  try {
    const graph = vueFlowToGraph(props.modelValue.startNodeId, nodes.value, edges.value)
    localError.value = ''
    lastEmitted.value = JSON.stringify(graph)
    emit('update:modelValue', graph)
    emit('change', graph)
  } catch (e) {
    localError.value = e instanceof Error ? e.message : String(e)
  }
}

function onConnect(connection: Connection) {
  if (props.readonly) {
    return
  }
  if (!connection.source || !connection.target || connection.source === connection.target) {
    return
  }
  const sourceNode = nodes.value.find((node) => node.id === connection.source)
  const sourceType = nodeData(sourceNode).flowType
  const usedWhens = edges.value
    .filter((edge) => edge.source === connection.source)
    .map((edge) => String(edge.data?.when || 'default'))
  const when = nextEdgeWhen(sourceType, usedWhens)
  if (!when) {
    return
  }
  const pairTaken = edges.value.some(
    (edge) =>
      edge.source === connection.source &&
      edge.target === connection.target &&
      String(edge.data?.when || 'default') === when
  )
  if (pairTaken) {
    return
  }
  const edgeId = `e-${connection.source}-${connection.target}-${when}-${edges.value.length}`
  edges.value = withParallelEdgeLayout([
    ...edges.value,
    {
      id: edgeId,
      source: connection.source,
      target: connection.target,
      data: { when },
      label: edgeWhenLabel(when),
      animated: when !== 'default',
      markerEnd: 'arrowclosed'
    }
  ])
  nextTick(() => {
    commit()
    if (when !== 'default') {
      openEdgeInspector(edgeId)
    }
  })
}

function onNodeClick(event: { node: { id: string } }) {
  openNodeInspector(event.node.id)
}

function onNodeDoubleClick(event: { node: { id: string } }) {
  openNodeInspector(event.node.id)
}

function onEdgeClick(payload: { edge: { id: string } }) {
  openEdgeInspector(payload.edge.id)
}

function onEdgeDoubleClick(payload: { edge: { id: string } }) {
  openEdgeInspector(payload.edge.id)
}

function onPaneClick() {
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
  editingNodeId.value = ''
  editingEdgeId.value = ''
  closeVarPicker()
}

function openNodeInspector(id: string) {
  selectedNodeId.value = id
  selectedEdgeId.value = ''
  editingNodeId.value = id
  editingEdgeId.value = ''
  promptCaretTouched.value = { systemPrompt: false, text: false }
  promptCaret.value = { systemPrompt: { start: 0, end: 0 }, text: { start: 0, end: 0 } }
  closeVarPicker()
}

function openEdgeInspector(id: string) {
  selectedEdgeId.value = id
  selectedNodeId.value = ''
  editingEdgeId.value = id
  editingNodeId.value = ''
}

function closeInspector() {
  editingNodeId.value = ''
  editingEdgeId.value = ''
  closeVarPicker()
}

function onNodeDragStop() {
  if (!props.readonly) {
    commit()
  }
}

function defaultConfig(type: string): Record<string, unknown> {
  const ports = portsFor(type)
  const base: Record<string, unknown> = {
    inputs: { ...ports.defaultInputs }
  }
  if (type === 'llm_chat') {
    return {
      ...base,
      systemPrompt: String(ports.defaultInputs.systemPrompt || '你是有帮助的中文助手。'),
      memoryWindowTurns: 12,
      skills: [] as string[]
    }
  }
  if (type === 'answer') {
    return { ...base, text: '{{#sys.query#}}' }
  }
  if (type === 'start') {
    return { memoryWindowTurns: 12 }
  }
  if (type === 'knowledge') {
    return { ...base, topK: 5, skills: ['knowledge'] }
  }
  if (type === 'if_else') {
    return {
      ...base,
      left: '{{#知识判定.canAnswer#}}',
      op: '==',
      right: true
    }
  }
  if (type === 'ontology') {
    return { ...base, skills: ['ontology'] }
  }
  if (type === 'metadata') {
    return { ...base, recover: true }
  }
  if (type === 'insight') {
    return { ...base, skills: ['sql', 'preference'] }
  }
  return base
}

function addNode(type: string, position?: { x: number; y: number }) {
  if (props.readonly) {
    return
  }
  if (type === 'start' && nodes.value.some((node) => nodeData(node).flowType === 'start')) {
    localError.value = '流程只能有一个开始节点'
    return
  }
  const meta = catalogItem(type)
  const id = newNodeId(type)
  const maxX = nodes.value.reduce((max, node) => Math.max(max, node.position.x), 0)
  const avgY =
    nodes.value.reduce((sum, node) => sum + node.position.y, 0) / Math.max(nodes.value.length, 1)
  nodes.value = [
    ...nodes.value,
    {
      id,
      type: 'flow',
      position: position || { x: maxX + 220, y: avgY || 120 },
      data: {
        flowType: type,
        title: meta.title,
        config: defaultConfig(type)
      },
      sourcePosition: 'right',
      targetPosition: 'left'
    }
  ]
  openNodeInspector(id)
  commit()
}

function onPaletteDragStart(event: DragEvent, type: string) {
  if (props.readonly || !event.dataTransfer) {
    return
  }
  event.dataTransfer.setData('application/federation-node', type)
  event.dataTransfer.effectAllowed = 'copy'
}

function onCanvasDragOver(event: DragEvent) {
  if (props.readonly) {
    return
  }
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
}

function onCanvasDrop(event: DragEvent) {
  if (props.readonly) {
    return
  }
  event.preventDefault()
  const type = event.dataTransfer?.getData('application/federation-node')
  if (!type) {
    return
  }
  const target = event.currentTarget as HTMLElement
  const rect = target.getBoundingClientRect()
  addNode(type, {
    x: Math.max(0, event.clientX - rect.left - 80),
    y: Math.max(0, event.clientY - rect.top - 24)
  })
}

function toggleSkill(skillId: AskSkillId, enabled: boolean) {
  if (!editingNode.value) {
    return
  }
  const data = nodeData(editingNode.value)
  const current = new Set(skillsForNode(data.flowType, data.config))
  if (enabled) {
    current.add(skillId)
  } else {
    current.delete(skillId)
  }
  const next = [...current]
  updateNodeConfig('skills', next)
  updateInputValue('skills', next)
}

function inputValue(config: Record<string, unknown>, name: string, fallback: unknown): unknown {
  const inputs = (config.inputs || {}) as Record<string, unknown>
  if (name in inputs) {
    return inputs[name]
  }
  if (name in config) {
    return config[name]
  }
  return fallback
}

function updateInputValue(name: string, value: unknown) {
  if (!editingNode.value) {
    return
  }
  const data = nodeData(editingNode.value)
  const inputs = { ...((data.config.inputs || {}) as Record<string, unknown>), [name]: value }
  updateNodeConfig('inputs', inputs)
  if (
    name === 'topK' ||
    name === 'systemPrompt' ||
    name === 'recover' ||
    name === 'skills' ||
    name === 'memoryWindowTurns' ||
    name === 'text' ||
    name === 'result' ||
    name === 'left' ||
    name === 'op' ||
    name === 'right'
  ) {
    updateNodeConfig(name, value)
  }
}

type VarPathItem = { path: string; label: string; group: string }

function availableVarPaths(currentNodeId: string): VarPathItem[] {
  void portsEpoch.value
  const upstream = upstreamNodeIds(currentNodeId, edges.value)
  const paths: VarPathItem[] = sysVars().map((item) => ({
    path: item.path,
    label: item.label,
    group: '系统'
  }))
  for (const node of nodes.value) {
    if (node.id === currentNodeId || !upstream.has(node.id)) {
      continue
    }
    const data = nodeData(node)
    const title = String(data.title || node.id).trim() || String(node.id)
    const group = `${title}（${catalogItem(data.flowType).title}）`
    for (const port of portsFor(data.flowType).outputs) {
      const base = `${title}.${port.name}`
      const typeHint = port.dataType ? ` · ${port.dataType}` : ''
      paths.push({
        path: base,
        label: `${port.label}${typeHint}`,
        group
      })
      if (port.dataType === 'Object' && port.fields?.length) {
        for (const field of port.fields) {
          paths.push({
            path: `${base}.${field.name}`,
            label: `${port.label}.${field.label}${field.dataType ? ` · ${field.dataType}` : ''}`,
            group
          })
        }
      }
      if (port.dataType === 'Array[Object]' && port.fields?.length) {
        for (const field of port.fields) {
          paths.push({
            path: `${base}.0.${field.name}`,
            label: `${port.label}[0].${field.label}${field.dataType ? ` · ${field.dataType}` : ''}`,
            group
          })
        }
      }
    }
  }
  return paths
}

/** 本节点写出的字段（仅字段名 + 说明，不含节点名前缀）。 */
function ownOutputHints(flowType: string): Array<{ name: string; label: string }> {
  void portsEpoch.value
  return portsFor(flowType).outputs.map((port) => ({
    name: port.name,
    label: port.label || port.name
  }))
}

/** 绑定下拉：保留当前已绑路径（如提示词 JSON 字段 canAnswer），即使不在端口目录中。 */
function bindingVarOptions(currentNodeId: string, currentPath: string | null | undefined): VarPathItem[] {
  const paths = availableVarPaths(currentNodeId)
  const cur = (currentPath || '').trim()
  if (!cur || paths.some((item) => item.path === cur)) {
    return paths
  }
  return [{ path: cur, label: cur, group: '已绑定' }, ...paths]
}

/** 按节点分组，供提示词「{x} 插入变量」下拉使用。 */
function varPathGroups(currentNodeId: string): Array<{ group: string; items: VarPathItem[] }> {
  const map = new Map<string, VarPathItem[]>()
  for (const item of availableVarPaths(currentNodeId)) {
    const list = map.get(item.group) || []
    list.push(item)
    map.set(item.group, list)
  }
  return [...map.entries()].map(([group, items]) => ({ group, items }))
}

function filteredVarPathGroups(currentNodeId: string): Array<{ group: string; items: VarPathItem[] }> {
  const q = varPicker.value.filter.trim().toLowerCase()
  return varPathGroups(currentNodeId)
    .map((group) => ({
      group: group.group,
      items: q
        ? group.items.filter(
            (item) =>
              item.path.toLowerCase().includes(q) ||
              item.label.toLowerCase().includes(q) ||
              group.group.toLowerCase().includes(q)
          )
        : group.items
    }))
    .filter((group) => group.items.length > 0)
}

function openVarPicker(fieldName: 'systemPrompt' | 'text') {
  if (props.readonly) {
    return
  }
  const el = fieldName === 'systemPrompt' ? promptTextareaRef.value : answerTextareaRef.value
  if (el) {
    rememberPromptCaret(fieldName, el)
  }
  varPicker.value = {
    field: varPicker.value.field === fieldName ? '' : fieldName,
    filter: ''
  }
}

function pickVarIntoPrompt(path: string) {
  const field = varPicker.value.field
  if (!field || !path) {
    return
  }
  insertVarIntoPrompt(path, field)
  varPicker.value = { field: '', filter: '' }
}

function closeVarPicker() {
  varPicker.value = { field: '', filter: '' }
}

function skillAllowed(flowType: string, skillId: AskSkillId): boolean {
  return skillsBindableOn(flowType).some((skill) => skill.id === skillId)
}

function rememberPromptCaret(fieldName: 'systemPrompt' | 'text', el: EventTarget | null) {
  if (!(el instanceof HTMLTextAreaElement)) {
    return
  }
  promptCaretTouched.value[fieldName] = true
  promptCaret.value[fieldName] = {
    start: el.selectionStart ?? el.value.length,
    end: el.selectionEnd ?? el.value.length
  }
}

function insertVarIntoPrompt(path: string, fieldName: 'systemPrompt' | 'text' = 'systemPrompt') {
  if (!editingNode.value || !path) {
    return
  }
  const data = nodeData(editingNode.value)
  const current = String(
    inputValue(data.config, fieldName, data.config[fieldName] || '') ?? ''
  )
  const snippet = asRef(path)
  const el = fieldName === 'systemPrompt' ? promptTextareaRef.value : answerTextareaRef.value
  let start = promptCaret.value[fieldName].start
  let end = promptCaret.value[fieldName].end
  if (el && document.activeElement === el) {
    start = el.selectionStart ?? start
    end = el.selectionEnd ?? end
  } else if (!promptCaretTouched.value[fieldName]) {
    start = current.length
    end = current.length
  }
  start = Math.max(0, Math.min(start, current.length))
  end = Math.max(start, Math.min(end, current.length))
  const next = current.slice(0, start) + snippet + current.slice(end)
  updateInputValue(fieldName, next)
  const caretPos = start + snippet.length
  promptCaretTouched.value[fieldName] = true
  promptCaret.value[fieldName] = { start: caretPos, end: caretPos }
  nextTick(() => {
    const box = fieldName === 'systemPrompt' ? promptTextareaRef.value : answerTextareaRef.value
    if (!box) {
      return
    }
    box.focus()
    box.setSelectionRange(caretPos, caretPos)
  })
}

function updateNodeConfig(key: string, value: unknown) {
  if (!editingNode.value) {
    return
  }
  const id = editingNode.value.id
  nodes.value = nodes.value.map((node) => {
    if (node.id !== id) {
      return node
    }
    const data = nodeData(node)
    return {
      ...node,
      data: { ...data, config: { ...data.config, [key]: value } }
    }
  })
  commit()
}

function removeSelected() {
  if (props.readonly) {
    return
  }
  if (selectedNode.value) {
    if (nodeData(selectedNode.value).flowType === 'start') {
      localError.value = '不能删除开始节点'
      return
    }
    const id = selectedNode.value.id
    nodes.value = nodes.value.filter((node) => node.id !== id)
    edges.value = edges.value.filter((edge) => edge.source !== id && edge.target !== id)
    selectedNodeId.value = ''
    editingNodeId.value = ''
    commit()
    return
  }
  if (selectedEdge.value) {
    const id = selectedEdge.value.id
    edges.value = edges.value.filter((edge) => edge.id !== id)
    selectedEdgeId.value = ''
    editingEdgeId.value = ''
    commit()
  }
}

function updateNodeTitle(title: string) {
  if (!editingNode.value) {
    return
  }
  const trimmed = title.trim()
  if (!trimmed) {
    localError.value = '节点名称不能为空'
    return
  }
  if (/[#.{}]/.test(trimmed)) {
    localError.value = '节点名称不能包含 # . { }'
    return
  }
  const id = editingNode.value.id
  const duplicated = nodes.value.some(
    (node) => node.id !== id && String(nodeData(node).title || '').trim() === trimmed
  )
  if (duplicated) {
    localError.value = `节点名称不能重复：${trimmed}`
    return
  }
  localError.value = ''
  nodes.value = nodes.value.map((node) => {
    if (node.id !== id) {
      return node
    }
    const data = nodeData(node)
    return { ...node, data: { ...data, title: trimmed } }
  })
  commit()
}

function updateNodePrompt(prompt: string) {
  if (!editingNode.value) {
    return
  }
  const id = editingNode.value.id
  nodes.value = nodes.value.map((node) => {
    if (node.id !== id) {
      return node
    }
    const data = nodeData(node)
    return {
      ...node,
      data: { ...data, config: { ...data.config, systemPrompt: prompt } }
    }
  })
  commit()
}

function updateEdgeWhen(when: string) {
  if (!editingEdge.value || props.readonly) {
    return
  }
  const current = editingEdge.value
  const clash = edges.value.some(
    (edge) =>
      edge.id !== current.id &&
      edge.source === current.source &&
      edge.target === current.target &&
      String(edge.data?.when || 'default') === when
  )
  if (clash) {
    return
  }
  const nextId = `e-${current.source}-${current.target}-${when}`
  edges.value = withParallelEdgeLayout(
    edges.value.map((edge) =>
      edge.id === current.id
        ? {
            ...edge,
            id: nextId,
            data: { ...(edge.data || {}), when },
            label: edgeWhenLabel(when),
            animated: when !== 'default'
          }
        : edge
    )
  )
  editingEdgeId.value = nextId
  selectedEdgeId.value = nextId
  commit()
}

function edgeWhenChoices(edge: { source?: string } | null) {
  const source = nodes.value.find((node) => node.id === edge?.source)
  return edgeWhenOptionsFor(nodeData(source).flowType)
}

function edgeEndpointTitle(nodeId: string) {
  const node = nodes.value.find((item) => item.id === nodeId)
  return String(nodeData(node).title || nodeId)
}

const selectedData = computed(() => {
  void portsEpoch.value
  return nodeData(editingNode.value)
})

const selectedPorts = computed(() => {
  void portsEpoch.value
  return portsFor(selectedData.value.flowType)
})

/** LLM 节点：记忆 / 提示词 / 技能已有专用区，不再重复列出端口表单。 */
const HIDDEN_INPUT_PORTS: Record<string, string[]> = {
  llm_chat: ['query', 'memoryWindowTurns', 'skills', 'systemPrompt'],
  start: ['memoryWindowTurns'],
  insight: ['skills'],
  ontology: ['skills']
}

const visibleInputPorts = computed(() => {
  const flowType = selectedData.value.flowType
  const hidden = new Set(HIDDEN_INPUT_PORTS[flowType] ?? [])
  if (skillsBindableOn(flowType).length) {
    hidden.add('skills')
  }
  if (flowType === 'if_else') {
    const op = String(
      inputValue(
        selectedData.value.config,
        'op',
        selectedPorts.value.defaultInputs.op ?? '=='
      ) ?? ''
    )
    if (op === 'empty' || op === 'not_empty') {
      hidden.add('right')
    }
  }
  return selectedPorts.value.inputs.filter((p) => !hidden.has(p.name))
})
</script>

<template>
  <div class="dag" :class="{ readonly }">
    <aside class="palette" aria-label="节点调色板">
      <h3>节点</h3>
      <button
        v-for="item in addable"
        :key="item.type"
        type="button"
        class="palette-item"
        :disabled="readonly"
        draggable="true"
        :style="{ '--swatch': item.color }"
        @dragstart="onPaletteDragStart($event, item.type)"
        @click="addNode(item.type)"
      >
        <span class="swatch" aria-hidden="true" />
        <span>
          <strong>{{ item.title }}</strong>
          <small>{{ item.description }}</small>
        </span>
      </button>
      <p class="hint">拖到画布添加；单击节点即可配置（可改节点名称）。</p>
    </aside>

    <div
      class="canvas"
      role="application"
      aria-label="流程画布"
      @dragover="onCanvasDragOver"
      @drop="onCanvasDrop"
    >
      <VueFlow
        v-model:nodes="nodes"
        v-model:edges="edges"
        :node-types="nodeTypes"
        :nodes-draggable="!readonly"
        :nodes-connectable="!readonly"
        :elements-selectable="true"
        :default-edge-options="{ markerEnd: 'arrowclosed' }"
        fit-view-on-init
        @connect="onConnect"
        @node-click="onNodeClick"
        @node-double-click="onNodeDoubleClick"
        @edge-click="onEdgeClick"
        @edge-double-click="onEdgeDoubleClick"
        @pane-click="onPaneClick"
        @node-drag-stop="onNodeDragStop"
      >
        <Background pattern-color="#66666655" :gap="18" />
        <Controls />
        <MiniMap pannable zoomable />
      </VueFlow>
      <p v-if="localError" class="err" role="alert">{{ localError }}</p>
    </div>

    <aside class="inspector" aria-label="节点属性">
      <template v-if="editingNode">
        <div class="inspector-head">
          <div class="name-block">
            <span class="type-badge">{{ catalogItem(selectedData.flowType).title }}</span>
            <input
              class="node-name"
              :value="selectedData.title"
              type="text"
              maxlength="64"
              :disabled="readonly"
              placeholder="节点名称（不可重复）"
              @change="updateNodeTitle(($event.target as HTMLInputElement).value)"
            />
          </div>
          <button type="button" class="ghost" @click="closeInspector">关闭</button>
        </div>
        <label v-if="selectedData.flowType === 'start' || selectedData.flowType === 'llm_chat'">
          对话记忆（最近 N 条）
          <input
            :value="
              Number(
                inputValue(
                  selectedData.config,
                  'memoryWindowTurns',
                  selectedData.config.memoryWindowTurns ?? 12
                )
              )
            "
            type="number"
            min="0"
            max="100"
            :disabled="readonly"
            @change="
              selectedData.flowType === 'llm_chat'
                ? updateInputValue(
                    'memoryWindowTurns',
                    Number(($event.target as HTMLInputElement).value) || 0
                  )
                : updateNodeConfig(
                    'memoryWindowTurns',
                    Number(($event.target as HTMLInputElement).value) || 0
                  )
            "
          />
        </label>
        <fieldset
          v-if="visibleInputPorts.length"
          class="skills"
          :disabled="readonly"
        >
          <legend>输入</legend>
          <label
            v-for="port in visibleInputPorts"
            :key="port.name"
            class="io-field"
          >
            <span>
              <strong>{{ port.label }}</strong>
              <small v-if="port.dataType">{{ port.dataType }}</small>
            </span>
            <template v-if="port.name === 'topK'">
              <input
                type="number"
                min="1"
                max="20"
                :value="Number(inputValue(selectedData.config, 'topK', 5))"
                :disabled="readonly"
                @change="
                  updateInputValue('topK', Number(($event.target as HTMLInputElement).value) || 5)
                "
              />
            </template>
            <template v-else-if="port.name === 'recover'">
              <label class="check">
                <input
                  type="checkbox"
                  :checked="inputValue(selectedData.config, 'recover', true) !== false"
                  :disabled="readonly"
                  @change="updateInputValue('recover', ($event.target as HTMLInputElement).checked)"
                />
                启用
              </label>
            </template>
            <template v-else-if="port.name === 'op'">
              <select
                :value="String(inputValue(selectedData.config, 'op', selectedPorts.defaultInputs.op ?? '==') ?? '==')"
                :disabled="readonly"
                @change="updateInputValue('op', ($event.target as HTMLSelectElement).value)"
              >
                <option v-for="item in IF_ELSE_OPS" :key="item.value" :value="item.value">
                  {{ item.label }}
                </option>
              </select>
            </template>
            <template v-else-if="port.name === 'text'">
              <div class="prompt-box">
                <div class="prompt-tools">
                  <span class="char-count">{{
                    String(
                      inputValue(
                        selectedData.config,
                        'text',
                        selectedData.config.text || selectedPorts.defaultInputs.text || ''
                      ) ?? ''
                    ).length
                  }}</span>
                  <button
                    type="button"
                    class="var-btn"
                    title="插入变量"
                    :disabled="readonly"
                    :class="{ open: varPicker.field === 'text' }"
                    @mousedown.prevent="openVarPicker('text')"
                  >
                    {x}
                  </button>
                </div>
                <textarea
                  ref="answerTextareaRef"
                  rows="4"
                  :value="
                    String(
                      inputValue(
                        selectedData.config,
                        port.name,
                        selectedData.config[port.name] || selectedPorts.defaultInputs[port.name] || ''
                      )
                    )
                  "
                  :disabled="readonly"
                  @click="rememberPromptCaret('text', $event.target)"
                  @keyup="rememberPromptCaret('text', $event.target)"
                  @select="rememberPromptCaret('text', $event.target)"
                  @blur="rememberPromptCaret('text', $event.target)"
                  @input="
                    rememberPromptCaret('text', $event.target);
                    updateInputValue('text', ($event.target as HTMLTextAreaElement).value)
                  "
                />
                <div v-if="varPicker.field === 'text'" class="var-picker" @mousedown.prevent>
                  <input
                    v-model="varPicker.filter"
                    type="search"
                    class="var-filter"
                    placeholder="搜索变量"
                    autofocus
                  />
                  <div class="var-picker-body">
                    <template v-if="filteredVarPathGroups(editingNode.id).length">
                      <div
                        v-for="group in filteredVarPathGroups(editingNode.id)"
                        :key="group.group"
                        class="var-group"
                      >
                        <div class="var-group-title">{{ group.group }}</div>
                        <button
                          v-for="item in group.items"
                          :key="item.path"
                          type="button"
                          class="var-item"
                          @mousedown.prevent="pickVarIntoPrompt(item.path)"
                        >
                          <span class="var-x">{x}</span>
                          <span class="var-label">{{ item.label }}</span>
                          <code>{{ item.path }}</code>
                        </button>
                      </div>
                    </template>
                    <p v-else class="hint">无上游变量（请先连线上游节点）</p>
                  </div>
                </div>
              </div>
            </template>
            <template v-else>
              <select
                :value="
                  parseRef(
                    inputValue(selectedData.config, port.name, selectedPorts.defaultInputs[port.name])
                  ) || ''
                "
                :disabled="readonly"
                @change="
                  updateInputValue(
                    port.name,
                    ($event.target as HTMLSelectElement).value
                      ? asRef(($event.target as HTMLSelectElement).value)
                      : ''
                  )
                "
              >
                <option value="">固定值</option>
                <option
                  v-for="item in bindingVarOptions(
                    editingNode.id,
                    parseRef(
                      inputValue(selectedData.config, port.name, selectedPorts.defaultInputs[port.name])
                    )
                  )"
                  :key="item.path"
                  :value="item.path"
                >
                  {{ item.group }} / {x} {{ item.label }}
                </option>
              </select>
              <input
                v-if="!parseRef(inputValue(selectedData.config, port.name, ''))"
                type="text"
                :value="String(inputValue(selectedData.config, port.name, '') ?? '')"
                :disabled="readonly"
                :placeholder="port.name === 'right' ? '例如 true / 0' : '固定值'"
                @change="updateInputValue(port.name, ($event.target as HTMLInputElement).value)"
              />
            </template>
          </label>
        </fieldset>

        <fieldset
          v-if="selectedData.flowType === 'llm_chat'"
          class="skills"
          :disabled="readonly"
        >
          <legend>SYSTEM 提示词</legend>
          <div class="prompt-box">
            <div class="prompt-tools">
              <span class="char-count">{{
                String(
                  inputValue(
                    selectedData.config,
                    'systemPrompt',
                    selectedData.config.systemPrompt ||
                      selectedPorts.defaultInputs.systemPrompt ||
                      ''
                  ) ?? ''
                ).length
              }}</span>
              <button
                type="button"
                class="var-btn"
                title="插入变量"
                :disabled="readonly"
                :class="{ open: varPicker.field === 'systemPrompt' }"
                @mousedown.prevent="openVarPicker('systemPrompt')"
              >
                {x}
              </button>
            </div>
            <textarea
              ref="promptTextareaRef"
              rows="8"
              :value="
                String(
                  inputValue(
                    selectedData.config,
                    'systemPrompt',
                    selectedData.config.systemPrompt ||
                      portsFor('llm_chat').defaultInputs.systemPrompt ||
                      selectedPorts.defaultInputs.systemPrompt ||
                      ''
                  )
                )
              "
              :disabled="readonly"
              placeholder="在这里写提示词，点右上角 {x} 插入变量"
              @click="rememberPromptCaret('systemPrompt', $event.target)"
              @keyup="rememberPromptCaret('systemPrompt', $event.target)"
              @select="rememberPromptCaret('systemPrompt', $event.target)"
              @blur="rememberPromptCaret('systemPrompt', $event.target)"
              @input="
                rememberPromptCaret('systemPrompt', $event.target);
                updateInputValue('systemPrompt', ($event.target as HTMLTextAreaElement).value)
              "
            />
            <div v-if="varPicker.field === 'systemPrompt'" class="var-picker" @mousedown.prevent>
              <input
                v-model="varPicker.filter"
                type="search"
                class="var-filter"
                placeholder="搜索变量"
                autofocus
              />
              <div class="var-picker-body">
                <template v-if="filteredVarPathGroups(editingNode.id).length">
                  <div
                    v-for="group in filteredVarPathGroups(editingNode.id)"
                    :key="group.group"
                    class="var-group"
                  >
                    <div class="var-group-title">{{ group.group }}</div>
                    <button
                      v-for="item in group.items"
                      :key="item.path"
                      type="button"
                      class="var-item"
                      @mousedown.prevent="pickVarIntoPrompt(item.path)"
                    >
                      <span class="var-x">{x}</span>
                      <span class="var-label">{{ item.label }}</span>
                      <code>{{ item.path }}</code>
                    </button>
                  </div>
                </template>
                <p v-else class="hint">无上游变量（请先连线上游节点）</p>
              </div>
            </div>
          </div>
        </fieldset>

        <fieldset
          v-if="skillsBindableOn(selectedData.flowType).length"
          class="skills"
          :disabled="readonly"
        >
          <legend>工具技能</legend>
          <label
            v-for="skill in skillsBindableOn(selectedData.flowType)"
            :key="skill.id"
            class="check skill-block"
          >
            <input
              type="checkbox"
              :checked="skillsForNode(selectedData.flowType, selectedData.config).includes(skill.id)"
              :disabled="readonly || (selectedData.flowType === 'insight' && skill.id === 'sql')"
              @change="toggleSkill(skill.id, ($event.target as HTMLInputElement).checked)"
            />
            <span>
              <strong>{{ skill.label }}</strong>
              <small>{{ skill.description }}</small>
            </span>
          </label>
        </fieldset>
        <div v-if="ownOutputHints(selectedData.flowType).length" class="out-block">
          <div class="out-title">本节点输出</div>
          <ul class="out-list">
            <li v-for="item in ownOutputHints(selectedData.flowType)" :key="item.name">
              <code>{{ item.name }}</code>
              <span>{{ item.label }}</span>
            </li>
          </ul>
          <p v-if="selectedData.flowType === 'llm_chat'" class="hint">
            JSON 字段会写入；澄清会并入 answer.clarify，下游用「节点名.字段」引用。
          </p>
          <p v-else-if="selectedData.flowType === 'ontology'" class="hint">
            澄清会并入 context.clarify。
          </p>
        </div>
        <button type="button" class="danger" :disabled="readonly" @click="removeSelected">删除节点</button>
      </template>
      <template v-else-if="editingEdge">
        <div class="inspector-head">
          <h3>配置连线</h3>
          <button type="button" class="ghost" @click="closeInspector">关闭</button>
        </div>
        <p class="hint">
          {{ edgeEndpointTitle(editingEdge.source) }} → {{ edgeEndpointTitle(editingEdge.target) }}
        </p>
        <label>
          分支
          <select
            :value="String(editingEdge.data?.when || 'default')"
            :disabled="readonly"
            @change="updateEdgeWhen(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="opt in edgeWhenChoices(editingEdge)" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </label>
        <button type="button" class="danger" :disabled="readonly" @click="removeSelected">删除连线</button>
      </template>
      <template v-else>
        <h3>属性</h3>
        <p class="hint">
          单击节点配置；变量只能引用上游出参；从条件/本体等拖线会自动标分支。
        </p>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.dag {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr) minmax(280px, 340px);
  min-height: 420px;
  height: min(70vh, 640px);
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  background: var(--panel);
}

.palette,
.inspector {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  background: var(--panel-2);
  overflow: auto;
}

.inspector-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.out-block {
  margin-top: auto;
  padding-top: 8px;
  border-top: 1px solid var(--line);
}

.out-title {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 6px;
}

.out-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.out-list li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
}

.out-list code {
  font-size: 11px;
  min-width: 7em;
}

.out-list span {
  color: var(--muted);
}

.name-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  flex: 1;
}

.type-badge {
  font-size: 11px;
  color: var(--muted);
}

.node-name {
  font-size: 15px;
  font-weight: 600;
  padding: 6px 8px;
}

.prompt-box {
  position: relative;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  overflow: hidden;
}

.prompt-tools {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--line);
  background: var(--panel-2);
}

.char-count {
  font-size: 11px;
  color: var(--muted);
}

.var-btn {
  border: 1px solid var(--line);
  background: var(--panel);
  color: var(--text);
  border-radius: 6px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  line-height: 1.4;
}

.var-btn:hover:not(:disabled),
.var-btn.open {
  border-color: var(--accent);
  color: var(--accent);
}

.var-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.prompt-box textarea {
  border: none;
  border-radius: 0;
  width: 100%;
  resize: vertical;
  background: transparent;
}

.var-picker {
  position: absolute;
  right: 8px;
  top: 34px;
  z-index: 20;
  width: min(280px, calc(100% - 16px));
  max-height: 260px;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.18);
  overflow: hidden;
}

.var-filter {
  margin: 0;
  border: none;
  border-bottom: 1px solid var(--line);
  border-radius: 0;
  padding: 8px 10px;
  font-size: 12px;
}

.var-picker-body {
  overflow: auto;
  max-height: 220px;
  padding: 4px 0 8px;
}

.var-group-title {
  padding: 6px 10px 2px;
  font-size: 11px;
  color: var(--muted);
}

.var-item {
  display: grid;
  grid-template-columns: auto 1fr;
  grid-template-rows: auto auto;
  column-gap: 8px;
  width: 100%;
  border: none;
  background: transparent;
  color: var(--text);
  text-align: left;
  padding: 6px 10px;
  cursor: pointer;
}

.var-item:hover {
  background: color-mix(in srgb, var(--accent) 12%, transparent);
}

.var-x {
  grid-row: 1 / span 2;
  align-self: center;
  font-size: 11px;
  font-weight: 700;
  color: var(--accent);
}

.var-label {
  font-size: 12px;
}

.var-item code {
  grid-column: 2;
  font-size: 10px;
  color: var(--muted);
}

.inspector .primary,
.inspector .ghost,
.inspector .danger {
  width: 100%;
}

.inspector .ghost {
  width: auto;
  border: 1px solid var(--line);
  background: transparent;
  color: var(--muted);
  border-radius: 6px;
  padding: 4px 8px;
  cursor: pointer;
  font-size: 12px;
}

.inspector .primary {
  border: none;
  background: var(--accent);
  color: #fff;
  border-radius: 6px;
  padding: 8px 10px;
  cursor: pointer;
  font-size: 13px;
}

.skills {
  margin: 0;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.skills legend {
  padding: 0 4px;
  font-size: 12px;
  color: var(--muted);
}

.check {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  font-size: 12px;
}

.check small {
  display: block;
  color: var(--muted);
  font-weight: normal;
}

.io-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}

.io-field small {
  color: var(--muted);
}

.io-out {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.io-out code {
  font-size: 11px;
}

.io-out small {
  display: block;
  color: var(--muted);
}

.io-fields {
  list-style: none;
  margin: 4px 0 0;
  padding: 0 0 0 12px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  border-left: 2px solid var(--line);
}

.io-fields code {
  font-size: 10px;
}

.io-fields small {
  display: inline;
  margin-left: 6px;
}

.skill-block .tool-list {
  margin: 4px 0 0;
  padding-left: 16px;
  color: var(--muted);
  font-size: 11px;
}

.skill-block code {
  font-size: 10px;
}

.palette {
  border-right: 1px solid var(--line);
}

.inspector {
  border-left: 1px solid var(--line);
}

h3 {
  margin: 0 0 4px;
  font-size: 13px;
}

.palette-item {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  width: 100%;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  color: var(--text);
  text-align: left;
}

.palette-item:hover:not(:disabled) {
  border-color: var(--accent);
}

.palette-item strong {
  display: block;
  font-size: 12px;
}

.palette-item small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--muted);
}

.swatch {
  width: 8px;
  height: 8px;
  margin-top: 4px;
  border-radius: 50%;
  background: var(--swatch);
  flex: none;
}

.canvas {
  position: relative;
  min-width: 0;
  min-height: 420px;
}

.canvas :deep(.vue-flow) {
  width: 100%;
  height: 100%;
  background: var(--panel);
}

.hint {
  margin: 0;
  font-size: 11px;
  color: var(--muted);
  line-height: 1.4;
}

.err {
  position: absolute;
  left: 12px;
  bottom: 12px;
  z-index: 5;
  margin: 0;
  padding: 6px 10px;
  border-radius: 8px;
  background: #5c2222;
  color: #f2b8b5;
  font-size: 12px;
}

label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--muted);
}

input,
select,
textarea {
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  color: var(--text);
  font: inherit;
}

button.danger {
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: transparent;
  color: #f2b8b5;
}

@media (max-width: 960px) {
  .dag {
    grid-template-columns: 1fr;
    grid-template-rows: auto 360px auto;
    height: auto;
  }

  .palette,
  .inspector {
    border: 0;
  }
}
</style>
