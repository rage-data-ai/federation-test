import { describe, expect, it } from 'vitest'
import { assertAcyclic, graphToVueFlow, vueFlowToGraph } from './graphAdapter'
import type { FlowGraph } from '../types'

const sample: FlowGraph = {
  startNodeId: 'start',
  nodes: [
    { id: 'start', type: 'start', title: '开始', config: { x: 10, y: 20 } },
    { id: 'chat', type: 'llm_chat', title: '对话', config: { systemPrompt: 'hi' } },
    { id: 'end', type: 'end', title: '结束', config: {} }
  ],
  edges: [
    { from: 'start', to: 'chat', when: 'default' },
    { from: 'chat', to: 'end', when: 'answered' }
  ]
}

describe('graphAdapter', () => {
  it('round-trips through Vue Flow shapes and keeps branch when', () => {
    const { nodes, edges } = graphToVueFlow(sample)
    expect(nodes).toHaveLength(3)
    expect(nodes[0]?.position).toEqual({ x: 10, y: 20 })
    expect(edges[1]?.data?.when).toBe('answered')
    const back = vueFlowToGraph(sample.startNodeId, nodes, edges)
    expect(back.startNodeId).toBe('start')
    expect(back.nodes.find((node) => node.id === 'chat')?.config?.systemPrompt).toBe('hi')
    expect(back.nodes.find((node) => node.id === 'start')?.config?.x).toBe(10)
    expect(back.edges.map((edge) => edge.when)).toEqual(['default', 'answered'])
  })

  it('rejects cycles', () => {
    expect(() =>
      assertAcyclic({
        startNodeId: 'a',
        nodes: [
          { id: 'a', type: 'start', title: 'a', config: {} },
          { id: 'b', type: 'end', title: 'b', config: {} }
        ],
        edges: [
          { from: 'a', to: 'b', when: 'default' },
          { from: 'b', to: 'a', when: 'default' }
        ]
      })
    ).toThrow(/环/)
  })
})
