<script setup>
import { computed, inject, nextTick, onActivated, reactive, ref, watch } from 'vue'
import { deleteEntity, draftOntology, getOntology, getOntologyTurtle, listCatalogs, listMetadata, llmStatus, renameSubject, saveEntity } from './api'
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
    { key: 'subject', label: '主题域（只有同主题域的概念能关联）', type: 'subject' },
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
    { key: 'codeList', label: '码表（填任意名字即标为码值列，系统按表名_列名命名）', type: 'text' },
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
    { key: 'codeList', label: '码表（属性上已有的码表）', type: 'select', options: 'codeLists' },
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
/** 全部主题域的本体。页面上的树、画布和下拉选项都只用当前主题域的 snapshot。 */
const everything = ref({ concepts: [], attributes: [], relations: [], codes: [], definedConcepts: [] })
/** 当前主题域；空串是"未分主题域" */
const subject = ref('')
const UNASSIGNED = '未分主题域'
const subjectOf = (concept) => (concept.subject || '').trim()

/** 只留一个主题域的概念，以及跟随它们的属性、关系、码值和派生概念。没有归属的实体算在未分主题域里。 */
function scopeTo(data, name) {
  const all = new Set(data.concepts.map((c) => c.name))
  const mine = new Set(data.concepts.filter((c) => subjectOf(c) === name).map((c) => c.name))
  const stray = (owner) => name === '' && !all.has(owner)
  const attributes = data.attributes.filter((a) => mine.has(a.domain) || stray(a.domain))
  const ownedLists = new Set(data.attributes.filter((a) => all.has(a.domain) && a.codeList).map((a) => a.codeList))
  const lists = new Set(attributes.filter((a) => a.codeList).map((a) => a.codeList))
  return {
    concepts: data.concepts.filter((c) => mine.has(c.name)),
    attributes,
    relations: data.relations.filter((r) => (mine.has(r.domain) && mine.has(r.range))
      || (name === '' && !all.has(r.domain) && !all.has(r.range))),
    codes: data.codes.filter((c) => lists.has(c.codeList) || (name === '' && !ownedLists.has(c.codeList))),
    definedConcepts: data.definedConcepts.filter((d) => mine.has(d.base) || stray(d.base))
  }
}

const subjects = computed(() => {
  const counts = new Map()
  for (const concept of everything.value.concepts) {
    counts.set(subjectOf(concept), (counts.get(subjectOf(concept)) || 0) + 1)
  }
  const named = [...counts.keys()].filter(Boolean).sort((a, b) => a.localeCompare(b, 'zh'))
  const list = named.map((name) => ({ name, label: name, count: counts.get(name) }))
  const loose = scopeTo(everything.value, '')
  if (counts.has('') || loose.attributes.length || loose.relations.length || loose.codes.length || loose.definedConcepts.length) {
    list.push({ name: '', label: UNASSIGNED, count: counts.get('') || 0 })
  }
  return list
})
const subjectNames = computed(() => subjects.value.map((item) => item.name).filter(Boolean))
const snapshot = computed(() => scopeTo(everything.value, subject.value))

watch(subjects, (list) => {
  if (list.length && !list.some((item) => item.name === subject.value)) {
    subject.value = list[0].name
  }
}, { immediate: true })
watch(subject, () => {
  selected.value = null
})
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
const draftSubject = ref('')
const renaming = ref(null)
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
/** 表已经属于哪个主题域的哪个概念：一张表只能属于一个主题域 */
const tableOwner = computed(() => {
  const map = {}
  for (const concept of everything.value.concepts) {
    if (concept.table) {
      map[concept.table.toLowerCase()] = { subject: subjectOf(concept), concept: concept.name }
    }
  }
  return map
})
function takenElsewhere(name) {
  const owner = tableOwner.value[name.toLowerCase()]
  return owner && owner.subject !== draftSubject.value.trim() ? owner : null
}
const columnsByTable = computed(() => {
  const map = {}
  for (const t of tables.value) {
    map[qualified(t).toLowerCase()] = (t.columns || []).map((c) => c.name)
  }
  return map
})
const conceptNames = computed(() => snapshot.value.concepts.map((c) => c.name))
const attributeNames = computed(() => snapshot.value.attributes.map((a) => a.name))

const openNodes = ref(new Set())

