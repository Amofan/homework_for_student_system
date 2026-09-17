import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Question } from '../api/types'
import QuestionView from './QuestionView.vue'

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('../api/client', () => ({
  api: { get: mocks.apiGet, post: mocks.apiPost },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
}))

const createObjectURL = vi.fn(() => 'blob:mock')
const revokeObjectURL = vi.fn()

function question(overrides: Partial<Question> = {}): Question {
  return {
    id: 401, questionCode: 'Q-001', type: 'SOLUTION', content: '解方程 $2x+1=5$', totalScore: 10,
    primaryKnowledgePointId: 301, acceptedAnswers: [], rubricItems: [], assets: [],
    ...overrides,
  }
}

async function mountView(item: Question): Promise<VueWrapper> {
  mocks.apiGet.mockImplementation((url: string) => {
    if (url === '/questions') return Promise.resolve({ data: { data: [item] } })
    if (url === '/knowledge-points') return Promise.resolve({ data: { data: [] } })
    // 配图走带鉴权的私有读取：PrivateImage 取回 Blob 再交给 <img>。
    if (url.startsWith('/teacher/files/')) return Promise.resolve({ data: new Blob([]) })
    return Promise.reject(new Error(`未预期的请求 ${url}`))
  })
  const wrapper = mount(QuestionView)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  ;(URL as unknown as { createObjectURL: unknown }).createObjectURL = createObjectURL
  ;(URL as unknown as { revokeObjectURL: unknown }).revokeObjectURL = revokeObjectURL
})

describe('QuestionView 题目配图', () => {
  it('只渲染 STEM_FIGURE，并在题干之后', async () => {
    const wrapper = await mountView(question({
      assets: [
        { id: 1, fileId: 7001, role: 'STEM_FIGURE', sortOrder: 1 },
        { id: 2, fileId: 7002, role: 'SOURCE_CROP', sortOrder: 2 },
        { id: 3, fileId: 7003, role: 'REFERENCE_IMAGE', sortOrder: 3 },
      ],
    }))

    const figures = wrapper.findAll('.question-figure')
    expect(figures).toHaveLength(1)
    // 来源裁剪图与参考答案图是校对信息，不该出现在题库页上。
    expect(figures[0].find('img').attributes('src')).toBe('blob:mock')

    // 题图排在题干之后：文字与公式是主体，图是补充说明。
    const container = wrapper.find('.formula-text')
    expect(container.find('.question-figure').exists()).toBe(true)
    expect(container.element.firstElementChild?.className).not.toBe('question-figure')

    // 只请求了题图那一张，另外两种角色的文件根本没被读取。
    const urls = mocks.apiGet.mock.calls.map(call => call[0])
    expect(urls).toContain('/teacher/files/7001')
    expect(urls).not.toContain('/teacher/files/7002')
    expect(urls).not.toContain('/teacher/files/7003')
  })

  it('没有配图时不渲染空图块', async () => {
    const wrapper = await mountView(question())

    expect(wrapper.findAll('.question-figure')).toHaveLength(0)
    // 题干本身照旧渲染。
    expect(wrapper.find('.formula-text').text()).toContain('解方程')
  })
})
