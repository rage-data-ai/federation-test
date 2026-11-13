<script setup lang="ts">
import { computed, inject, onActivated, ref, watch, type Ref } from 'vue'
import {
  createOntologyVersion,
  getOntology,
  getOntologyTurtle,
  listCatalogs,
  listMetadata,
  listOntologyVersions,
  llmStatus,
  renameSubject,
  restoreOntologyVersion,
  type OntologyVersionInfo
} from './api'
import OntologyGraph from './OntologyGraph.vue'
import DraftDialog from './components/ontology/DraftDialog.vue'
import { qualified, useOntologyDraft } from './composables/useOntologyDraft'
import { KINDS, OPERATORS, useOntologyEditor, type KindKey } from './composables/useOntologyEditor'
import type { CatalogRecord, LlmStatus, MetadataTable, OntologyBundle, OntologyEntity } from './types'
import { emptyOntology, errMessage } from './types'

type TreeAdd = { kind: KindKey; preset?: Record<string, unknown> } | null

type TreeRow = {
  type: 'section' | 'item'
  key: string
  depth: number
  label?: string
  count?: number
  warn?: boolean
  kind?: KindKey | string
  item?: OntologyEntity
  out?: boolean
  toggle?: boolean
  open?: boolean
  add?: TreeAdd
}

type SubjectTab = { name: string; label: string; count: number }
type RenameState = { from: string; to: string; error: string }

const tables = ref<MetadataTable[]>([])
const catalogs = ref<CatalogRecord[]>([])

const openLlmSettings = inject<() => void>('openLlmSettings', () => {})
const llmRevision = inject<Ref<number>>('llmRevision', ref(0))
/** 全部主题域的本体。页面上的树、画布和下拉选项都只用当前主题域的 snapshot。 */
const everything = ref<OntologyBundle>(emptyOntology())
/** 当前主题域；空串是"未分主题域" */
const subject = ref('')
const UNASSIGNED = '未分主题域'
const subjectOf = (concept: OntologyEntity) => (concept.subject || '').trim()

/** 只留一个主题域的概念，以及跟随它们的属性、关系、码值和派生概念。没有归属的实体算在未分主题域里。 */
function scopeTo(data: OntologyBundle, name: string): OntologyBundle {
  const all = new Set(data.concepts.map((c) => c.name))
  const mine = new Set(data.concepts.filter((c) => subjectOf(c) === name).map((c) => c.name))
  const stray = (owner: string) => name === '' && !all.has(owner)
  const attributes = data.attributes.filter((a) => mine.has(a.domain || '') || stray(a.domain || ''))
  const ownedLists = new Set(
    data.attributes.filter((a) => all.has(a.domain || '') && a.codeList).map((a) => a.codeList as string)
  )
  const lists = new Set(attributes.filter((a) => a.codeList).map((a) => a.codeList as string))
  return {
    concepts: data.concepts.filter((c) => mine.has(c.name)),
    attributes,
    relations: data.relations.filter(
      (r) =>
        (mine.has(r.domain || '') && mine.has(r.range || '')) ||
        (name === '' && !all.has(r.domain || '') && !all.has(r.range || ''))
    ),
    codes: data.codes.filter((c) => lists.has(c.codeList || '') || (name === '' && !ownedLists.has(c.codeList || ''))),
    definedConcepts: data.definedConcepts.filter((d) => mine.has(d.base || '') || stray(d.base || ''))
  }
}

const subjects = computed(() => {
  const counts = new Map<string, number>()
  for (const concept of everything.value.concepts) {
    counts.set(subjectOf(concept), (counts.get(subjectOf(concept)) || 0) + 1)
  }
  const named = [...counts.keys()].filter(Boolean).sort((a, b) => a.localeCompare(b, 'zh'))
  const list: SubjectTab[] = named.map((name) => ({ name, label: name, count: counts.get(name) || 0 }))
  const loose = scopeTo(everything.value, '')
  if (counts.has('') || loose.attributes.length || loose.relations.length || loose.codes.length || loose.definedConcepts.length) {
    list.push({ name: '', label: UNASSIGNED, count: counts.get('') || 0 })
  }
  return list
})
const subjectNames = computed(() => subjects.value.map((item) => item.name).filter(Boolean))
const snapshot = computed(() => scopeTo(everything.value, subject.value))

