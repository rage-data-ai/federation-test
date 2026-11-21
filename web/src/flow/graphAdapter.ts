import type { FlowGraph } from '../types'
import { catalogItem, edgeWhenLabel } from './nodeCatalog'

export type NodeRunStatus = 'idle' | 'running' | 'ok' | 'error'

export type DagNodeData = {
  flowType: string
  title: string
  config: Record<string, unknown>
  runStatus?: NodeRunStatus
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
  type?: string
  label?: string
  data: { when: string }
  animated: boolean
  markerEnd: string
  pathOptions?: { offset?: number; borderRadius?: number }
}

const DEFAULT_GAP_X = 220
const DEFAULT_GAP_Y = 120

function num(value: unknown, fallback: number): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback
}

/** 从流程 JSON 生成 Vue Flow 节点；缺坐标时按拓扑自动排成一行。 */
export function graphToVueFlow(graph: FlowGraph): { nodes: DagCanvasNode[]; edges: DagCanvasEdge[] } {
  const normalized = stripEndNodes(graph)
  const order = layoutOrder(normalized)
  const nodes: DagCanvasNode[] = normalized.nodes.map((node, index) => {
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
  const edges: DagCanvasEdge[] = withParallelEdgeLayout(
    normalized.edges.map((edge, index) => {
      const when = edge.when && String(edge.when).trim() ? String(edge.when).trim() : 'default'
      return {
        id: `e-${edge.from}-${edge.to}-${when}-${index}`,
        source: edge.from,
        target: edge.to,
        label: edgeWhenLabel(when),
        data: { when },
        animated: when !== 'default',
        markerEnd: 'arrowclosed'
      }
    })
  )
  return { nodes, edges }
}

/**
 * 同起终点多条分支边错开绘制，避免「出错/默认」叠成一条只看见其中一个。
 */
export function withParallelEdgeLayout(edges: DagCanvasEdge[]): DagCanvasEdge[] {
  const groups = new Map<string, number[]>()
  edges.forEach((edge, index) => {
    const key = `${edge.source}=>${edge.target}`
    const list = groups.get(key) || []
    list.push(index)
    groups.set(key, list)
  })
  return edges.map((edge, index) => {
    const idxs = groups.get(`${edge.source}=>${edge.target}`) || [index]
    if (idxs.length < 2) {
      return { ...edge, type: edge.type || 'default', pathOptions: undefined }
    }
    const pos = idxs.indexOf(index)
    const offset = Math.round((pos - (idxs.length - 1) / 2) * 40)
    return {
      ...edge,
      type: 'smoothstep',
      pathOptions: { offset, borderRadius: 8 }
    }
  })
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
  const normalized = stripEndNodes(graph)
  assertHasChatExit(normalized)
  assertUniqueTitles(normalized)
  return normalized
}

export function assertUniqueTitles(graph: FlowGraph) {
  const seen = new Set<string>()
  for (const node of graph.nodes) {
    const title = String(node.title || '').trim()
    if (!title) {
      throw new Error(`节点名称不能为空：${node.id}`)
    }
    if (/[#.{}]/.test(title)) {
      throw new Error(`节点名称不能包含 # . { }：${title}`)
    }
    if (seen.has(title)) {
      throw new Error(`节点名称不能重复：${title}`)
    }
    seen.add(title)
  }
}

/** ChatFlow：去掉遗留 end（对齐 Dify，无结束节点）。 */
export function stripEndNodes(graph: FlowGraph): FlowGraph {
  const ends = (graph.nodes || []).filter((node) => node.type === 'end')
  if (!ends.length) {
    return graph
  }
  const endIds = new Set(ends.map((node) => node.id))
  const hasReply = (graph.nodes || []).some(
    (node) =>
      node.type !== 'end' &&
      (node.type === 'answer' ||
        node.type === 'llm_chat' ||
        node.type === 'insight' ||
        node.type === 'knowledge')
  )
  let nodes = (graph.nodes || [])
    .filter((node) => node.type !== 'end')
    .map((node) => normalizeAnswerNode(node))
  let edges = (graph.edges || []).filter((edge) => !endIds.has(edge.from) && !endIds.has(edge.to))
  if (!hasReply && ends[0]) {
    const first = ends[0]
    const title =
      first.title === '结束' || first.title === 'end' || !first.title ? '回答' : first.title
    const text =
      (first.config?.inputs as Record<string, unknown> | undefined)?.text ??
      first.config?.text ??
      '{{#sys.query#}}'
    nodes = [
      ...nodes,
      {
        id: first.id,
        type: 'answer',
        title,
        config: {
          ...(first.config || {}),
          text,
          inputs: { ...((first.config?.inputs as object) || {}), text }
        }
      }
    ]
    edges = [
      ...edges,
      ...(graph.edges || [])
        .filter((edge) => endIds.has(edge.to) && !endIds.has(edge.from))
        .map((edge) => ({ ...edge, to: first.id }))
    ]
  }
  let startNodeId = graph.startNodeId
  if (endIds.has(startNodeId) && nodes[0]) {
    startNodeId = nodes[0].id
  }
  return { startNodeId, nodes, edges }
}

function normalizeAnswerNode(node: FlowGraph['nodes'][number]): FlowGraph['nodes'][number] {
  if (node.type !== 'answer') {
    return node
  }
  const config = { ...(node.config || {}) }
  const inputs = { ...((config.inputs as Record<string, unknown>) || {}) }
  if (inputs.text == null && config.text != null) {
    inputs.text = config.text
  }
  if (config.text == null && inputs.text != null) {
    config.text = inputs.text
  }
  if (inputs.text == null && config.text == null) {
    inputs.text = '{{#sys.query#}}'
    config.text = '{{#sys.query#}}'
  }
  config.inputs = inputs
  return { ...node, config }
}

/** 须有回答 / 大模型 / 知识库等可产生用户可见回复的出口。 */
export function assertHasChatExit(graph: FlowGraph) {
  const ok = (graph.nodes || []).some(
    (node) =>
      node.type === 'answer' ||
      node.type === 'llm_chat' ||
      node.type === 'insight' ||
      node.type === 'knowledge'
  )
  if (!ok) {
    throw new Error('流程需要至少一个回答 / 大模型 / 知识库节点作为对话出口')
  }
}

/** @deprecated 使用 assertHasChatExit；ChatFlow 已无结束节点。 */
export function assertHasEnd(graph: FlowGraph) {
  assertHasChatExit(graph)
}

/**
 * 当前节点全部上游（沿入边回溯，不含自身）。
 * 用于变量选择器：只能引用上游出参。
 */
export function upstreamNodeIds(
  nodeId: string,
  edges: Array<{ source: string; target: string }>
): Set<string> {
  const incoming = new Map<string, string[]>()
  for (const edge of edges) {
    const list = incoming.get(edge.target) || []
    list.push(edge.source)
    incoming.set(edge.target, list)
  }
  const seen = new Set<string>()
  const stack = [...(incoming.get(nodeId) || [])]
  while (stack.length) {
    const id = stack.pop()!
    if (seen.has(id) || id === nodeId) {
      continue
    }
    seen.add(id)
    for (const prev of incoming.get(id) || []) {
      stack.push(prev)
    }
  }
  return seen
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
