import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { KnowledgePoint, PaperCandidate, PaperImport, PaperPage, PaperRegion } from '../../api/types'
import PaperReviewView from './PaperReviewView.vue'

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  apiGet: vi.fn(),
  push: vi.fn(),
  getImport: vi.fn(),
  patchRegion: vi.fn(),
  confirmImport: vi.fn(),
  errorCode: vi.fn(),
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { assignmentId: '501' } }),
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('../../api/client', () => ({
  api: { get: mocks.apiGet },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
  errorCode: mocks.errorCode,
}))

// 只替换网络调用，保留真实的文案函数：paperWarningText / documentState 的输出本身就是断言对象。
vi.mock('../../api/paperImport', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/paperImport')>()),
  getPaperImport: mocks.getImport,
  patchPaperRegion: mocks.patchRegion,
  confirmPaperImport: mocks.confirmImport,
}))

// jsdom 不实现 createObjectURL，PrivateImage 会走到「加载失败」分支。
// 补上桩之后它渲染的是真实的 <img src>，于是「fileId 是否换来一次带鉴权的请求」也可被验证。
const createObjectURL = vi.fn(() => 'blob:mock')
const revokeObjectURL = vi.fn()

function region(overrides: Partial<PaperRegion> & { regionId: number }): PaperRegion {
  return { regionType: 'TEXT_BLOCK', x: 0.08, y: 0.1, width: 0.84, height: 0.04, reviewStatus: 'PENDING', ...overrides }
}

function page(): PaperPage {
  return {
    pageId: 7001, pageNo: 1, pageFileId: 6001, thumbnailFileId: 6002, width: 1654, height: 2339,
    regions: [
      region({ regionId: 9001, candidateId: 1, confidence: 0.97, ocrText: '1. 计算下列各题。' }),
      region({ regionId: 9002, regionType: 'FIGURE', candidateId: 1, confidence: 0.88, x: 0.12, y: 0.3, width: 0.3, height: 0.18 }),
      // 低置信度：既要颜色，也要文字标签。
      region({ regionId: 9003, candidateId: 2, confidence: 0.62, ocrText: '2. 解方程。' }),
      // 没有归属任何候选题：识别把它的题号漏掉了。
      region({ regionId: 9004, confidence: 0.9, ocrText: '孤立的一段文字' }),
    ],
  }
}

function candidate(overrides: Partial<PaperCandidate> & { id: number; orderNo: number }): PaperCandidate {
  return {
    documentId: 6501, documentKind: 'EXAM_PAPER', acceptedAnswers: [], rubricItems: [],
    sourceRegionIds: [], assetRegionIds: [], warnings: [], reviewStatus: 'PENDING', version: 0,
    ...overrides,
  }
}

function paperImport(overrides: Partial<PaperImport> = {}): PaperImport {
  return {
    assignmentId: 501, classId: 101, title: '第一单元测验', status: 'OCR_REVIEW', confirmed: false,
    documents: [{
      documentId: 6501, documentKind: 'EXAM_PAPER', status: 'NEEDS_REVIEW', ocrStatus: 'NEEDS_REVIEW',
      pageCount: 1, pages: [page()],
    }],
    candidates: [
      candidate({
        id: 1, orderNo: 1, questionCode: '1', questionType: 'SOLUTION', content: '计算下列各题。',
        standardAnswer: 'x = 2', totalScore: 8, difficulty: 'MEDIUM', primaryKnowledgePointId: 301,
        sourceRegionIds: [9001, 9002], assetRegionIds: [9002], warnings: ['SCORE_NOT_DETECTED'], version: 3,
      }),
      candidate({ id: 2, orderNo: 2, questionCode: '2', sourceRegionIds: [9003], version: 0 }),
    ],
    warnings: ['LOW_CONFIDENCE'],
    ...overrides,
  }
}

function buttonWith(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(button => button.text().includes(label))
}

/**
 * 触发 pointerdown。
 *
 * 不能用 `wrapper.trigger('pointerdown', { clientX })`：jsdom 里 `MouseEvent.clientX`
 * 是只读访问器，而 test-utils 是逐属性赋值到事件对象上的，会直接抛
 * "Cannot set property clientX"。构造函数参数没有这个问题。
 */
async function pointerDown(element: Element, clientX = 0, clientY = 0): Promise<void> {
  element.dispatchEvent(new MouseEvent('pointerdown', { bubbles: true, clientX, clientY }))
  await flushPromises()
}

