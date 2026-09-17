import { enableAutoUnmount, flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'

import type {
  AnswerCandidate, AnswerPage, AnswerRegion, AnswerReview, Assignment, Question, SubmissionQueue,
} from '../../api/types'
import SubmissionReviewView from './SubmissionReviewView.vue'

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  replace: vi.fn(),
  apiGet: vi.fn(),
  errorCode: vi.fn(),
  listAssignments: vi.fn(),
  getQueue: vi.fn(),
  getReview: vi.fn(),
  processAnswers: vi.fn(),
  patchCandidate: vi.fn(),
  confirmAnswers: vi.fn(),
  getReferences: vi.fn(),
}))

/**
 * 查询参数是这一页的导航状态，所以它必须是真的响应式的：
 * 页面用 `watch(assignmentId/versionId)` 决定什么时候重新拉数据，
 * 用普通对象替身的话"点了队列里另一份答卷会不会重新拉"就永远测不到。
 */
const route = reactive({ query: {} as Record<string, string> })

/**
 * 每个用例结束都卸载掉挂载过的组件。
 *
 * <p>不是整洁癖：这一页的 `route` 是**模块级共享的响应式对象**，而页面用
 * `watch(assignmentId/versionId)` 决定什么时候重新拉数据。前一个用例挂出来的组件
 * 如果还活着，下一个用例改动 `route.query` 时它们会一起重新拉一遍 ——
 * 表现是"这次操作调了几次接口"里混进了十几个别的用例留下的次数，
 * 看起来像被测代码在反复重拉，其实只是旧组件还在监听。
 */
enableAutoUnmount(afterEach)

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ replace: mocks.replace }),
}))

vi.mock('../../api/client', () => ({
  // 视图自己不直接调 `api`，但 PrivateImage 要走它取图片 Blob：
  // 少了这个替身，答案图与页面图都会落进"加载失败"分支，图片相关的断言就全成了空跑。
  api: { get: mocks.apiGet },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
  errorCode: mocks.errorCode,
}))

// 只替换网络调用，保留真实的文案函数：警告文案与状态文案本身就是断言对象。
vi.mock('../../api/teacherAnswerReview', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/teacherAnswerReview')>()),
  listReviewAssignments: mocks.listAssignments,
  getSubmissionQueue: mocks.getQueue,
  getAnswerReview: mocks.getReview,
  processAnswers: mocks.processAnswers,
  patchAnswerCandidate: mocks.patchCandidate,
  confirmAnswers: mocks.confirmAnswers,
  getReferenceAnswers: mocks.getReferences,
}))

// jsdom 不实现 createObjectURL，PrivateImage 会走到"加载失败"分支。
// 补上桩之后它渲染的是真实的 <img src>，于是"答案图是不是学生的那张"也可被验证。
const createObjectURL = vi.fn(() => 'blob:mock')
const revokeObjectURL = vi.fn()

function region(overrides: Partial<AnswerRegion> & { regionId: number }): AnswerRegion {
  return { pageNo: 1, regionType: 'ANSWER_BLOCK', x: 0.1, y: 0.2, width: 0.5, height: 0.1, ...overrides }
}

function page(overrides: Partial<AnswerPage> = {}): AnswerPage {
  return {
    submissionPageId: 8001, pageNo: 1, documentId: 4001, documentPageNo: 1, fileName: '答卷.png',
    pageFileId: 5001, rotatedFileId: 5001, width: 400, height: 600,
    templatePageNo: 1, alignmentConfidence: 0.93,
    regions: [
      region({ regionId: 9001, candidateId: 21, confidence: 0.96, ocrText: 'x=2', cropFileId: 6001 }),
      region({ regionId: 9002, candidateId: 22, confidence: 0.61, ocrText: '2. 解方程' }),
      // 没有被任何候选认领：识别把它的题号漏掉了，只能在页面上看见。
      region({ regionId: 9003, confidence: 0.9, ocrText: '孤立的一段作答' }),
    ],
    ...overrides,
  }
}

function candidate(overrides: Partial<AnswerCandidate> & { candidateId: number }): AnswerCandidate {
  return {
    questionId: 300 + overrides.candidateId, questionCode: String(overrides.candidateId - 20),
    questionOrder: overrides.candidateId - 20, blank: false, reviewStatus: 'PENDING',
    version: 0, regions: [], warnings: [],
    ...overrides,
  }
}