const query = ref('')
const llm = ref<LlmStatus | null>(null)
const fresh = ref<string[]>([])
const renaming = ref<RenameState | null>(null)
const turtle = ref('')
const versionsOpen = ref(false)
const versions = ref<OntologyVersionInfo[]>([])
const versionsBusy = ref(false)
const versionsNote = ref('')
const openNodes = ref(new Set<string>())

const columnsByTable = computed(() => {
  const map: Record<string, string[]> = {}
  for (const t of tables.value) {
    map[qualified(t).toLowerCase()] = (t.columns || []).map((c) => c.name)
  }
  return map
})
const conceptNames = computed(() => snapshot.value.concepts.map((c) => c.name))
const attributeNames = computed(() => snapshot.value.attributes.map((a) => a.name))

const {
  selected,
  form,
  isNew,
  message,
  error,
  saving,
  pendingDelete,
  fields,
  open,
  createKind,
  options,
  columnsFor,
  save,
  removeNamed,
  confirmDelete,
  remove,
  closeEditor
} = useOntologyEditor({
  everything,
  subject,
  snapshot,
  conceptNames,
  columnsByTable,
  fresh
})

async function load() {
  try {
    everything.value = await getOntology()
    error.value = ''
  } catch (e) {
    error.value = errMessage(e)
  }
}

const {
  showDraft,
  draftTables,
  draftQuery,
  draftNote,
  draftSubject,
  drafting,
  draftResult,
  draftError,
  draftStep,
  draftDetail,
  draftLog,
  draftElapsed,
  draftable,
  draftCandidates,
  openDraft,
  toggleDraftTable,
  draftReached,
  runDraft,
  finishDraft,
  takenElsewhere
} = useOntologyDraft({
  tables,
  catalogs,
  everything,
  subject,
  subjects,
  load,
  fresh
})

watch(
  subjects,
  (list) => {
    if (list.length && !list.some((item) => item.name === subject.value)) {
      subject.value = list[0]!.name
    }
  },
  { immediate: true }
)
watch(subject, () => {
  selected.value = null
})

function groupBy<T>(items: T[] | undefined, key: (item: T) => string): Map<string, T[]> {
  const map = new Map<string, T[]>()
  for (const item of items || []) {
    const k = key(item)
    if (!map.has(k)) {
      map.set(k, [])
    }
    map.get(k)!.push(item)
  }
  return map
}

function toggleNode(key: string) {
  const next = new Set(openNodes.value)
  if (next.has(key)) {
    next.delete(key)
  } else {
    next.add(key)
  }
  openNodes.value = next
}

