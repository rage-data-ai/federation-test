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
  /** 同一 handler 闭包；各用例改这个值即可切换首次配置 / 登录。 */
  const authStub = {
    needsSetup: false,
    me: {
      name: 'demo',
      displayName: 'demo',
      roles: ['ADMIN', 'QUERY'],
      securityEnabled: true,
      jwtEnabled: false,
      catalogAcl: false,
      catalogs: ['*']
    } as Record<string, unknown> | null
  }

  test.beforeEach(async ({ page }) => {
    authStub.needsSetup = false
    authStub.me = {
      name: 'demo',
      displayName: 'demo',
      roles: ['ADMIN', 'QUERY'],
      securityEnabled: true,
      jwtEnabled: false,
      catalogAcl: false,
      catalogs: ['*']
    }
    await page.addInitScript(() => {
      localStorage.setItem('theme-mode', 'light')
    })
    // 鉴权始终开启：默认 stub 成「已有管理员 + 已登录」，避免打到网关 401 被踢回登录页。
    await page.route('**/api/v1/auth/setup-status', (route) =>
      route.fulfill({ json: { needsSetup: authStub.needsSetup } })
    )
    await page.route('**/api/v1/auth/me', (route) => {
      if (!authStub.me) {
        return route.fulfill({ status: 401, json: { message: '缺少 Authorization: Bearer <token>' } })
      }
      return route.fulfill({ json: authStub.me })
    })
    await page.route('**/api/v1/llm/config', (route) =>
      route.fulfill({
        json: { configured: true, apiKeySet: true, protocol: 'openai', model: 'qwen', baseUrl: 'http://llm.local' }
      })
    )
    await page.route('**/api/v1/catalogs', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/examples', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/metadata**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/ask/conversations**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/ask/knowledge**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/ask/prompts**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/ontology/**', (route) => route.fulfill({ json: [] }))
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

  test('llm dialog accepts any model name, not only presets', async ({ page }) => {
    await page.goto('/')
    await page.getByRole('button', { name: '大模型配置' }).click()
    const dialog = page.getByRole('dialog', { name: '大模型' })
    await expect(dialog).toBeVisible()
    const model = dialog.getByLabel('模型')
    await expect(model).toHaveAttribute('list', 'llm-model-presets')
    await model.fill('my-local-qwen')
    await expect(model).toHaveValue('my-local-qwen')
    await dialog.getByRole('button', { name: 'GPT-4o' }).click()
    await expect(model).toHaveValue('gpt-4o')
    await expect(dialog.getByLabel('接口地址')).toHaveValue('https://api.openai.com/v1')
    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
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

    authStub.me = {
      name: 'root',
      roles: ['ADMIN', 'QUERY'],
      securityEnabled: true,
      jwtEnabled: false,
      catalogAcl: false,
      catalogs: ['*']
    }
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

    await page.route('**/api/v1/ask/knowledge**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/ask/prompts**', (route) => route.fulfill({ json: [] }))

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

    await views.getByRole('button', { name: '提示词' }).click()
    await expect(page.getByRole('form', { name: '新增提示词' })).toBeVisible()

    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
    const blocking = results.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious')
    expect(blocking, JSON.stringify(blocking, null, 2)).toEqual([])

    await page.getByRole('navigation', { name: '模块' }).getByRole('button', { name: '知识库' }).click()
    await expect(page.getByRole('form', { name: '新增知识' })).toBeVisible()

    await page.getByRole('navigation', { name: '模块' }).getByRole('button', { name: '智能问数' }).click()
    await views.getByRole('button', { name: '对话' }).click()
    await expect(page.getByText('web-01 最多，5 条')).toBeVisible()
    await page.getByRole('textbox', { name: '问题输入' }).fill('只看生产环境')
    await page.getByRole('button', { name: '发送问题' }).click()
    await expect(page.getByRole('article', { name: '回合 2' })).toBeVisible()
    expect(asked.map((body) => body.conversationId)).toEqual([undefined, 'conv-1'])
  })

  test('without login cannot open the console shell', async ({ page }) => {
    authStub.needsSetup = false
    authStub.me = null
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '登录控制台', level: 1 })).toBeVisible()
    await expect(page.locator('#main-stage')).toHaveCount(0)
    await expect(page.getByRole('navigation', { name: '模块' })).toHaveCount(0)
  })

  test('anonymous me response stays on login', async ({ page }) => {
    authStub.needsSetup = false
    authStub.me = {
      name: 'anonymous',
      roles: [],
      securityEnabled: false,
      jwtEnabled: false,
      catalogAcl: false,
      catalogs: []
    }
    await page.goto('/')
    await expect(page.getByRole('heading', { name: '登录控制台', level: 1 })).toBeVisible()
    await expect(page.locator('#main-stage')).toHaveCount(0)
  })

  test('first-time setup creates the admin account', async ({ page }) => {
    authStub.needsSetup = true
    authStub.me = null
    await page.route('**/api/v1/auth/setup', (route) => {
      authStub.needsSetup = false
      authStub.me = {
        name: 'admin',
        displayName: '管理员',
        localAccount: true,
        roles: ['ADMIN', 'QUERY'],
        securityEnabled: true,
        jwtEnabled: false,
        catalogAcl: false,
        catalogs: ['*']
      }
      return route.fulfill({
        json: {
          token: 'tok-admin',
          expiresAt: 9,
          user: {
            id: 1,
            username: 'admin',
            displayName: '管理员',
            roles: ['ADMIN', 'QUERY'],
            status: 'active',
            createdAt: 1,
            lastLoginAt: 1
          }
        }
      })
    })

    await page.goto('/')
    await expect(page.getByRole('heading', { name: '创建管理员', level: 1 })).toBeVisible()
    const submit = page.getByRole('button', { name: '创建并进入' })
    // 未填完也可点；提交时给出明确错误，而不是一直灰掉。
    await expect(submit).toBeEnabled()
    await submit.click()
    await expect(page.getByRole('alert')).toContainText('用户名至少 3 位')

    await page.getByLabel('用户名').fill('admin')
    await page.getByLabel('显示名（可选）').fill('管理员')
    await page.getByLabel('密码', { exact: true }).fill('admin-pass-1')
    await page.getByLabel('确认密码').fill('admin-pass-1')
    await expect(submit).toBeEnabled()
    await submit.click()
    await expect(page.getByRole('navigation', { name: '模块' })).toBeVisible()
    await expect(page.getByRole('button', { name: '身份：admin' })).toContainText('管理员')
  })

  test('setup submit reads autofilled password fields', async ({ page }) => {
    authStub.needsSetup = true
    authStub.me = null
    await page.route('**/api/v1/auth/setup', (route) => {
      authStub.needsSetup = false
      authStub.me = {
        name: 'autofill',
        displayName: 'autofill',
        localAccount: true,
        roles: ['ADMIN', 'QUERY'],
        securityEnabled: true,
        jwtEnabled: false,
        catalogAcl: false,
        catalogs: ['*']
      }
      return route.fulfill({
        json: {
          token: 'tok-auto',
          expiresAt: 9,
          user: {
            id: 2,
            username: 'autofill',
            displayName: 'autofill',
            roles: ['ADMIN', 'QUERY'],
            status: 'active',
            createdAt: 1,
            lastLoginAt: 1
          }
        }
      })
    })

    await page.goto('/')
    await expect(page.getByRole('heading', { name: '创建管理员', level: 1 })).toBeVisible()
    // 模拟浏览器自动填充：只改 DOM value，不派发 input，旧逻辑会让按钮一直 disabled。
    await page.locator('input[name="username"]').evaluate((el) => {
      ;(el as HTMLInputElement).value = 'autofill'
    })
    await page.locator('input[name="password"]').evaluate((el) => {
      ;(el as HTMLInputElement).value = 'autofill-pass'
    })
    await page.locator('input[name="confirm"]').evaluate((el) => {
      ;(el as HTMLInputElement).value = 'autofill-pass'
    })
    await page.getByRole('button', { name: '创建并进入' }).click()
    await expect(page.getByRole('navigation', { name: '模块' })).toBeVisible()
  })

  test('gateway that requires login: sign in, manage users, sign out, and expire', async ({ page }) => {
    const alice = {
      name: 'alice',
      displayName: '爱丽丝',
      localAccount: true,
      roles: ['ADMIN', 'QUERY'],
      securityEnabled: true,
      jwtEnabled: false,
      catalogAcl: false,
      catalogs: ['*']
    }
    const accounts = [
      { id: 1, username: 'alice', displayName: '爱丽丝', roles: ['ADMIN', 'QUERY'], status: 'active', createdAt: 1, lastLoginAt: 2 },
      { id: 2, username: 'bob', displayName: 'bob', roles: ['QUERY'], status: 'active', createdAt: 3, lastLoginAt: 0 }
    ]
    let sessionValid = true
    const unauthorized = { status: 401, json: { message: '无效的访问令牌' } }
    const signedIn = (route: import('@playwright/test').Route) =>
      sessionValid && route.request().headers().authorization === 'Bearer tok-alice'

    authStub.needsSetup = false
    authStub.me = null
    await page.route('**/api/v1/auth/me', (route) => {
      if (signedIn(route)) {
        authStub.me = alice
        return route.fulfill({ json: alice })
      }
      return route.fulfill(unauthorized)
    })
    await page.route('**/api/v1/auth/login', (route) => {
      const body = route.request().postDataJSON() as { username: string; password: string }
      return body.password === 'alice-pass-1'
        ? route.fulfill({ json: { token: 'tok-alice', expiresAt: 9, user: accounts[0] } })
        : route.fulfill({ status: 401, json: { message: '用户名或密码错误' } })
    })
    await page.route('**/api/v1/auth/logout', (route) => route.fulfill({ json: { success: true, revoked: true } }))
    await page.route('**/api/v1/users', (route) => route.fulfill(signedIn(route) ? { json: accounts } : unauthorized))
    await page.route('**/api/v1/users/2', (route) => {
      accounts[1] = { ...accounts[1], ...(route.request().postDataJSON() as object) }
      return route.fulfill({ json: accounts[1] })
    })

    await page.goto('/')
    await expect(page.getByRole('heading', { name: '登录控制台', level: 1 })).toBeVisible()
    await expect(page.getByRole('navigation', { name: '模块' })).toHaveCount(0)
    const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
    expect(results.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious')).toEqual([])

    await page.getByLabel('用户名').fill('alice')
    await page.getByLabel('密码', { exact: true }).fill('wrong-pass-1')
    await page.getByRole('button', { name: '登录', exact: true }).last().click()
    await expect(page.getByRole('alert')).toHaveText('用户名或密码错误')
    await expect(page.getByRole('heading', { name: '登录控制台', level: 1 })).toBeVisible()

    await page.getByLabel('密码', { exact: true }).fill('alice-pass-1')
    await page.getByRole('button', { name: '登录', exact: true }).last().click()
    await expect(page.getByRole('navigation', { name: '模块' })).toBeVisible()
    await expect(page.getByRole('button', { name: '身份：alice' })).toContainText('爱丽丝')

    await page.getByRole('button', { name: '用户管理' }).click()
    const bob = page.getByRole('row', { name: /bob/ })
    await expect(bob).toContainText('正常')
    await bob.getByRole('button', { name: '禁用' }).click()
    await expect(bob).toContainText('已禁用')
    await expect(page.getByRole('row', { name: /alice/ }).getByRole('button', { name: '禁用' })).toBeDisabled()

    await page.getByRole('button', { name: '身份：alice' }).click()
    const dialog = page.getByRole('dialog', { name: '访问身份' })
    await expect(dialog.getByLabel('原密码')).toBeFocused()
    await dialog.getByRole('button', { name: '退出登录' }).click()
    await expect(page.getByRole('heading', { name: '登录控制台', level: 1 })).toBeVisible()
    await expect(page.getByRole('status')).toHaveCount(0)

    await page.getByLabel('用户名').fill('alice')
    await page.getByLabel('密码', { exact: true }).fill('alice-pass-1')
    await page.getByRole('button', { name: '登录', exact: true }).last().click()
    await page.getByRole('button', { name: '用户管理' }).click()
    await expect(page.getByRole('row', { name: /bob/ })).toBeVisible()
    sessionValid = false
    await page.getByRole('button', { name: '刷新' }).click()
    await expect(page.getByRole('heading', { name: '登录控制台', level: 1 })).toBeVisible()
    await expect(page.getByRole('status')).toHaveText('令牌无效或登录已过期，请重新登录')
  })

  test('register form checks the confirmation before calling the gateway', async ({ page }) => {
    const registered: unknown[] = []
    let token = ''
    authStub.needsSetup = false
    authStub.me = null
    await page.route('**/api/v1/auth/me', (route) => {
      if (token && route.request().headers().authorization === `Bearer ${token}`) {
        authStub.me = {
          name: 'carol',
          displayName: '卡萝',
          localAccount: true,
          roles: ['QUERY'],
          securityEnabled: true,
          jwtEnabled: false,
          catalogAcl: false,
          catalogs: ['*']
        }
        return route.fulfill({ json: authStub.me })
      }
      return route.fulfill({ status: 401, json: { message: '缺少 Authorization: Bearer <token>' } })
    })
    await page.route('**/api/v1/auth/register', (route) => {
      registered.push(route.request().postDataJSON())
      token = 'tok-carol'
      return route.fulfill({ json: { token, expiresAt: 9, user: { id: 3, username: 'carol' } } })
    })

    await page.goto('/')
    await page.getByRole('tab', { name: '注册' }).click()
    await expect(page.getByRole('heading', { name: '注册账号', level: 1 })).toBeVisible()
    await page.getByLabel('用户名').fill('carol')
    await page.getByLabel('显示名（可选）').fill('卡萝')
    await page.getByLabel('密码', { exact: true }).fill('carol-pass-1')
    await page.getByLabel('确认密码').fill('carol-pass-2')
    await expect(page.getByRole('status')).toHaveText('两次输入的密码不一致')
    await expect(page.getByRole('button', { name: '注册并登录' })).toBeDisabled()
    expect(registered).toEqual([])

    await page.getByLabel('确认密码').fill('carol-pass-1')
    await expect(page.getByRole('button', { name: '注册并登录' })).toBeEnabled()
    await page.getByRole('button', { name: '注册并登录' }).click()
    await expect(page.getByRole('navigation', { name: '模块' })).toBeVisible()
    expect(registered).toEqual([{ username: 'carol', password: 'carol-pass-1', displayName: '卡萝' }])
    await expect(page.getByRole('button', { name: '身份：carol' })).toContainText('卡萝')
    await expect(page.getByRole('button', { name: '用户管理' })).toHaveCount(0)
  })

  test('ask history lists own conversations and restores turns', async ({ page }) => {
    const conversations = [
      {
        conversationId: 'conv-1',
        title: '最近一天高危告警最多的资产',
        lastStatus: 'answered',
        runCount: 2,
        createdAt: 1,
        updatedAt: 2
      }
    ]
    const turns = [
      {
        run: {
          id: 'run-1',
          conversationId: 'conv-1',
          userName: 'alice',
          question: '最近一天高危告警最多的资产',
          history: [],
          status: 'answered',
          answer: 'web-01 最多，5 条',
          sql: 'SELECT 1',
          evidence: {},
          error: null,
          createdAt: 1,
          elapsedMs: 10
        },
        rating: null
      },
      {
        run: {
          id: 'run-2',
          conversationId: 'conv-1',
          userName: 'alice',
          question: '只看生产环境',
          history: [],
          status: 'answered',
          answer: '生产环境 3 条',
          sql: null,
          evidence: {},
          error: null,
          createdAt: 2,
          elapsedMs: 8
        },
        rating: 'up'
      }
    ]
    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({
        json: {
          name: 'alice',
          roles: ['QUERY'],
          securityEnabled: true,
          jwtEnabled: true,
          catalogAcl: false,
          catalogs: []
        }
      })
    )
    await page.route('**/api/v1/llm/config', (route) =>
      route.fulfill({
        json: { configured: true, apiKeySet: true, protocol: 'openai', model: 'qwen', baseUrl: 'http://llm.local' }
      })
    )
    // Default module (datasource) loads in parallel; without stubs a secured gateway 401s and kicks to login.
    await page.route('**/api/v1/catalogs', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/examples', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/metadata**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/ask/conversations/conv-1', (route) => route.fulfill({ json: turns }))
    await page.route('**/api/v1/ask/conversations', (route) => {
      if (route.request().method() === 'GET' && !/\/conversations\/[^/?]+/.test(route.request().url())) {
        return route.fulfill({ json: conversations })
      }
      return route.continue()
    })

    await page.goto('/')
    await page.getByRole('button', { name: '智能问数' }).click()
    const history = page.getByRole('complementary', { name: '对话历史' })
    await expect(history).toBeVisible()
    await expect(history.getByRole('button', { name: /最近一天高危告警最多的资产/ })).toBeVisible()
    await history.getByRole('button', { name: /最近一天高危告警最多的资产/ }).click()
    await expect(page.getByRole('article', { name: '回合 1' })).toContainText('web-01 最多，5 条')
    await expect(page.getByRole('article', { name: '回合 2' })).toContainText('生产环境 3 条')
    await expect(history.getByRole('button', { name: /最近一天高危告警最多的资产/ })).toHaveAttribute(
      'aria-current',
      'true'
    )
  })

  test('desktop shell visual baseline', async ({ page }) => {
    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({
        json: { name: 'root', roles: ['ADMIN', 'QUERY'], securityEnabled: true, jwtEnabled: true, catalogAcl: false, catalogs: [] }
      })
    )
    await page.route('**/api/v1/llm/config', (route) =>
      route.fulfill({
        json: { configured: true, apiKeySet: true, protocol: 'openai', model: 'qwen', baseUrl: 'http://llm.local' }
      })
    )
    await page.route('**/api/v1/catalogs', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/examples', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/metadata**', (route) => route.fulfill({ json: [] }))
    await page.route('**/api/v1/ask/conversations', (route) => route.fulfill({ json: [] }))
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
