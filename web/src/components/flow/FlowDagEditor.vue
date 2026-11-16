<script setup lang="ts">
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import {
  VueFlow,
  type Connection,
  type NodeTypesObject
} from '@vue-flow/core'
import { computed, markRaw, nextTick, ref, watch } from 'vue'
import type { FlowGraph } from '../../types'
import {
  graphToVueFlow,
  newNodeId,
  vueFlowToGraph,
  type DagNodeData
} from '../../flow/graphAdapter'
import { EDGE_WHEN_OPTIONS, NODE_CATALOG, catalogItem } from '../../flow/nodeCatalog'
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
const selectedNodeId = ref('')
const selectedEdgeId = ref('')
const localError = ref('')
const syncing = ref(false)
const lastEmitted = ref('')

const nodeTypes = { flow: markRaw(FlowNodeCard) } as unknown as NodeTypesObject

const selectedNode = computed(
  () => nodes.value.find((node) => node.id === selectedNodeId.value) || null
)
const selectedEdge = computed(
  () => edges.value.find((edge) => edge.id === selectedEdgeId.value) || null
)
const addable = computed(() => NODE_CATALOG.filter((item) => item.addable))

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
  const exists = edges.value.some(
    (edge) => edge.source === connection.source && edge.target === connection.target
  )
  if (exists) {
    return
  }
  edges.value = [
    ...edges.value,
    {
      id: `e-${connection.source}-${connection.target}-default-${edges.value.length}`,
      source: connection.source,
      target: connection.target,
      data: { when: 'default' },
      label: '',
      animated: false,
      markerEnd: 'arrowclosed'
    }
  ]
  nextTick(commit)
}

function onNodeClick(event: { node: { id: string } }) {
  selectedNodeId.value = event.node.id
  selectedEdgeId.value = ''
}

function onEdgeClick(payload: { edge: { id: string } }) {
  selectedEdgeId.value = payload.edge.id
  selectedNodeId.value = ''
}

function onPaneClick() {
  selectedNodeId.value = ''
  selectedEdgeId.value = ''
}

function onNodeDragStop() {
  if (!props.readonly) {
    commit()
  }
}

function defaultConfig(type: string): Record<string, unknown> {
  if (type === 'llm_chat') {
    return { systemPrompt: '你是有帮助的中文助手。' }
  }
  if (type === 'start') {
    return { memoryWindowTurns: 12 }
  }
  if (type === 'knowledge') {
    return { enabled: true, topK: 5 }
  }
  return {}
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
  selectedNodeId.value = id
  selectedEdgeId.value = ''
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

function updateNodeConfig(key: string, value: unknown) {
  if (!selectedNode.value) {
    return
  }
  const id = selectedNode.value.id
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
    commit()
    return
  }
  if (selectedEdge.value) {
    const id = selectedEdge.value.id
    edges.value = edges.value.filter((edge) => edge.id !== id)
    selectedEdgeId.value = ''
    commit()
  }
}

function updateNodeTitle(title: string) {
  if (!selectedNode.value) {
    return
  }
  const id = selectedNode.value.id
  nodes.value = nodes.value.map((node) => {
    if (node.id !== id) {
      return node
    }
    const data = nodeData(node)
    return { ...node, data: { ...data, title } }
  })
  commit()
}

function updateNodePrompt(prompt: string) {
  if (!selectedNode.value) {
    return
  }
  const id = selectedNode.value.id
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
  if (!selectedEdge.value) {
    return
  }
  edges.value = edges.value.map((edge) =>
    edge.id === selectedEdge.value!.id
      ? {
          ...edge,
          data: { ...(edge.data || {}), when },
          label: when === 'default' ? '' : when,
          animated: when !== 'default'
        }
      : edge
  )
  commit()
}

const selectedData = computed(() => nodeData(selectedNode.value))
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
      <p class="hint">从左侧拖到画布添加节点（也可点击）；节点右侧把手连线；点选边改分支条件。</p>
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
        @edge-click="onEdgeClick"
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
      <template v-if="selectedNode">
        <h3>节点</h3>
        <label>
          类型
          <input :value="catalogItem(selectedData.flowType).title" type="text" readonly />
        </label>
        <label>
          标题
          <input
            :value="selectedData.title"
            type="text"
            maxlength="64"
            :disabled="readonly"
            @change="updateNodeTitle(($event.target as HTMLInputElement).value)"
          />
        </label>
        <label v-if="selectedData.flowType === 'start'">
          对话记忆（最近 N 条消息）
          <input
            :value="Number(selectedData.config.memoryWindowTurns ?? 12)"
            type="number"
            min="0"
            max="100"
            :disabled="readonly"
            @change="
              updateNodeConfig(
                'memoryWindowTurns',
                Number(($event.target as HTMLInputElement).value) || 0
              )
            "
          />
        </label>
        <label v-if="selectedData.flowType === 'llm_chat'">
          系统提示词
          <textarea
            :value="String(selectedData.config.systemPrompt || '')"
            rows="5"
            :disabled="readonly"
            @change="updateNodePrompt(($event.target as HTMLTextAreaElement).value)"
          />
        </label>
        <label v-if="selectedData.flowType === 'knowledge'">
          TopK
          <input
            :value="Number(selectedData.config.topK ?? 5)"
            type="number"
            min="1"
            max="20"
            :disabled="readonly"
            @change="updateNodeConfig('topK', Number(($event.target as HTMLInputElement).value) || 5)"
          />
        </label>
        <p
          v-if="['knowledge', 'ontology', 'metadata', 'insight'].includes(selectedData.flowType)"
          class="hint"
        >
          当前运行仍整段折叠为一次问数；边条件（answered / continue 等）待拆跑后生效。画布用于编排与配置。
        </p>
        <button type="button" class="danger" :disabled="readonly" @click="removeSelected">删除节点</button>
      </template>
      <template v-else-if="selectedEdge">
        <h3>连线 / 分支</h3>
        <label>
          条件
          <select
            :value="String(selectedEdge.data?.when || 'default')"
            :disabled="readonly"
            @change="updateEdgeWhen(($event.target as HTMLSelectElement).value)"
          >
            <option v-for="opt in EDGE_WHEN_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </label>
        <p class="hint">{{ selectedEdge.source }} → {{ selectedEdge.target }}</p>
        <button type="button" class="danger" :disabled="readonly" @click="removeSelected">删除连线</button>
      </template>
      <template v-else>
        <h3>属性</h3>
        <p class="hint">点选画布上的节点或连线进行编辑。</p>
      </template>
    </aside>
  </div>
</template>

<style scoped>
.dag {
  display: grid;
  grid-template-columns: 168px minmax(0, 1fr) 200px;
  min-height: 420px;
  height: min(62vh, 560px);
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
