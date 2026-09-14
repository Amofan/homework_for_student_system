import { expect, test } from '@playwright/test'

test('教师登录后可查看作业、复核队列与学情画像', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('教师账号').fill('demo')
  await page.getByLabel('登录密码').fill('MathDemo!2026')
  await page.getByRole('button', { name: '登录并开始批改' }).click()

  await expect(page.getByRole('heading', { name: '今天，从哪份作业开始？' })).toBeVisible()
  await expect(page.getByText('一元一次方程课堂巩固')).toBeVisible()

  await page.getByRole('link', { name: /教师复核/ }).click()
  await expect(page.getByText('李沐').or(page.getByText('张晨'))).toBeVisible()
  await expect(page.getByText('AI 建议')).toBeVisible()

  await page.getByRole('link', { name: /学情分析/ }).click()
  await expect(page.getByRole('heading', { name: '知识点掌握度' })).toBeVisible()
  await expect(page.getByText('高频问题')).toBeVisible()
})