/** 概念下挂属性、关系、派生概念和子概念，属性下挂码值；挂不上的放进"未归属"。 */
const tree = computed(() => {
  const s = snapshot.value
  const q = query.value.trim().toLowerCase()
  const hit = (item: OntologyEntity) =>
    !q ||
    item.name.toLowerCase().includes(q) ||
    (item.labelZh || '').toLowerCase().includes(q) ||
    (item.aliases || []).some((a) => a.toLowerCase().includes(q)) ||
    (item.value != null && String(item.value).toLowerCase() === q)
  const conceptSet = new Set(s.concepts.map((c) => c.name))
  const attrsOf = groupBy(s.attributes, (a) => a.domain || '')
  const codesOf = groupBy(s.codes, (c) => c.codeList || '')
  const outOf = groupBy(s.relations, (r) => r.domain || '')
  const inOf = groupBy(s.relations, (r) => r.range || '')
  const definedOf = groupBy(s.definedConcepts, (d) => d.base || '')
  const childrenOf = groupBy(
    s.concepts.filter((c) => c.parent && conceptSet.has(c.parent)),
    (c) => c.parent || ''
  )
  const codesFor = (attribute: OntologyEntity) => (attribute.codeList ? codesOf.get(attribute.codeList) || [] : [])
  const isOpen = (key: string) => !!q || openNodes.value.has(key)

  const memo = new Map<string, boolean>()
  const conceptHit = (concept: OntologyEntity): boolean => {
    if (!memo.has(concept.name)) {
      memo.set(concept.name, false)
      memo.set(
        concept.name,
        hit(concept) ||
          (attrsOf.get(concept.name) || []).some((a) => hit(a) || codesFor(a).some(hit)) ||
          (outOf.get(concept.name) || []).some(hit) ||
          (definedOf.get(concept.name) || []).some(hit) ||
          (childrenOf.get(concept.name) || []).some(conceptHit)
      )
    }
    return memo.get(concept.name)!
  }

  const rows: TreeRow[] = []
  const visit = (concept: OntologyEntity, depth: number) => {
    if (q && !conceptHit(concept)) {
      return
    }
    const key = `c:${concept.name}`
    const attrs = attrsOf.get(concept.name) || []
    const relations = [
      ...(outOf.get(concept.name) || []).map((r) => ({ r, out: true })),
      ...(inOf.get(concept.name) || []).filter((r) => r.domain !== concept.name).map((r) => ({ r, out: false }))
    ]
    const defined = definedOf.get(concept.name) || []
    const children = childrenOf.get(concept.name) || []
    rows.push({ type: 'item', kind: 'concept', key, depth, item: concept, toggle: true, open: isOpen(key) })
    if (!isOpen(key)) {
      return
    }
    const all = !q || hit(concept)
    const shownAttrs = all ? attrs : attrs.filter((a) => hit(a) || codesFor(a).some(hit))
    if (!q || shownAttrs.length) {
      rows.push({
        type: 'section',
        key: `${key}:attrs`,
        depth: depth + 1,
        label: '属性',
        count: attrs.length,
        add: { kind: 'attribute', preset: { domain: concept.name } }
      })
    }
    for (const attribute of shownAttrs) {
      const codes = codesFor(attribute)
      const akey = `a:${attribute.name}`
      const aOpen = codes.length > 0 && (openNodes.value.has(akey) || (!!q && codes.some(hit) && !hit(attribute)))
      rows.push({
        type: 'item',
        kind: 'attribute',
        key: akey,
        depth: depth + 2,
        item: attribute,
        toggle: codes.length > 0,
        open: aOpen,
        count: codes.length,
        add: attribute.codeList ? { kind: 'code', preset: { codeList: attribute.codeList } } : null
      })
      if (aOpen) {
        const shownCodes = all || hit(attribute) ? codes : codes.filter(hit)
        for (const code of shownCodes) {
          rows.push({ type: 'item', kind: 'code', key: `k:${code.name}`, depth: depth + 3, item: code })
        }
      }
    }
    const shownRelations = all ? relations : relations.filter(({ r }) => hit(r))
    if (!q || shownRelations.length) {
      rows.push({
        type: 'section',
        key: `${key}:rels`,
        depth: depth + 1,
        label: '关系',
        count: relations.length,
        add: { kind: 'relation', preset: { domain: concept.name } }
      })
    }
    for (const { r, out } of shownRelations) {
      rows.push({ type: 'item', kind: 'relation', key: `r:${concept.name}:${r.name}`, depth: depth + 2, item: r, out })
    }
    const shownDefined = all ? defined : defined.filter(hit)
    if (shownDefined.length) {
      rows.push({
        type: 'section',
        key: `${key}:defs`,
        depth: depth + 1,
        label: '派生概念',
        count: defined.length,
        add: { kind: 'defined', preset: { base: concept.name } }
      })
      for (const item of shownDefined) {
        rows.push({ type: 'item', kind: 'defined', key: `d:${item.name}`, depth: depth + 2, item })
      }
    }
    if (children.length) {
      rows.push({
        type: 'section',
        key: `${key}:kids`,
        depth: depth + 1,
        label: '子概念',
        count: children.length,
        add: { kind: 'concept', preset: { parent: concept.name } }
      })
      for (const child of children) {
        visit(child, depth + 2)
      }
    }
  }
  s.concepts.filter((c) => !c.parent || !conceptSet.has(c.parent)).forEach((c) => visit(c, 0))

  const ownedLists = new Set(
    s.attributes.filter((a) => conceptSet.has(a.domain || '') && a.codeList).map((a) => a.codeList as string)
  )
  const orphans: Array<{ kind: KindKey; item: OntologyEntity; out?: boolean }> = [
    ...s.attributes.filter((a) => !conceptSet.has(a.domain || '')).map((item) => ({ kind: 'attribute' as const, item })),
    ...s.relations
      .filter((r) => !conceptSet.has(r.domain || '') && !conceptSet.has(r.range || ''))
      .map((item) => ({ kind: 'relation' as const, item, out: true })),
    ...s.codes.filter((c) => !ownedLists.has(c.codeList || '')).map((item) => ({ kind: 'code' as const, item })),
    ...s.definedConcepts.filter((d) => !conceptSet.has(d.base || '')).map((item) => ({ kind: 'defined' as const, item }))
  ].filter(({ item }) => hit(item))
  if (orphans.length) {
    rows.push({
      type: 'section',
      key: 'orphans',
      depth: 0,
      label: '未归属（所属概念已不存在）',
      count: orphans.length,
      warn: true
    })
    for (const { kind, item, out } of orphans) {
      rows.push({ type: 'item', kind, key: `o:${kind}:${item.name}`, depth: 1, item, out })
    }
  }
  return rows
})

