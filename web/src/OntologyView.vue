<script setup>
import { computed, inject, onActivated, reactive, ref, watch } from 'vue'
import { deleteEntity, draftOntology, getOntology, getOntologyTurtle, listCatalogs, listMetadata, llmStatus, saveEntity } from './api'
import OntologyGraph from './OntologyGraph.vue'

const tables = ref([])
const catalogs = ref([])

const KINDS = [
  { key: 'concept', list: 'concepts', label: '概念' },
  { key: 'attribute', list: 'attributes', label: '属性' },
  { key: 'relation', list: 'relations', label: '关系' },
  { key: 'code', list: 'codes', label: '码值' },
  { key: 'defined', list: 'definedConcepts', label: '派生概念' }
]
const DATATYPES = ['string', 'integer', 'long', 'decimal', 'double', 'boolean', 'date', 'dateTime']
const OPERATORS = ['=', '>', '>=', '<', '<=']
const COMMON = [
  { key: 'name', label: '名称（英文标识，创建后不可改）', type: 'text' },
  { key: 'labelZh', label: '中文名', type: 'text' },
  { key: 'labelEn', label: '英文名', type: 'text' },
  { key: 'aliases', label: '中文别名（逗号分隔）', type: 'list' }
]
const FIELDS = {
  concept: [
    { key: 'parent', label: '父概念', type: 'select', options: 'concepts' },
    { key: 'table', label: '映射表 catalog.schema.table', type: 'table' },
    { key: 'timeColumn', label: '时间列', type: 'column', of: 'table' },
    { key: 'largeTable', label: '大表（查询必须带时间范围）', type: 'check' }
  ],
  attribute: [
    { key: 'domain', label: '所属概念', type: 'select', options: 'concepts' },
    { key: 'datatype', label: '数据类型', type: 'select', options: 'datatypes' },
    { key: 'column', label: '映射列', type: 'column', of: 'domain' },
    { key: 'unit', label: '单位', type: 'text' },
    { key: 'codeList', label: '码表（如 AlertLevel）', type: 'text' },
    { key: 'identifier', label: '标识列（主键）', type: 'check' }
  ],
  relation: [
    { key: 'domain', label: '起点概念', type: 'select', options: 'concepts' },
    { key: 'range', label: '终点概念', type: 'select', options: 'concepts' },
    { key: 'joinFrom', label: '起点表的关联列', type: 'column', of: 'domain' },
    { key: 'joinTo', label: '终点表的关联列', type: 'column', of: 'range' },
    { key: 'inverse', label: '反向关系名（可选）', type: 'text' },
    { key: 'functional', label: '函数性（一个起点最多对应一个终点）', type: 'check' },
    { key: 'transitive', label: '传递性', type: 'check' }
  ],
  code: [
    { key: 'codeList', label: '码表', type: 'text' },
    { key: 'value', label: '库中存储的值', type: 'text' }
  ],
  defined: [
    { key: 'base', label: '基础概念', type: 'select', options: 'concepts' },
    { key: 'conditions', label: '条件（全部满足）', type: 'conditions' },
    { key: 'definitionText', label: '文字定义（条件表达不了时填写）', type: 'textarea' }
  ]
}

const openLlmSettings = inject('openLlmSettings', () => {})
const llmRevision = inject('llmRevision', ref(0))
const snapshot = ref({ concepts: [], attributes: [], relations: [], codes: [], definedConcepts: [] })
const query = ref('')
const selected = ref(null)
const form = reactive({})
const isNew = ref(false)
const message = ref('')
const error = ref('')
const saving = ref(false)
const llm = ref(null)

const showDraft = ref(false)
const pendingDelete = ref(null)
const draftTables = ref([])
const draftQuery = ref('')
const draftNote = ref('')
const drafting = ref(false)
const draftResult = ref(null)
const draftError = ref('')
const fresh = ref([])
const draftStep = ref('')
const draftDetail = ref('')
const draftLog = ref([])
const draftStartedAt = ref(0)
const draftClock = ref(0)
let draftClockTimer = 0
const draftElapsed = computed(() => draftStartedAt.value
  ? Math.max(0, Math.round((draftClock.value - draftStartedAt.value) / 1000))
  : 0)
const DRAFT_STEPS = [
  { key: 'sample', label: '读取列和样例' },
  { key: 'concepts', label: '起草概念' },
  { key: 'attributes', label: '起草属性' },
  { key: 'codes', label: '起草码值' },
  { key: 'relations', label: '起草关系' },
  { key: 'defined', label: '起草派生概念' }
]