function groupBy(items, key) {
  const map = new Map()
  for (const item of items || []) {
    const k = key(item)
    if (!map.has(k)) {
      map.set(k, [])
    }
    map.get(k).push(item)
  }
  return map
}

function toggleNode(key) {
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
  const hit = (item) => !q
    || item.name.toLowerCase().includes(q)
    || (item.labelZh || '').toLowerCase().includes(q)
    || (item.aliases || []).some((a) => a.toLowerCase().includes(q))
    || (item.value != null && String(item.value).toLowerCase() === q)
  const conceptSet = new Set(s.concepts.map((c) => c.name))
  const attrsOf = groupBy(s.attributes, (a) => a.domain)
  const codesOf = groupBy(s.codes, (c) => c.codeList)
  const outOf = groupBy(s.relations, (r) => r.domain)
  const inOf = groupBy(s.relations, (r) => r.range)
  const definedOf = groupBy(s.definedConcepts, (d) => d.base)
  const childrenOf = groupBy(s.concepts.filter((c) => c.parent && conceptSet.has(c.parent)), (c) => c.parent)
  const codesFor = (attribute) => (attribute.codeList ? codesOf.get(attribute.codeList) || [] : [])
  const isOpen = (key) => !!q || openNodes.value.has(key)

  const memo = new Map()
  const conceptHit = (concept) => {
    if (!memo.has(concept.name)) {
      memo.set(concept.name, false)
      memo.set(concept.name, hit(concept)
        || (attrsOf.get(concept.name) || []).some((a) => hit(a) || codesFor(a).some(hit))
        || (outOf.get(concept.name) || []).some(hit)
        || (definedOf.get(concept.name) || []).some(hit)
        || (childrenOf.get(concept.name) || []).some(conceptHit))
    }
    return memo.get(concept.name)
  }

  const rows = []
  const visit = (concept, depth) => {
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
      rows.push({ type: 'section', key: `${key}:attrs`, depth: depth + 1, label: '属性', count: attrs.length,
        add: { kind: 'attribute', preset: { domain: concept.name } } })
    }
    for (const attribute of shownAttrs) {
      const codes = codesFor(attribute)
      const akey = `a:${attribute.name}`
      const aOpen = codes.length > 0 && (openNodes.value.has(akey) || (!!q && codes.some(hit) && !hit(attribute)))
      rows.push({ type: 'item', kind: 'attribute', key: akey, depth: depth + 2, item: attribute,
        toggle: codes.length > 0, open: aOpen, count: codes.length,
        add: attribute.codeList ? { kind: 'code', preset: { codeList: attribute.codeList } } : null })
      if (aOpen) {
        const shownCodes = all || hit(attribute) ? codes : codes.filter(hit)
        for (const code of shownCodes) {
          rows.push({ type: 'item', kind: 'code', key: `k:${code.name}`, depth: depth + 3, item: code })
        }
      }
    }
    const shownRelations = all ? relations : relations.filter(({ r }) => hit(r))
    if (!q || shownRelations.length) {
      rows.push({ type: 'section', key: `${key}:rels`, depth: depth + 1, label: '关系', count: relations.length,
        add: { kind: 'relation', preset: { domain: concept.name } } })
    }
    for (const { r, out } of shownRelations) {
      rows.push({ type: 'item', kind: 'relation', key: `r:${concept.name}:${r.name}`, depth: depth + 2, item: r, out })
    }
    const shownDefined = all ? defined : defined.filter(hit)
    if (shownDefined.length) {
      rows.push({ type: 'section', key: `${key}:defs`, depth: depth + 1, label: '派生概念', count: defined.length,
        add: { kind: 'defined', preset: { base: concept.name } } })
      for (const item of shownDefined) {
        rows.push({ type: 'item', kind: 'defined', key: `d:${item.name}`, depth: depth + 2, item })
      }
    }
    if (children.length) {
      rows.push({ type: 'section', key: `${key}:kids`, depth: depth + 1, label: '子概念', count: children.length,
        add: { kind: 'concept', preset: { parent: concept.name } } })
      for (const child of children) {
        visit(child, depth + 2)
      }
    }
  }
  s.concepts.filter((c) => !c.parent || !conceptSet.has(c.parent)).forEach((c) => visit(c, 0))

  const ownedLists = new Set(s.attributes.filter((a) => conceptSet.has(a.domain) && a.codeList).map((a) => a.codeList))
  const orphans = [
    ...s.attributes.filter((a) => !conceptSet.has(a.domain)).map((item) => ({ kind: 'attribute', item })),
    ...s.relations.filter((r) => !conceptSet.has(r.domain) && !conceptSet.has(r.range)).map((item) => ({ kind: 'relation', item, out: true })),
    ...s.codes.filter((c) => !ownedLists.has(c.codeList)).map((item) => ({ kind: 'code', item })),
    ...s.definedConcepts.filter((d) => !conceptSet.has(d.base)).map((item) => ({ kind: 'defined', item }))
  ].filter(({ item }) => hit(item))
  if (orphans.length) {
    rows.push({ type: 'section', key: 'orphans', depth: 0, label: '未归属（所属概念已不存在）', count: orphans.length, warn: true })
    for (const { kind, item, out } of orphans) {
      rows.push({ type: 'item', kind, key: `o:${kind}:${item.name}`, depth: 1, item, out })
    }
  }
  return rows
})

