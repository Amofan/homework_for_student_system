import { stat } from 'node:fs/promises'

import { expect, test } from '@playwright/test'

/** 三个层级各用一道只属于该层的题目做标记，避免断言落到别的层里。 */
const tiers = [
  { name: '基础巩固层', marker: 'Q-ALG-004' },
  { name: '方法纠错层', marker: 'Q-ALG-002' },
  { name: '综合提升层', marker: 'Q-GEO-001' },
] as const

test('教师登录后可查看作业、复核队列与学情画像，并生成分层练习导出 Word', async ({ page }) => {
  await page.goto('/login')
  await page.getByLabel('教师账号').fill('demo')
  await page.getByLabel('登录密码').fill('MathDemo!2026')
  await page.getByRole('button', { name: '登录并开始批改' }).click()

  await expect(page.getByRole('heading', { name: '今天，从哪份作业开始？' })).toBeVisible()
  await expect(page.getByText('一元一次方程课堂巩固')).toBeVisible()

  // 题库是公式出现的第一站：先在这里确认 $...$ 真的被渲染成了公式节点。
  // 这道题有两个公式段，所以断言 .first()——不加的话严格模式会因为匹配到两个元素而报错。
  await page.getByRole('link', { name: /数学题库/ }).click()
  const equationCard = page.locator('.question-card').filter({ hasText: 'Q-ALG-001' })
  await expect(equationCard.locator('.katex').first()).toBeVisible()
  await expect(equationCard).not.toContainText('$2x+1=5$')

  await page.getByRole('link', { name: /教师复核/ }).click()
  await expect(page.getByText('李沐').or(page.getByText('张晨'))).toBeVisible()
  await expect(page.getByText('AI 建议')).toBeVisible()

  await page.getByRole('link', { name: /学情分析/ }).click()
  await expect(page.getByRole('heading', { name: '知识点掌握度' })).toBeVisible()
  await expect(page.getByText('高频问题')).toBeVisible()

  // 分层练习：页面进入时已默认选中第一个班级与来源作业，不必再操作下拉框。
  await page.getByRole('link', { name: /分层练习/ }).click()
  await expect(page.getByRole('heading', { name: '分层练习' })).toBeVisible()
  await page.getByRole('button', { name: '生成分层练习' }).click()

  // 三层都要有题，且题库充足时不该出现“不会编造题目”的提示。
  for (const tier of tiers) {
    const card = page.locator('article.assignment-card').filter({ hasText: tier.name })
    await expect(card.locator('.question-card')).toHaveCount(5)
    await expect(card.getByText(tier.marker)).toBeVisible()
  }
  await expect(page.locator('.notice-strip')).toHaveCount(0)

  // 草稿阶段导出被禁用，确认之后才放开。
  await expect(page.getByRole('button', { name: '导出 Word' })).toBeDisabled()
  await page.getByRole('button', { name: '确认练习单' }).click()
  // 精确匹配：右上角的成功提示也含这四个字，模糊匹配会撞上两个元素。
  await expect(page.getByText('练习单已确认', { exact: true })).toBeVisible()

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.getByRole('button', { name: '导出 Word' }).click(),
  ])

  expect(download.suggestedFilename()).toMatch(/\.docx$/)
  const filepath = await download.path()
  expect(filepath).toBeTruthy()
  // 文件名对了但内容为空说明导出链路断在服务端，必须连文件一起验。
  expect((await stat(filepath!)).size).toBeGreaterThan(0)
})
