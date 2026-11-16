/** 与后端 FlowNodePorts 对齐的入参 / 出参目录。 */
export type NodePort = {
  name: string
  label: string
  description: string
}

export type NodePortSpec = {
  inputs: NodePort[]
  outputs: NodePort[]
  defaultInputs: Record<string, unknown>
}

export const SYS_VARS = [
  { path: 'sys.query', label: '用户问题' },
  { path: 'sys.history', label: '对话历史' },
  { path: 'sys.conversation_id', label: '会话 ID' },
  { path: 'sys.app_id', label: '应用 ID' },
  { path: 'sys.user', label: '当前用户' }
] as const

export const NODE_PORTS: Record<string, NodePortSpec> = {
  start: {
    inputs: [],
    outputs: [
      { name: 'query', label: '问题', description: '写入 sys.query' },
      { name: 'history', label: '历史', description: '写入 sys.history' },
      { name: 'conversation_id', label: '会话', description: '写入 sys.conversation_id' }
    ],
    defaultInputs: {}
  },
  knowledge: {
    inputs: [
      { name: 'query', label: '查询文本', description: '默认绑定 sys.query' },
      { name: 'topK', label: 'TopK', description: '召回条数' }
    ],
    outputs: [
      { name: 'outcome', label: '结果条件', description: 'answered / continue' },
      { name: 'answered', label: '是否短路', description: 'boolean' },
      { name: 'answer', label: '答案文本', description: '短路时的回答' },
      { name: 'matches', label: '命中数', description: '召回条数' }
    ],
    defaultInputs: { query: '{{#sys.query#}}', topK: 5 }
  },
  ontology: {
    inputs: [
      { name: 'query', label: '查询文本', description: '默认绑定 sys.query' },
      { name: 'skills', label: '工具技能', description: 'ontology 等' }
    ],
    outputs: [
      { name: 'outcome', label: '结果条件', description: 'continue / clarify' },
      { name: 'context', label: '业务上下文', description: '摘要' },
      { name: 'answerability', label: '可回答性', description: '预检结果' }
    ],
    defaultInputs: { query: '{{#sys.query#}}', skills: ['ontology'] }
  },
  metadata: {
    inputs: [{ name: 'recover', label: '自动恢复', description: '缺表时重采元数据' }],
    outputs: [
      { name: 'outcome', label: '结果条件', description: 'continue / error' },
      { name: 'report', label: '元数据报告', description: '摘要' },
      { name: 'tables', label: '表数量', description: '可用表数' }
    ],
    defaultInputs: { recover: true }
  },
  insight: {
    inputs: [
      { name: 'query', label: '查询文本', description: '默认绑定 sys.query' },
      { name: 'skills', label: '工具技能', description: 'sql / preference' }
    ],
    outputs: [
      { name: 'outcome', label: '结果条件', description: 'answered / clarify / error' },
      { name: 'answer', label: '答案文本', description: '' },
      { name: 'sql', label: 'SQL', description: '' },
      { name: 'rowCount', label: '行数', description: '' }
    ],
    defaultInputs: { query: '{{#sys.query#}}', skills: ['sql', 'preference'] }
  },
  llm_chat: {
    inputs: [
      { name: 'query', label: '用户消息', description: '默认绑定 sys.query' },
      { name: 'systemPrompt', label: '系统提示词', description: '支持 {{#var#}}' },
      { name: 'skills', label: '工具技能', description: 'ontology / knowledge / preference' }
    ],
    outputs: [
      { name: 'outcome', label: '结果条件', description: 'default' },
      { name: 'answer', label: '回复文本', description: '' }
    ],
    defaultInputs: {
      query: '{{#sys.query#}}',
      systemPrompt: '你是有帮助的中文助手。',
      skills: []
    }
  },
  builtin_ask: {
    inputs: [{ name: 'query', label: '查询文本', description: '默认绑定 sys.query' }],
    outputs: [{ name: 'outcome', label: '结果条件', description: 'default' }],
    defaultInputs: { query: '{{#sys.query#}}' }
  },
  end: { inputs: [], outputs: [], defaultInputs: {} }
}

export function portsFor(type: string): NodePortSpec {
  return NODE_PORTS[type] || { inputs: [], outputs: [], defaultInputs: {} }
}

export function asRef(path: string): string {
  return `{{#${path}#}}`
}

export function parseRef(value: unknown): string | null {
  if (typeof value !== 'string') {
    return null
  }
  const text = value.trim()
  if (text.startsWith('{{#') && text.endsWith('#}}')) {
    return text.slice(3, -3).trim()
  }
  return null
}
