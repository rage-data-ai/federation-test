<script setup lang="ts">
import { computed, onMounted, ref, toRaw } from 'vue'
import {
  createApp,
  deleteApp,
  getFlow,
  listApps,
  listFlowTemplates,
  publishApp,
  updateApp,
  updateFlow
} from './api'
import FlowDagEditor from './components/flow/FlowDagEditor.vue'
import type { ChatApp, FlowDraft, FlowGraph, FlowTemplate } from './types'
import { errMessage } from './types'

const BUILTIN_APP_ID = 'app-builtin-ask'

const apps = ref<ChatApp[]>([])
const templates = ref<FlowTemplate[]>([])
const selected = ref<ChatApp | null>(null)
const flow = ref<FlowDraft | null>(null)
const draftGraph = ref<FlowGraph | null>(null)
const dirty = ref(false)
const metaDirty = ref(false)
const showJson = ref(false)
const graphText = ref('')
const name = ref('')
const description = ref('')
const editName = ref('')
const editDescription = ref('')
const template = ref('llm_chat')
const busy = ref('')
const error = ref('')
const message = ref('')

const emit = defineEmits<{ 'open-app': [appId: string] }>()

const readonly = computed(() => false)
const canDeleteSelected = computed(
  () => !!selected.value && selected.value.id !== BUILTIN_APP_ID
)

onMounted(async () => {
  try {
    templates.value = await listFlowTemplates()
    await refresh()
  } catch (e) {
    error.value = errMessage(e)
  }
})

async function refresh() {
  apps.value = await listApps()
}

function syncMetaForm(app: ChatApp) {
  editName.value = app.name
  editDescription.value = app.description || ''
  metaDirty.value = false
}

async function create() {
  busy.value = 'create'
  error.value = ''
  message.value = ''
  try {
    const app = await createApp({
      name: name.value.trim() || undefined,
      description: description.value.trim() || undefined,
      template: template.value
    })
    name.value = ''
    description.value = ''
    message.value = `已创建草稿「${app.name}」，编辑画布并发布后可在「应用广场」打开`
    await refresh()
    await open(app)
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = ''
  }
}

async function open(app: ChatApp) {
  selected.value = app
  syncMetaForm(app)
  error.value = ''
  message.value = ''
  dirty.value = false
  try {
    const draft = await getFlow(app.flowId)
    // Clone before assigning into refs — structuredClone cannot clone Vue proxies.
    draftGraph.value = structuredClone(draft.graph)
    graphText.value = JSON.stringify(draft.graph, null, 2)
    flow.value = draft
  } catch (e) {
    error.value = errMessage(e)
  }
}

function onGraphChange(graph: FlowGraph) {
  draftGraph.value = graph
  graphText.value = JSON.stringify(graph, null, 2)
  dirty.value = true
}

function onMetaInput() {
  metaDirty.value = true
}

function applyJson() {
  try {
    const graph = JSON.parse(graphText.value) as FlowGraph
    draftGraph.value = graph
    dirty.value = true
    error.value = ''
    message.value = '已从 JSON 载入到画布，记得保存'
  } catch (e) {
    error.value = errMessage(e, '流程图 JSON 无效')
  }
}

async function saveMeta() {
  if (!selected.value) {
    return
  }
  const nextName = editName.value.trim()
  if (!nextName) {
    error.value = '应用名称不能为空'
    return
  }
  busy.value = 'meta'
  error.value = ''
  message.value = ''
  try {
    const updated = await updateApp(selected.value.id, {
      name: nextName,
      description: editDescription.value.trim()
    })
    selected.value = updated
    syncMetaForm(updated)
    await refresh()
    message.value = '应用信息已保存'
  } catch (e) {
    error.value = errMessage(e, '保存应用信息失败')
  } finally {
    busy.value = ''
  }
}

