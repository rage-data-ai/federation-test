/** 画布上可添加的节点类型与展示信息。 */
export type FlowNodeKind =
  | 'start'
  | 'end'
  | 'builtin_ask'
  | 'llm_chat'
  | 'answer'
  | 'knowledge'
  | 'if_else'
  | 'ontology'
  | 'metadata'
  | 'insight'

export type NodeCatalogItem = {
  type: FlowNodeKind
  title: string
  description: string
  /** 调色板是否允许用户再添加（start 通常只能有一个）。 */
  addable: boolean
  color: string
}

/** 左侧调色板：只展示当前推荐节点（已合并/旧兼容类型不出现在此）。 */
export const NODE_CATALOG: NodeCatalogItem[] = [
  { type: 'start', title: '开始', description: '流程入口', addable: false, color: '#3d8bfd' },
  {
    type: 'knowledge',
    title: '知识库',
    description: '只检索，输出 result Array[Object] 供提示词引用',
    addable: true,
    color: '#a98bff'
  },
  {
    type: 'if_else',
    title: '条件',
    description: '比较分流：是 / 否',
    addable: true,
    color: '#dee2e6'
  },
  {
    type: 'ontology',
    title: '本体',
    description: '白盒：确定性检索；可挂本体工具探索',
    addable: true,
    color: '#ffc107'
  },
  { type: 'metadata', title: '元数据', description: '白盒：核对映射表与列', addable: true, color: '#fd7e14' },
  {
    type: 'llm_chat',
    title: '大模型对话',
    description: '可配记忆；勾选 SQL=联邦查询；可开知识判定',
    addable: true,
    color: '#75b798'
  },
  {
    type: 'answer',
    title: '直接回答',
    description: '模板输出最终回答（支持变量插值）',
    addable: true,
    color: '#9ec5fe'
  },
  { type: 'end', title: '结束', description: '流程出口', addable: true, color: '#adb5bd' }
]

/** 旧图仍可能出现；仅用于标题/颜色回显，不进调色板。 */
const LEGACY_NODES: Record<string, NodeCatalogItem> = {
  insight: {
    type: 'insight',
    title: 'SQL 洞察',
    description: '已合并进大模型对话 + SQL',
    addable: false,
    color: '#20c997'
  },
  builtin_ask: {
    type: 'builtin_ask',
    title: '智能问数（已移除）',
    description: '请迁移为白盒阶段图；运行时会拒绝',
    addable: false,
    color: '#6ea8fe'
  }
}

/**
 * 边条件：value 与运行时 outcome 对齐；short 画在连线上。
 * 默认线上不写字；continue 与 default 同义；clarify 可单独分边（本体澄清对齐黑盒 ask）。
 */
export const EDGE_WHEN_OPTIONS = [
  { value: 'default', label: '默认', short: '' },
  { value: 'true', label: '是', short: '是' },
  { value: 'false', label: '否', short: '否' },
  { value: 'error', label: '出错', short: '出错' },
  { value: 'answered', label: '已回答', short: '已答' },
  { value: 'clarify', label: '澄清', short: '澄清' },
  { value: 'continue', label: '默认', short: '' }
] as const

export type EdgeWhenValue = (typeof EDGE_WHEN_OPTIONS)[number]['value']

/** 各节点类型可画的分支（continue 仍与 default 同义，不进新建选项）。 */
const EDGE_WHEN_BY_SOURCE: Record<string, EdgeWhenValue[]> = {
  if_else: ['true', 'false', 'default'],
  ontology: ['default', 'clarify'],
  metadata: ['error', 'default'],
  llm_chat: ['default'],
  insight: ['default'],
  answer: ['default'],
  knowledge: ['default'],
  start: ['default'],
  builtin_ask: ['default'],
  end: ['default']
}

export function edgeWhenMeta(when: string): (typeof EDGE_WHEN_OPTIONS)[number] {
  const value = when === 'continue' ? 'default' : when
  return EDGE_WHEN_OPTIONS.find((item) => item.value === value) || EDGE_WHEN_OPTIONS[0]
}

/** 画布边标签：默认不显示文字。 */
export function edgeWhenLabel(when: string | undefined | null): string {
  const value = when && String(when).trim() ? String(when).trim() : 'default'
  return edgeWhenMeta(value).short
}

export function edgeWhenOptionsFor(sourceType: string): typeof EDGE_WHEN_OPTIONS[number][] {
  const allowed = EDGE_WHEN_BY_SOURCE[sourceType] || ['default']
  return EDGE_WHEN_OPTIONS.filter((item) => allowed.includes(item.value))
}

/** 从该源节点再连一条边时推荐的 when（优先尚未占用的分支）。 */
export function nextEdgeWhen(
  sourceType: string,
  usedWhens: Iterable<string>
): EdgeWhenValue | null {
  const used = new Set(
    [...usedWhens].map((item) => (item && String(item).trim() ? String(item).trim() : 'default'))
  )
  const candidates = EDGE_WHEN_BY_SOURCE[sourceType] || ['default']
  for (const when of candidates) {
    if (!used.has(when)) {
      return when
    }
  }
  return null
}

export function catalogItem(type: string): NodeCatalogItem {
  return (
    NODE_CATALOG.find((item) => item.type === type) ||
    LEGACY_NODES[type] || {
      type: type as FlowNodeKind,
      title: type,
      description: '',
      addable: false,
      color: '#888'
    }
  )
}
