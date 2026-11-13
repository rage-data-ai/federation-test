import { computed, ref, type ComputedRef, type Ref } from 'vue'
import { draftOntology } from '../api'

export const DRAFT_STEPS = [
  { key: 'sample', label: '读取列和样例' },
  { key: 'concepts', label: '起草概念' },
  { key: 'attributes', label: '起草属性' },
  { key: 'codes', label: '起草码值' },
  { key: 'relations', label: '起草关系' },
  { key: 'defined', label: '起草派生概念' }
] as const

export type OntologyTableRef = {
  catalog: string
  schema: string
  table: string
  [key: string]: unknown
}

export type OntologyCatalogRef = {
  name: string
  type?: string
  [key: string]: unknown
}

export type OntologyEntity = {
  name: string
  subject?: string
  table?: string
  [key: string]: unknown
}

export type OntologyBundle = {
  concepts: OntologyEntity[]
  attributes: OntologyEntity[]
  relations: OntologyEntity[]
  codes: OntologyEntity[]
  definedConcepts: OntologyEntity[]
  [key: string]: unknown
}

export type DraftLogEntry = {
  status: string
  text: string
  notes: unknown[]
}

export type OntologyDraftOptions = {
  tables: Ref<OntologyTableRef[]>
  catalogs: Ref<OntologyCatalogRef[]>
  everything: Ref<OntologyBundle>
  subject: Ref<string>
  subjects: ComputedRef<Array<{ name: string; [key: string]: unknown }>>
  load: () => Promise<void>
  fresh: Ref<string[]>
}

export function qualified(table: Pick<OntologyTableRef, 'catalog' | 'schema' | 'table'>): string {
  return `${table.catalog}.${table.schema}.${table.table}`
}

function entityNames(data: OntologyBundle): Set<string> {
  return new Set([
    ...(data.concepts || []).map((item) => item.name),
    ...(data.attributes || []).map((item) => item.name),
    ...(data.relations || []).map((item) => item.name),
    ...(data.codes || []).map((item) => item.name),
    ...(data.definedConcepts || []).map((item) => item.name)
  ])
}

function subjectOf(concept: OntologyEntity): string {
  return (concept.subject || '').trim()
}

/**
 * LLM ontology draft modal state and run/progress helpers.
 */
export function useOntologyDraft({
  tables,
  catalogs,
  everything,
  subject,
  subjects,
  load,
  fresh
}: OntologyDraftOptions) {
  const showDraft = ref(false)
  const draftTables = ref<string[]>([])
  const draftQuery = ref('')
  const draftNote = ref('')
  const draftSubject = ref('')
  const drafting = ref(false)
  const draftResult = ref<unknown>(null)
  const draftError = ref('')
  const draftStep = ref('')
  const draftDetail = ref('')
  const draftLog = ref<DraftLogEntry[]>([])
  const draftStartedAt = ref(0)
  const draftClock = ref(0)
  let draftClockTimer = 0

  const draftElapsed = computed(() =>
    draftStartedAt.value ? Math.max(0, Math.round((draftClock.value - draftStartedAt.value) / 1000)) : 0
  )

  const kafka = computed(
    () => new Set(catalogs.value.filter((c) => c.type === 'kafka').map((c) => c.name))
  )
  const draftable = computed(() => tables.value.filter((t) => !kafka.value.has(t.catalog)))
  const draftCandidates = computed(() => {
    const q = draftQuery.value.trim().toLowerCase()
    const list = q ? draftable.value.filter((t) => qualified(t).toLowerCase().includes(q)) : draftable.value
    return list.slice(0, 200)
  })

  /** 表已经属于哪个主题域的哪个概念：一张表只能属于一个主题域 */
  const tableOwner = computed(() => {
    const map: Record<string, { subject: string; concept: string }> = {}
    for (const concept of everything.value.concepts) {
      if (concept.table) {
        map[concept.table.toLowerCase()] = { subject: subjectOf(concept), concept: concept.name }
      }
    }
    return map
  })

  function takenElsewhere(name: string) {
    const owner = tableOwner.value[name.toLowerCase()]
    return owner && owner.subject !== draftSubject.value.trim() ? owner : null
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

  function toggleDraftTable(name: string) {
    const index = draftTables.value.indexOf(name)
    if (index >= 0) {
      draftTables.value.splice(index, 1)
    } else {
      draftTables.value.push(name)
    }
  }

  function draftReached(key: string) {
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
      await draftOntology(
        { tables: draftTables.value, instructions: draftNote.value || null, subject: target },
        (event, data) => {
          const payload = data && typeof data === 'object' ? (data as Record<string, unknown>) : {}
          if (event === 'phase') {
            draftStep.value = String(payload.phase ?? '')
            draftDetail.value = String(payload.message ?? '')
            draftLog.value.push({
              status: String(payload.status || 'start'),
              text: String(payload.message ?? ''),
              notes: Array.isArray(payload.notes) ? payload.notes : []
            })
          } else if (event === 'result') {
            draftResult.value = data
            draftStep.value = 'done'
            draftDetail.value = ''
          } else if (event === 'error') {
            failed = String(payload.message || '起草失败')
          }
        }
      )
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
      draftError.value = e instanceof Error ? e.message : String(e)
      if (draftStep.value !== 'done') {
        draftStep.value = ''
      }
    } finally {
      window.clearInterval(draftClockTimer)
      drafting.value = false
    }
  }

  function finishDraft() {
    showDraft.value = false
  }

  return {
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
    takenElsewhere,
    qualified
  }
}