const KIND_TAG: Record<string, string> = {
  concept: '概念',
  attribute: '属性',
  code: '码值',
  relation: '关系',
  defined: '派生'
}

function rowSub(row: TreeRow) {
  const item = row.item
  if (!item) {
    return ''
  }
  switch (row.kind) {
    case 'concept':
      return item.table || '未映射表'
    case 'attribute':
      return [item.column || '未映射列', item.datatype, item.identifier ? '主键' : ''].filter(Boolean).join('  ')
    case 'code':
      return `取值 ${item.value}`
    case 'relation': {
      const other = row.out ? item.range : item.domain
      const label = snapshot.value.concepts.find((c) => c.name === other)?.labelZh || other || '?'
      const join = item.joinFrom && item.joinTo ? `  ${item.joinFrom} = ${item.joinTo}` : ''
      return `${row.out ? '→' : '←'} ${label}${join}`
    }
    default:
      return (
        (item.conditions || []).map((c) => `${c.attribute} ${c.operator} ${c.value}`).join(' 且 ') ||
        item.definitionText ||
        ''
      )
  }
}

function openRow(row: TreeRow) {
  const kind = KINDS.find((k) => k.key === row.kind)
  if (kind && row.item) {
    open(kind, row.item)
  }
}

/** 选中实体时把它在树里的上级都展开。 */
function revealInTree(kindKey: string, item: OntologyEntity) {
  const s = snapshot.value
  const next = new Set(openNodes.value)
  const addConcept = (name?: string) => {
    let current = s.concepts.find((c) => c.name === name)
    const seen = new Set<string>()
    while (current && !seen.has(current.name)) {
      seen.add(current.name)
      next.add(`c:${current.name}`)
      current = s.concepts.find((c) => c.name === current!.parent)
    }
  }
  if (kindKey === 'concept') {
    addConcept(item.parent)
  } else if (kindKey === 'attribute') {
    addConcept(item.domain)
  } else if (kindKey === 'relation') {
    addConcept(item.domain)
  } else if (kindKey === 'defined') {
    addConcept(item.base)
  } else if (kindKey === 'code') {
    const owner = s.attributes.find((a) => a.codeList && a.codeList === item.codeList)
    if (owner) {
      addConcept(owner.domain)
      next.add(`a:${owner.name}`)
    }
  }
  openNodes.value = next
}

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

