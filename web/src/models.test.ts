import { describe, expect, it } from 'vitest'
import { AGENT_MODELS, findModel } from './models'

describe('llm presets', () => {
  it('exposes shortcuts without claiming exclusivity', () => {
    expect(AGENT_MODELS.length).toBeGreaterThan(3)
    expect(AGENT_MODELS.some((item) => item.id.startsWith('grok-'))).toBe(true)
    expect(AGENT_MODELS.some((item) => item.protocol === 'anthropic')).toBe(true)
    expect(AGENT_MODELS.some((item) => item.baseUrl.includes('openai.com'))).toBe(true)
  })

  it('looks up a preset by id and ignores unknown ids', () => {
    expect(findModel('grok-4.7')?.baseUrl).toBe('https://api.x.ai/v1')
    expect(findModel('my-local-model')).toBeUndefined()
  })
})
