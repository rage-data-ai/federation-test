import { describe, expect, it } from 'vitest'
import {
  assertAcyclic,
  assertHasChatExit,
  graphToVueFlow,
  stripEndNodes,
  upstreamNodeIds,
  vueFlowToGraph,
  withParallelEdgeLayout
} from './graphAdapter'
import type { FlowGraph } from '../types'

const sample: FlowGraph = {
  startNodeId: 'start',
  nodes: [
    { id: 'start', type: 'start', title: '开始', config: { x: 10, y: 20 } },
    { id: 'chat', type: 'llm_chat', title: '对话', config: { systemPrompt: 'hi' } },
    {
      id: 'ans',
      type: 'answer',
      title: '回答',
      config: { inputs: { text: '{{#对话.answer#}}' }, text: '{{#对话.answer#}}' }
    }
  ],
  edges: [
    { from: 'start', to: 'chat', when: 'default' },
    { from: 'chat', to: 'ans', when: 'answered' }
  ]
}

describe('graphAdapter', () => {
  it('round-trips through Vue Flow shapes and keeps branch when', () => {
    const { nodes, edges } = graphToVueFlow(sample)
    expect(nodes).toHaveLength(3)
    expect(nodes[0]?.position).toEqual({ x: 10, y: 20 })
    expect(edges[1]?.data?.when).toBe('answered')
    expect(edges[1]?.label).toBe('已答')
    const back = vueFlowToGraph(sample.startNodeId, nodes, edges)
    expect(back.startNodeId).toBe('start')
    expect(back.nodes.find((node) => node.id === 'chat')?.config?.systemPrompt).toBe('hi')
    expect(back.nodes.find((node) => node.id === 'start')?.config?.x).toBe(10)
    expect(back.edges.map((edge) => edge.when)).toEqual(['default', 'answered'])
  })

  it('strips legacy end nodes on load and save', () => {
    const withEnd: FlowGraph = {
      startNodeId: 'start',
      nodes: [
        { id: 'start', type: 'start', title: '开始', config: {} },
        { id: 'chat', type: 'llm_chat', title: '对话', config: {} },
        { id: 'end', type: 'end', title: '结束', config: {} }
      ],
      edges: [
        { from: 'start', to: 'chat', when: 'default' },
        { from: 'chat', to: 'end', when: 'default' }
      ]
    }
    const stripped = stripEndNodes(withEnd)
    expect(stripped.nodes.some((n) => n.type === 'end')).toBe(false)
    expect(stripped.nodes).toHaveLength(2)
    const { nodes } = graphToVueFlow(withEnd)
    expect(nodes.some((n) => n.data.flowType === 'end')).toBe(false)
    assertHasChatExit(stripped)
  })

  it('offsets parallel branch edges so same endpoints both show', () => {
    const laid = withParallelEdgeLayout([
      {
        id: 'e1',
        source: 'metadata',
        target: 'ans',
        data: { when: 'error' },
        label: '出错',
        animated: true,
        markerEnd: 'arrowclosed'
      },
      {
        id: 'e2',
        source: 'metadata',
        target: 'ans',
        data: { when: 'default' },
        label: '',
        animated: true,
        markerEnd: 'arrowclosed'
      }
    ])
    expect(laid[0]?.type).toBe('smoothstep')
    expect(laid[1]?.type).toBe('smoothstep')
    expect(laid[0]?.pathOptions?.offset).not.toBe(laid[1]?.pathOptions?.offset)
    expect(laid.map((edge) => edge.label).sort()).toEqual(['', '出错'])
  })

  it('lists only upstream node ids', () => {
    const edges = [
      { source: 'start', target: 'kb' },
      { source: 'kb', target: 'judge' },
      { source: 'judge', target: 'iff' },
      { source: 'iff', target: 'onto' },
      { source: 'onto', target: 'meta' },
      { source: 'meta', target: 'llm' }
    ]
    expect([...upstreamNodeIds('llm', edges)].sort()).toEqual(
      ['iff', 'judge', 'kb', 'meta', 'onto', 'start'].sort()
    )
    expect([...upstreamNodeIds('judge', edges)].sort()).toEqual(['kb', 'start'].sort())
    expect(upstreamNodeIds('start', edges).size).toBe(0)
  })

  it('rejects cycles', () => {
    expect(() =>
      assertAcyclic({
        startNodeId: 'a',
        nodes: [
          { id: 'a', type: 'start', title: 'a', config: {} },
          { id: 'b', type: 'answer', title: 'b', config: {} }
        ],
        edges: [
          { from: 'a', to: 'b', when: 'default' },
          { from: 'b', to: 'a', when: 'default' }
        ]
      })
    ).toThrow(/环/)
  })
})
