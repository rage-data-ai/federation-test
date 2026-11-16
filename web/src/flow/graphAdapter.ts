import type { FlowGraph } from '../types'
import { catalogItem } from './nodeCatalog'

export type DagNodeData = {
  flowType: string
  title: string
  config: Record<string, unknown>
}

/** Loose canvas shapes — avoid Vue Flow Node/Edge generics (TS2589). */
export type DagCanvasNode = {
  id: string
  type: string
  position: { x: number; y: number }
  data: DagNodeData
  sourcePosition: string
  targetPosition: string
}

export type DagCanvasEdge = {
  id: string
  source: string
  target: string
  label?: string
  data: { when: string }
  animated: boolean
  markerEnd: string
}

const DEFAULT_GAP_X = 220
const DEFAULT_GAP_Y = 120

function num(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/** 从流程 JSON 生成 Vue Flow 节点；缺坐标时按拓扑自动排成一行。 */
export function graphToVueFlow(graph: FlowGraph): { nodes: DagCanvasNode[]; edges: DagCanvasEdge[] } {
  const order = layoutOrder(graph)
  const nodes: DagCanvasNode[] = graph.nodes.map((node, index) => {
    const config = { ...(node.config || {}) }
    const fallbackIndex = order.indexOf(node.id)
    const slot = fallbackIndex >= 0 ? fallbackIndex : index
    const x = num(config.x, 40 + slot * DEFAULT_GAP_X)
    const y = num(config.y, 80 + (slot % 2) * DEFAULT_GAP_Y)
    delete config.x
    delete config.y
    const meta = catalogItem(node.type)
    return {
      id: node.id,
      type: 'flow',
      position: { x, y },
      data: {
        flowType: node.type,
        title: node.title || meta.title,
        config
      },
      sourcePosition: 'right',
      targetPosition: 'left'
    }
  })
  const edges: DagCanvasEdge[] = graph.edges.map((edge, index) => {
    const when = edge.when && String(edge.when).trim() ? String(edge.when).trim() : 'default'
    return {
      id: `e-${edge.from}-${edge.to}-${when}-${index}`,
      source: edge.from,
      target: edge.to,
      label: when === 'default' ? '' : when,
      data: { when },
      animated: when !== 'default',
      markerEnd: 'arrowclosed'
    }
  })
  return { nodes, edges }
}

/** Vue Flow 状态写回网关 FlowGraph；并做环检测。 */
export function vueFlowToGraph(
  startNodeId: string,
  nodes: Array<{ id: string; position: { x: number; y: number }; data?: Partial<DagNodeData> }>,
  edges: Array<{ id: string; source: string; target: string; label?: unknown; data?: { when?: string } }>
): FlowGraph {
  if (!nodes.length) {
    throw new Error('流程至少需要一个节点')
  }
  const start = nodes.some((node) => node.id === startNodeId)
    ? startNodeId
    : nodes.find((node) => node.data?.flowType === 'start')?.id || nodes[0]!.id
  const graph: FlowGraph = {
    startNodeId: start,
    nodes: nodes.map((node) => {
      const data = node.data || {}
      return {
        id: node.id,
        type: data.flowType || 'llm_chat',
        title: data.title || node.id,
        config: {
          ...(data.config || {}),
          x: Math.round(node.position.x),
          y: Math.round(node.position.y)
        }
      }
    }),
    edges: edges.map((edge) => ({
      from: edge.source,
      to: edge.target,
      when: String(edge.data?.when || edge.label || 'default')
    }))
  }
  assertAcyclic(graph)
  assertHasEnd(graph)
  return graph
}

export function assertHasEnd(graph: FlowGraph) {
  if (!graph.nodes.some((node) => node.type === 'end')) {
    throw new Error('流程需要至少一个结束节点')
  }
}

/** Kahn：有环则抛错。 */
export function assertAcyclic(graph: FlowGraph) {
  const ids = new Set(graph.nodes.map((node) => node.id))
  const indegree = new Map<string, number>()
  const adj = new Map<string, string[]>()
  for (const id of ids) {
    indegree.set(id, 0)
    adj.set(id, [])
  }
  for (const edge of graph.edges) {
    if (!ids.has(edge.from) || !ids.has(edge.to)) {
      throw new Error(`边引用了不存在的节点：${edge.from} → ${edge.to}`)
    }
    adj.get(edge.from)!.push(edge.to)
    indegree.set(edge.to, (indegree.get(edge.to) || 0) + 1)
  }
  const queue = [...ids].filter((id) => (indegree.get(id) || 0) === 0)
  let seen = 0
  while (queue.length) {
    const id = queue.shift()!
    seen += 1
    for (const next of adj.get(id) || []) {
      const nextDeg = (indegree.get(next) || 0) - 1
      indegree.set(next, nextDeg)
      if (nextDeg === 0) {
        queue.push(next)
      }
    }
  }
  if (seen !== ids.size) {
    throw new Error('流程图不能有环，请去掉回边后再保存')
  }
}

function layoutOrder(graph: FlowGraph): string[] {
  const ids = graph.nodes.map((node) => node.id)
  const indegree = new Map(ids.map((id) => [id, 0]))
  const adj = new Map(ids.map((id) => [id, [] as string[]]))
  for (const edge of graph.edges) {
    if (!indegree.has(edge.from) || !indegree.has(edge.to)) {
      continue
    }
    adj.get(edge.from)!.push(edge.to)
    indegree.set(edge.to, (indegree.get(edge.to) || 0) + 1)
  }
  const queue = ids.filter((id) => (indegree.get(id) || 0) === 0)
  const order: string[] = []
  while (queue.length) {
    const id = queue.shift()!
    order.push(id)
    for (const next of adj.get(id) || []) {
      const nextDeg = (indegree.get(next) || 0) - 1
      indegree.set(next, nextDeg)
      if (nextDeg === 0) {
        queue.push(next)
      }
    }
  }
  for (const id of ids) {
    if (!order.includes(id)) {
      order.push(id)
    }
  }
  return order
}

export function newNodeId(type: string): string {
  return `${type}-${Math.random().toString(36).slice(2, 8)}`
}
