<script setup lang="ts">
import { computed, ref } from 'vue'
import { askPreviewStream } from '../../api'
import type { FlowGraph } from '../../types'
import { errMessage } from '../../types'
import type { NodeRunStatus } from '../../flow/graphAdapter'

const props = defineProps<{
  appId: string
  graph: FlowGraph
  dirty?: boolean
}>()

const emit = defineEmits<{
  'update:nodeStatuses': [statuses: Record<string, NodeRunStatus>]
  'update:activeEdges': [edgeKeys: string[]]
  'node-outputs': [payload: { nodeId: string; outputs: Record<string, unknown> }]
  'select-node': [nodeId: string]
}>()

type PreviewLine =
  | { kind: 'user'; text: string }
  | { kind: 'assistant'; text: string }
  | { kind: 'system'; text: string }
  | { kind: 'error'; text: string }

const question = ref('')
const busy = ref(false)
const lines = ref<PreviewLine[]>([])
const nodeStatuses = ref<Record<string, NodeRunStatus>>({})
const activeEdges = ref<string[]>([])
const selectedOutputs = ref<Record<string, Record<string, unknown>>>({})
const inspectNodeId = ref('')
const abort = ref<AbortController | null>(null)
/** 上一完成节点，用于边高亮：finish → 下一 start */
let lastFinish: { nodeId: string; outcome: string } | null = null

function edgeKey(from: string, to: string, when: string) {
  return `${from}->${to}:${when || 'default'}`
}

function setActiveEdges(keys: string[]) {
  activeEdges.value = keys
  emit('update:activeEdges', [...keys])
}

const inspectJson = computed(() => {
  const id = inspectNodeId.value
  if (!id) {
    return ''
  }
  const outs = selectedOutputs.value[id]
  return outs ? JSON.stringify(outs, null, 2) : '（该节点尚无出参）'
})

function setStatuses(next: Record<string, NodeRunStatus>) {
  nodeStatuses.value = next
  emit('update:nodeStatuses', { ...next })
}

function resetStatuses() {
  const blank: Record<string, NodeRunStatus> = {}
  for (const node of props.graph.nodes || []) {
    blank[node.id] = 'idle'
  }
  setStatuses(blank)
  lastFinish = null
  setActiveEdges([])
}

function stop() {
  abort.value?.abort()
  abort.value = null
  busy.value = false
}

async function send() {
  const q = question.value.trim()
  if (!q || busy.value) {
    return
  }
  stop()
  resetStatuses()
  selectedOutputs.value = {}
  lines.value = [...lines.value, { kind: 'user', text: q }]
  question.value = ''
  busy.value = true
  const controller = new AbortController()
  abort.value = controller
  let answerText = ''
  const statuses: Record<string, NodeRunStatus> = { ...nodeStatuses.value }

  try {
    await askPreviewStream(
      {
        appId: props.appId,
        question: q,
        history: [],
        graph: props.graph
      },
      (event, data) => {
        const payload = data && typeof data === 'object' ? (data as Record<string, unknown>) : {}
        if (event === 'node_start') {
          const id = String(payload.nodeId || '')
          if (id) {
            statuses[id] = 'running'
            setStatuses({ ...statuses })
            if (lastFinish) {
              const when = lastFinish.outcome || 'default'
              const match = (props.graph.edges || []).find(
                (edge) =>
                  edge.from === lastFinish!.nodeId &&
                  edge.to === id &&
                  String(edge.when || 'default') === when
              )
              const fallback = (props.graph.edges || []).find(
                (edge) => edge.from === lastFinish!.nodeId && edge.to === id
              )
              const edge = match || fallback
              if (edge) {
                const key = edgeKey(edge.from, edge.to, String(edge.when || 'default'))
                if (!activeEdges.value.includes(key)) {
                  setActiveEdges([...activeEdges.value, key])
                }
              }
            }
          }
        } else if (event === 'node_finish') {
          const id = String(payload.nodeId || '')
          if (id) {
            const outcome = String(payload.outcome || 'default')
            statuses[id] = payload.error || outcome === 'error' ? 'error' : 'ok'
            setStatuses({ ...statuses })
            lastFinish = { nodeId: id, outcome }
          }
        } else if (event === 'vars') {
          const id = String(payload.nodeId || '')
          const outputs =
            payload.outputs && typeof payload.outputs === 'object'
              ? (payload.outputs as Record<string, unknown>)
              : {}
          if (id) {
            selectedOutputs.value = { ...selectedOutputs.value, [id]: outputs }
            emit('node-outputs', { nodeId: id, outputs })
            if (!inspectNodeId.value) {
              inspectNodeId.value = id
            }
          }
        } else if (event === 'answer') {
          answerText = String(payload.answer ?? '')
        } else if (event === 'clarify') {
          answerText = String(payload.question || '需要澄清')
        } else if (event === 'error') {
          lines.value = [
            ...lines.value,
            { kind: 'error', text: String(payload.message || '预览失败') }
          ]
        } else if (event === 'run' && payload.preview) {
          lines.value = [
            ...lines.value,
            {
              kind: 'system',
              text: props.dirty ? '预览 · 当前画布（未保存）' : '预览 · 当前图'
            }
          ]
        }
      },
      controller.signal
    )
    if (answerText) {
      lines.value = [...lines.value, { kind: 'assistant', text: answerText }]
    }
  } catch (e) {
    if ((e as Error)?.name !== 'AbortError') {
      lines.value = [...lines.value, { kind: 'error', text: errMessage(e, '预览失败') }]
    }
  } finally {
    busy.value = false
    abort.value = null
  }
}