function onPick(target: { kind: string; name: string }) {
  const kind = KINDS.find((item) => item.key === target.kind)
  const entity = kind && (snapshot.value[kind.list] as OntologyEntity[]).find((item) => item.name === target.name)
  if (kind && entity) {
    open(kind, entity)
  }
}

watch(selected, () => {
  if (selected.value && !isNew.value) {
    const kind = KINDS.find((item) => item.key === selected.value!.kind)
    const entity = kind && (snapshot.value[kind.list] as OntologyEntity[]).find((item) => item.name === form.name)
    if (kind && entity) {
      revealInTree(kind.key, entity)
    }
  }
})

function openRename() {
  renaming.value = { from: subject.value, to: subject.value, error: '' }
}

async function confirmRename() {
  if (!renaming.value) {
    return
  }
  const { from, to } = renaming.value
  const target = to.trim()
  if (!target || target === from) {
    renaming.value = null
    return
  }
  try {
    everything.value = await renameSubject(from, target)
    subject.value = target
    renaming.value = null
  } catch (e) {
    if (renaming.value) {
      renaming.value.error = errMessage(e)
    }
  }
}

async function showTurtle() {
  try {
    turtle.value = await getOntologyTurtle()
  } catch (e) {
    error.value = errMessage(e)
  }
}

async function openVersions() {
  versionsOpen.value = true
  versionsNote.value = ''
  await refreshVersions()
}

async function refreshVersions() {
  versionsBusy.value = true
  try {
    versions.value = await listOntologyVersions()
  } catch (e) {
    error.value = errMessage(e)
  } finally {
    versionsBusy.value = false
  }
}

async function snapshotVersion() {
  versionsBusy.value = true
  versionsNote.value = ''
  try {
    const created = await createOntologyVersion('manual')
    versionsNote.value = `已保存 ${created.id}`
    await refreshVersions()
  } catch (e) {
    versionsNote.value = errMessage(e)
  } finally {
    versionsBusy.value = false
  }
}

