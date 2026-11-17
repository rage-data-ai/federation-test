/** 画布上可添加的节点类型与展示信息。 */
export type FlowNodeKind =
  | 'start'
  | 'end'
  | 'builtin_ask'
  | 'llm_chat'
  | 'answer'
  | 'knowledge'
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
    description: '白盒：知识召回 + 判定；answered 可短路',
    addable: true,
    color: '#a98bff'
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
    description: '可配记忆轮数；勾选 SQL 即联邦查询能力',
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
    title: '智能问数（整段）',
    description: '兼容旧图整段管线',
    addable: false,
    color: '#6ea8fe'
  }
}

export const EDGE_WHEN_OPTIONS = [
  { value: 'default', label: '默认' },
  { value: 'answered', label: '已回答' },
  { value: 'clarify', label: '需澄清' },
  { value: 'continue', label: '继续' },
  { value: 'error', label: '出错' }
] as const

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
