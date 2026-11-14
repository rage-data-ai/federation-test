import AxeBuilder from '@axe-core/playwright'
import { expect, test } from '@playwright/test'

/** Stabilize glyph rasterization across CI runner images. */
async function stabilizeFonts(page: import('@playwright/test').Page) {
  await page.addStyleTag({
    content: `
      *, *::before, *::after {
        font-family: Arial, Helvetica, sans-serif !important;
        letter-spacing: 0 !important;
        word-spacing: 0 !important;
      }
    `
  })
  await page.evaluate(() => {
    const fonts = (document as Document & { fonts?: { ready?: Promise<unknown> } }).fonts
    return fonts?.ready ?? Promise.resolve()
  })
}

test.describe('console smoke', () => {
  test.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('theme-mode', 'light')
    })
  })

  test('skip-link, main landmark, and module navigation', async ({ page }) => {
    await page.goto('/')
    await expect(page.locator('#main-stage')).toBeVisible()
    await expect(page.getByRole('navigation', { name: '模块' })).toBeVisible()

    const skip = page.getByRole('link', { name: '跳到主内容' })
    await skip.focus()
    await expect(skip).toBeFocused()

    await page.getByRole('button', { name: '本体建模' }).click()
    await expect(page.locator('#onto-title')).toHaveText('本体建模')

    await page.getByRole('button', { name: '智能问数' }).click()
    await expect(page.locator('#ask-title')).toHaveText('智能问数')
  })

  test('auth dialog traps focus and closes on Escape', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: /身份/ }).click()
    const dialog = page.getByRole('dialog', { name: '访问身份' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toHaveAttribute('aria-modal', 'true')

    const token = dialog.locator('input[type="password"]')
    await expect(token).toBeFocused()

    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
  })

  test('ontology draft dialog traps focus and closes on Escape', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '本体建模' }).click()
    await page.getByRole('button', { name: /起草/ }).first().click()
    const dialog = page.getByRole('dialog', { name: '大模型起草' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toHaveAttribute('aria-modal', 'true')
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
  })

  test('catalog form dialog traps focus and closes on Escape', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '数据源管理' }).click()
    await expect(page.locator('#ds-title')).toBeVisible()
    await page.getByRole('button', { name: '添加数据源' }).click()
    const dialog = page.getByRole('dialog', { name: '添加数据源' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toHaveAttribute('aria-modal', 'true')
    await expect(dialog.locator('input').first()).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
  })

  test('axe: no serious/critical violations on module shells', async ({ page }) => {
    const modules: Array<{ button: string; title: string }> = [
      { button: '数据源管理', title: '#ds-title' },
      { button: '本体建模', title: '#onto-title' },
      { button: '智能问数', title: '#ask-title' }
    ]
    for (const mod of modules) {
      await page.goto('/')
      await page.getByRole('button', { name: mod.button }).click()
      await expect(page.locator(mod.title)).toBeVisible()
      const results = await new AxeBuilder({ page })
        .withTags(['wcag2a', 'wcag2aa'])
        .analyze()
      const blocking = results.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious')
      expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([])
    }
  })

  test('ask critical path exposes landmarks and composer semantics', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '智能问数' }).click()
    await expect(page.getByRole('heading', { name: '智能问数', level: 1 })).toBeVisible()
    const thread = page.getByRole('log', { name: '问数对话记录' })
    await expect(thread).toBeVisible()
    await expect(thread).toHaveAttribute('aria-live', 'polite')
    await expect(page.getByRole('group', { name: '默认问题' })).toBeVisible()
    const composer = page.getByRole('region', { name: '提问区' })
    await expect(composer).toBeVisible()
    await expect(page.getByRole('textbox', { name: '问题输入' })).toBeVisible()
    await expect(page.getByRole('button', { name: '发送问题' })).toBeDisabled()
  })

  test('rate an answer, suggest a fix, and handle the suggestion', async ({ page }) => {
    const question = '最近一天高危告警最多的资产'
    const suggestions: Array<Record<string, unknown>> = []
    const ratings: unknown[] = []
    const asked: Array<Record<string, unknown>> = []

    await page.route('**/api/v1/llm/config', (route) =>
      route.fulfill({
        json: { configured: true, apiKeySet: true, protocol: 'openai', model: 'qwen', baseUrl: 'http://llm.local' }
      })
    )
    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({
        json: { name: 'root', roles: ['ADMIN', 'QUERY'], securityEnabled: false, jwtEnabled: false, catalogAcl: false, catalogs: [] }
      })
    )
    await page.route('**/api/v1/ask', (route) => {
      asked.push(route.request().postDataJSON())
      const events: Array<[string, unknown]> = [
        ['run', { runId: 'run-1', conversationId: 'conv-1' }],
        ['answer', { answer: 'web-01 最多，5 条', sql: 'SELECT 1', columns: [], rows: [], rowCount: 0 }],
        ['done', {}]
      ]
      return route.fulfill({
        contentType: 'text/event-stream',
        body: events.map(([name, data]) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`).join('')
      })
    })
    await page.route('**/api/v1/ask/runs/run-1/rating', (route) => {
      const { rating } = route.request().postDataJSON()
      ratings.push(rating)
      return route.fulfill({ json: { run: { id: 'run-1' }, rating } })
    })
    await page.route('**/api/v1/ask/suggestions**', (route) => {
      const request = route.request()
      if (request.method() === 'POST') {
        const body = request.postDataJSON()
        const created = {
          id: suggestions.length + 1,
          runId: body.runId,
          runQuestion: body.runId ? question : null,
          userName: 'root',
          content: body.content,
          status: 'open',
          disposition: null,
          reply: null,
          handledBy: null,
          createdAt: 1_794_000_000_000,
          updatedAt: 1_794_000_000_000
        }
        suggestions.push(created)
        return route.fulfill({ json: created })
      }
      if (request.method() === 'PUT') {
        const patch = request.postDataJSON()
        Object.assign(suggestions[0], patch, {
          disposition: patch.disposition || null,
          reply: patch.reply || null,
          handledBy: 'root'
        })
        return route.fulfill({ json: suggestions[0] })
      }
      return route.fulfill({ json: suggestions })
    })

    await page.goto('/')
    await page.getByRole('button', { name: '智能问数' }).click()
    await page.getByRole('textbox', { name: '问题输入' }).fill(question)
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page.getByText('web-01 最多，5 条')).toBeVisible()

    const feedback = page.getByRole('group', { name: '评价这次问数' })
    const useful = feedback.getByRole('button', { name: '有用' })
    await useful.click()
    await expect(useful).toHaveAttribute('aria-pressed', 'true')
    expect(ratings).toEqual(['up'])

    await feedback.getByRole('button', { name: '提建议' }).click()
    await feedback.getByRole('textbox').fill('高危告警应该按 level >= 4 统计')
    await feedback.getByRole('button', { name: '提交建议' }).click()
    await expect(feedback.getByRole('status')).toContainText('建议已提交')
    expect(suggestions[0]).toMatchObject({ runId: 'run-1', content: '高危告警应该按 level >= 4 统计' })

    const views = page.getByRole('navigation', { name: '问数视图' })
    await views.getByRole('button', { name: '建议' }).click()
    const item = page.getByRole('list', { name: '建议列表' }).getByRole('listitem')
    await expect(item).toHaveCount(1)
    await expect(item).toContainText('待处理')
    await expect(item).toContainText(question)
    await expect(page.getByRole('region', { name: '提问区' })).toBeHidden()

    await item.getByRole('button', { name: '采纳', exact: true }).click()
    await item.getByRole('combobox').selectOption('ontology')
    await item.getByRole('textbox').fill('已改派生概念')
    await item.getByRole('button', { name: '确认采纳' }).click()
    await expect(item).toContainText('已采纳')
    await expect(item).toContainText('去向：本体')
    await expect(item).toContainText('root 答复：已改派生概念')

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
    const blocking = results.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious')
    expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([])

    await views.getByRole('button', { name: '对话' }).click()
    await expect(page.getByText('web-01 最多，5 条')).toBeVisible()
    await page.getByRole('textbox', { name: '问题输入' }).fill('只看生产环境')
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page.getByRole('article', { name: '回合 2' })).toBeVisible()
    expect(asked.map((body) => body.conversationId)).toEqual([undefined, 'conv-1'])
  })

  test('desktop shell visual baseline', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '智能问数' }).click()
    await expect(page.locator('#ask-title')).toBeVisible()
    await stabilizeFonts(page)
    await expect(page).toHaveScreenshot('ask-desktop.png', {
      fullPage: true
    })
  })

  test('mobile shell visual baseline', async ({ page }) => {
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/')
    await page.getByRole('button', { name: '数据源管理' }).click()
    await expect(page.locator('#ds-title')).toBeVisible()
    await stabilizeFonts(page)
    await expect(page).toHaveScreenshot('datasource-mobile.png', {
      fullPage: true
    })
  })
})
