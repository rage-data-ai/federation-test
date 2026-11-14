import { nextTick, onUnmounted, ref, type Ref } from 'vue'
import { askStream } from '../api'
import type {
  AskKnowledgeHits,
  AskKnowledgeMatch,
  AskMetadataReport,
  AskOntologyContext,
  AskRating,
  AskSqlItem
} from '../types'

export const SAMPLES = [
  '哪些账户在大量不同主机上登录失败？各失败了多少次、涉及多少台主机？',
  '用网络登录（登录类型 3）成功登上很多台主机的账户有哪些？各覆盖多少台主机？',
  '哪些主机在事件日志服务停止（事件 1100）之后，又出现了进程创建（事件 4688）？这样的主机有多少台？'
]

export const STAGE_LABEL: Record<string, string> = {
  ontology: '本体',
  metadata: '元数据',
  insight: '分析',
  knowledge: '知识'
}
export const SOURCE_LABEL: Record<string, string> = { ontology: '本体定义', inferred: '推断', user: '用户指定' }
export const SQL_STATUS: Record<string, string> = { executed: '已执行', rejected: '校验拒绝', failed: '执行失败' }

export type AskStage = {
  message?: string
  [key: string]: unknown
}

export type AskTurn = {
  question: string
  /** 网关给这次问数的记录号；流一开始就下发，评价和建议靠它指回这次问数。 */
  runId: string
  conversationId: string
  rating: AskRating | null
  stages: AskStage[]
  knowledge: AskKnowledgeHits | null
  ontology: AskOntologyContext | null
  answerability: Record<string, unknown> | null
  metadata: AskMetadataReport | null
  sqls: AskSqlItem[]
  results: unknown[]
  filterChecks: Record<string, unknown>[]
  answer: Record<string, unknown> | null
  clarify: Record<string, unknown> | null
  error: string
  done: boolean
  showTrace: boolean
}

export type ChatHistoryItem = { role: 'user' | 'assistant'; content: string }

export function cell(value: unknown): string {
  if (value === null || value === undefined) {
    return 'NULL'
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

export function lastStage(turn: Pick<AskTurn, 'stages'>): string {
  const stage = turn.stages[turn.stages.length - 1]
  return stage ? String(stage.message ?? '提交中…') : '提交中…'
}

export function emptyTurn(question: string): AskTurn {
  return {
    question,
    runId: '',
    conversationId: '',
    rating: null,
    stages: [],
    knowledge: null,
    ontology: null,
    answerability: null,
    metadata: null,
    sqls: [],
    results: [],
    filterChecks: [],
    answer: null,
    clarify: null,
    error: '',
    done: false,
    showTrace: true
  }
}

function buildHistory(turns: AskTurn[]): ChatHistoryItem[] {
  const items: ChatHistoryItem[] = []
  for (const turn of turns) {
    items.push({ role: 'user', content: turn.question })
    const reply =
      (typeof turn.answer?.answer === 'string' ? turn.answer.answer : undefined) ||
      (typeof turn.clarify?.question === 'string' ? turn.clarify.question : undefined)
    if (reply) {
      items.push({ role: 'assistant', content: reply })
    }
  }
  return items
}

function asRecord(data: unknown): Record<string, unknown> {
  return data && typeof data === 'object' ? (data as Record<string, unknown>) : {}
}

export function applyEvent(turn: AskTurn, name: string, data: unknown): void {
  const payload = asRecord(data)
  switch (name) {
    case 'run':
      turn.runId = typeof payload.runId === 'string' ? payload.runId : ''
      turn.conversationId = typeof payload.conversationId === 'string' ? payload.conversationId : ''
      break
    case 'stage':
      turn.stages.push(payload as AskStage)
      break
    case 'knowledge': {
      const raw = payload.matches
      const matches = Array.isArray(raw) ? (raw as AskKnowledgeMatch[]) : []
      turn.knowledge = { matches }
      break
    }
    case 'ontology':
      turn.ontology = {
        ...asRecord(payload.context),
        mode: payload.mode as string | undefined,
        recovery: payload.recovery as boolean | undefined
      } as AskOntologyContext
      break
    case 'answerability':
      turn.answerability = payload
      break
    case 'metadata':
      turn.metadata = {
        ...asRecord(payload.report),
        recovery: payload.recovery as boolean | undefined
      } as AskMetadataReport
      break
    case 'sql':
      turn.sqls.push(asRecord(data) as AskSqlItem)
      break
    case 'result':
      turn.results.push(data)
      break
    case 'filter_check':
      turn.filterChecks = turn.filterChecks ?? []
      turn.filterChecks.push(payload)
      break
    case 'answer':
      turn.answer = payload
      break
    case 'clarify':
      turn.clarify = payload
      break
    case 'error':
      turn.error = typeof payload.message === 'string' ? payload.message : String(payload.message ?? '')
      break
    default:
      break
  }
}

/**
 * SSE ask flow: turns, draft, busy, send/stop, and scroll.
 */
export function useAskStream() {
  const turns = ref<AskTurn[]>([])
  const draft = ref('')
  const busy = ref(false)
  const scroller = ref<HTMLElement | null>(null)
  let controller: AbortController | null = null

  function scrollDown() {
    nextTick(() => {
      if (scroller.value) {
        scroller.value.scrollTop = scroller.value.scrollHeight
      }
    })
  }

  function onEvent(turn: AskTurn, name: string, data: unknown) {
    applyEvent(turn, name, data)
    scrollDown()
  }

  async function send(text?: string) {
    const question = (text ?? draft.value).trim()
    if (!question || busy.value) {
      return
    }
    const prior = buildHistory(turns.value)
    const conversationId = turns.value.find((turn) => turn.conversationId)?.conversationId
    draft.value = ''
    turns.value.push(emptyTurn(question))
    const live = turns.value[turns.value.length - 1]
    busy.value = true
    controller = new AbortController()
    scrollDown()
    try {
      await askStream({ question, history: prior, conversationId }, (name, data) => onEvent(live, name, data), controller.signal)
    } catch (e) {
      live.error = e instanceof Error ? e.message : String(e)
    } finally {
      live.done = true
      busy.value = false
      controller = null
      if (live.answer) {
        live.showTrace = false
      }
      scrollDown()
    }
  }

  function stop() {
    controller?.abort()
  }

  function clearTurns() {
    turns.value = []
  }

  function onKey(event: KeyboardEvent) {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault()
      void send()
    }
  }

  onUnmounted(() => controller?.abort())

  return {
    turns: turns as Ref<AskTurn[]>,
    draft,
    busy,
    scroller,
    send,
    stop,
    clearTurns,
    onKey,
    scrollDown
  }
}
