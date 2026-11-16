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
import { asRef, parseRef, portsFor, SYS_VARS } from '../../flow/nodePorts'
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

function toggleSkill(skillId: AskSkillId, enabled: boolean) {
  if (!selectedNode.value) {
    return
  }
  const data = nodeData(selectedNode.value)
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
  if (!selectedNode.value) {
    return
  }
  const data = nodeData(selectedNode.value)
  const inputs = { ...((data.config.inputs || {}) as Record<string, unknown>), [name]: value }
  updateNodeConfig('inputs', inputs)
  if (
    name === 'topK' ||
    name === 'systemPrompt' ||
    name === 'recover' ||
    name === 'skills' ||
    name === 'memoryWindowTurns' ||
    name === 'text'
  ) {
    updateNodeConfig(name, value)
  }
}

function availableVarPaths(currentNodeId: string): Array<{ path: string; label: string }> {
  const paths: Array<{ path: string; label: string }> = SYS_VARS.map((item) => ({
    path: item.path,
    label: item.label
  }))
  for (const node of nodes.value) {
    const data = nodeData(node)
    if (node.id === currentNodeId) {
      continue
    }
    for (const port of portsFor(data.flowType).outputs) {
      paths.push({
        path: `${String(node.id)}.${port.name}`,
        label: `${data.title || node.id}.${port.label}`
      })
    }
  }
  return paths
}

function skillAllowed(flowType: string, skillId: AskSkillId): boolean {
  return skillsBindableOn(flowType).some((skill) => skill.id === skillId)
}

function insertVarIntoPrompt(path: string) {
  if (!selectedNode.value || !path) {
    return
  }
  const data = nodeData(selectedNode.value)
  const current = String(
    inputValue(data.config, 'systemPrompt', data.config.systemPrompt || '') ?? ''
  )
  const snippet = asRef(path)
  const next = current ? `${current}\n${snippet}` : snippet
  updateInputValue('systemPrompt', next)
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
        <label v-if="selectedData.flowType === 'start' || selectedData.flowType === 'llm_chat'">
          对话记忆（最近 N 条消息）
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
          <small v-if="selectedData.flowType === 'llm_chat'" class="hint">
            仅作用于本 LLM 节点；覆盖开始节点窗口。
          </small>
        </label>
        <fieldset
          v-if="portsFor(selectedData.flowType).inputs.length"
          class="skills"
          :disabled="readonly"
        >
          <legend>输入</legend>
          <label
            v-for="port in portsFor(selectedData.flowType).inputs"
            :key="port.name"
            class="io-field"
          >
            <span>
              <strong>{{ port.label }}</strong>
              <small>{{ port.description }}</small>
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
            <template v-else-if="port.name === 'skills'">
              <span class="hint">见下方「工具技能」</span>
            </template>
            <template v-else-if="port.name === 'systemPrompt' || port.name === 'text'">
              <textarea
                rows="4"
                :value="
                  String(
                    inputValue(
                      selectedData.config,
                      port.name,
                      selectedData.config[port.name] || portsFor(selectedData.flowType).defaultInputs[port.name] || ''
                    )
                  )
                "
                :disabled="readonly"
                @change="
                  updateInputValue(port.name, ($event.target as HTMLTextAreaElement).value)
                "
              />
              <label v-if="port.name === 'systemPrompt'" class="inline">
                插入变量
                <select
                  :disabled="readonly"
                  @change="
                    insertVarIntoPrompt(($event.target as HTMLSelectElement).value);
                    ($event.target as HTMLSelectElement).value = ''
                  "
                >
                  <option value="">选择…</option>
                  <option
                    v-for="item in availableVarPaths(selectedNode.id)"
                    :key="item.path"
                    :value="item.path"
                  >
                    {{ item.path }}
                  </option>
                </select>
              </label>
            </template>
            <template v-else-if="port.name === 'memoryWindowTurns'">
              <span class="hint">见上方「对话记忆」</span>
            </template>
            <template v-else>
              <select
                :value="parseRef(inputValue(selectedData.config, port.name, portsFor(selectedData.flowType).defaultInputs[port.name])) || ''"
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
                <option value="">（字面量 / 空）</option>
                <option
                  v-for="item in availableVarPaths(selectedNode.id)"
                  :key="item.path"
                  :value="item.path"
                >
                  {{ item.path }} — {{ item.label }}
                </option>
              </select>
              <input
                v-if="!parseRef(inputValue(selectedData.config, port.name, ''))"
                type="text"
                :value="String(inputValue(selectedData.config, port.name, '') ?? '')"
                :disabled="readonly"
                placeholder="字面量或 {{#sys.query#}}"
                @change="updateInputValue(port.name, ($event.target as HTMLInputElement).value)"
              />
            </template>
          </label>
        </fieldset>

        <fieldset
          v-if="portsFor(selectedData.flowType).outputs.length"
          class="skills"
        >
          <legend>输出</legend>
          <ul class="io-out">
            <li v-for="port in portsFor(selectedData.flowType).outputs" :key="port.name">
              <code>{{ selectedNode.id }}.{{ port.name }}</code>
              <small>{{ port.label }} — {{ port.description }}</small>
            </li>
          </ul>
          <p class="hint">下游可用双花括号 #节点ID.字段# 语法引用（与 Dify 相同）。</p>
        </fieldset>

        <fieldset
          v-if="skillsBindableOn(selectedData.flowType).length"
          class="skills"
          :disabled="readonly"
        >
          <legend>工具技能（自由绑定）</legend>
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
              <ul class="tool-list">
                <li v-for="tool in skill.tools" :key="tool.name">
                  <code>{{ tool.name }}</code> {{ tool.label }}
                </li>
              </ul>
              <small v-if="selectedData.flowType === 'llm_chat' && skill.id === 'sql'" class="hint">
                勾选后走联邦 SQL（原「SQL 洞察」节点能力）。
              </small>
            </span>
          </label>
          <p class="hint">已有工具按技能包展示；勾选即绑定到本节点运行时。</p>
        </fieldset>
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
