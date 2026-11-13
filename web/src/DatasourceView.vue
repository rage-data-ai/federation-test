<script setup>
import { computed, nextTick, onActivated, onDeactivated, onMounted, onUnmounted, reactive, ref } from 'vue'
import { collectMetadata, createCatalog, deleteCatalog, deleteMetadata, explainQuery, listCatalogs, listExamples, listMetadata, runQuery, testCatalog, updateCatalog } from './api'
import { SOURCE_TYPES, emptyCatalog, typeLabel } from './examples'

const catalogs = ref([])
const examples = ref([])
const sql = ref('SHOW CATALOGS')
const loading = ref(false)
const error = ref('')
const result = ref(null)
const sideError = ref('')
const gatewayOk = ref(null)

const showForm = ref(false)
const editing = ref(false)
const form = reactive(emptyCatalog())
const formMessage = ref('')
const formError = ref('')
const saving = ref(false)
const showRewritten = ref(false)
const preview = ref('')
const snapshots = ref([])
const collecting = ref('')
const openCatalogs = ref({})
const openSchemas = ref({})
const openTables = ref({})
const metaQuery = ref('')
const menu = ref('')
const sideOpen = ref(false)
const sqlBox = ref(null)
const TABLE_CAP = 80
let errorTimer = 0

const hasRows = computed(() => !!result.value?.columns?.length)

function columnHits(item, q) {
  return (item.columns || []).filter((col) => col.name.toLowerCase().includes(q) || String(col.type || '').toLowerCase().includes(q))
}

const tree = computed(() => {
  const q = metaQuery.value.trim().toLowerCase()
  const byCatalog = {}
  const order = []
  const ensure = (name, source) => {
    if (!byCatalog[name]) {
      byCatalog[name] = { name, source, schemas: {}, tableCount: 0, matched: 0 }
      order.push(name)
    }
    return byCatalog[name]
  }
  for (const item of catalogs.value) {
    ensure(item.name, item)
  }
  for (const item of snapshots.value) {
    const cat = ensure(item.catalog, null)
    cat.tableCount += 1
    const hits = q ? columnHits(item, q) : []
    const selfHit = !q
      || item.catalog.toLowerCase().includes(q)
      || item.schema.toLowerCase().includes(q)
      || item.table.toLowerCase().includes(q)
    if (!selfHit && !hits.length) {
      continue
    }
    const sch = cat.schemas[item.schema] || (cat.schemas[item.schema] = { name: item.schema, tables: [] })
    sch.tables.push({ ...item, columns: q && hits.length && !selfHit ? hits : item.columns || [] })
    cat.matched += 1
  }
  const cap = q ? 200 : TABLE_CAP
  return order
    .map((name) => byCatalog[name])
    .filter((cat) => !q || cat.matched || cat.name.toLowerCase().includes(q))
    .map((cat) => ({
      name: cat.name,
      source: cat.source,
      tableCount: cat.tableCount,
      schemas: Object.values(cat.schemas).map((sch) => ({
        name: sch.name,
        tables: sch.tables.slice(0, cap),
        hidden: Math.max(0, sch.tables.length - cap)
      }))
    }))
})

const gatewayLabel = computed(() => (gatewayOk.value === false ? '网关离线' : gatewayOk.value ? '网关在线' : '检测中'))

onMounted(() => {
  loadCatalogs()
  loadExamples()
  loadMetadata()
})

onActivated(() => {
  window.addEventListener('keydown', onHotkey)
  window.addEventListener('click', closeMenu)
})

onDeactivated(() => {
  window.removeEventListener('keydown', onHotkey)
  window.removeEventListener('click', closeMenu)
  menu.value = ''
  sideOpen.value = false
})

onUnmounted(() => {
  window.clearTimeout(errorTimer)
  window.removeEventListener('keydown', onHotkey)
  window.removeEventListener('click', closeMenu)
})

function onHotkey(event) {
  if ((event.metaKey || event.ctrlKey) && event.key === 'Enter') {
    event.preventDefault()
    if (!loading.value) {
      submit(false)
    }
  }
  if (event.key === 'Escape') {
    menu.value = ''
  }
}

function toggleMenu(key) {
  menu.value = menu.value === key ? '' : key
}

function closeMenu() {
  menu.value = ''
}

