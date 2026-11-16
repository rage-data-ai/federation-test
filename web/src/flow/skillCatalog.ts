/** 与后端 AskSkillIds 对齐的可绑定工具技能。 */
export type AskSkillId = 'ontology' | 'preference' | 'knowledge' | 'sql'

export type SkillCatalogItem = {
  id: AskSkillId
  label: string
  description: string
}

export const SKILL_CATALOG: SkillCatalogItem[] = [
  { id: 'knowledge', label: '知识库检索', description: 'searchKnowledge：召回已审知识' },
  { id: 'ontology', label: '本体工具', description: 'listConcepts / searchEntities / describeEntity 等' },
  { id: 'preference', label: '偏好记忆', description: 'rememberPreference：记住问数偏好' },
  { id: 'sql', label: 'SQL 执行', description: 'runSql / 过滤校验 / 元数据恢复（InsightSession）' }
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
  const raw = config?.skills
  if (Array.isArray(raw)) {
    return raw.filter((item): item is AskSkillId =>
      SKILL_CATALOG.some((skill) => skill.id === item)
    )
  }
  return [...(DEFAULT_SKILLS[flowType] || [])]
}