function inspect(nodeId: string) {
  inspectNodeId.value = nodeId
  emit('select-node', nodeId)
}

defineExpose({ inspect, resetStatuses, stop })
</script>

<template>
  <aside class="preview" aria-label="流程预览">
    <header class="preview-head">
      <h3>预览</h3>
      <p class="hint">用当前画布调试，不必发布。正式对话请用应用广场。</p>
    </header>

    <div class="thread" role="log" aria-live="polite">
      <p v-if="!lines.length" class="hint empty">输入问题后按节点顺序跑通当前图。</p>
      <div v-for="(line, index) in lines" :key="index" class="line" :class="line.kind">
        {{ line.text }}
      </div>
      <p v-if="busy" class="hint">运行中…</p>
    </div>

    <div class="inspect">
      <label>
        节点出参
        <select v-model="inspectNodeId" aria-label="选择节点查出参">
          <option value="">选择节点</option>
          <option v-for="node in graph.nodes" :key="node.id" :value="node.id">
            {{ node.title || node.id }}
          </option>
        </select>
      </label>
      <pre v-if="inspectNodeId" class="outs">{{ inspectJson }}</pre>
    </div>

    <form class="composer" @submit.prevent="send">
      <textarea
        v-model="question"
        rows="3"
        :disabled="busy"
        placeholder="预览问题，例如：最近一天高危告警有多少？"
        aria-label="预览问题"
        @keydown.enter.exact.prevent="send"
      />
      <div class="actions">
        <button v-if="busy" type="button" @click="stop">停止</button>
        <button class="primary" type="submit" :disabled="busy || !question.trim()">发送</button>
      </div>
    </form>
  </aside>
</template>

<style scoped>
.preview {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 320px;
  min-width: 280px;
  max-width: 360px;
  border-left: 1px solid var(--line);
  padding: 12px;
  background: var(--panel, var(--bg));
  min-height: 0;
}

.preview-head h3 {
  margin: 0 0 4px;
  font-size: 15px;
}

.hint {
  margin: 0;
  font-size: 12px;
  color: var(--muted);
  line-height: 1.4;
}

.thread {
  flex: 1;
  min-height: 120px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.line {
  font-size: 13px;
  line-height: 1.4;
  white-space: pre-wrap;
}

.line.user {
  align-self: flex-end;
  max-width: 90%;
  padding: 6px 10px;
  border-radius: 10px 10px 2px 10px;
  background: var(--accent);
  color: #fff;
}

.line.assistant {
  padding: 6px 0;
}

.line.system {
  font-size: 11px;
  color: var(--muted);
}

.line.error {
  color: var(--err, #c44);
}

.empty {
  margin: auto;
  text-align: center;
}

.inspect {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.inspect label {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: var(--muted);
}

.inspect select {
  padding: 4px 6px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: var(--panel-2, var(--bg));
  color: var(--text);
}

.outs {
  margin: 0;
  max-height: 140px;
  overflow: auto;
  padding: 8px;
  border-radius: 6px;
  background: var(--panel-2, var(--bg));
  font-size: 11px;
  line-height: 1.35;
}

.composer {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.composer textarea {
  resize: vertical;
  min-height: 64px;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-2, var(--bg));
  color: var(--text);
  font: inherit;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