function flashError(message) {
  sideError.value = message
  window.clearTimeout(errorTimer)
  errorTimer = window.setTimeout(() => {
    sideError.value = ''
  }, 4000)
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
    gatewayOk.value = true
  } catch (e) {
    gatewayOk.value = false
    flashError(e.message || '无法加载数据源')
  }
}

async function loadMetadata() {
  try {
    snapshots.value = await listMetadata()
  } catch {
    snapshots.value = []
  }
}

function refreshAll() {
  loadCatalogs()
  loadMetadata()
}

function useExample(item) {
  sql.value = item.sql
  menu.value = ''
}

function quoteIdent(name) {
  return /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) ? name : `"${String(name).replaceAll('"', '""')}"`
}

function tableKey(item) {
  return `${item.catalog}.${item.schema}.${item.table}`
}

function schemaKey(catalog, schema) {
  return `${catalog}.${schema}`
}

function qualifiedTable(item) {
  return `${quoteIdent(item.catalog)}.${quoteIdent(item.schema)}.${quoteIdent(item.table)}`
}

function isCatalogOpen(name) {
  if (metaQuery.value.trim()) {
    return true
  }
  return openCatalogs.value[name] !== false
}

function isSchemaOpen(catalog, schema) {
  return !!metaQuery.value.trim() || !!openSchemas.value[schemaKey(catalog, schema)]
}

function isTableOpen(item) {
  if (openTables.value[tableKey(item)]) {
    return true
  }
  const q = metaQuery.value.trim().toLowerCase()
  return !!q && columnHits(item, q).length > 0
}

function toggleCatalogNode(name) {
  openCatalogs.value = { ...openCatalogs.value, [name]: !isCatalogOpen(name) }
}

function toggleSchema(catalog, schema) {
  const key = schemaKey(catalog, schema)
  openSchemas.value = { ...openSchemas.value, [key]: !isSchemaOpen(catalog, schema) }
}

function toggleTable(item) {
  const key = tableKey(item)
  openTables.value = { ...openTables.value, [key]: !openTables.value[key] }
}

function insertAtCursor(text) {
  const el = sqlBox.value
  const current = sql.value || ''
  if (!el) {
    sql.value = current && !/\s$/.test(current) ? `${current} ${text}` : `${current}${text}`
    return
  }
  const start = el.selectionStart ?? current.length
  const end = el.selectionEnd ?? current.length
  const before = current.slice(0, start)
  const after = current.slice(end)
  const pad = before && !/\s$/.test(before) ? ' ' : ''
  sql.value = `${before}${pad}${text}${after}`
  const pos = start + pad.length + text.length
  nextTick(() => {
    el.focus()
    el.setSelectionRange(pos, pos)
  })
}

function useTable(item) {
  const cols = item.columns?.length
    ? item.columns.map((col) => quoteIdent(col.name)).join(', ')
    : '*'
  sql.value = `SELECT ${cols}\nFROM ${qualifiedTable(item)}\nLIMIT 20`
}

function insertTable(item) {
  menu.value = ''
  insertAtCursor(qualifiedTable(item))
}

function insertColumn(item, name) {
  insertAtCursor(`${quoteIdent(item.table)}.${quoteIdent(name)}`)
}

async function collectCatalog(name) {
  menu.value = ''
  collecting.value = name
  try {
    await collectMetadata({ catalog: name })
    openCatalogs.value = { ...openCatalogs.value, [name]: true }
    await loadMetadata()
  } catch (e) {
    flashError(e.message || '采集失败')
  } finally {
    collecting.value = ''
  }
}

async function removeTable(item) {
  menu.value = ''
  if (!confirm(`从已采集的元数据中删除 ${qualifiedTable(item)}？数据库里的表不会被删除。`)) {
    return
  }
  try {
    await deleteMetadata(item.catalog, item.schema, item.table)
    await loadMetadata()
  } catch (e) {
    flashError(e.message || '删除失败')
  }
}

async function collectTable(item) {
  menu.value = ''
  collecting.value = tableKey(item)
  try {
    await collectMetadata({ catalog: item.catalog, schema: item.schema, table: item.table })
    await loadMetadata()
  } catch (e) {
    flashError(e.message || '重采失败')
  } finally {
    collecting.value = ''
  }
}

