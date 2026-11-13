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
