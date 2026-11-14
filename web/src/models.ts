/**
 * 大模型配置里的快捷填入项。只是省事，不是白名单：
 * 模型名、协议、接口地址都可以手写任意值。
 */
export type AgentModel = {
  id: string
  label: string
  protocol: string
  baseUrl: string
}

export const AGENT_MODELS: AgentModel[] = [
  {
    id: 'grok-4.7',
    label: 'Grok 4.7',
    protocol: 'openai',
    baseUrl: 'https://api.x.ai/v1'
  },
  {
    id: 'grok-4.6',
    label: 'Grok 4.6',
    protocol: 'openai',
    baseUrl: 'https://api.x.ai/v1'
  },
  {
    id: 'grok-4.5',
    label: 'Grok 4.5',
    protocol: 'openai',
    baseUrl: 'https://api.x.ai/v1'
  },
  {
    id: 'gpt-4.1',
    label: 'GPT-4.1',
    protocol: 'openai',
    baseUrl: 'https://api.openai.com/v1'
  },
  {
    id: 'gpt-4o',
    label: 'GPT-4o',
    protocol: 'openai',
    baseUrl: 'https://api.openai.com/v1'
  },
  {
    id: 'claude-sonnet-4-5',
    label: 'Claude Sonnet 4.5',
    protocol: 'anthropic',
    baseUrl: 'https://api.anthropic.com'
  },
  {
    id: 'qwen-plus',
    label: '通义千问 Plus',
    protocol: 'openai',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1'
  },
  {
    id: 'deepseek-chat',
    label: 'DeepSeek Chat',
    protocol: 'openai',
    baseUrl: 'https://api.deepseek.com/v1'
  }
]

/** 表单空白时快捷填入用，不强制选用。 */
export const DEFAULT_MODEL = AGENT_MODELS[0]

export function findModel(id: string) {
  return AGENT_MODELS.find((item) => item.id === id)
}