function review(overrides: Partial<AnswerReview> = {}): AnswerReview {
  return {
    versionId: 701, assignmentId: 501, studentId: 91, studentName: '张三', versionNo: 1,
    status: 'NEEDS_REVIEW', confirmed: false,
    warnings: [],
    pages: [page()],
    candidates: [
      candidate({ candidateId: 21, answerText: 'x=2', warnings: ['LOW_CONFIDENCE_FORMULA'], version: 3 }),
      candidate({ candidateId: 22, answerText: '', blank: true, version: 0 }),
    ],
    ...overrides,
  }
}

function queue(): SubmissionQueue {
  return {
    assignmentId: 501, title: '第一单元测验',
    items: [
      {
        versionId: 701, versionNo: 1, studentId: 91, studentNo: '20240101', studentName: '张三',
        status: 'NEEDS_REVIEW', current: true, pageCount: 1, candidateCount: 2, pendingCount: 2,
      },
      {
        versionId: 702, versionNo: 2, studentId: 92, studentNo: '20240102', studentName: '李四',
        status: 'PROCESSING', current: true, pageCount: 1, candidateCount: 0, pendingCount: 0,
      },
    ],
  }
}

function assignment(id: number, title: string): Assignment {
  return { id, classId: 101, title, status: 'PUBLISHED', version: 0, questionIds: [] }
}

function question(id: number, standardAnswer: string): Question {
  return {
    id, questionCode: '1', type: 'SOLUTION', content: '解方程', standardAnswer, totalScore: 8,
    primaryKnowledgePointId: 301, acceptedAnswers: ['2'], rubricItems: [], assets: [],
  }
}

async function mountView(query: Record<string, string> = { assignmentId: '501', versionId: '701' }) {
  route.query = { ...query }
  const wrapper = mount(SubmissionReviewView)
  await flushPromises()
  return wrapper
}

/** 页面上可见的全部文字。用来断言"某句话有没有说给教师听"。 */
function textOf(wrapper: VueWrapper): string {
  return wrapper.text()
}

function queueButtons(wrapper: VueWrapper) {
  return wrapper.findAll('.submission-queue button')
}

function candidateButtons(wrapper: VueWrapper) {
  return wrapper.findAll('.candidate-list button')
}

function buttonWithText(wrapper: VueWrapper, text: string) {
  return wrapper.findAll('button').find(button => button.text().includes(text))
}

beforeEach(() => {
  vi.clearAllMocks()
  route.query = {}
  mocks.replace.mockImplementation((to: { query?: Record<string, string> }) => {
    route.query = { ...(to.query ?? {}) }
    return Promise.resolve()
  })
  mocks.apiGet.mockResolvedValue({ data: new Blob(['x']) })
  mocks.listAssignments.mockResolvedValue([assignment(501, '第一单元测验')])
  mocks.getQueue.mockResolvedValue(queue())
  mocks.getReview.mockResolvedValue(review())
  mocks.patchCandidate.mockResolvedValue(review())
  mocks.processAnswers.mockResolvedValue(review())
  mocks.confirmAnswers.mockResolvedValue(review({ confirmed: true, status: 'CONFIRMED' }))
  mocks.getReferences.mockResolvedValue([question(321, 'x = 2')])
  mocks.errorCode.mockReturnValue(undefined)
  vi.stubGlobal('URL', { ...URL, createObjectURL, revokeObjectURL })
})