async function mountView(data: PaperImport = paperImport()): Promise<VueWrapper> {
  mocks.getImport.mockResolvedValue(data)
  mocks.apiGet.mockImplementation((url: string) => {
    if (url === '/knowledge-points') {
      return Promise.resolve({
        data: { data: [{ id: 301, code: 'ALG', name: '一元一次方程', grade: 7, active: true } as KnowledgePoint] },
      })
    }
    return Promise.reject(new Error(`未预期的请求 ${url}`))
  })
  // 不加 `stubs: { teleport: true }`：ElSelect 的下拉挂在 Teleport 上，桩掉之后弹层永远收敛不了，
  // 渲染效应会自触发到 Vue 的递归上限（每个用例刷十几条 unhandled rejection）。
  const wrapper = mount(PaperReviewView)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.patchRegion.mockResolvedValue(paperImport())
  mocks.confirmImport.mockResolvedValue({
    id: 501, classId: 101, title: '第一单元测验', status: 'OCR_REVIEW', version: 0,
    questionIds: [1, 2, 3, 4],
  })
  mocks.errorCode.mockReturnValue(undefined)
  mocks.apiGet.mockReset()
  mocks.getImport.mockReset()
  ;(URL as unknown as { createObjectURL: unknown }).createObjectURL = createObjectURL
  ;(URL as unknown as { revokeObjectURL: unknown }).revokeObjectURL = revokeObjectURL
  // jsdom 的布局全是 0：不给一个真实尺寸，拖动会被判成「页面还没排好」而直接返回。
  Element.prototype.getBoundingClientRect = vi.fn(() => ({
    width: 400, height: 600, top: 0, left: 0, right: 400, bottom: 600, x: 0, y: 0,
    toJSON: () => ({}),
  })) as unknown as typeof Element.prototype.getBoundingClientRect
})

