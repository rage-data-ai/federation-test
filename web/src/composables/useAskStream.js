import { nextTick, onUnmounted, ref } from 'vue'
import { askStream } from '../api'

export const SAMPLES = [
  '哪些账户在大量不同主机上登录失败？各失败了多少次、涉及多少台主机？',
  '用网络登录（登录类型 3）成功登上很多台主机的账户有哪些？各覆盖多少台主机？',
  '哪些主机在事件日志服务停止（事件 1100）之后，又出现了进程创建（事件 4688）？这样的主机有多少台？'
]

export const STAGE_LABEL = { ontology: '本体', metadata: '元数据', insight: '分析' }
export const SOURCE_LABEL = { ontology: '本体定义', inferred: '推断', user: '用户指定' }
export const SQL_STATUS = { executed: '已执行', rejected: '校验拒绝', failed: '执行失败' }

export function cell(value) {
  if (value === null || value === undefined) {
    return 'NULL'
  }
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}

export function lastStage(turn) {
  const stage = turn.stages[turn.stages.length - 1]
  return stage ? stage.message : '提交中…'
}

function emptyTurn(question) {
  return {
    question,
    stages: [],
    ontology: null,
    metadata: null,
    sqls: [],
    results: [],
    answer: null,
    clarify: null,
    error: '',
    done: false,
    showTrace: true
  }
}

function buildHistory(turns) {
  const items = []
  for (const turn of turns) {
    items.push({ role: 'user', content: turn.question })
    const reply = turn.answer?.answer || turn.clarify?.question
    if (reply) {
      items.push({ role: 'assistant', content: reply })
    }
  }
  return items
}

function applyEvent(turn, name, data) {
  switch (name) {
    case 'stage':
      turn.stages.push(data)
      break
    case 'ontology':
      turn.ontology = { ...data.context, mode: data.mode, recovery: data.recovery }
      break
    case 'metadata':
      turn.metadata = { ...data.report, recovery: data.recovery }
      break
    case 'sql':
      turn.sqls.push(data)
      break
    case 'result':
      turn.results.push(data)
      break
    case 'answer':
      turn.answer = data
      break
    case 'clarify':
      turn.clarify = data
      break
    case 'error':
      turn.error = data.message
      break
    default:
      break
  }
}

/**
 * SSE ask flow: turns, draft, busy, send/stop, and scroll.
 */
export function useAskStream() {
  const turns = ref([])
  const draft = ref('')
  const busy = ref(false)
  const scroller = ref(null)
  let controller = null

  function scrollDown() {
    nextTick(() => {
      if (scroller.value) {
        scroller.value.scrollTop = scroller.value.scrollHeight
      }
    })
  }

  function onEvent(turn, name, data) {
    applyEvent(turn, name, data)
    scrollDown()
  }

  async function send(text) {
    const question = (text ?? draft.value).trim()
    if (!question || busy.value) {
      return
    }
    const prior = buildHistory(turns.value)
    draft.value = ''
    turns.value.push(emptyTurn(question))
    const live = turns.value[turns.value.length - 1]
    busy.value = true
    controller = new AbortController()
    scrollDown()
    try {
      await askStream({ question, history: prior }, (name, data) => onEvent(live, name, data), controller.signal)
    } catch (e) {
      live.error = e.message
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

  function onKey(event) {
    if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
      event.preventDefault()
      send()
    }
  }

  onUnmounted(() => controller?.abort())

  return {
    turns,
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
