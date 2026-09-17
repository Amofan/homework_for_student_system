import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { AnswerAsset, ReviewQueueItem } from '../api/types'
import ReviewView from './ReviewView.vue'

// jsdom 不实现 createObjectURL，PrivateImage 会落进"加载失败"分支，
// 于是"取了几张图、什么时候回收"就全都无从断言。补上桩之后它渲染的是真实的 <img src>。
const createObjectURL = vi.fn(() => 'blob:mock')
const revokeObjectURL = vi.fn()

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  apiGet: vi.fn(),
  apiPost: vi.fn(),
}))

// 只替换 ElMessage，保留其余导出：模板里的 Element Plus 组件由按需导入插件注入，
// 整包 mock 掉会让 ElSelect/ElOption 变成 undefined。本文件刻意不 stub 下拉框——
// 「最终错因必须是一个真正的下拉框」正是要验证的行为。
vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('../api/client', () => ({
  api: { get: mocks.apiGet, post: mocks.apiPost },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
}))

function queueItem(overrides: Partial<ReviewQueueItem> = {}): ReviewQueueItem {
  return {
    resultId: 701, answerId: 611, studentNo: '001', studentName: '张三',
    questionCode: 'Q1', questionContent: '解方程 $2x+1=5$', answerContent: 'x=3', source: 'AI',
    suggestedScore: 8, totalScore: 10, errorType: 'METHOD_ERROR',
    teacherExplanation: '解法方向有误', studentFeedback: '', scoreDetails: '[]',
    answerAssets: [],
    ...overrides,
  }
}

function asset(overrides: Partial<AnswerAsset> & { fileId: number }): AnswerAsset {
  return { role: 'SOURCE_CROP', sortOrder: 1, pageNo: 1, ...overrides }
}

/** 取私有图片的那几次请求。列表与作业请求混在一起，这里只挑图片路径。 */
function imageFetches(): string[] {
  return mocks.apiGet.mock.calls
    .map(call => call[0] as string)
    .filter(url => url.startsWith('/teacher/files/'))
}

function queueButtons(wrapper: VueWrapper) {
  return wrapper.findAll('.review-list button')
}

/** 第一个下拉框是页头的作业选择器，错因下拉框在复核表单里，即第二个。 */
function errorTypeSelect(wrapper: VueWrapper) {
  const selects = wrapper.findAllComponents({ name: 'ElSelect' })
  expect(selects).toHaveLength(2)
  return selects[1]
}

function buttonWith(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(button => button.text().includes(label))
}

async function mountView(...items: ReviewQueueItem[]): Promise<VueWrapper> {
  const queue = items.length > 0 ? items : [queueItem()]
  mocks.apiGet.mockImplementation((url: string) => {
    if (url === '/assignments') {
      return Promise.resolve({ data: { data: [{ id: 501, classId: 101, title: '第一单元作业', status: 'DRAFT', questionIds: [401] }] } })
    }
    if (url === '/grading/assignments/501/review-queue') {
      return Promise.resolve({ data: { data: queue } })
    }
    if (url.startsWith('/teacher/files/')) {
      return Promise.resolve({ data: new Blob(['x']) })
    }
    return Promise.reject(new Error(`未预期的请求 ${url}`))
  })
  mocks.apiPost.mockResolvedValue({ data: { data: { resultId: queue[0].resultId } } })

  const wrapper = mount(ReviewView, { attachTo: document.body })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
})

// 挂在 body 上的组件不会随测试结束自动清理，累积的 DOM 会让下拉项的查询串到别的用例。
afterEach(() => {
  document.body.innerHTML = ''
})

