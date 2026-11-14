import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { askStream, type SseHandler } from '../api'
import { SAMPLES, applyEvent, cell, emptyTurn, lastStage, useAskStream } from './useAskStream'

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
})
