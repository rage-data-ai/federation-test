import { describe, expect, it } from 'vitest'
import { SAMPLES, cell, lastStage } from './useAskStream'

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