describe('ReviewView 最终错因', () => {
  it('用固定下拉框选错因，不再接受自由文本', async () => {
    const wrapper = await mountView()

    // 下拉框以模型建议值为初值，教师不改也能看到当前错因
    expect(errorTypeSelect(wrapper).props('modelValue')).toBe('METHOD_ERROR')
    expect(wrapper.find('input[aria-label="最终错因自由文本"]').exists()).toBe(false)
  })

  it('下拉框里是七个固定编码，没有中文标签充当取值', async () => {
    const wrapper = await mountView()
    const options = errorTypeSelect(wrapper).findAllComponents({ name: 'ElOption' })

    // 提交给后端的是 value（编码），label 只用于显示。这一条盯着两者不被颠倒。
    expect(options.map(option => option.props('value'))).toEqual([
      'CORRECT', 'ANSWER_MISMATCH', 'CALCULATION_ERROR', 'METHOD_ERROR',
      'CONCEPT_ERROR', 'INCOMPLETE', 'OTHER',
    ])
    const labels = options.map(option => option.props('label'))
    expect(labels).toEqual(['正确', '答案不一致', '计算错误', '方法错误', '概念错误', '过程不完整', '其他'])
    expect(labels).not.toContain('METHOD_ERROR')
  })

  it('提交的是固定编码而不是中文标签', async () => {
    const wrapper = await mountView()
    await buttonWith(wrapper, '修改')!.trigger('click')
    await errorTypeSelect(wrapper).setValue('CONCEPT_ERROR')
    await flushPromises()

    await buttonWith(wrapper, '确认本条复核')!.trigger('click')
    await flushPromises()

    expect(mocks.apiPost).toHaveBeenCalledTimes(1)
    expect(mocks.apiPost.mock.calls[0][1]).toMatchObject({ decision: 'MODIFY', errorType: 'CONCEPT_ERROR' })
  })

  it('修改或驳回时空错因不允许提交', async () => {
    const wrapper = await mountView()
    await buttonWith(wrapper, '修改')!.trigger('click')
    await errorTypeSelect(wrapper).setValue('')
    await flushPromises()

    expect(buttonWith(wrapper, '确认本条复核')!.attributes('disabled')).toBeDefined()

    await errorTypeSelect(wrapper).setValue('OTHER')
    await flushPromises()
    expect(buttonWith(wrapper, '确认本条复核')!.attributes('disabled')).toBeUndefined()
  })

  it('采纳时不需要额外选错因，沿用建议值即可提交', async () => {
    const wrapper = await mountView()

    expect(buttonWith(wrapper, '确认本条复核')!.attributes('disabled')).toBeUndefined()
  })
})

describe('ReviewView 答案图', () => {
  it('选中的那条才去取答案图，并按学生写的顺序排', async () => {
    const wrapper = await mountView(queueItem({
      answerAssets: [asset({ fileId: 902, pageNo: 1, sortOrder: 1 }), asset({ fileId: 903, pageNo: 2, sortOrder: 2 })],
    }))

    expect(imageFetches()).toEqual(['/teacher/files/902', '/teacher/files/903'])
    // 跨页续写的两块要能看出各自是哪一页的，否则教师没法判断是不是漏看了半道题
    expect(wrapper.findAll('.answer-asset figcaption').map(node => node.text()))
      .toEqual(['第 1 张 · 第 1 页', '第 2 张 · 第 2 页'])
  })

  it('没选中的答卷不会预先取图，切过去才取', async () => {
    const wrapper = await mountView(
      queueItem({ answerAssets: [asset({ fileId: 902 })] }),
      queueItem({ resultId: 702, answerId: 612, studentName: '李四', answerAssets: [asset({ fileId: 905 })] }),
    )
    // 队列一屏几十条，全预取等于把流量和时间花在没人翻开的那几条上
    expect(imageFetches()).toEqual(['/teacher/files/902'])

    await queueButtons(wrapper)[1].trigger('click')
    await flushPromises()

    expect(imageFetches()).toEqual(['/teacher/files/902', '/teacher/files/905'])
  })

  it('切换条目时把上一张图的对象 URL 回收掉', async () => {
    const wrapper = await mountView(
      queueItem({ answerAssets: [asset({ fileId: 902 })] }),
      queueItem({ resultId: 702, answerId: 612, studentName: '李四', answerAssets: [asset({ fileId: 905 })] }),
    )
    revokeObjectURL.mockClear()

    await queueButtons(wrapper)[1].trigger('click')
    await flushPromises()

    // 对象 URL 拿的是一份内存引用，切换时不撤销就会一直留到页面关闭
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock')
  })

  it('离开页面时回收已经取回的图', async () => {
    const wrapper = await mountView(queueItem({ answerAssets: [asset({ fileId: 902 })] }))
    revokeObjectURL.mockClear()

    wrapper.unmount()

    expect(revokeObjectURL).toHaveBeenCalledWith('blob:mock')
  })

  it('没有答案图的答案照常显示识别文字', async () => {
    const wrapper = await mountView(queueItem({ answerAssets: [], answerContent: 'x=3' }))

    expect(imageFetches()).toEqual([])
    expect(wrapper.find('.answer-image-missing').exists()).toBe(true)
    expect(wrapper.find('.student-answer').text()).toContain('x=3')
  })

  it('来源页丢了就照实说，不编一个页码', async () => {
    const wrapper = await mountView(queueItem({
      answerAssets: [asset({ fileId: 902, pageNo: undefined })],
    }))

    expect(wrapper.find('.answer-asset figcaption').text()).toBe('第 1 张 · 来源页未知')
  })
})