async function submit(explainOnly) {
  error.value = ''
  result.value = null
  preview.value = ''
  loading.value = true
  try {
    const body = { sql: sql.value, skipCache: false }
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
  menu.value = ''
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
    openCatalogs.value = { ...openCatalogs.value, [body.name]: true }
    await loadCatalogs()
    await loadMetadata()
  } catch (e) {
    formError.value = e.message || '保存失败'
  } finally {
    saving.value = false
  }
}

async function removeCatalog(catalog) {
  menu.value = ''
  if (!confirm(`删除数据源 ${catalog.name}？`)) {
    return
  }
  try {
    await deleteCatalog(catalog.name)
    await loadCatalogs()
    await loadMetadata()
  } catch (e) {
    flashError(e.message || '删除失败')
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
  if (!item) {
    return ''
  }
  const host = item.host || '-'
  const port = item.port || '-'
  return item.database ? `${host}:${port} / ${item.database}` : `${host}:${port}`
}
</script>

<template>
  <div class="layout">
    <div v-if="sideOpen" class="drawer-mask" @click="sideOpen = false" />

    <aside class="side" :class="{ open: sideOpen }">
      <div class="side-top">
        <input v-model="metaQuery" class="search" type="search" placeholder="搜索表 / 字段" />
        <button class="icon" type="button" title="添加数据源" @click="openCreate">＋</button>
        <button class="icon" type="button" title="刷新" @click="refreshAll">↻</button>
      </div>
      <p v-if="sideError" class="flash">{{ sideError }}</p>

      <p v-if="gatewayOk === false && !catalogs.length" class="hint pad">网关未连接（localhost:18080）。</p>
      <p v-else-if="gatewayOk && !catalogs.length" class="hint pad">还没有数据源，点右上角 ＋ 添加。</p>
      <p v-else-if="metaQuery && !tree.length" class="hint pad">没有匹配「{{ metaQuery }}」的表或字段。</p>

      <ul class="tree">
        <li v-for="cat in tree" :key="cat.name">
          <div class="row lvl0" :class="{ hot: menu === `c:${cat.name}` }" :title="catalogEndpoint(cat.source)">
            <button class="node" type="button" @click="toggleCatalogNode(cat.name)">
              <span class="caret">{{ isCatalogOpen(cat.name) ? '▾' : '▸' }}</span>
              <span class="label">{{ cat.name }}</span>
              <em v-if="cat.source" class="badge" :class="cat.source.type">{{ typeLabel(cat.source.type) }}</em>
            </button>
            <span class="count">{{ collecting === cat.name ? '采集中…' : cat.tableCount || '' }}</span>
            <div v-if="cat.source" class="ops">
              <button class="icon sm" type="button" title="更多" @click.stop="toggleMenu(`c:${cat.name}`)">⋯</button>
              <div v-if="menu === `c:${cat.name}`" class="menu" @click.stop>
                <button type="button" :disabled="!!collecting" @click="collectCatalog(cat.name)">采集</button>
                <button type="button" @click="openEdit(cat.source)">编辑</button>
                <button class="danger" type="button" @click="removeCatalog(cat.source)">删除</button>
              </div>
            </div>
          </div>

          <template v-if="isCatalogOpen(cat.name)">
            <div v-if="!cat.schemas.length && !metaQuery" class="row lvl1 pending">
              <span>尚未采集</span>
              <button class="link" type="button" :disabled="!!collecting" @click="collectCatalog(cat.name)">采集</button>
            </div>
            <template v-for="group in cat.schemas" :key="schemaKey(cat.name, group.name)">
              <div class="row lvl1">
                <button class="node" type="button" @click="toggleSchema(cat.name, group.name)">
                  <span class="caret">{{ isSchemaOpen(cat.name, group.name) ? '▾' : '▸' }}</span>
                  <span class="label">{{ group.name }}</span>
                </button>
              </div>
              <template v-if="isSchemaOpen(cat.name, group.name)">
                <template v-for="table in group.tables" :key="tableKey(table)">
                  <div class="row lvl2" :class="{ hot: menu === `t:${tableKey(table)}` }" :title="qualifiedTable(table)">
                    <button class="node" type="button" @click="toggleTable(table)">
                      <span class="caret">{{ isTableOpen(table) ? '▾' : '▸' }}</span>
                      <span class="label">{{ table.table }}</span>
                    </button>
                    <span v-if="collecting === tableKey(table)" class="count">重采中…</span>
                    <div class="ops">
                      <button class="tiny" type="button" title="生成 SELECT 模板" @click="useTable(table)">SELECT</button>
                      <button class="icon sm" type="button" title="更多" @click.stop="toggleMenu(`t:${tableKey(table)}`)">⋯</button>
                      <div v-if="menu === `t:${tableKey(table)}`" class="menu" @click.stop>
                        <button type="button" @click="insertTable(table)">引用到光标处</button>
                        <button type="button" :disabled="!!collecting" @click="collectTable(table)">重采</button>
                        <button class="danger" type="button" @click="removeTable(table)">删除</button>
                      </div>
                    </div>
                  </div>
                  <template v-if="isTableOpen(table)">
                    <button
                      v-for="col in table.columns"
                      :key="col.name"
                      class="row lvl3 col"
                      type="button"
                      title="插入到光标处"
                      @click="insertColumn(table, col.name)"
                    >
                      <span class="label">{{ col.name }}</span>
                      <em>{{ col.type }}</em>
                    </button>
                  </template>
                </template>
                <p v-if="group.hidden" class="hint lvl2 more">还有 {{ group.hidden }} 张表，输入名称过滤</p>
              </template>
            </template>
          </template>
        </li>
      </ul>
    </aside>

    <main>
      <header>
        <div class="head-left">
          <button class="icon burger" type="button" title="数据源" @click="sideOpen = !sideOpen">☰</button>
          <h1>数据源管理</h1>
          <span class="dot" :class="gatewayOk === false ? 'down' : gatewayOk ? 'up' : ''" :title="gatewayLabel" />
        </div>
        <div class="head-right">
          <button type="button" :disabled="loading" @click="submit(true)">查看计划</button>
          <button class="primary" type="button" :disabled="loading" @click="submit(false)">
            {{ loading ? '执行中…' : '执行查询' }}
          </button>
        </div>
      </header>

      <section class="workspace">
        <section class="editor">
          <div v-if="examples.length" class="editor-bar">
            <div class="dropdown">
              <button class="ghost tiny" type="button" @click.stop="toggleMenu('examples')">示例 ▾</button>
              <div v-if="menu === 'examples'" class="menu left" @click.stop>
                <button v-for="item in examples" :key="item.name" type="button" @click="useExample(item)">
                  {{ item.name }}
                </button>
              </div>
            </div>
          </div>
          <textarea
            ref="sqlBox"
            v-model="sql"
            spellcheck="false"
            placeholder="SELECT * FROM catalog.schema.table    ⌘ / Ctrl + Enter 执行"
          />
        </section>

        <section class="result">
          <p v-if="error" class="err banner">{{ error }}</p>
          <div v-if="result && result.success" class="meta">
            <span>{{ result.rowCount }} 行 · {{ result.elapsedMs }} ms<template v-if="result.cached"> · 缓存</template></span>
            <button v-if="result.rewrittenSql" class="link" type="button" @click="showRewritten = !showRewritten">
              {{ showRewritten ? '隐藏改写 SQL' : '改写 SQL' }}
            </button>
          </div>
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
          <p v-else-if="!error && !loading" class="empty">执行查询后，结果显示在这里；单击单元格可预览长文本。</p>
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
        <div class="pair">
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
        <div class="pair">
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
          保存后由 Trino 动态加载，无需重启。Kafka 填用户名密码即 SASL_PLAINTEXT/PLAIN。
        </p>
        <p v-if="formMessage" class="ok">{{ formMessage }}</p>
        <p v-if="formError" class="err">{{ formError }}</p>
        <div class="actions">
          <button type="button" @click="testConnection">测试连接</button>
          <button type="button" @click="showForm = false">取消</button>
          <button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
      </form>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 300px 1fr;
  min-height: 0;
}

