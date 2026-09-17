import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Assignment } from '../api/types'
import AssignmentView from './AssignmentView.vue'

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  downloadEvaluationCases: vi.fn(),
  publishAssignment: vi.fn(),
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('../api/client', () => ({
  api: { get: mocks.apiGet, post: mocks.apiPost },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
  downloadEvaluationCases: mocks.downloadEvaluationCases,
  publishAssignment: mocks.publishAssignment,
}))

function buttonWith(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(button => button.text().includes(label))
}

async function mountView(overrides: Partial<Assignment> = {}): Promise<VueWrapper> {
  const draft: Assignment = {
    id: 501, classId: 101, title: '第一单元作业', status: 'DRAFT', version: 0, questionIds: [401],
    ...overrides,
  }
  mocks.apiGet.mockImplementation((url: string) => {
    if (url === '/assignments') {
      return Promise.resolve({ data: { data: [draft] } })
    }
    if (url === '/classes') {
      return Promise.resolve({ data: { data: [{ id: 101, classCode: 'C-1', name: '七年级一班', studentCount: 30 }] } })
    }
    if (url === '/questions') {
      return Promise.resolve({ data: { data: [{ id: 401, questionCode: 'Q1', type: 'FILL_BLANK', content: '题1', totalScore: 10, primaryKnowledgePointId: 301, acceptedAnswers: ['2'], rubricItems: [], assets: [] }] } })
    }
    return Promise.reject(new Error(`未预期的请求 ${url}`))
  })
  // Teleport 必须打桩：Element Plus 的弹窗默认传送到 body，
  // 不打桩就只能在 document.body 上找按钮，测试会变得又脆又难读。
  const wrapper = mount(AssignmentView, { global: { stubs: { RouterLink: true } } })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('AssignmentView 导出评测样本', () => {
  it('导出成功后报告实际导出的条数', async () => {
    mocks.downloadEvaluationCases.mockResolvedValue({ reviewed: 12, exported: 10, skippedMissingAiError: 0 })
    const wrapper = await mountView()

    await buttonWith(wrapper, '导出评测样本')!.trigger('click')
    await flushPromises()

    expect(mocks.downloadEvaluationCases).toHaveBeenCalledWith(501)
    expect(mocks.messages.success).toHaveBeenCalledWith('已导出 10 条评测样本')
    expect(mocks.messages.warning).not.toHaveBeenCalled()
  })

  it('有样本被剔除时额外警告，不能只报成功', async () => {
    // 剔除是静默的：文件本身看不出少了样本，不警告教师就会把样本量当成了全量
    mocks.downloadEvaluationCases.mockResolvedValue({ reviewed: 12, exported: 10, skippedMissingAiError: 2 })
    const wrapper = await mountView()

    await buttonWith(wrapper, '导出评测样本')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.warning).toHaveBeenCalledWith(
      '另有 2 条历史样本因缺少模型原始错因未导出，样本量请以导出结果为准',
    )
  })

  it('没有可导出的样本时给出说明而不是报成功', async () => {
    mocks.downloadEvaluationCases.mockResolvedValue({ reviewed: 0, exported: 0, skippedMissingAiError: 0 })
    const wrapper = await mountView()

    await buttonWith(wrapper, '导出评测样本')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.success).not.toHaveBeenCalled()
    expect(mocks.messages.warning).toHaveBeenCalledWith('这份作业还没有已复核的 AI 样本')
  })

  it('导出失败时展示后端原因并恢复按钮', async () => {
    mocks.downloadEvaluationCases.mockRejectedValue(new Error('作业不存在或无权访问'))
    const wrapper = await mountView()

    await buttonWith(wrapper, '导出评测样本')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.error).toHaveBeenCalledWith('作业不存在或无权访问')
    expect(buttonWith(wrapper, '导出评测样本')!.attributes('disabled')).toBeUndefined()
  })

  it('导出过程中按钮保持禁用，避免重复下载', async () => {
    let release: (value: unknown) => void = () => {}
    mocks.downloadEvaluationCases.mockReturnValue(new Promise(resolve => { release = resolve }))
    const wrapper = await mountView()

    await buttonWith(wrapper, '导出评测样本')!.trigger('click')
    await flushPromises()
    expect(buttonWith(wrapper, '导出评测样本')!.attributes('disabled')).toBeDefined()

    release({ reviewed: 1, exported: 1, skippedMissingAiError: 0 })
    await flushPromises()
    expect(buttonWith(wrapper, '导出评测样本')!.attributes('disabled')).toBeUndefined()
  })
})