describe('PaperReviewView 校对工作台', () => {
  it('渲染候选题目列表、页面区域与警告文案', async () => {
    const wrapper = await mountView()

    expect(wrapper.findAll('.region-box')).toHaveLength(4)
    expect(wrapper.text()).toContain('1. 1')
    expect(wrapper.text()).toContain('2. 2')
    expect(wrapper.text()).toContain('没有识别到分值，请手动填写')
    expect(wrapper.text()).toContain('识别置信度偏低，请核对文字是否准确')
  })

  it('低置信度区域在颜色之外还有可见的文字标签', async () => {
    const wrapper = await mountView()

    const low = wrapper.findAll('.region-box').find(box => box.classes().includes('low'))
    expect(low).toBeDefined()
    // 只靠颜色等于没有提示：色觉差异、投影仪偏色都会让它消失。
    expect(low!.text()).toContain('低置信度 62%')
  })

  it('点击区域会切换到它所属的候选题目', async () => {
    const wrapper = await mountView()
    const questionCode = () => (wrapper.find('input.text-input').element as HTMLInputElement).value
    expect(questionCode()).toBe('1')

    await pointerDown(wrapper.findAll('.region-box')[2].element)
    await flushPromises()

    expect(questionCode()).toBe('2')
  })

  it('未归属的区域可以并入当前题', async () => {
    const wrapper = await mountView()

    await pointerDown(wrapper.findAll('.region-box')[3].element)
    await flushPromises()
    await buttonWith(wrapper, '把选中区域并入本题')!.trigger('click')
    await flushPromises()

    expect(mocks.patchRegion).toHaveBeenCalledWith(501, 9004, { candidateId: 1, version: 3 })
  })

  it('保存时把当前版本号原样回传', async () => {
    const wrapper = await mountView()

    await buttonWith(wrapper, '保存')!.trigger('click')
    await flushPromises()

    const [assignmentId, regionId, body] = mocks.patchRegion.mock.calls.at(-1)!
    expect(assignmentId).toBe(501)
    expect(regionId).toBe(9001)
    // 版本号不带回服务端，乐观锁就无从判断「你手里的数据是不是最新的」。
    expect(body.version).toBe(3)
    expect(body.candidateId).toBe(1)
    expect(body.candidate.questionCode).toBe('1')
    expect(body.candidate.totalScore).toBe(8)
  })

  it('拖动区域框后提交新的几何', async () => {
    const wrapper = await mountView()

    await pointerDown(wrapper.findAll('.region-box')[0].element)
    // 位移取 20px 而不是 40px：9001 宽 0.84，往右最多只能挪到 0.16（贴边钳制），
    // 40px 会被钳成 0.16 从而掩盖"dx 算错"这类接线问题。钳制本身由 regions.test.ts 单独覆盖。
    window.dispatchEvent(new MouseEvent('pointermove', { clientX: 20, clientY: 0 }))
    window.dispatchEvent(new MouseEvent('pointerup'))
    await flushPromises()

    const [, regionId, body] = mocks.patchRegion.mock.calls.at(-1)!
    expect(regionId).toBe(9001)
    // 400px 宽的画面移动 20px 就是归一化后的 0.05，x 从 0.08 变成 0.13。
    expect(body.x).toBeCloseTo(0.13, 5)
    expect(body.candidateId).toBe(1)
  })

  it('拆分把区域变成一道新题目', async () => {
    const wrapper = await mountView()

    await pointerDown(wrapper.findAll('.region-box')[0].element)
    await flushPromises()
    await buttonWith(wrapper, '拆分为新题')!.trigger('click')
    await flushPromises()

    expect(mocks.patchRegion).toHaveBeenCalledWith(501, 9001, {
      candidateId: 1, version: 3, split: true,
    })
  })

  it('版本冲突时提示并重新拉取，避免第二次仍然冲突', async () => {
    mocks.errorCode.mockReturnValue('OCR_REVIEW_CONFLICT')
    mocks.patchRegion.mockRejectedValueOnce(new Error('这道题已被其他操作修改，请刷新后重试'))
    const wrapper = await mountView()
    expect(mocks.getImport).toHaveBeenCalledTimes(1)

    await buttonWith(wrapper, '保存')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.error).toHaveBeenCalledWith('这道题已被其他操作修改，请刷新后重试')
    // 只提示不刷新的话，本地版本号始终没变，教师点第二次还是会撞同一个冲突。
    expect(mocks.getImport).toHaveBeenCalledTimes(2)
  })

  it('勾选题图后会随保存一起提交', async () => {
    const wrapper = await mountView()
    // 9001 默认不是题图，勾上它。
    const toggles = wrapper.findAll('.asset-toggle input')
    await toggles[0].setValue(true)
    await buttonWith(wrapper, '保存')!.trigger('click')
    await flushPromises()

    const body = mocks.patchRegion.mock.calls.at(-1)![2]
    expect(body.candidate.assetRegionIds).toContain(9001)
  })

  it('确认入库成功后回到导入页', async () => {
    const wrapper = await mountView()

    await buttonWith(wrapper, '确认入库')!.trigger('click')
    await flushPromises()

    expect(mocks.confirmImport).toHaveBeenCalledWith(501)
    expect(mocks.messages.success).toHaveBeenCalledWith('已确认入库，共 4 道题')
    expect(mocks.push).toHaveBeenCalledWith('/teacher/paper-imports')
  })

  it('确认失败时留在页面并说明原因', async () => {
    mocks.confirmImport.mockRejectedValue(new Error('第 2 题还不能入库：未填写分值'))
    const wrapper = await mountView()

    await buttonWith(wrapper, '确认入库')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.error).toHaveBeenCalledWith('第 2 题还不能入库：未填写分值')
    expect(mocks.push).not.toHaveBeenCalled()
  })

  it('移动端选项卡切换靠 data-tab 驱动样式', async () => {
    const wrapper = await mountView()
    expect(wrapper.find('.paper-workspace').attributes('data-tab')).toBe('candidate')

    await buttonWith(wrapper, '页面与区域')!.trigger('click')
    await flushPromises()

    expect(wrapper.find('.paper-workspace').attributes('data-tab')).toBe('page')
  })

  it('已确认的导入不能再编辑', async () => {
    const wrapper = await mountView(paperImport({ confirmed: true }))

    expect(buttonWith(wrapper, '确认入库')!.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('这份整卷已经确认入库，候选不再可编辑')
  })

  it('页面图与缩略图都走带鉴权的私有读取', async () => {
    await mountView()

    const urls = mocks.apiGet.mock.calls.map(call => call[0])
    expect(urls).toContain('/teacher/files/6001')
    expect(urls).toContain('/teacher/files/6002')
  })
})