/* ---------- 侧栏 ---------- */

.side {
  display: flex;
  flex-direction: column;
  min-height: 0;
  border-right: 1px solid var(--line);
  background: var(--panel);
}

.side-top {
  display: flex;
  gap: 6px;
  padding: 12px;
  border-bottom: 1px solid var(--line);
}

.search {
  flex: 1;
  min-width: 0;
  padding: 6px 10px;
}

.flash {
  margin: 0;
  padding: 6px 12px;
  font-size: 12px;
  color: var(--err);
  background: rgba(248, 81, 73, 0.1);
}

.pad {
  padding: 12px;
  margin: 0;
}

.tree {
  flex: 1;
  overflow: auto;
  list-style: none;
  margin: 0;
  padding: 6px 0 12px;
}

.row {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  min-height: 28px;
  padding-right: 8px;
  font-size: 13px;
}

.row:hover,
.row.hot {
  background: var(--panel-2);
}

.lvl0 { padding-left: 8px; font-weight: 600; }
.lvl1 { padding-left: 22px; color: var(--muted); }
.lvl2 { padding-left: 36px; }
.lvl3 { padding-left: 52px; }

.node {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: inherit;
  font-weight: inherit;
  text-align: left;
}

.caret {
  width: 10px;
  flex: none;
  font-size: 10px;
  color: var(--muted);
}