const turtle = ref('')

const kafka = computed(() => new Set(catalogs.value.filter((c) => c.type === 'kafka').map((c) => c.name)))
const qualified = (t) => `${t.catalog}.${t.schema}.${t.table}`
const draftable = computed(() => tables.value.filter((t) => !kafka.value.has(t.catalog)))
const draftCandidates = computed(() => {
  const q = draftQuery.value.trim().toLowerCase()
  const list = q ? draftable.value.filter((t) => qualified(t).toLowerCase().includes(q)) : draftable.value
  return list.slice(0, 200)
})
const columnsByTable = computed(() => {
  const map = {}
  for (const t of tables.value) {
    map[qualified(t).toLowerCase()] = (t.columns || []).map((c) => c.name)
  }
  return map
})
const conceptNames = computed(() => snapshot.value.concepts.map((c) => c.name))
const attributeNames = computed(() => snapshot.value.attributes.map((a) => a.name))

const groups = computed(() => {
  const q = query.value.trim().toLowerCase()
  return KINDS.map((kind) => ({
    ...kind,
    items: snapshot.value[kind.list].filter((item) => !q
      || item.name.toLowerCase().includes(q)
      || (item.labelZh || '').includes(q)
      || (item.aliases || []).some((a) => a.includes(q)))
  }))
})
const fields = computed(() => (selected.value ? [...COMMON, ...FIELDS[selected.value.kind]] : []))

watch(llmRevision, refreshLlm)
onActivated(async () => {
  await Promise.all([load(), loadSources(), refreshLlm()])
})

async function refreshLlm() {
  try {
    llm.value = await llmStatus()
  } catch {
    llm.value = null
  }
}

async function loadSources() {
  try {
    const [catalogList, metadata] = await Promise.all([listCatalogs(), listMetadata()])
    catalogs.value = catalogList
    tables.value = metadata
  } catch {
    tables.value = []
  }
}

