import { defineConfig, devices } from '@playwright/test'

const port = 4173
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  use: {
    baseURL,
    trace: 'on-first-retry',
    colorScheme: 'dark',
    // Reduce cross-host font/glyph flake before comparing pixels.
    reducedMotion: 'reduce'
  },
  expect: {
    toHaveScreenshot: {
      // Slightly looser than 0.03: AA / font hinting differs across runner images.
      maxDiffPixelRatio: 0.08,
      animations: 'disabled',
      caret: 'hide'
    }
  },
  webServer: {
    command: process.env.CI
      ? `npm run preview -- --host 127.0.0.1 --port ${port}`
      : `npm run build && npm run preview -- --host 127.0.0.1 --port ${port}`,
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 120_000
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
