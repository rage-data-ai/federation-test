import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { askStream, type SseHandler } from '../api'
import {
  SAMPLES,
  applyEvent,
  cell,
  emptyTurn,
  lastStage,
  turnFromRunView,
  useAskStream
} from './useAskStream'

vi.mock('../api', () => ({ askStream: vi.fn() }))

describe('SAMPLES', () => {
  it('exposes three Chinese sample questions', () => {
    expect(SAMPLES).toHaveLength(3)
    for (const sample of SAMPLES) {
      expect(typeof sample).toBe('string')
      expect(sample.length).toBeGreaterThan(0)
    }
  })
})

describe('cell', () => {
  it('renders nullish as NULL', () => {
    expect(cell(null)).toBe('NULL')
    expect(cell(undefined)).toBe('NULL')
  })

  it('stringifies primitives', () => {
    expect(cell('ok')).toBe('ok')
    expect(cell(42)).toBe('42')
    expect(cell(true)).toBe('true')
  })

  it('JSON-stringifies objects and arrays', () => {
    expect(cell({ a: 1 })).toBe('{"a":1}')
    expect(cell([1, 2])).toBe('[1,2]')
  })
})

describe('lastStage', () => {
  it('returns fallback when stages are empty', () => {
    expect(lastStage({ stages: [] })).toBe('提交中…')
  })

  it('returns the last stage message', () => {
    expect(
      lastStage({
        stages: [{ message: '本体检索' }, { message: '生成 SQL' }]
      })
    ).toBe('生成 SQL')
  })
})

describe('applyEvent', () => {
  it('keeps the run id and conversation id the gateway hands out', () => {
    const turn = emptyTurn('今天有多少告警？')
    applyEvent(turn, 'run', { runId: 'run-1', conversationId: 'conv-1' })
    expect(turn.runId).toBe('run-1')
    expect(turn.conversationId).toBe('conv-1')
    expect(turn.rating).toBeNull()
  })

  it('leaves the run id empty when the payload is malformed', () => {
    const turn = emptyTurn('q')
    applyEvent(turn, 'run', 'oops')
    expect(turn.runId).toBe('')
    expect(turn.conversationId).toBe('')
  })

  it('stores knowledge matches from the knowledge event', () => {
    const turn = emptyTurn('最近一天高危告警有多少？')
    expect(turn.knowledge).toBeNull()
    applyEvent(turn, 'knowledge', {
      matches: [
        { id: 3, score: 0.91, question: '最近一天高危告警有多少？', answer: '12 条' }
      ]
    })
    expect(turn.knowledge).toEqual({
      matches: [{ id: 3, score: 0.91, question: '最近一天高危告警有多少？', answer: '12 条' }]
    })
  })

  it('treats a knowledge event without matches as an empty list', () => {
    const turn = emptyTurn('q')
    applyEvent(turn, 'knowledge', {})
    expect(turn.knowledge).toEqual({ matches: [] })
  })

  it('tracks node_start / node_finish run details', () => {
    const turn = emptyTurn('q')
    applyEvent(turn, 'node_start', {
      nodeId: 'ans',
      title: '回答',
      type: 'answer',
      inputs: { text: 'hi' }
    })
    expect(turn.nodeRuns).toHaveLength(1)
    expect(turn.nodeRuns[0]).toMatchObject({
      nodeId: 'ans',
      status: 'running',
      inputs: { text: 'hi' }
    })
    applyEvent(turn, 'vars', { nodeId: 'ans', outputs: { answer: 'hi' }, paths: ['ans.answer'] })
    applyEvent(turn, 'node_finish', {
      nodeId: 'ans',
      title: '回答',
      type: 'answer',
      outcome: 'default',
      durationMs: 12,
      outputs: { answer: 'hi' }
    })
    expect(turn.nodeRuns[0]).toMatchObject({
      status: 'ok',
      outcome: 'default',
      durationMs: 12,
      outputs: { answer: 'hi' }
    })
  })
})