async function load() {
  try {
    snapshot.value = await getOntology()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

function blank(kind) {
  const base = { name: '', labelZh: '', labelEn: '', aliases: [], comment: '' }
  const extra = {
    concept: { parent: '', table: '', timeColumn: '', largeTable: false },
    attribute: { domain: '', datatype: 'string', column: '', unit: '', identifier: false, codeList: '' },
    relation: { domain: '', range: '', joinFrom: '', joinTo: '', inverse: '', transitive: false, functional: false },
    code: { codeList: '', value: '' },
    defined: { base: '', conditions: [], definitionText: '' }
  }
  return { ...base, ...extra[kind] }
}

function fill(values) {
  for (const key of Object.keys(form)) {
    delete form[key]
  }
  Object.assign(form, JSON.parse(JSON.stringify(values)))
  form.aliasText = (form.aliases || []).join('，')
  message.value = ''
  error.value = ''
}

function open(kind, item) {
  selected.value = { kind: kind.key, label: kind.label }
  isNew.value = false
  fill({ ...blank(kind.key), ...item })
}

function create(kind) {
  selected.value = { kind: kind.key, label: kind.label }
  isNew.value = true
  fill(blank(kind.key))
}

function options(field) {
  if (field.options === 'datatypes') {
    return DATATYPES
  }
  return conceptNames.value.filter((name) => name !== form.name)
}

function columnsFor(field) {
  const table = field.of === 'table'
    ? form.table
    : snapshot.value.concepts.find((c) => c.name === form[field.of])?.table
  return table ? columnsByTable.value[table.toLowerCase()] || [] : []
}

function emptyToNull(value) {
  return typeof value === 'string' && !value.trim() ? null : value
}

async function save() {
  saving.value = true
  message.value = ''
  error.value = ''
  try {
    const body = {}
    for (const field of fields.value) {
      body[field.key] = field.type === 'list'
        ? form.aliasText.split(/[,，、]/).map((s) => s.trim()).filter(Boolean)
        : emptyToNull(form[field.key])
    }
    body.comment = emptyToNull(form.comment)
    if (selected.value.kind === 'defined') {
      body.conditions = (form.conditions || []).filter((c) => c.attribute && c.value !== '')
    }
    snapshot.value = await saveEntity(selected.value.kind, form.name, body)
    isNew.value = false
    message.value = '已保存'
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

function removeNamed(name) {
  const kind = groups.value.find((group) => group.items.some((item) => item.name === name))
  pendingDelete.value = {
    name,
    note: kind?.key === 'concept' ? '它的属性、派生概念和码值会一起删除。' : ''
  }
}

async function confirmDelete() {
  const name = pendingDelete.value?.name
  if (!name) {
    return
  }
  pendingDelete.value = null
  try {
    snapshot.value = await deleteEntity(name)
    fresh.value = fresh.value.filter((item) => item !== name)
    if (form.name === name) {
      selected.value = null
    }
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

function remove() {
  return removeNamed(form.name)
}

function openDraft() {
  showDraft.value = true
  draftResult.value = null
  draftError.value = ''
  draftLog.value = []
  draftStep.value = ''
  draftDetail.value = ''
}

function toggleDraftTable(name) {
  const index = draftTables.value.indexOf(name)
  if (index >= 0) {
    draftTables.value.splice(index, 1)
  } else {
    draftTables.value.push(name)
  }
}

function entityNames(data) {
  return new Set([
    ...(data.concepts || []).map((item) => item.name),
    ...(data.attributes || []).map((item) => item.name),
    ...(data.relations || []).map((item) => item.name),
    ...(data.codes || []).map((item) => item.name),
    ...(data.definedConcepts || []).map((item) => item.name)
  ])
}

function draftReached(key) {
  if (draftStep.value === 'done') {
    return true
  }
  const current = DRAFT_STEPS.findIndex((step) => step.key === draftStep.value)
  const target = DRAFT_STEPS.findIndex((step) => step.key === key)
  return current >= 0 && target >= 0 && current >= target
}

async function runDraft() {
  drafting.value = true
  draftStep.value = 'sample'
  draftDetail.value = '读取列和样例'
  draftError.value = ''
  draftResult.value = null
  draftLog.value = []
  draftStartedAt.value = Date.now()
  draftClock.value = draftStartedAt.value
  window.clearInterval(draftClockTimer)
  draftClockTimer = window.setInterval(() => {
    draftClock.value = Date.now()
  }, 1000)
  const before = entityNames(snapshot.value)
  let failed = ''
  try {
    await draftOntology({ tables: draftTables.value, instructions: draftNote.value || null }, (event, data) => {
      if (event === 'phase') {
        draftStep.value = data.phase
        draftDetail.value = data.message
        draftLog.value.push({
          status: data.status || 'start',
          text: data.message,
          notes: data.notes || []
        })
      } else if (event === 'result') {
        draftResult.value = data
        draftStep.value = 'done'
        draftDetail.value = ''
      } else if (event === 'error') {
        failed = data.message || '起草失败'
      }
    })
    if (failed) {
      throw new Error(failed)
    }
    await load()
    fresh.value = [...entityNames(snapshot.value)].filter((name) => !before.has(name))
  } catch (e) {
    draftError.value = e.message
    if (draftStep.value !== 'done') {
      draftStep.value = ''
    }
  } finally {
    window.clearInterval(draftClockTimer)
    drafting.value = false
  }
}

function onPick(target) {
  const kind = KINDS.find((item) => item.key === target.kind)
  const entity = kind && snapshot.value[kind.list].find((item) => item.name === target.name)
  if (kind && entity) {
    open(kind, entity)
  }
}

function finishDraft() {
  showDraft.value = false
}

async function showTurtle() {
  try {
    turtle.value = await getOntologyTurtle()
  } catch (e) {
    error.value = e.message
  }
}

function downloadTurtle() {
  const url = URL.createObjectURL(new Blob([turtle.value], { type: 'text/turtle' }))
  const link = document.createElement('a')
  link.href = url
  link.download = 'ontology.ttl'
  link.click()
  URL.revokeObjectURL(url)
}

function subtitle(kind, item) {
  switch (kind) {
    case 'concept': return item.table || '未映射表'
    case 'attribute': return `${item.domain || '?'}.${item.column || '—'}`
    case 'relation': return `${item.domain} → ${item.range}`
    case 'code': return `${item.codeList} = ${item.value}`
    default: return item.base
  }
}
</script>

<template>
  <section class="onto">
    <header class="module-head">
      <h1>本体建模</h1>
      <div class="head-side">
        <span class="head-note">
          概念 {{ snapshot.concepts.length }} · 属性 {{ snapshot.attributes.length }} · 关系 {{ snapshot.relations.length }}
        </span>
        <button type="button" @click="showTurtle">导出 Turtle</button>
        <button v-if="llm && !llm.configured" type="button" @click="openLlmSettings">配置大模型</button>
        <button v-else class="primary" type="button" @click="openDraft">大模型起草</button>
      </div>
    </header>

    <div class="onto-body" :class="{ editing: !!selected }">
    <aside class="list">
      <div class="list-top">
        <input v-model="query" type="search" placeholder="搜索名称 / 中文名 / 别名" />
      </div>
      <p v-if="error && !selected" class="err pad">{{ error }}</p>
      <div v-for="group in groups" :key="group.key" class="group">
        <div class="group-head">
          <span>{{ group.label }} <em>{{ group.items.length }}</em></span>
          <button class="icon sm" type="button" :title="`新建${group.label}`" @click="create(group)">＋</button>
        </div>
        <div v-for="item in group.items" :key="item.name" class="item-row">
          <button
            class="item"
            :class="{ active: selected && !isNew && form.name === item.name }"
            type="button"
            @click="open(group, item)"
          >
            <span>{{ item.labelZh || item.name }} <em>{{ item.name }}</em></span>
            <small>{{ subtitle(group.key, item) }}</small>
          </button>
          <button class="icon sm danger" type="button" title="删除" @click="removeNamed(item.name)">✕</button>
        </div>
      </div>
    </aside>

    <div class="canvas">
      <OntologyGraph :snapshot="snapshot" :fresh="fresh" :active="selected && !isNew ? form.name : ''" @pick="onPick" />
      <div v-if="!snapshot.concepts.length" class="guide">
        <h2>从数据表起草本体</h2>
        <ol>
          <li>
            <b>勾选表</b>
            <span>用已经采集的 MySQL、ClickHouse、ES 表。Kafka 暂不映射。</span>
          </li>
          <li>
            <b>读结构和样例</b>
            <span>每张表取 3 行样例，连同列名交给大模型。</span>
          </li>
          <li>
            <b>生成并写入</b>
            <span>概念、属性、关系、码值直接进本体，图上用琥珀色描边标出。</span>
          </li>
        </ol>
        <button v-if="llm && !llm.configured" type="button" @click="openLlmSettings">先配置大模型</button>
        <button v-else class="primary" type="button" @click="openDraft">选择表并起草</button>
      </div>
      <div v-if="fresh.length" class="draft-note">
        本次起草新写入 {{ fresh.length }} 个实体，图中琥珀色描边。
        <button class="link" type="button" @click="fresh = []">知道了</button>
      </div>
    </div>
    <form v-if="selected" class="entity" @submit.prevent="save">
        <h3>{{ isNew ? `新建${selected.label}` : `${selected.label}：${form.labelZh || form.name}` }}</h3>
        <template v-for="field in fields" :key="field.key">
          <label v-if="field.type === 'text'">
            {{ field.label }}
            <input v-model="form[field.key]" :disabled="field.key === 'name' && !isNew" :required="field.key === 'name'" />
          </label>
          <label v-else-if="field.type === 'list'">
            {{ field.label }}
            <input v-model="form.aliasText" />
          </label>
          <label v-else-if="field.type === 'textarea'">
            {{ field.label }}
            <textarea v-model="form[field.key]" rows="3" />
          </label>
          <label v-else-if="field.type === 'select'">
            {{ field.label }}
            <select v-model="form[field.key]">
              <option v-if="field.options !== 'datatypes'" value="">（无）</option>
              <option v-for="opt in options(field)" :key="opt" :value="opt">{{ opt }}</option>
            </select>
          </label>
          <label v-else-if="field.type === 'table'">
            {{ field.label }}
            <input v-model="form[field.key]" list="onto-tables" placeholder="例如 ck81.pangu.alert" />
          </label>
          <label v-else-if="field.type === 'column'">
            {{ field.label }}
            <input v-model="form[field.key]" :list="`cols-${field.key}`" />
            <datalist :id="`cols-${field.key}`">
              <option v-for="col in columnsFor(field)" :key="col" :value="col" />
            </datalist>
          </label>
          <label v-else-if="field.type === 'check'" class="check">
            <input v-model="form[field.key]" type="checkbox" />
            {{ field.label }}
          </label>
          <div v-else-if="field.type === 'conditions'" class="conditions">
            <span class="cond-label">{{ field.label }}</span>
            <div v-for="(cond, cidx) in form.conditions" :key="cidx" class="cond">
              <select v-model="cond.attribute">
                <option v-for="name in attributeNames" :key="name" :value="name">{{ name }}</option>
              </select>
              <select v-model="cond.operator">
                <option v-for="op in OPERATORS" :key="op" :value="op">{{ op }}</option>
              </select>
              <input v-model="cond.value" placeholder="值" />
              <button class="icon sm" type="button" title="删除条件" @click="form.conditions.splice(cidx, 1)">✕</button>
            </div>
            <button class="link" type="button" @click="form.conditions.push({ attribute: attributeNames[0] || '', operator: '>=', value: '' })">＋ 添加条件</button>
          </div>
        </template>
        <label>
          说明
          <textarea v-model="form.comment" rows="2" />
        </label>
        <p v-if="message" class="ok">{{ message }}</p>
        <p v-if="error" class="err">{{ error }}</p>
        <div class="actions">
          <button v-if="!isNew" class="danger" type="button" @click="remove">删除</button>
          <button type="button" @click="selected = null">关闭</button>
          <button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
    </form>
    <datalist id="onto-tables">
      <option v-for="t in draftable" :key="qualified(t)" :value="qualified(t)" />
    </datalist>
    </div>

    <div v-if="pendingDelete" class="mask" @click.self="pendingDelete = null">
      <div class="dialog confirm" role="alertdialog" aria-labelledby="delete-title">
        <h3 id="delete-title">删除 {{ pendingDelete.name }}？</h3>
        <p v-if="pendingDelete.note">{{ pendingDelete.note }}</p>
        <div class="actions">
          <button type="button" @click="pendingDelete = null">取消</button>
          <button class="danger" type="button" @click="confirmDelete">删除</button>
        </div>
      </div>
    </div>

    <div v-if="showDraft" class="mask" @click.self="!drafting && (showDraft = false)">
      <div class="dialog draft">
        <h3>大模型起草</h3>
        <ol class="steps">
          <li v-for="step in DRAFT_STEPS" :key="step.key" :class="{ on: draftReached(step.key) }">{{ step.label }}</li>
        </ol>
        <template v-if="!drafting && !draftLog.length">
          <input v-model="draftQuery" type="search" placeholder="过滤表名" />
          <div class="picker">
            <label v-for="t in draftCandidates" :key="qualified(t)" class="check">
              <input type="checkbox" :checked="draftTables.includes(qualified(t))" @change="toggleDraftTable(qualified(t))" />
              {{ qualified(t) }} <em>{{ (t.columns || []).length }} 列</em>
            </label>
            <p v-if="!draftCandidates.length" class="hint">没有可选的表，请先在左侧数据源中采集元数据。</p>
          </div>
          <p class="hint">已选 {{ draftTables.length }} 张：{{ draftTables.join('，') }}</p>
          <label>
            补充说明（可选，如业务背景、哪些列是码值）
            <textarea v-model="draftNote" rows="3" />
          </label>
          <p class="hint">开始后会逐步列出每一段：读了哪些表、写入了几个概念和属性、哪一段被跳过。</p>
        </template>
        <p v-else class="hint">已选 {{ draftTables.length }} 张：{{ draftTables.join('，') }}</p>
        <ol v-if="draftLog.length" class="draft-log">
          <li v-for="(line, index) in draftLog" :key="index" :class="line.status">
            <span>{{ line.text }}</span>
            <ul v-if="line.notes.length">
              <li v-for="note in line.notes" :key="note">{{ note }}</li>
            </ul>
          </li>
        </ol>
        <p v-if="draftError" class="err">{{ draftError }}</p>
        <div v-if="draftResult" class="ok">
          已写入：概念 {{ draftResult.concepts }}、属性 {{ draftResult.attributes }}、关系 {{ draftResult.relations }}、
          码值 {{ draftResult.codes }}、派生概念 {{ draftResult.definedConcepts }}
        </div>
        <div class="actions">
          <button v-if="!drafting && draftLog.length" type="button" @click="openDraft">重新选择表</button>
          <button type="button" :disabled="drafting" @click="finishDraft">{{ draftStep === 'done' ? '在图中查看' : '关闭' }}</button>
          <button class="primary" type="button" :disabled="drafting || !draftTables.length" @click="runDraft">
            {{ drafting ? `${draftDetail || '起草中…'} · ${draftElapsed}s` : '开始起草' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="turtle" class="mask" @click.self="turtle = ''">
      <div class="dialog preview">
        <h3>本体 Turtle</h3>
        <pre>{{ turtle }}</pre>
        <div class="actions">
          <button type="button" @click="downloadTurtle">下载 .ttl</button>
          <button class="primary" type="button" @click="turtle = ''">关闭</button>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.onto {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-height: 0;
}

.onto-body {
  display: grid;
  grid-template-columns: 260px 1fr;
  flex: 1;
  min-height: 0;
}

.onto-body.editing {
  grid-template-columns: 240px 1fr 360px;
}

.canvas {
  position: relative;
  min-width: 0;
  min-height: 0;
}

.guide {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  justify-content: center;
  gap: 16px;
  max-width: 460px;
  margin: 0 auto;
  padding: 24px;
  pointer-events: none;
}

.guide h2 {
  margin: 0;
  font-size: 22px;
  font-weight: 650;
}

.guide ol {
  margin: 0;
  padding: 0 0 0 18px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.guide li {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.guide span {
  color: var(--muted);
  font-size: 13px;
}

.guide button {
  pointer-events: auto;
}

.draft-note {
  position: absolute;
  top: 12px;
  left: 12px;
  right: 12px;
  width: fit-content;
  max-width: calc(100% - 24px);
  padding: 8px 12px;
  border: 1px solid var(--warn);
  border-radius: 8px;
  background: var(--panel);
  font-size: 13px;
}

.steps {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.steps li {
  padding: 3px 8px;
  border-radius: 999px;
  border: 1px solid var(--line);
  color: var(--muted);
  font-size: 12px;
}

.steps li.on {
  color: var(--text);
  border-color: var(--accent);
  background: var(--panel-2);
}

.entity {
  min-height: 0;
  overflow: auto;
  padding: 16px;
  border-left: 1px solid var(--line);
}

.list {
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: auto;
  border-right: 1px solid var(--line);
  padding-bottom: 16px;
}

.list-top {
  padding: 10px 12px 6px;
}

.list-top input {
  width: 100%;
}

.pad {
  padding: 0 12px;
}

.group-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px 4px;
  font-size: 12px;
  font-weight: 600;
  color: var(--muted);
}

.group-head em,
.item em,
.picker em {
  font-style: normal;
  font-weight: 400;
  color: var(--muted);
  font-size: 11px;
}

.item-row {
  display: flex;
  align-items: center;
}

.item-row > .icon {
  flex: none;
  margin-right: 6px;
}

.item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  flex: 1;
  min-width: 0;
  padding: 6px 4px 6px 20px;
  border: 0;
  border-radius: 0;
  background: transparent;
  text-align: left;
  font-size: 13px;
}

.item:hover {
  background: var(--panel-2);
}

.item.active {
  background: var(--panel-2);
  box-shadow: inset 2px 0 0 var(--accent);
}

.item small {
  color: var(--muted);
  font-size: 11px;
  word-break: break-all;
}

.entity {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.entity h3 {
  margin: 0;
}

.conditions {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.cond-label {
  font-size: 12px;
  color: var(--muted);
}

.cond {
  display: grid;
  grid-template-columns: 1fr 70px 1fr 26px;
  gap: 6px;
  align-items: center;
}

.conditions .link {
  align-self: flex-start;
}

.draft {
  width: min(680px, 100%);
  max-height: 88vh;
}

.picker {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 34vh;
  overflow: auto;
  padding: 8px;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.picker .check {
  color: var(--text);
  font-size: 13px;
  gap: 8px;
}

.draft-log {
  margin: 0;
  padding: 8px 10px;
  max-height: 42vh;
  overflow: auto;
  border: 1px solid var(--line);
  border-radius: 8px;
  list-style: none;
  font-size: 13px;
}

.draft-log li {
  padding: 4px 0;
  border-bottom: 1px solid var(--line);
}

.draft-log li:last-child {
  border-bottom: 0;
}

.draft-log li.start {
  color: var(--text);
}

.draft-log li.done {
  color: var(--muted);
}

.draft-log ul {
  margin: 4px 0 0;
  padding-left: 18px;
  color: var(--warn);
  font-size: 12px;
}

.warnings {
  margin: 0;
  padding-left: 18px;
  max-height: 20vh;
  overflow: auto;
  color: var(--warn);
  font-size: 12px;
}
</style>