const KIND_TAG = { concept: '概念', attribute: '属性', code: '码值', relation: '关系', defined: '派生' }

function rowSub(row) {
  const item = row.item
  switch (row.kind) {
    case 'concept': return item.table || '未映射表'
    case 'attribute': return [item.column || '未映射列', item.datatype, item.identifier ? '主键' : ''].filter(Boolean).join('  ')
    case 'code': return `取值 ${item.value}`
    case 'relation': {
      const other = row.out ? item.range : item.domain
      const label = snapshot.value.concepts.find((c) => c.name === other)?.labelZh || other || '?'
      const join = item.joinFrom && item.joinTo ? `  ${item.joinFrom} = ${item.joinTo}` : ''
      return `${row.out ? '→' : '←'} ${label}${join}`
    }
    default: return (item.conditions || []).map((c) => `${c.attribute} ${c.operator} ${c.value}`).join(' 且 ') || item.definitionText || ''
  }
}

function openRow(row) {
  open(KINDS.find((kind) => kind.key === row.kind), row.item)
}

function kindOf(name) {
  return KINDS.find((kind) => snapshot.value[kind.list].some((item) => item.name === name))
}

/** 选中实体时把它在树里的上级都展开。 */
function revealInTree(kindKey, item) {
  const s = snapshot.value
  const next = new Set(openNodes.value)
  const addConcept = (name) => {
    let current = s.concepts.find((c) => c.name === name)
    const seen = new Set()
    while (current && !seen.has(current.name)) {
      seen.add(current.name)
      next.add(`c:${current.name}`)
      current = s.concepts.find((c) => c.name === current.parent)
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
    everything.value = await getOntology()
    error.value = ''
  } catch (e) {
    error.value = e.message
  }
}

function blank(kind) {
  const base = { name: '', labelZh: '', labelEn: '', aliases: [], comment: '' }
  const extra = {
    concept: { subject: '', parent: '', table: '', timeColumn: '', largeTable: false },
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

function create(kind, preset = {}) {
  selected.value = { kind: kind.key, label: kind.label }
  isNew.value = true
  fill({ ...blank(kind.key), ...(kind.key === 'concept' ? { subject: subject.value } : {}), ...preset })
}

function createKind(key, preset) {
  create(KINDS.find((kind) => kind.key === key), preset)
}

function options(field) {
  if (field.options === 'datatypes') {
    return DATATYPES
  }
  if (field.options === 'codeLists') {
    const lists = (snapshot.value?.attributes || []).map((attribute) => attribute.codeList).filter(Boolean)
    return [...new Set(lists)].sort()
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
    const kind = selected.value
    const name = form.name
    everything.value = await saveEntity(kind.kind, name, body)
    if (kind.kind === 'concept' && (body.subject || '') !== subject.value) {
      subject.value = body.subject || ''
      await nextTick()
      onPick({ kind: 'concept', name })
    }
    isNew.value = false
    message.value = '已保存'
  } catch (e) {
    error.value = e.message
  } finally {
    saving.value = false
  }
}

function removeNamed(name) {
  const kind = kindOf(name)
  const notes = {
    concept: '它的属性、码值、关系和派生概念会一起删除。',
    attribute: '它独有的码值会一起删除。'
  }
  pendingDelete.value = { name, note: notes[kind?.key] || '' }
}

async function confirmDelete() {
  const name = pendingDelete.value?.name
  if (!name) {
    return
  }
  pendingDelete.value = null
  try {
    everything.value = await deleteEntity(name)
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
  if (!draftSubject.value.trim()) {
    draftSubject.value = subject.value
  }
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
  const before = entityNames(everything.value)
  const target = draftSubject.value.trim()
  let failed = ''
  try {
    await draftOntology({ tables: draftTables.value, instructions: draftNote.value || null, subject: target }, (event, data) => {
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
    subject.value = target
    fresh.value = [...entityNames(everything.value)].filter((name) => !before.has(name))
  } catch (e) {
    await load()
    if (subjects.value.some((item) => item.name === target)) {
      subject.value = target
    }
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

watch(selected, () => {
  if (selected.value && !isNew.value) {
    const kind = KINDS.find((item) => item.key === selected.value.kind)
    const entity = kind && snapshot.value[kind.list].find((item) => item.name === form.name)
    if (entity) {
      revealInTree(kind.key, entity)
    }
  }
})

function finishDraft() {
  showDraft.value = false
}

function openRename() {
  renaming.value = { from: subject.value, to: subject.value, error: '' }
}

async function confirmRename() {
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
    renaming.value.error = e.message
  }
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
</script>

<template>
  <section class="onto">
    <header class="module-head">
      <h1>本体建模</h1>
      <div class="head-side">
        <span class="head-note">
          <template v-if="subjects.length">{{ subject || UNASSIGNED }}：</template>
          概念 {{ snapshot.concepts.length }}，属性 {{ snapshot.attributes.length }}，码值 {{ snapshot.codes.length }}，关系 {{ snapshot.relations.length }}
        </span>
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
          :class="[row.type, row.kind, { active: row.type === 'item' && selected && !isNew && form.name === row.item.name, warn: row.warn }]"
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
                <i class="tag" :class="row.kind">{{ KIND_TAG[row.kind] }}</i>
                <span class="node-label">{{ row.kind === 'code' ? (row.item.labelZh || row.item.value) : (row.item.labelZh || row.item.name) }}</span>
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
            <button class="icon sm danger ghost" type="button" title="删除" @click="removeNamed(row.item.name)">✕</button>
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

    <div v-if="showDraft" class="mask" @click.self="!drafting && (showDraft = false)">
      <div class="dialog draft">
        <h3>大模型起草</h3>
        <ol class="steps">
          <li v-for="step in DRAFT_STEPS" :key="step.key" :class="{ on: draftReached(step.key) }">{{ step.label }}</li>
        </ol>
        <template v-if="!drafting && !draftLog.length">
          <label>
            主题域（必填）
            <input v-model="draftSubject" list="onto-subjects" placeholder="选一个已有的主题域，或输入新名字，例如 安全日志" />
          </label>
          <p class="hint">本次勾选的表都归到这个主题域，起草时只会和这个主题域里已有的概念建立关系。</p>
          <input v-model="draftQuery" type="search" placeholder="过滤表名" />
          <div class="picker">
            <label v-for="t in draftCandidates" :key="qualified(t)" class="check" :class="{ off: takenElsewhere(qualified(t)) }">
              <input
                type="checkbox"
                :checked="draftTables.includes(qualified(t))"
                :disabled="!!takenElsewhere(qualified(t)) && !draftTables.includes(qualified(t))"
                @change="toggleDraftTable(qualified(t))"
              />
              {{ qualified(t) }} <em>{{ (t.columns || []).length }} 列</em>
              <em v-if="takenElsewhere(qualified(t))" class="taken">已在{{ takenElsewhere(qualified(t)).subject ? `主题域 ${takenElsewhere(qualified(t)).subject}` : UNASSIGNED }}</em>
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
        <p v-else class="hint">主题域 {{ draftSubject.trim() }}，已选 {{ draftTables.length }} 张：{{ draftTables.join('，') }}</p>
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
          <button class="primary" type="button" :disabled="drafting || !draftTables.length || !draftSubject.trim()" @click="runDraft">
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

.picker .check.off {
  color: var(--muted);
}

.picker .taken {
  color: var(--warn);
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

.picker em,
.section-label em,
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
  overflow: auto;
}

.draft > * {
  flex-shrink: 0;
}

.draft .hint {
  margin: 0;
}

.picker {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-height: 120px;
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