async function restoreVersion(id: string) {
  if (!window.confirm(`确认回滚到版本 ${id}？当前本体将被覆盖。`)) {
    return
  }
  versionsBusy.value = true
  versionsNote.value = ''
  try {
    everything.value = await restoreOntologyVersion(id)
    versionsNote.value = `已回滚到 ${id}`
    await refreshVersions()
  } catch (e) {
    versionsNote.value = errMessage(e)
  } finally {
    versionsBusy.value = false
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

function ensureConditions() {
  if (!form.conditions) {
    form.conditions = []
  }
  return form.conditions
}
</script>

<template>
  <section class="onto" aria-labelledby="onto-title">
    <header class="module-head">
      <h1 id="onto-title">本体建模</h1>
      <div class="head-side">
        <span class="head-note">
          <template v-if="subjects.length">{{ subject || UNASSIGNED }}：</template>
          概念 {{ snapshot.concepts.length }}，属性 {{ snapshot.attributes.length }}，码值 {{ snapshot.codes.length }}，关系 {{ snapshot.relations.length }}
        </span>
        <button type="button" @click="openVersions">版本</button>
        <button type="button" @click="showTurtle">导出 Turtle</button>
        <button v-if="llm && !llm.configured" type="button" @click="openLlmSettings">配置大模型</button>
        <button v-else class="primary" type="button" @click="openDraft">大模型起草</button>
      </div>
    </header>

    <nav v-if="subjects.length" class="subjects" aria-label="主题域">
      <span class="subjects-title">主题域</span>
      <button
        v-for="item in subjects"
        :key="item.name"
        type="button"
        class="subject-tab"
        :class="{ on: item.name === subject }"
        :aria-pressed="item.name === subject"
        @click="subject = item.name"
      >{{ item.label }} <em>{{ item.count }}</em></button>
      <button v-if="subject" class="link" type="button" @click="openRename">重命名</button>
      <span class="subjects-note">只有同一主题域里的概念能建立关系、共用码值和派生概念</span>
    </nav>

    <div class="onto-body" :class="{ editing: !!selected }">
    <aside class="list">
      <div class="list-top">
        <input v-model="query" type="search" placeholder="搜索名称、中文名、别名或码值" />
        <div class="list-actions">
          <button type="button" @click="createKind('concept')">＋ 概念</button>
          <button type="button" @click="createKind('defined')">＋ 派生概念</button>
        </div>
      </div>
      <p v-if="error && !selected" class="err pad">{{ error }}</p>
      <p v-if="!tree.length" class="hint pad">{{ query ? '没有匹配的实体' : subjects.length ? '这个主题域还是空的，先起草或新建概念' : '本体还是空的，先起草或新建概念' }}</p>
      <div class="tree">
        <div
          v-for="row in tree"
          :key="row.key"
          class="tree-row"
          :class="[row.type, row.kind, { active: row.type === 'item' && selected && !isNew && form.name === row.item?.name, warn: row.warn }]"
          :style="{ paddingLeft: `${6 + row.depth * 14}px` }"
        >
          <template v-if="row.type === 'section'">
            <span class="caret-gap" />
            <span class="section-label">{{ row.label }} <em>{{ row.count }}</em></span>
            <button
              v-if="row.add"
              class="icon sm"
              type="button"
              :title="`新建${KIND_TAG[row.add.kind]}`"
              @click="createKind(row.add.kind, row.add.preset)"
            >＋</button>
          </template>
          <template v-else>
            <button
              v-if="row.toggle"
              class="caret"
              type="button"
              :aria-expanded="row.open"
              :title="row.open ? '收起' : '展开'"
              @click="toggleNode(row.key)"
            >{{ row.open ? '▾' : '▸' }}</button>
            <span v-else class="caret-gap" />
            <button class="node-btn" type="button" @click="openRow(row)">
              <span class="node-line">
                <i class="tag" :class="row.kind">{{ row.kind ? KIND_TAG[row.kind] : '' }}</i>
                <span class="node-label">{{ row.kind === 'code' ? (row.item?.labelZh || row.item?.value) : (row.item?.labelZh || row.item?.name) }}</span>
                <em v-if="row.item?.certified" class="certified" title="已审定">审</em>
                <em v-if="row.count" class="count">{{ row.count }}</em>
              </span>
              <small>{{ rowSub(row) }}</small>
            </button>
            <button
              v-if="row.add"
              class="icon sm ghost"
              type="button"
              :title="`新建${KIND_TAG[row.add.kind]}`"
              @click="createKind(row.add.kind, row.add.preset)"
            >＋</button>
            <button v-if="row.item" class="icon sm danger ghost" type="button" title="删除" @click="removeNamed(row.item.name)">✕</button>
          </template>
        </div>
      </div>
    </aside>

    <div class="canvas">
      <OntologyGraph :snapshot="snapshot" :fresh="fresh" :active="selected && !isNew ? form.name : ''" @pick="onPick" />
      <div v-if="!snapshot.concepts.length" class="guide">
        <h2>从数据表起草本体</h2>
        <ol>
          <li>
            <b>定主题域，勾选表</b>
            <span>一次起草的表归到同一个主题域。用已经采集的 MySQL、ClickHouse、ES 表，Kafka 暂不映射。</span>
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
          <label v-else-if="field.type === 'subject'">
            {{ field.label }}
            <input v-model="form[field.key]" list="onto-subjects" placeholder="例如 安全日志；留空为未分主题域" />
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
            <div v-for="(cond, cidx) in ensureConditions()" :key="cidx" class="cond">
              <select v-model="cond.attribute">
                <option v-for="name in attributeNames" :key="name" :value="name">{{ name }}</option>
              </select>
              <select v-model="cond.operator">
                <option v-for="op in OPERATORS" :key="op" :value="op">{{ op }}</option>
              </select>
              <input v-model="cond.value" placeholder="值" />
              <button class="icon sm" type="button" title="删除条件" @click="ensureConditions().splice(cidx, 1)">✕</button>
            </div>
            <button class="link" type="button" @click="ensureConditions().push({ attribute: attributeNames[0] || '', operator: '>=', value: '' })">＋ 添加条件</button>
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
          <button type="button" @click="closeEditor">关闭</button>
          <button class="primary" type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存' }}</button>
        </div>
    </form>
    <datalist id="onto-subjects">
      <option v-for="name in subjectNames" :key="name" :value="name" />
    </datalist>
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

    <div v-if="renaming" class="mask" @click.self="renaming = null">
      <form class="dialog confirm" @submit.prevent="confirmRename">
        <h3>重命名主题域</h3>
        <label>
          新名字
          <input v-model="renaming.to" required />
        </label>
        <p class="hint">填成另一个已有主题域的名字，两个主题域会合并。</p>
        <p v-if="renaming.error" class="err">{{ renaming.error }}</p>
        <div class="actions">
          <button type="button" @click="renaming = null">取消</button>
          <button class="primary" type="submit">保存</button>
        </div>
      </form>
    </div>

    <DraftDialog
      :show="showDraft"
      :drafting="drafting"
      :draft-reached="draftReached"
      :draft-subject="draftSubject"
      :draft-query="draftQuery"
      :draft-note="draftNote"
      :draft-tables="draftTables"
      :draft-candidates="draftCandidates"
      :draft-log="draftLog"
      :draft-error="draftError"
      :draft-result="draftResult"
      :draft-step="draftStep"
      :draft-detail="draftDetail"
      :draft-elapsed="draftElapsed"
      :taken-elsewhere="takenElsewhere"
      :unassigned-label="UNASSIGNED"
      @close="showDraft = false"
      @update:draft-subject="draftSubject = $event"
      @update:draft-query="draftQuery = $event"
      @update:draft-note="draftNote = $event"
      @toggle-table="toggleDraftTable"
      @reselect="openDraft"
      @finish="finishDraft"
      @run="runDraft"
    />

    <div v-if="versionsOpen" class="mask" @click.self="versionsOpen = false">
      <div class="dialog preview" role="dialog" aria-labelledby="versions-title">
        <h3 id="versions-title">本体版本</h3>
        <p class="hint">起草前会自动打 checkpoint；也可手动快照或回滚。</p>
        <p v-if="versionsNote" class="head-note">{{ versionsNote }}</p>
        <div class="actions" style="margin-bottom: 10px">
          <button type="button" :disabled="versionsBusy" @click="snapshotVersion">保存快照</button>
          <button type="button" :disabled="versionsBusy" @click="refreshVersions">刷新</button>
          <button class="primary" type="button" @click="versionsOpen = false">关闭</button>
        </div>
        <ul class="version-list">
          <li v-if="!versions.length" class="hint">暂无版本</li>
          <li v-for="item in versions" :key="item.id" class="version-row">
            <div>
              <strong>{{ item.id }}</strong>
              <span class="hint">
                {{ item.note || '' }} · 概念 {{ item.concepts ?? 0 }} / 属性 {{ item.attributes ?? 0 }} / 派生 {{ item.definedConcepts ?? 0 }}
              </span>
            </div>
            <button type="button" :disabled="versionsBusy" @click="restoreVersion(item.id)">回滚</button>
          </li>
        </ul>
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

.version-list {
  list-style: none;
  margin: 0;
  padding: 0;
  max-height: 50vh;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.version-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 8px;
}

.version-row .hint {
  display: block;
  margin-top: 2px;
}

.onto-body {
  display: grid;
  grid-template-columns: 300px 1fr;
  flex: 1;
  min-height: 0;
}

.onto-body.editing {
  grid-template-columns: 280px 1fr 360px;
}

.subjects {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  padding: 8px 12px;
  border-bottom: 1px solid var(--line);
}

.subjects-title {
  margin-right: 4px;
  color: var(--muted);
  font-size: 12px;
}

.subject-tab {
  padding: 3px 10px;
  border-radius: 999px;
  font-size: 13px;
}

.subject-tab.on {
  border-color: var(--accent);
  background: var(--panel-2);
  font-weight: 650;
}

.subject-tab em {
  font-style: normal;
  font-weight: 400;
  color: var(--muted);
  font-size: 11px;
}

.subjects-note {
  margin-left: auto;
  color: var(--muted);
  font-size: 12px;
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
  top: 52px;
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

.section-label em,
.certified {
  flex: none;
  margin-left: 4px;
  padding: 0 4px;
  border-radius: 3px;
  font-size: 10px;
  font-style: normal;
  color: #1f6f4a;
  background: #e6f4ec;
}
.count {
  font-style: normal;
  font-weight: 400;
  color: var(--muted);
  font-size: 11px;
}

.list-actions {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}

.list-actions button {
  flex: 1;
  padding: 4px 6px;
  font-size: 12px;
}

.tree {
  display: flex;
  flex-direction: column;
}

.tree-row {
  display: flex;
  align-items: center;
  min-height: 26px;
  padding-right: 6px;
}

.tree-row:hover {
  background: var(--panel-2);
}

.tree-row.active {
  background: var(--panel-2);
  box-shadow: inset 2px 0 0 var(--accent);
}

.tree-row.section {
  min-height: 24px;
  margin-top: 2px;
}

.tree-row.section:hover {
  background: transparent;
}

.section-label {
  flex: 1;
  font-size: 11px;
  font-weight: 600;
  color: var(--muted);
}

.tree-row.warn .section-label {
  color: var(--warn);
}

.caret,
.caret-gap {
  flex: none;
  width: 18px;
  height: 18px;
}

.caret {
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--muted);
  font-size: 11px;
  line-height: 18px;
}

.caret:hover {
  color: var(--text);
}

.node-btn {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  flex: 1;
  min-width: 0;
  padding: 4px 4px;
  border: 0;
  border-radius: 0;
  background: transparent;
  text-align: left;
  font-size: 13px;
}

.node-line {
  display: flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
}

.node-label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tree-row.concept .node-label {
  font-weight: 650;
}

.node-btn small {
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--muted);
  font-size: 11px;
}

.tag {
  flex: none;
  padding: 0 4px;
  border-radius: 4px;
  border: 1px solid var(--line);
  color: var(--muted);
  font-size: 10px;
  font-style: normal;
  line-height: 15px;
}

.tag.concept {
  border-color: var(--accent-2);
  color: var(--accent-2);
}

.tag.relation {
  border-color: var(--accent-2);
}

.tag.defined {
  border-color: var(--warn);
  color: var(--warn);
}

.tree-row .ghost {
  flex: none;
  visibility: hidden;
}

.tree-row:hover .ghost,
.tree-row.active .ghost {
  visibility: visible;
}

.entity {
  min-height: 0;
  overflow: auto;
  padding: 16px;
  border-left: 1px solid var(--line);
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

.warnings {
  margin: 0;
  padding-left: 18px;
  max-height: 20vh;
  overflow: auto;
  color: var(--warn);
  font-size: 12px;
}
</style>
