import { describe, expect, it } from 'vitest'
import { edgeWhenLabel, edgeWhenOptionsFor, nextEdgeWhen } from './nodeCatalog'

describe('edge when catalog', () => {
  it('uses short chinese labels on canvas', () => {
    expect(edgeWhenLabel('true')).toBe('是')
    expect(edgeWhenLabel('false')).toBe('否')
    expect(edgeWhenLabel('continue')).toBe('')
    expect(edgeWhenLabel('default')).toBe('')
    expect(edgeWhenLabel('clarify')).toBe('')
    expect(edgeWhenLabel('error')).toBe('出错')
  })

  it('suggests branch order per source type', () => {
    expect(nextEdgeWhen('if_else', [])).toBe('true')
    expect(nextEdgeWhen('if_else', ['true'])).toBe('false')
    expect(nextEdgeWhen('if_else', ['true', 'false'])).toBe('default')
    expect(nextEdgeWhen('ontology', [])).toBe('default')
    expect(nextEdgeWhen('metadata', [])).toBe('error')
    expect(nextEdgeWhen('metadata', ['error'])).toBe('default')
  })

  it('filters options by source', () => {
    expect(edgeWhenOptionsFor('if_else').map((item) => item.value)).toEqual([
      'default',
      'true',
      'false'
    ])
    expect(edgeWhenOptionsFor('ontology').map((item) => item.value)).toEqual(['default'])
    expect(edgeWhenOptionsFor('metadata').map((item) => item.value)).toEqual([
      'default',
      'error'
    ])
    expect(edgeWhenOptionsFor('knowledge').map((item) => item.value)).toEqual(['default'])
  })
})