async function saveGraph() {
  if (!selected.value || !flow.value || !draftGraph.value) {
    return
  }
  busy.value = 'save'
  error.value = ''
  message.value = ''
  try {
    const saved = await updateFlow(flow.value.id, { graph: toRaw(draftGraph.value) })
    draftGraph.value = structuredClone(saved.graph)
    graphText.value = JSON.stringify(saved.graph, null, 2)
    flow.value = saved
    dirty.value = false
    message.value = '流程草稿已保存'
  } catch (e) {
    error.value = errMessage(e, '保存失败')
  } finally {
    busy.value = ''
  }
}

async function publish() {
  if (!selected.value) {
    return
  }
  if (metaDirty.value) {
    await saveMeta()
    if (error.value) {
      return
    }
  }
  if (dirty.value) {
    await saveGraph()
    if (error.value) {
      return
    }
  }
  busy.value = 'publish'
  error.value = ''
  message.value = ''
  try {
    await publishApp(selected.value.id)
    message.value = '已发布，可在「应用广场」打开对话'
    await refresh()
    selected.value = apps.value.find((item) => item.id === selected.value?.id) || selected.value
    if (selected.value) {
      syncMetaForm(selected.value)
    }
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    busy.value = ''
  }
}

async function removeSelected() {
  if (!selected.value || !canDeleteSelected.value) {
    return
  }
  if (!confirm(`删除应用「${selected.value.name}」？发布入口也会失效（可恢复需重新创建）。`)) {
    return
  }
  busy.value = 'delete'
  error.value = ''
  message.value = ''
  try {
    const id = selected.value.id
    await deleteApp(id)
    selected.value = null
    flow.value = null
    draftGraph.value = null
    dirty.value = false
    metaDirty.value = false
    await refresh()
    message.value = '应用已删除'
  } catch (e) {
    error.value = errMessage(e, '删除失败')
  } finally {
    busy.value = ''
  }
}

function statusLabel(status: string) {
  if (status === 'published') {
    return '已发布'
  }
  if (status === 'draft') {
    return '草稿'
  }
  if (status === 'archived') {
    return '已归档'
  }
  return status
}
</script>

<template>
  <section class="apps" aria-labelledby="apps-title">
    <header class="module-head">
      <div class="head-main">
        <h1 id="apps-title">问数应用</h1>
        <p class="hint">创建、编辑与发布应用；发布后请到「应用广场」打开对话。侧栏「智能问数」固定为内置应用。</p>
      </div>
    </header>

    <p v-if="error" class="err" role="alert">{{ error }}</p>
    <p v-else-if="message" class="ok" role="status">{{ message }}</p>

    <div class="layout">
      <aside class="side" aria-label="应用列表">
        <form class="create" @submit.prevent="create">
          <h3>新建</h3>
          <label>
            名称
            <input v-model="name" type="text" maxlength="64" placeholder="例如：值班助手" />
          </label>
          <label>
            模板
            <select v-model="template">
              <option v-for="item in templates" :key="item.id" :value="item.id">
                {{ item.name }} — {{ item.description }}
              </option>
            </select>
          </label>
          <label>
            说明
            <input v-model="description" type="text" maxlength="200" placeholder="可选" />
          </label>
          <button class="primary" type="submit" :disabled="!!busy">从模板创建</button>
        </form>

        <ul class="list" aria-label="全部应用">
          <li v-for="app in apps" :key="app.id">
            <button
              type="button"
              class="item"
              :class="{ active: selected?.id === app.id }"
              @click="open(app)"
            >
              <span class="title">{{ app.name }}</span>
              <span class="meta">{{ statusLabel(app.status) }} · {{ app.id }}</span>
            </button>
          </li>
        </ul>
      </aside>

      <main v-if="selected && flow && draftGraph" class="detail" aria-label="应用详情">
        <header class="detail-head">
          <div>
            <h2>
              {{ selected.name }}
              <span v-if="dirty || metaDirty" class="dirty">未保存</span>
            </h2>
            <p class="meta">流程 {{ flow.id }} · 状态 {{ statusLabel(selected.status) }}</p>
          </div>
          <div class="actions">
            <button type="button" :disabled="!!busy || !metaDirty" @click="saveMeta">保存信息</button>
            <button type="button" :disabled="!!busy || !dirty" @click="saveGraph">保存草稿</button>
            <button class="primary" type="button" :disabled="!!busy" @click="publish">发布应用</button>
            <button
              v-if="selected.status === 'published'"
              type="button"
              :disabled="!!busy"
              @click="emit('open-app', selected.id)"
            >
              打开对话
            </button>
            <button
              v-if="canDeleteSelected"
              type="button"
              class="danger"
              :disabled="!!busy"
              @click="removeSelected"
            >
              删除应用
            </button>
            <button type="button" class="ghost" @click="showJson = !showJson">
              {{ showJson ? '收起 JSON' : '高级 JSON' }}
            </button>
          </div>
        </header>

        <form class="meta-form" aria-label="应用信息" @submit.prevent="saveMeta">
          <label>
            名称
            <input
              v-model="editName"
              type="text"
              maxlength="64"
              required
              :disabled="!!busy"
              @input="onMetaInput"
            />
          </label>
          <label>
            说明
            <input
              v-model="editDescription"
              type="text"
              maxlength="200"
              :disabled="!!busy"
              @input="onMetaInput"
            />
          </label>
        </form>

        <FlowDagEditor
          :key="flow.id"
          v-model="draftGraph"
          :readonly="readonly"
          @change="onGraphChange"
        />

        <div v-if="showJson" class="json-panel">
          <label class="graph">
            流程图 JSON
            <textarea v-model="graphText" rows="12" spellcheck="false" aria-label="流程图 JSON" />
          </label>
          <button type="button" :disabled="!!busy" @click="applyJson">应用到画布</button>
        </div>
      </main>
      <main v-else class="detail empty">
        <p class="hint">选择左侧应用查看与编辑，或从模板新建。</p>
      </main>
    </div>
  </section>