.label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.count {
  flex: none;
  font-size: 11px;
  font-weight: 400;
  color: var(--muted);
}

.ops {
  position: relative;
  display: none;
  align-items: center;
  gap: 4px;
}

.row:hover .ops,
.row.hot .ops {
  display: flex;
}

.row:hover .count,
.row.hot .count {
  display: none;
}

.pending {
  gap: 10px;
  font-size: 12px;
}

.col {
  width: 100%;
  justify-content: space-between;
  border: 0;
  border-radius: 0;
  background: transparent;
  padding-top: 2px;
  padding-bottom: 2px;
  min-height: 24px;
  font-size: 12px;
  text-align: left;
}

.col em {
  flex: none;
  font-style: normal;
  color: var(--muted);
}

.more {
  margin: 4px 0;
  font-size: 12px;
}

.badge {
  flex: none;
  font-style: normal;
  font-weight: 400;
  font-size: 10px;
  color: var(--muted);
}

.badge.clickhouse { color: #e3a23c; }
.badge.elasticsearch { color: #c9a21a; }
.badge.kafka { color: #9d84e8; }
.badge.mysql { color: #4f9fe0; }
.badge.postgresql { color: #5a8fd6; }

/* ---------- 弹出菜单 ---------- */

.dropdown {
  position: relative;
}

.menu {
  position: absolute;
  top: calc(100% + 4px);
  right: 0;
  z-index: 20;
  min-width: 128px;
  display: flex;
  flex-direction: column;
  padding: 4px;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: var(--panel);
  box-shadow: var(--shadow);
}

.menu.left {
  left: 0;
  right: auto;
  max-height: 320px;
  overflow: auto;
}

.menu button {
  border: 0;
  background: transparent;
  padding: 6px 10px;
  font-size: 13px;
  font-weight: 400;
  text-align: left;
  white-space: nowrap;
}

.menu button:hover:not(:disabled) {
  background: var(--panel-2);
}

/* ---------- 主区域 ---------- */

main {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
}

header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 16px;
  border-bottom: 1px solid var(--line);
  background: var(--panel);
}

.head-left,
.head-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

h1 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--muted);
}

.dot.up { background: var(--ok); }
.dot.down { background: var(--err); }

.head-left .burger {
  display: none;
}

.workspace {
  display: grid;
  grid-template-rows: minmax(200px, 34%) 1fr;
  min-height: 0;
  flex: 1;
}

.editor {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-height: 0;
  padding: 10px 16px 14px;
  border-bottom: 1px solid var(--line);
}

.editor-bar {
  display: flex;
}

textarea {
  flex: 1;
  width: 100%;
  min-height: 0;
  resize: none;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  line-height: 1.55;
}

.result {
  min-height: 0;
  overflow: auto;
  padding: 12px 16px;
}

.meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: var(--muted);
}


.drawer-mask {
  display: none;
}

/* ---------- 窄屏：侧栏变抽屉 ---------- */

@media (max-width: 900px) {
  .layout {
    grid-template-columns: 1fr;
  }

  .head-left .burger {
    display: inline-flex;
  }

  .side {
    position: fixed;
    top: 0;
    bottom: 0;
    left: 0;
    z-index: 30;
    width: min(320px, 86vw);
    transform: translateX(-100%);
    transition: transform 0.18s ease;
  }

  .side.open {
    transform: none;
    box-shadow: var(--shadow);
  }

  .drawer-mask {
    display: block;
    position: fixed;
    inset: 0;
    z-index: 25;
    background: rgba(4, 8, 16, 0.4);
  }
}
</style>
