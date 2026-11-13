<script setup lang="ts">
import { computed, onActivated, onDeactivated, onMounted, onUnmounted, ref, watch } from 'vue'
import {
  collectMetadata,
  deleteMetadata,
  explainQuery,
  listCatalogs,
  listExamples,
  listMetadata,
  runQuery
} from './api'
import { typeLabel } from './examples'
import CatalogFormModal from './components/datasource/CatalogFormModal.vue'
import { useFocusTrap } from './composables/useFocusTrap'
import { useCatalogForm, type CatalogRecord } from './composables/useCatalogForm'
import { useMetadataTree, type MetadataTable } from './composables/useMetadataTree'
import type { ExampleItem, QueryResult } from './types'
import { errMessage } from './types'

const catalogs = ref<CatalogRecord[]>([])
const examples = ref<ExampleItem[]>([])
const sql = ref('SHOW CATALOGS')
const loading = ref(false)
const error = ref('')
const result = ref<QueryResult | null>(null)
const sideError = ref('')
const gatewayOk = ref<boolean | null>(null)

const showRewritten = ref(false)
const preview = ref('')
const snapshots = ref<MetadataTable[]>([])
const collecting = ref('')
const menu = ref('')
const sideOpen = ref(false)
const sqlBox = ref<HTMLTextAreaElement | null>(null)
const previewDialog = ref<HTMLElement | null>(null)
const previewOpen = computed(() => !!preview.value)
useFocusTrap(previewOpen, previewDialog, () => {
  preview.value = ''
})
let errorTimer = 0

const hasRows = computed(() => !!result.value?.columns?.length)

// Escape closes the mobile drawer
watch(sideOpen, (open, _prev, onCleanup) => {
  if (!open) {
    return
  }
  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Escape') {
      sideOpen.value = false
      menu.value = ''
    }
  }
  document.addEventListener('keydown', onKey, true)
  onCleanup(() => document.removeEventListener('keydown', onKey, true))
})

function closeMenu() {
  menu.value = ''
}

function flashError(message: string) {
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
      sql.value = examples.value[0]?.sql ?? sql.value
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
    flashError(errMessage(e, '无法加载数据源'))
  }
}

async function loadMetadata() {
  try {
    snapshots.value = await listMetadata()
  } catch {
    snapshots.value = []
  }
}

async function refreshAfterCatalogChange() {
  await loadCatalogs()
  await loadMetadata()
}

const {
  openCatalogs,
  metaQuery,
  tree,
  isCatalogOpen,
  isSchemaOpen,
  isTableOpen,
  toggleCatalogNode,
  toggleSchema,
  toggleTable,
  useTable,
  insertTable,
  insertColumn,
  tableKey,
  schemaKey,
  qualifiedTable
} = useMetadataTree({
  catalogs,
  snapshots,
  sql,
  sqlBox,
  clearMenu: closeMenu
})

const {
  showForm,
  editing,
  form,
  formMessage,
  formError,
  saving,
  onTypeChange,
  openCreate,
  openEdit,
  testConnection,
  saveCatalog,
  removeCatalog
} = useCatalogForm({
  onSaved: refreshAfterCatalogChange,
  flashError,
  clearMenu: closeMenu,
  openCatalogs
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

function onHotkey(event: KeyboardEvent) {
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

function toggleMenu(key: string) {
  menu.value = menu.value === key ? '' : key
}

function refreshAll() {
  loadCatalogs()
  loadMetadata()
}

function useExample(item: ExampleItem) {
  sql.value = item.sql
  menu.value = ''
}

async function collectCatalog(name: string) {
  menu.value = ''
  collecting.value = name
  try {
    await collectMetadata({ catalog: name })
    openCatalogs.value = { ...openCatalogs.value, [name]: true }
    await loadMetadata()
  } catch (e) {
    flashError(errMessage(e, '采集失败'))
  } finally {
    collecting.value = ''
  }
}

async function removeTable(item: MetadataTable) {
  menu.value = ''
  if (!confirm(`从已采集的元数据中删除 ${qualifiedTable(item)}？数据库里的表不会被删除。`)) {
    return
  }
  try {
    await deleteMetadata(item.catalog, item.schema, item.table)
    await loadMetadata()
  } catch (e) {
    flashError(errMessage(e, '删除失败'))
  }
}

async function collectTable(item: MetadataTable) {
  menu.value = ''
  collecting.value = tableKey(item)
  try {
    await collectMetadata({ catalog: item.catalog, schema: item.schema, table: item.table })
    await loadMetadata()
  } catch (e) {
    flashError(errMessage(e, '重采失败'))
  } finally {
    collecting.value = ''
  }
}

async function submit(explainOnly: boolean) {
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
    error.value = errMessage(e, '请求失败')
  } finally {
    loading.value = false
  }
}

function displayCell(cell: unknown) {
  if (cell == null) {
    return { text: 'NULL', title: '', empty: true }
  }
  const text = typeof cell === 'object' ? JSON.stringify(cell) : String(cell)
  return { text, title: text, empty: false }
}

function openPreview(cell: unknown) {
  const shown = displayCell(cell)
  preview.value = shown.empty ? 'NULL' : shown.text
}

function catalogEndpoint(item: CatalogRecord | null | undefined) {
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
        <button class="icon" type="button" title="添加数据源" aria-label="添加数据源" @click="openCreate">＋</button>
        <button class="icon" type="button" title="刷新" aria-label="刷新数据源" @click="refreshAll">↻</button>
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

    <main aria-labelledby="ds-title">
      <header>
        <div class="head-left">
          <button class="icon burger" type="button" title="数据源" aria-label="打开数据源列表" @click="sideOpen = !sideOpen">☰</button>
          <h1 id="ds-title">数据源管理</h1>
          <span
            class="dot"
            role="img"
            :class="gatewayOk === false ? 'down' : gatewayOk ? 'up' : ''"
            :title="gatewayLabel"
            :aria-label="gatewayLabel"
          />
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
          <label class="sr-only" for="sql-editor">SQL 编辑器</label>
          <textarea
            id="sql-editor"
            ref="sqlBox"
            v-model="sql"
            spellcheck="false"
            aria-label="SQL 编辑器"
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
                  <th v-for="col in result?.columns || []" :key="col.name">{{ col.name }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, idx) in result?.rows || []" :key="idx">
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
      <div
        ref="previewDialog"
        class="dialog preview"
        role="dialog"
        aria-modal="true"
        aria-labelledby="cell-preview-title"
      >
        <h3 id="cell-preview-title">单元格预览</h3>
        <pre>{{ preview }}</pre>
        <div class="actions">
          <button class="primary" type="button" @click="preview = ''">关闭</button>
        </div>
      </div>
    </div>

    <CatalogFormModal
      :show="showForm"
      :editing="editing"
      :form="form"
      :form-message="formMessage"
      :form-error="formError"
      :saving="saving"
      @close="showForm = false"
      @save="saveCatalog"
      @test="testConnection"
      @type-change="onTypeChange"
    />
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
