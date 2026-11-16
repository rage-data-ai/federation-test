/** 与后端 AskSkillIds 对齐的可绑定工具技能；附带具体 @Tool 清单供 LLM 节点展示。 */
export type AskSkillId = 'ontology' | 'preference' | 'knowledge' | 'sql'

export type ToolMethod = {
  name: string
  label: string
}

export type SkillCatalogItem = {
  id: AskSkillId
  label: string
  description: string
  /** 该技能下已有工具方法（展示用）。 */
  tools: ToolMethod[]
  /** 哪些节点类型允许勾选（空=仅展示）。 */
  bindableOn: string[]
}

export const SKILL_CATALOG: SkillCatalogItem[] = [
  {
    id: 'knowledge',
    label: '知识库检索',
    description: '召回已审核知识问答对',
    tools: [{ name: 'searchKnowledge', label: '按问题检索知识' }],
    // 知识库有独立节点，不在通用 llm_chat 上重复勾选
    bindableOn: ['knowledge']
  },
  {
    id: 'ontology',
    label: '本体工具',
    description: '业务语义检索与可回答性预检',
    tools: [
      { name: 'listConcepts', label: '列出业务概念' },
      { name: 'searchEntities', label: '关键词检索实体' },
      { name: 'describeEntity', label: '查看实体详情' },
      { name: 'findPaths', label: '概念间关系路径' },
      { name: 'listDefinedConcepts', label: '列出派生概念' },
      { name: 'getBusinessContext', label: '确定性业务上下文' },
      { name: 'checkAnswerable', label: '可回答性预检' }
    ],
    bindableOn: ['llm_chat', 'ontology']
  },
  {
    id: 'preference',
    label: '偏好记忆',
    description: '记住用户明确提出的问数偏好',
    tools: [{ name: 'rememberPreference', label: '记住偏好' }],
    bindableOn: ['llm_chat', 'insight']
  },
  {
    id: 'sql',
    label: 'SQL 执行',
    description: '联邦 SQL 执行与元数据/本体恢复（InsightSession）',
    tools: [
      { name: 'runSql', label: '执行只读 SQL' },
      { name: 'validateFilterValues', label: '校验过滤值' },
      { name: 'scanFilterLiterals', label: '扫描字面量' },
      { name: 'recoverOntology', label: '本体恢复' },
      { name: 'recoverMetadata', label: '元数据恢复' }
    ],
    // SQL 洞察节点即带工具的 LLM；不在通用 llm_chat 上重复提供
    bindableOn: ['insight']
  }
]

export const DEFAULT_SKILLS: Record<string, AskSkillId[]> = {
  knowledge: ['knowledge'],
  ontology: ['ontology'],
  insight: ['sql', 'preference'],
  llm_chat: []
}

export function skillsForNode(
  flowType: string,
  config: Record<string, unknown> | undefined
): AskSkillId[] {
  const allowed = new Set(skillsBindableOn(flowType).map((skill) => skill.id))
  const raw = config?.skills
  if (Array.isArray(raw)) {
    return raw.filter((item): item is AskSkillId => allowed.has(item as AskSkillId))
  }
  return [...(DEFAULT_SKILLS[flowType] || [])].filter((id) => allowed.has(id))
}

export function skillsBindableOn(flowType: string): SkillCatalogItem[] {
  return SKILL_CATALOG.filter((skill) => skill.bindableOn.includes(flowType))
}