describe('turnFromRunView', () => {
  it('restores answered and clarify turns from stored runs', () => {
    const answered = turnFromRunView({
      run: {
        id: 'run-1',
        conversationId: 'conv-1',
        userName: 'alice',
        question: '订单一共有多少笔',
        history: [],
        status: 'answered',
        answer: '42 笔',
        sql: 'SELECT 1',
        evidence: { rows: [[42]], columns: ['cnt'], rowCount: 1 },
        error: null,
        createdAt: 1,
        elapsedMs: 10
      },
      rating: 'up'
    })
    expect(answered.runId).toBe('run-1')
    expect(answered.conversationId).toBe('conv-1')
    expect(answered.rating).toBe('up')
    expect(answered.answer?.answer).toBe('42 笔')
    expect(answered.sqls[0]?.sql).toBe('SELECT 1')
    expect(answered.done).toBe(true)

    const clarify = turnFromRunView({
      run: {
        id: 'run-2',
        conversationId: 'conv-1',
        userName: 'alice',
        question: '再细一点',
        history: [],
        status: 'clarify',
        answer: null,
        sql: null,
        evidence: { clarify: { question: '要看哪个时间范围？', options: ['今天', '本周'] } },
        error: null,
        createdAt: 2,
        elapsedMs: 5
      },
      rating: null
    })
    expect(clarify.clarify).toEqual({ question: '要看哪个时间范围？', options: ['今天', '本周'] })
    expect(clarify.answer).toBeNull()
  })
})

describe('useAskStream conversation', () => {
  beforeEach(() => {
    // 组合式函数在组件外调用时 Vue 会对 onUnmounted 告警，与这里要验证的行为无关。
    vi.spyOn(console, 'warn').mockImplementation(() => {})
    let runs = 0
    vi.mocked(askStream).mockImplementation(async (_body: unknown, onEvent: SseHandler) => {
      runs += 1
      onEvent('run', { runId: `run-${runs}`, conversationId: 'conv-1' })
      onEvent('answer', { answer: `回答 ${runs}` })
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.mocked(askStream).mockReset()
  })

  it('sends follow-ups under the conversation of the first run and starts over after clearing', async () => {
    const { turns, send, clearTurns } = useAskStream()

    await send('最近一天高危告警最多的资产')
    await send('只看生产环境')

    const bodies = () => vi.mocked(askStream).mock.calls.map((call) => call[0])
    expect(bodies()[0]).toEqual({ question: '最近一天高危告警最多的资产', history: [], conversationId: undefined })
    expect(bodies()[1]).toEqual({
      question: '只看生产环境',
      history: [
        { role: 'user', content: '最近一天高危告警最多的资产' },
        { role: 'assistant', content: '回答 1' }
      ],
      conversationId: 'conv-1'
    })
    expect(turns.value.map((turn) => turn.runId)).toEqual(['run-1', 'run-2'])

    clearTurns()
    await send('换个问题')
    expect(bodies()[2]).toEqual({ question: '换个问题', history: [], conversationId: undefined })
  })

  it('restores a saved conversation and continues under its id', async () => {
    const { turns, send, restoreConversation } = useAskStream()
    restoreConversation([
      {
        run: {
          id: 'run-9',
          conversationId: 'conv-saved',
          userName: 'alice',
          question: '历史问题',
          history: [],
          status: 'answered',
          answer: '历史回答',
          sql: null,
          evidence: {},
          error: null,
          createdAt: 1,
          elapsedMs: 1
        },
        rating: null
      }
    ])
    expect(turns.value).toHaveLength(1)
    expect(turns.value[0].conversationId).toBe('conv-saved')

    await send('接着问')
    expect(vi.mocked(askStream).mock.calls.at(-1)?.[0]).toEqual({
      question: '接着问',
      history: [
        { role: 'user', content: '历史问题' },
        { role: 'assistant', content: '历史回答' }
      ],
      conversationId: 'conv-saved'
    })
  })
})
