<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { createCatalog, deleteCatalog, explainQuery, listCatalogs, listExamples, runQuery, testCatalog, updateCatalog } from './api'
import { SOURCE_TYPES, emptyCatalog, sampleSql, typeLabel } from './examples'

const catalogs = ref([])
const examples = ref([])
const sql = ref('SHOW CATALOGS')
const loading = ref(false)
const error = ref('')
const result = ref(null)
const catalogError = ref('')
const gatewayOk = ref(null)
const selectedCatalog = ref('')

const showForm = ref(false)
const editing = ref(false)
const form = reactive(emptyCatalog())
const formMessage = ref('')
const formError = ref('')
const saving = ref(false)
const showRewritten = ref(false)
const preview = ref('')

const routeBadge = computed(() => result.value?.engine || '-')
const hasRows = computed(() => !!result.value?.columns?.length)

onMounted(() => {
  loadCatalogs()
  loadExamples()
  window.addEventListener('keydown', onHotkey)
})

onUnmounted(() => {
  window.removeEventListener('keydown', onHotkey)
})

function onHotkey(event) {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault()
    if (!loading.value) {
      submit(false)
    }
  }
}

async function loadExamples() {
  try {
    examples.value = await listExamples()
    if (examples.value.length && sql.value === 'SHOW CATALOGS') {
      sql.value = examples.value[0].sql
    }
  } catch {
    examples.value = []
  }
}

async function loadCatalogs() {
  try {
    catalogs.value = await listCatalogs()
    catalogError.value = ''
    gatewayOk.value = true
  } catch (e) {
    catalogError.value = e.message || '无法加载数据源，请先启动网关 localhost:18080'
    gatewayOk.value = false
  }
}

function buildBody() {
  return {
    sql: sql.value,
    skipCache: false
  }
}

async function submit(explainOnly) {
  error.value = ''
  result.value = null
  preview.value = ''
  loading.value = true
  try {
    const body = buildBody()
    result.value = explainOnly ? await explainQuery(body) : await runQuery(body)
    if (!result.value.success) {
      error.value = result.value.message || '查询失败'
    }
  } catch (e) {
    error.value = e.message || '请求失败'
  } finally {
    loading.value = false
  }
}

function onTypeChange() {
  const found = SOURCE_TYPES.find((item) => item.value === form.type)
  if (found) {
    form.port = found.port
  }
}

function openCreate() {
  Object.assign(form, emptyCatalog())
  editing.value = false
  formMessage.value = ''
  formError.value = ''
  showForm.value = true
}

function openEdit(catalog) {
  Object.assign(form, emptyCatalog(), {
    name: catalog.name,
    type: catalog.type,
    host: catalog.host || '127.0.0.1',
    port: catalog.port || SOURCE_TYPES.find((item) => item.value === catalog.type)?.port || 3306,
    database: catalog.database || '',
    username: catalog.username || '',
    password: '',
    url: catalog.url || '',
    extra: catalog.extra || '',
    tls: !!catalog.tls
  })
  editing.value = true
  formMessage.value = ''
  formError.value = ''
  showForm.value = true
}

function useCatalog(catalog) {
  selectedCatalog.value = catalog.name
  sql.value = sampleSql(catalog)
}

function payload() {
  return {
    name: form.name.trim(),
    type: form.type,
    route: 'TRINO',
    host: form.host.trim(),
    port: Number(form.port),
    database: form.database.trim(),
    username: form.username.trim(),
    password: form.password,
    url: form.url.trim(),
    extra: form.extra.trim(),
    tls: !!form.tls
  }
}

async function testConnection() {
  formMessage.value = ''
  formError.value = ''
  try {
    const data = await testCatalog(payload())
    if (data.success) {
      formMessage.value = data.message
    } else {
      formError.value = data.message || '连接失败'
    }
  } catch (e) {
    formError.value = e.message || '连接失败'
  }
}