</template>

<style scoped>
.apps {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  flex: 1;
}

.hint {
  margin: 0;
  color: var(--muted);
  font-size: 13px;
}

.err {
  color: var(--danger, #c44);
}

.ok {
  color: var(--accent);
}

.layout {
  display: flex;
  flex: 1;
  min-height: 0;
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  background: var(--panel);
}

.side {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: 260px;
  flex: none;
  padding: 12px;
  border-right: 1px solid var(--line);
  overflow: auto;
}

.create {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.create h3 {
  margin: 0;
  font-size: 13px;
}

.meta-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
  gap: 10px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel-2);
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
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel-2);
  color: var(--text);
  font: inherit;
}

.meta-form input {
  background: var(--panel);
}

.list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.item {
  display: flex;
  flex-direction: column;
  gap: 2px;
  width: 100%;
  padding: 8px 10px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--text);
  text-align: left;
}

.item:hover,
.item.active {
  background: var(--panel-2);
}

.title {
  font-size: 13px;
  font-weight: 600;
}

.meta {
  font-size: 11px;
  color: var(--muted);
}

.detail-head .meta {
  margin: 4px 0 0;
}

.detail {
  display: flex;
  flex-direction: column;
  gap: 12px;
  flex: 1;
  min-width: 0;
  padding: 12px 16px 16px;
  overflow: auto;
}

.detail.empty {
  justify-content: center;
  align-items: center;
}

.detail-head {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  justify-content: space-between;
  align-items: flex-start;
}

.detail-head h2 {
  margin: 0;
  font-size: 18px;
}

.dirty {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 500;
  color: var(--warn, #e6a23c);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.json-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.graph textarea {
  width: 100%;
  min-height: 180px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.45;
}

button.primary {
  background: var(--accent);
  color: #fff;
  border: 0;
  border-radius: 8px;
  padding: 8px 14px;
}

button.ghost {
  background: transparent;
}

button.danger {
  color: #f2b8b5;
  border-color: #5c2222;
  background: transparent;
}

button {
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 14px;
  background: var(--panel-2);
  color: var(--text);
}

@media (max-width: 900px) {
  .meta-form {
    grid-template-columns: 1fr;
  }
}
</style>
