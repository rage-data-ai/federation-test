import { computed, nextTick, reactive, ref } from 'vue'
import { deleteEntity, saveEntity } from '../api'

export const KINDS = [
  { key: 'concept', list: 'concepts', label: '概念' },
  { key: 'attribute', list: 'attributes', label: '属性' },
  { key: 'relation', list: 'relations', label: '关系' },
  { key: 'code', list: 'codes', label: '码值' },
  { key: 'defined', list: 'definedConcepts', label: '派生概念' }
]

export const DATATYPES = ['string', 'integer', 'long', 'decimal', 'double', 'boolean', 'date', 'dateTime']
export const OPERATORS = ['=', '>', '>=', '<', '<=']

export const COMMON = [
  { key: 'name', label: '名称（英文标识，创建后不可改）', type: 'text' },
  { key: 'labelZh', label: '中文名', type: 'text' },
  { key: 'labelEn', label: '英文名', type: 'text' },
  { key: 'aliases', label: '中文别名（逗号分隔）', type: 'list' }
]

export const FIELDS = {
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

function emptyToNull(value) {
  return typeof value === 'string' && !value.trim() ? null : value
}

/**
 * Entity create/edit form state and save/delete actions.
 * @param {{
 *   everything: import('vue').Ref,
 *   subject: import('vue').Ref<string>,
 *   snapshot: import('vue').ComputedRef,
 *   conceptNames: import('vue').ComputedRef<string[]>,
 *   columnsByTable: import('vue').ComputedRef<Record<string, string[]>>,
 *   fresh: import('vue').Ref<string[]>
 * }} opts
 */
export function useOntologyEditor({
  everything,
  subject,
  snapshot,
  conceptNames,
  columnsByTable,
  fresh
}) {
  const selected = ref(null)
  const form = reactive({})
  const isNew = ref(false)
  const message = ref('')
  const error = ref('')
  const saving = ref(false)
  const pendingDelete = ref(null)

  const fields = computed(() => (selected.value ? [...COMMON, ...FIELDS[selected.value.kind]] : []))

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

  function kindOf(name) {
    return KINDS.find((kind) => snapshot.value[kind.list].some((item) => item.name === name))
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
        const entity = snapshot.value.concepts.find((item) => item.name === name)
        const conceptKind = KINDS.find((item) => item.key === 'concept')
        if (entity && conceptKind) {
          open(conceptKind, entity)
        }
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

  function closeEditor() {
    selected.value = null
  }

  return {
    selected,
    form,
    isNew,
    message,
    error,
    saving,
    pendingDelete,
    fields,
    open,
    create,
    createKind,
    kindOf,
    options,
    columnsFor,
    save,
    removeNamed,
    confirmDelete,
    remove,
    closeEditor
  }
}