async function saveCatalog() {
  formMessage.value = ''
  formError.value = ''
  saving.value = true
  try {
    const body = payload()
    if (editing.value) {
      await updateCatalog(body.name, body)
    } else {
      await createCatalog(body)
    }
    showForm.value = false
    await loadCatalogs()
    sql.value = sampleSql(body)
    selectedCatalog.value = body.name
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function removeCatalog(catalog) {
  if (!confirm(`删除数据源 ${catalog.name}？`)) {
    return
  }
  try {
    await deleteCatalog(catalog.name)
    if (selectedCatalog.value === catalog.name) {
      selectedCatalog.value = ''
    }
    await loadCatalogs()
  } catch (e) {
    catalogError.value = e.message || '删除失败'
  }
}

function displayCell(cell) {
  if (cell == null) {
    return { text: 'NULL', title: '', empty: true }
  }
  const text = typeof cell === 'object' ? JSON.stringify(cell) : String(cell)
  return { text, title: text, empty: false }
}

function openPreview(cell) {
  const shown = displayCell(cell)
  preview.value = shown.empty ? 'NULL' : shown.text
}

function catalogEndpoint(item) {
  const host = item.host || '-'
  const port = item.port || '-'
  return item.database ? `${host}:${port} / ${item.database}` : `${host}:${port}`
}
</script>

<template>
  <div class="layout">
    <aside>
      <div class="brand">
        <h1>联邦查询</h1>
        <p class="sub">跨 MySQL / ClickHouse / ES / Kafka</p>
        <span class="status" :class="gatewayOk === false ? 'down' : gatewayOk ? 'up' : ''">
          {{ gatewayOk === false ? '网关离线' : gatewayOk ? '网关在线' : '检测中' }}
        </span>
      </div>

      <div class="block-head">
        <h2>数据源</h2>
        <button class="tiny" type="button" @click="loadCatalogs">刷新</button>
      </div>
      <button class="primary add" type="button" @click="openCreate">添加数据源</button>
      <p v-if="catalogError" class="err">{{ catalogError }}</p>
      <p v-if="!catalogs.length && !catalogError" class="hint">还没有数据源，先登记已有库。</p>
      <ul class="sources">
        <li v-for="item in catalogs" :key="item.name" :class="{ active: selectedCatalog === item.name }">
          <button class="ghost name" type="button" @click="useCatalog(item)">
            <span class="title-row">
              <strong>{{ item.name }}</strong>
              <em class="badge" :class="item.type">{{ typeLabel(item.type) }}</em>
            </span>
            <span class="meta-line">{{ catalogEndpoint(item) }}</span>
            <span class="meta-line route">Trino</span>
          </button>
          <div class="ops">
            <button type="button" @click="openEdit(item)">编辑</button>
            <button class="danger" type="button" @click="removeCatalog(item)">删除</button>
          </div>
        </li>
      </ul>

      <h2>示例 SQL</h2>
      <div class="chips">
        <button v-for="item in examples" :key="item.name" class="chip" type="button" @click="sql = item.sql">
          {{ item.name }}
        </button>
      </div>
    </aside>

    <main>
      <header>
        <p class="engine-label">查询引擎 <b>Trino</b></p>
        <div class="run">
          <button type="button" :disabled="loading" @click="submit(true)">查看计划</button>
          <button class="primary" type="button" :disabled="loading" @click="submit(false)">
            {{ loading ? '执行中...' : '执行查询' }}
          </button>
        </div>
      </header>

      <section class="workspace">
        <section class="editor">
          <div class="editor-head">
            <span>SQL</span>
            <span class="hint">Ctrl / ⌘ + Enter 执行 · from、带 - 的名字请加双引号</span>
          </div>
          <textarea
            v-model="sql"
            spellcheck="false"
            placeholder="使用 catalog.schema.table，例如 es81.default.his_msg"
          />
        </section>

        <section class="result">
          <p v-if="error" class="err banner">{{ error }}</p>
          <div v-if="result && result.success" class="meta">
            <span>引擎 <b>{{ routeBadge }}</b></span>
            <span>行数 <b>{{ result.rowCount }}</b></span>
            <span>耗时 <b>{{ result.elapsedMs }} ms</b></span>
            <span v-if="result.cached" class="pill">缓存</span>
            <button v-if="result.rewrittenSql" class="link" type="button" @click="showRewritten = !showRewritten">
              {{ showRewritten ? '隐藏改写 SQL' : '改写 SQL' }}
            </button>
          </div>
          <p v-if="result?.routeReason" class="reason">{{ result.routeReason }}</p>
          <pre v-if="showRewritten && result?.rewrittenSql" class="sql">{{ result.rewrittenSql }}</pre>
          <div v-if="hasRows" class="table-wrap">
            <table>
              <thead>
                <tr>
                  <th v-for="col in result.columns" :key="col.name">{{ col.name }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, idx) in result.rows" :key="idx">
                  <td
                    v-for="(cell, cidx) in row"
                    :key="cidx"
                    :class="{ nulls: displayCell(cell).empty }"
                    :title="displayCell(cell).title"
                    @click="openPreview(cell)"
                  >
                    {{ displayCell(cell).text }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else-if="!error && !loading" class="empty">执行查询后，结果会显示在这里。长文本单击单元格可预览。</p>
        </section>
      </section>
    </main>

    <div v-if="preview" class="mask" @click.self="preview = ''">
      <div class="dialog preview">
        <h3>单元格预览</h3>
        <pre>{{ preview }}</pre>
        <div class="actions">
          <button class="primary" type="button" @click="preview = ''">关闭</button>
        </div>
      </div>
    </div>

    <div v-if="showForm" class="mask" @click.self="showForm = false">
      <form class="dialog" @submit.prevent="saveCatalog">
        <h3>{{ editing ? '编辑数据源' : '添加数据源' }}</h3>
        <label>
          名称（SQL 里的 catalog）
          <input v-model="form.name" :disabled="editing" required placeholder="例如 mysql70 或 es81" />
        </label>
        <label>
          类型
          <select v-model="form.type" @change="onTypeChange">
            <option v-for="item in SOURCE_TYPES" :key="item.value" :value="item.value">{{ item.label }}</option>
          </select>
        </label>
        <div class="row">
          <label>
            主机
            <input v-model="form.host" required placeholder="127.0.0.1" />
          </label>
          <label>
            端口
            <input v-model.number="form.port" type="number" required />
          </label>
        </div>
        <label>
          {{ form.type === 'elasticsearch' ? '默认 Schema' : form.type === 'kafka' ? 'Kafka Schema' : '数据库 / Schema' }}
          <input v-model="form.database" :placeholder="form.type === 'kafka' || form.type === 'elasticsearch' ? 'default' : '例如 shop'" />
        </label>
        <label v-if="form.type === 'kafka'">
          Topic 列表（逗号分隔，可后补）
          <input v-model="form.extra" placeholder="orders,my-topic" />
        </label>
        <label v-if="form.type === 'elasticsearch'" class="check">
          <input v-model="form.tls" type="checkbox" />
          启用 TLS
        </label>
        <div class="row">
          <label>
            用户名
            <input v-model="form.username" />
          </label>
          <label>
            密码
            <input v-model="form.password" type="password" :placeholder="editing ? '留空则不修改' : ''" />
          </label>
        </div>
        <p class="hint">
          全部通过 Trino 查询。保存后需 <code>docker compose restart trino</code> 加载 catalog。Kafka 填用户名密码即 SASL_PLAINTEXT/PLAIN。
        </p>
        <p v-if="formMessage" class="ok">{{ formMessage }}</p>
        <p v-if="formError" class="err">{{ formError }}</p>
        <div class="actions">
          <button type="button" @click="testConnection">测试连接</button>
          <button type="button" @click="showForm = false">取消</button>
          <button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中...' : '保存' }}</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 300px 1fr;
  height: 100%;
}

aside {
  border-right: 1px solid var(--line);
  background: rgba(18, 26, 43, 0.92);
  padding: 20px 16px;
  overflow: auto;
}

.brand {
  margin-bottom: 8px;
}

h1 {
  margin: 0;
  font-size: 20px;
  letter-spacing: 0.02em;
}

.sub,
.reason,
.hint,
.meta-line,
.empty {
  color: var(--muted);
}

.status {
  display: inline-flex;
  margin-top: 10px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 12px;
  background: var(--panel-2);
  color: var(--muted);
}

.status.up {
  color: var(--ok);
  background: rgba(62, 207, 142, 0.12);
}

.status.down {
  color: var(--err);
  background: rgba(255, 107, 122, 0.12);
}

.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18px;
}

h2 {
  margin: 18px 0 8px;
  font-size: 12px;
  color: var(--muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.block-head h2 {
  margin: 0;
}

.add {
  width: 100%;
  margin: 10px 0 12px;
}

.sources {
  list-style: none;
  padding: 0;
  margin: 0 0 8px;
}

.sources li {
  padding: 10px;
  margin-bottom: 8px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--panel-2);
}

.sources li.active {
  border-color: rgba(78, 161, 255, 0.55);
  box-shadow: inset 0 0 0 1px rgba(78, 161, 255, 0.2);
}

.name {
  width: 100%;
  text-align: left;
  padding: 0;
  border: 0;
}

.title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.meta-line,
.name strong {
  display: block;
}

.meta-line {
  margin-top: 4px;
  font-size: 12px;
}

.badge {
  font-style: normal;
  font-size: 11px;
  padding: 2px 6px;
  border-radius: 999px;
  background: #24324a;
  color: var(--accent-2);
}

.badge.clickhouse { color: #ffb14a; }
.badge.elasticsearch { color: #f5c518; }
.badge.kafka { color: #b9a0ff; }
.badge.mysql { color: #7ec0ff; }

.ops {
  display: flex;
  gap: 8px;
  margin-top: 8px;
}

.ops button,
.tiny {
  padding: 4px 8px;
  font-size: 12px;
}

.danger {
  color: var(--err);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  padding: 6px 10px;
  font-size: 12px;
}

main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

header {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  padding: 14px 20px;
  border-bottom: 1px solid var(--line);
  background: rgba(18, 26, 43, 0.72);
}

.engine-label {
  margin: 0;
  font-size: 13px;
  color: var(--muted);
}

.engine-label b {
  color: var(--ok);
}

.run {
  display: flex;
  gap: 8px;
}

label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 12px;
  color: var(--muted);
}

.grow {
  flex: 1;
  min-width: 180px;
}

input,
select,
textarea,
button {
  background: var(--panel-2);
  color: var(--text);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 8px 10px;
}

button {
  cursor: pointer;
}

button.primary {
  background: linear-gradient(180deg, #6bb4ff, #3d8eef);
  color: #061018;
  border-color: transparent;
  font-weight: 600;
}

button.ghost,
button.link {
  background: transparent;
}

button.link {
  border: 0;
  padding: 0;
  color: var(--accent-2);
  text-align: left;
  width: fit-content;
}

button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.workspace {
  display: grid;
  grid-template-rows: minmax(220px, 34%) 1fr;
  min-height: 0;
  flex: 1;
}

.editor,
.result {
  min-height: 0;
  padding: 14px 20px;
}

.editor {
  display: flex;
  flex-direction: column;
  gap: 8px;
  border-bottom: 1px solid var(--line);
}

.editor-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: var(--muted);
}

textarea {
  width: 100%;
  min-height: 0;
  flex: 1;
  resize: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  line-height: 1.55;
}

.params {
  flex: 0 0 88px;
}

.result {
  overflow: auto;
}

.meta {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  align-items: center;
  font-size: 13px;
  color: var(--muted);
}

.meta b {
  color: var(--ok);
}

.pill {
  padding: 2px 8px;
  border-radius: 999px;
  background: rgba(62, 207, 142, 0.12);
  color: var(--ok);
}

.sql,
.preview pre {
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 12px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
}

.table-wrap {
  overflow: auto;
  margin-top: 12px;
  border: 1px solid var(--line);
  border-radius: 10px;
}

table {
  border-collapse: collapse;
  width: max-content;
  min-width: 100%;
  font-size: 13px;
}

th,
td {
  border-bottom: 1px solid var(--line);
  padding: 8px 10px;
  text-align: left;
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

th {
  position: sticky;
  top: 0;
  background: #182338;
  color: var(--muted);
  font-weight: 600;
}

td {
  cursor: pointer;
}

td.nulls {
  color: var(--muted);
  font-style: italic;
}

.err {
  color: var(--err);
}

.ok {
  color: var(--ok);
}

.banner {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(255, 107, 122, 0.1);
}

.empty {
  margin-top: 28px;
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(4, 8, 16, 0.62);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 20px;
}

.dialog {
  width: min(540px, 100%);
  background: var(--panel);
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-shadow: var(--shadow);
}

.dialog.preview {
  width: min(720px, 100%);
  max-height: 80vh;
}

.dialog.preview pre {
  max-height: 52vh;
}

.dialog h3 {
  margin: 0;
}

.dialog code {
  font-size: 12px;
}

.row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.check {
  flex-direction: row;
  align-items: center;
}

.check input {
  width: auto;
}

@media (max-width: 960px) {
  .layout {
    grid-template-columns: 1fr;
  }

  aside {
    height: auto;
    max-height: 42vh;
    border-right: 0;
    border-bottom: 1px solid var(--line);
  }
}
</style>
