import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Assignment } from '../api/types'
import AssignmentView from './AssignmentView.vue'

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  downloadEvaluationCases: vi.fn(),
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('../api/client', () => ({
  api: { get: mocks.apiGet, post: mocks.apiPost },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
  downloadEvaluationCases: mocks.downloadEvaluationCases,
}))

function buttonWith(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(button => button.text().includes(label))
}

async function mountView(): Promise<VueWrapper> {
  mocks.apiGet.mockImplementation((url: string) => {
    if (url === '/assignments') {
      return Promise.resolve({
        data: { data: [{ id: 501, classId: 101, title: '第一单元作业', status: 'IMPORTED', questionIds: [401] } as Assignment] },
      })
    }
    if (url === '/classes') {
      return Promise.resolve({ data: { data: [{ id: 101, classCode: 'C-1', name: '七年级一班', studentCount: 30 }] } })
    }
    if (url === '/questions') {
      return Promise.resolve({ data: { data: [{ id: 401, questionCode: 'Q1', type: 'FILL_BLANK', content: '题1', totalScore: 10, primaryKnowledgePointId: 301, acceptedAnswers: ['2'], rubricItems: [] }] } })
    }
    return Promise.reject(new Error(`未预期的请求 ${url}`))
  })
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