describe('教师答卷校对', () => {
  it('没有指定作业时先让教师选一份', async () => {
    const wrapper = await mountView({})

    expect(textOf(wrapper)).toContain('先选一份作业')
    expect(mocks.getQueue).not.toHaveBeenCalled()
  })

  it('队列里显示每份答卷还有多少道题没校对', async () => {
    const wrapper = await mountView()

    expect(mocks.getQueue).toHaveBeenCalledWith(501)
    const [first] = queueButtons(wrapper)
    expect(first.text()).toContain('20240101 张三')
    expect(first.text()).toContain('还有 2/2 道题没校对')
    // 还没识别的那一份显示页数：它连"几道题"都还不知道。
    expect(queueButtons(wrapper)[1].text()).toContain('1 页，还没识别')
  })

  it('点开队列里的另一份答卷会写进查询参数', async () => {
    const wrapper = await mountView()

    await queueButtons(wrapper)[1].trigger('click')
    await flushPromises()

    expect(mocks.replace).toHaveBeenCalledWith({ query: { assignmentId: '501', versionId: '702' } })
    // 换了一份答卷就要重新拉一份：不能停在上一份的数据上。
    expect(mocks.getReview).toHaveBeenLastCalledWith(702)
  })

  it('整份答卷的问题与单道题的问题分开显示', async () => {
    mocks.getReview.mockResolvedValue(review({
      warnings: [{ code: 'PAGE_DUPLICATE', detail: '第 2 页和第 5 页都对上了模板第 2 页' }],
    }))
    const wrapper = await mountView()

    // 整份的问题：说清是哪一页，处理动作是"重拍"。
    expect(wrapper.find('.warning-list').text()).toContain('第 2 页和第 5 页都对上了模板第 2 页')
    // 单道题的问题：处理动作是"改这道题"。
    expect(candidateButtons(wrapper)[0].text()).toContain('公式识别置信度偏低')
  })

  it('没归到任何题的作答会留在页面上', async () => {
    const wrapper = await mountView()

    const block = wrapper.find('.unassigned-block')
    expect(block.text()).toContain('孤立的一段作答')
    // 归属了的那两块不在这个列表里，否则教师会以为它们也没归位。
    expect(block.text()).not.toContain('x=2')
  })

  it('点区域框会选中它所属的那道题', async () => {
    const wrapper = await mountView()

    expect(candidateButtons(wrapper)[0].classes()).toContain('active')
    // 第二块区域属于第 22 号候选。
    await wrapper.findAll('.region-box')[1].trigger('pointerdown')
    await flushPromises()

    expect(candidateButtons(wrapper)[1].classes()).toContain('active')
  })

  it('只剩没校对完的候选时才说还差几道', async () => {
    mocks.getReview.mockResolvedValue(review({
      candidates: [
        candidate({ candidateId: 21, answerText: 'x=2', reviewStatus: 'CONFIRMED', version: 4 }),
        candidate({ candidateId: 22, blank: true, version: 1 }),
      ],
    }))
    const wrapper = await mountView()

    expect(textOf(wrapper)).toContain('已校对 1/2 道题')
  })

  it('保存会把教师手里的版本号一起发出去', async () => {
    const wrapper = await mountView()

    await wrapper.find('textarea').setValue('x = 2')
    await buttonWithText(wrapper, '保存')?.trigger('click')
    await flushPromises()

    expect(mocks.patchCandidate).toHaveBeenCalledWith(701, 21, {
      version: 3, answerText: 'x = 2', answerLatex: '', blank: false,
    })
  })

  it('确认这道题会把文字与"已校对"一起提交', async () => {
    const wrapper = await mountView()

    await buttonWithText(wrapper, '确认这道题')?.trigger('click')
    await flushPromises()

    // 两次请求之间断网不会留下一半的改动：文字与状态在同一次写里。
    expect(mocks.patchCandidate).toHaveBeenCalledWith(701, 21,
      expect.objectContaining({ version: 3, reviewStatus: 'CONFIRMED' }))
  })

  it('标为空白的确认会把识别残文清掉', async () => {
    const wrapper = await mountView()

    await buttonWithText(wrapper, '标为空白并确认')?.trigger('click')
    await flushPromises()

    // 留着一段残文却又说"空白"，入库的就是自相矛盾的那一份。
    expect(mocks.patchCandidate).toHaveBeenCalledWith(701, 21, {
      version: 3, blank: true, answerText: '', answerLatex: '', reviewStatus: 'CONFIRMED',
    })
  })

  it('改派到别的题发的是对方那道题的题目 id', async () => {
    const wrapper = await mountView()

    await buttonWithText(wrapper, '换成第 2 题')?.trigger('click')
    await flushPromises()

    // 第 22 号候选对应 questionId 322：发的是它，而不是"移动"这种没有目标的说法。
    expect(mocks.patchCandidate).toHaveBeenCalledWith(701, 21, { version: 3, questionId: 322 })
    expect(mocks.messages.success).toHaveBeenCalledWith(
      expect.stringContaining('互换'))
  })

  it('改派列表里不包含自己', async () => {
    const wrapper = await mountView()

    expect(wrapper.find('.reassign-block').text()).toContain('换成第 2 题')
    expect(wrapper.find('.reassign-block').text()).not.toContain('换成第 1 题')
  })

  it('版本对不上会重新拉一份，而不是让教师一直撞同一个冲突', async () => {
    const wrapper = await mountView()
    mocks.patchCandidate.mockRejectedValueOnce(new Error('这份答卷已经被别人改过'))
    mocks.errorCode.mockReturnValue('OCR_REVIEW_CONFLICT')

    await buttonWithText(wrapper, '保存')?.trigger('click')
    await flushPromises()

    expect(mocks.messages.error).toHaveBeenCalledWith('这份答卷已经被别人改过')
    expect(mocks.getReview).toHaveBeenCalledTimes(2)
  })

  it('保存失败但不是版本冲突时不动界面上的数据', async () => {
    const wrapper = await mountView()
    mocks.patchCandidate.mockRejectedValueOnce(new Error('这块区域太窄，自动裁剪失败'))
    mocks.errorCode.mockReturnValue('ANSWER_CROP_FAILED')

    await buttonWithText(wrapper, '保存')?.trigger('click')
    await flushPromises()

    // 重拉一次会把教师还没保存的编辑一起冲掉，所以只有"手里数据过期"才重拉。
    expect(mocks.getReview).toHaveBeenCalledTimes(1)
  })

  it('确认入库之后会把队列刷新一遍', async () => {
    const wrapper = await mountView()
    const before = mocks.getQueue.mock.calls.length

    await buttonWithText(wrapper, '确认入库')?.trigger('click')
    await flushPromises()

    expect(mocks.confirmAnswers).toHaveBeenCalledWith(701)
    // 队列里的"还有几道题没校对"变了，不刷新就会一直显示旧的。
    expect(mocks.getQueue.mock.calls.length).toBe(before + 1)
  })

  it('确认被拦下时跳到还没校对的那道题', async () => {
    mocks.getReview.mockResolvedValue(review({
      candidates: [
        candidate({ candidateId: 21, answerText: 'x=2', reviewStatus: 'CONFIRMED', version: 4 }),
        candidate({ candidateId: 22, blank: true, version: 1 }),
      ],
    }))
    const wrapper = await mountView()
    // 先把选中挪到已校对的那条，才能看出"被拦下之后跳走了"。
    await candidateButtons(wrapper)[0].trigger('click')
    mocks.confirmAnswers.mockRejectedValueOnce(new Error('还有 1 道题没有校对确认'))
    mocks.errorCode.mockReturnValue('ANSWER_REVIEW_PENDING')

    await buttonWithText(wrapper, '确认入库')?.trigger('click')
    await flushPromises()

    expect(mocks.messages.error).toHaveBeenCalledWith('还有 1 道题没有校对确认')
    expect(candidateButtons(wrapper)[1].classes()).toContain('active')
  })

  it('还没识别时给的是"开始识别"，不是一片空白', async () => {
    mocks.getReview.mockResolvedValue(review({ candidates: [] }))
    const wrapper = await mountView()

    expect(textOf(wrapper)).toContain('先跑识别')
    await buttonWithText(wrapper, '开始识别')?.trigger('click')
    await flushPromises()

    expect(mocks.processAnswers).toHaveBeenCalledWith(701)
  })

  it('标准答案要点开才拉，收起后不再显示', async () => {
    const wrapper = await mountView()

    expect(mocks.getReferences).not.toHaveBeenCalled()
    await buttonWithText(wrapper, '看这道题的标准答案')?.trigger('click')
    await flushPromises()

    expect(mocks.getReferences).toHaveBeenCalledWith(701)
    expect(wrapper.find('.reference-panel').text()).toContain('x = 2')

    await buttonWithText(wrapper, '收起标准答案')?.trigger('click')
    await flushPromises()
    expect(wrapper.find('.reference-panel').text()).not.toContain('x = 2')
  })

  it('学生转过角度的页会说明框是按原图标出来的', async () => {
    mocks.getReview.mockResolvedValue(review({
      pages: [page({ rotatedFileId: 5009 })],
    }))
    const wrapper = await mountView()

    // 框与答案图都来自原始页面图，所以显示的是原图；转没转过必须说出来，
    // 否则教师会以为"这些框怎么全都歪了"。
    expect(wrapper.find('.page-view').text()).toContain('转过角度')
    expect(wrapper.find('.page-view img').attributes('src')).toBe('blob:mock')
  })

  it('没对上模板的页会明说没对上', async () => {
    mocks.getReview.mockResolvedValue(review({
      pages: [page({ templatePageNo: undefined, alignmentConfidence: undefined })],
    }))
    const wrapper = await mountView()

    expect(wrapper.find('.page-view').text()).toContain('没对上模板')
  })

  it('已经入库的答卷不再让教师改候选', async () => {
    mocks.getReview.mockResolvedValue(review({ confirmed: true, status: 'CONFIRMED' }))
    const wrapper = await mountView()

    expect(textOf(wrapper)).toContain('答案已经入库')
    expect(buttonWithText(wrapper, '保存')?.attributes('disabled')).toBeDefined()
    expect(buttonWithText(wrapper, '确认这道题')?.attributes('disabled')).toBeDefined()
    // 头部那个按钮自己也换了文案，所以按位置找它，而不是按"确认入库"这几个字。
    const headerButton = wrapper.find('.page-heading .primary-button')
    expect(headerButton.text()).toBe('答案已入库')
    expect(headerButton.attributes('disabled')).toBeDefined()
    // 改派的那排按钮也不该还能点：答案已经入库了，再互换不会回写到正式答案上。
    expect(wrapper.find('.reassign-block button').attributes('disabled')).toBeDefined()
  })
})