describe('AssignmentView 发布作业', () => {
  it('发布时把当前版本号回传给服务端', async () => {
    mocks.publishAssignment.mockResolvedValue({ id: 501, classId: 101, title: '第一单元作业', status: 'PUBLISHED', version: 1, questionIds: [401] })
    const wrapper = await mountView({ version: 3 })

    await buttonWith(wrapper, '发布给班级')!.trigger('click')
    await flushPromises()
    await buttonWith(wrapper, '确认发布')!.trigger('click')
    await flushPromises()

    // 版本号来自页面上读到的数据，不带它服务端就无法发现并发修改。
    expect(mocks.publishAssignment).toHaveBeenCalledWith(501, 3, undefined)
    expect(mocks.messages.success).toHaveBeenCalledWith('作业已发布，学生可在“我的作业”中看到')
  })

  it('发布时带上所选的截止时间', async () => {
    mocks.publishAssignment.mockResolvedValue({ id: 501, classId: 101, title: '第一单元作业', status: 'PUBLISHED', version: 1, questionIds: [401] })
    const wrapper = await mountView()

    await buttonWith(wrapper, '发布给班级')!.trigger('click')
    await flushPromises()
    await wrapper.find('input[type="datetime-local"]').setValue('2030-01-02T03:04')
    await flushPromises()
    await buttonWith(wrapper, '确认发布')!.trigger('click')
    await flushPromises()

    const [, version, dueAt] = mocks.publishAssignment.mock.calls.at(-1)!
    expect(version).toBe(0)
    // datetime-local 给的是本地时间，提交前必须转成 ISO 瞬时；
    // 断言的是"同一个时刻"，所以用本地解析结果比对，不写死 UTC 字符串（测试机时区会影响它）。
    expect(dueAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/)
    expect(new Date(dueAt).getTime()).toBe(new Date('2030-01-02T03:04').getTime())
  })

  it('截止时间早于当前时间时不允许确认发布', async () => {
    const wrapper = await mountView()

    await buttonWith(wrapper, '发布给班级')!.trigger('click')
    await flushPromises()
    await wrapper.find('input[type="datetime-local"]').setValue('2000-01-01T00:00')
    await flushPromises()

    expect(wrapper.text()).toContain('截止时间不能早于当前时间')
    expect(buttonWith(wrapper, '确认发布')!.attributes('disabled')).toBeDefined()
    expect(mocks.publishAssignment).not.toHaveBeenCalled()
  })

  it('已发布的作业不再显示发布按钮', async () => {
    const wrapper = await mountView({ status: 'PUBLISHED', publishedAt: '2026-09-16T00:00:00Z', version: 1 })

    expect(buttonWith(wrapper, '发布给班级')).toBeUndefined()
    expect(wrapper.text()).toContain('已发布')
  })

  it('没有题目的作业不能发布', async () => {
    const wrapper = await mountView({ questionIds: [] })

    const publish = buttonWith(wrapper, '发布给班级')
    expect(publish!.attributes('disabled')).toBeDefined()
    expect(publish!.attributes('title')).toBe('作业至少需要一道题目才能发布')
  })

  it('发布失败时展示服务端原因', async () => {
    mocks.publishAssignment.mockRejectedValue(new Error('作业已被其他操作修改，请刷新后重试'))
    const wrapper = await mountView()

    await buttonWith(wrapper, '发布给班级')!.trigger('click')
    await flushPromises()
    await buttonWith(wrapper, '确认发布')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.error).toHaveBeenCalledWith('作业已被其他操作修改，请刷新后重试')
  })
})
