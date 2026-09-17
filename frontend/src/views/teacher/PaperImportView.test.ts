import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { Classroom, PaperCandidate, PaperDocument, PaperImport } from '../../api/types'
import PaperImportView from './PaperImportView.vue'

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  apiGet: vi.fn(),
  push: vi.fn(),
  create: vi.fn(),
  upload: vi.fn(),
  process: vi.fn(),
  get: vi.fn(),
  listInProgress: vi.fn(),
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('../../api/client', () => ({
  api: { get: mocks.apiGet },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
}))

// 只替换网络调用，保留真实的文案函数（documentState / paperWarningText 正是要验的东西）。
vi.mock('../../api/paperImport', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../api/paperImport')>()),
  createPaperImport: mocks.create,
  uploadPaperFile: mocks.upload,
  processPaperImport: mocks.process,
  getPaperImport: mocks.get,
  listPaperImportsInProgress: mocks.listInProgress,
}))

function document(overrides: Partial<PaperDocument> = {}): PaperDocument {
  return {
    documentId: 6501, documentKind: 'EXAM_PAPER', status: 'PENDING', pageCount: 0, pages: [],
    ...overrides,
  }
}

function candidate(): PaperCandidate {
  return {
    id: 1, documentId: 6501, documentKind: 'EXAM_PAPER', orderNo: 1,
    acceptedAnswers: [], rubricItems: [], sourceRegionIds: [], assetRegionIds: [],
    warnings: [], reviewStatus: 'PENDING', version: 0,
  }
}

function paperImport(overrides: Partial<PaperImport> = {}): PaperImport {
  return {
    assignmentId: 501, classId: 101, title: '第一单元测验', status: 'OCR_REVIEW', confirmed: false,
    documents: [], candidates: [], warnings: [],
    ...overrides,
  }
}

function buttonWith(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(button => button.text().includes(label))
}

async function mountView(): Promise<VueWrapper> {
  mocks.apiGet.mockImplementation((url: string) => {
    if (url === '/classes') {
      return Promise.resolve({
        data: { data: [{ id: 101, classCode: 'C-1', name: '七年级一班', studentCount: 30 } as Classroom] },
      })
    }
    return Promise.reject(new Error(`未预期的请求 ${url}`))
  })
  const wrapper = mount(PaperImportView)
  await flushPromises()
  return wrapper
}

/** 填好建导表单。班级用 el-select，测试里直接触发它的 update:modelValue。 */
async function fillCreateForm(wrapper: VueWrapper): Promise<void> {
  await wrapper.findAll('input.el-input__inner')[0].setValue('第一单元测验')
  wrapper.findComponent({ name: 'ElSelect' }).vm.$emit('update:modelValue', 101)
  await flushPromises()
}

/** 选文件：jsdom 不允许直接给 file input 赋值，只能改写 files 属性再触发 change。 */
async function chooseFile(wrapper: VueWrapper, index: number, file: File): Promise<void> {
  const input = wrapper.findAll('input[type="file"]')[index]
  Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
  await input.trigger('change')
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.listInProgress.mockResolvedValue([])
})

afterEach(() => {
  vi.useRealTimers()
})

describe('PaperImportView 建立与上传', () => {
  it('建立导入后提示先上传空白试卷', async () => {
    mocks.create.mockResolvedValue(paperImport())
    const wrapper = await mountView()

    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await flushPromises()

    expect(mocks.create).toHaveBeenCalledWith(101, '第一单元测验')
    expect(wrapper.text()).toContain('上传空白试卷后才能开始识别')
  })

  it('未选班级时给出提示且不发请求', async () => {
    const wrapper = await mountView()

    await wrapper.findAll('input.el-input__inner')[0].setValue('第一单元测验')
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.warning).toHaveBeenCalledWith('请填写作业名称并选择班级')
    expect(mocks.create).not.toHaveBeenCalled()
  })

  it('扩展名不合法时当场拦下，不上传', async () => {
    mocks.create.mockResolvedValue(paperImport())
    const wrapper = await mountView()
    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await flushPromises()

    await chooseFile(wrapper, 0, new File(['hello'], '备注.txt', { type: 'text/plain' }))

    expect(mocks.messages.error).toHaveBeenCalledWith('只支持 PDF、PNG、JPG 三种格式')
    expect(mocks.upload).not.toHaveBeenCalled()
  })

  it('超过大小上限时当场拦下', async () => {
    mocks.create.mockResolvedValue(paperImport())
    const wrapper = await mountView()
    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await flushPromises()

    const huge = new File(['x'], '试卷.pdf', { type: 'application/pdf' })
    Object.defineProperty(huge, 'size', { value: 30 * 1024 * 1024 })
    await chooseFile(wrapper, 0, huge)

    expect(mocks.messages.error).toHaveBeenCalledWith('文件不能超过 25 MB')
    expect(mocks.upload).not.toHaveBeenCalled()
  })

  it('上传空白试卷后展示服务端返回的文档状态', async () => {
    mocks.create.mockResolvedValue(paperImport())
    mocks.upload.mockResolvedValue(paperImport({ documents: [document()] }))
    const wrapper = await mountView()
    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await flushPromises()

    await chooseFile(wrapper, 0, new File(['x'], '试卷.pdf', { type: 'application/pdf' }))
    await buttonWith(wrapper, '上传')!.trigger('click')
    await flushPromises()

    expect(mocks.upload).toHaveBeenCalledWith(501, 'EXAM_PAPER', expect.any(File))
    expect(wrapper.text()).toContain('待识别')
  })
})

describe('PaperImportView 识别与轮询', () => {
  it('识别完成后按退避轮询并在完成时停止', async () => {
    vi.useFakeTimers()
    mocks.create.mockResolvedValue(paperImport({ documents: [document()] }))
    mocks.process.mockResolvedValue(paperImport({
      documents: [document({ ocrStatus: 'RUNNING', status: 'PROCESSING' })],
    }))
    mocks.get.mockResolvedValue(paperImport({
      documents: [document({ status: 'NEEDS_REVIEW', ocrStatus: 'NEEDS_REVIEW' })],
      candidates: [candidate()],
    }))

    const wrapper = await mountView()
    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await vi.advanceTimersByTimeAsync(0)

    await buttonWith(wrapper, '开始识别')!.trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    expect(wrapper.text()).toContain('正在识别')

    // 第一次轮询在 1.5 秒后发出，退避从这一档起步。
    expect(mocks.get).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1_500)

    expect(mocks.get).toHaveBeenCalledWith(501)
    expect(wrapper.text()).toContain('识别完成，待校对')
    expect(wrapper.text()).not.toContain('正在识别')

    // 已经没有进行中的文档，再等多久都不该继续打请求。
    await vi.advanceTimersByTimeAsync(30_000)
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })

  it('组件卸载后停止轮询', async () => {
    vi.useFakeTimers()
    mocks.create.mockResolvedValue(paperImport({ documents: [document()] }))
    mocks.process.mockResolvedValue(paperImport({
      documents: [document({ ocrStatus: 'RUNNING', status: 'PROCESSING' })],
    }))
    mocks.get.mockResolvedValue(paperImport({
      documents: [document({ ocrStatus: 'RUNNING', status: 'PROCESSING' })],
    }))

    const wrapper = await mountView()
    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    await buttonWith(wrapper, '开始识别')!.trigger('click')
    await vi.advanceTimersByTimeAsync(0)
    await vi.advanceTimersByTimeAsync(1_500)
    expect(mocks.get).toHaveBeenCalledTimes(1)

    wrapper.unmount()
    await vi.advanceTimersByTimeAsync(60_000)

    // 继续跑会在已销毁的组件上写 ref，而且教师已经离开页面却还占着连接。
    expect(mocks.get).toHaveBeenCalledTimes(1)
  })

  it('识别服务不可用时提示可重试，并把按钮改成重试', async () => {
    mocks.create.mockResolvedValue(paperImport({ documents: [document()] }))
    mocks.process.mockResolvedValue(paperImport({
      documents: [document({ ocrStatus: 'RETRY_WAIT', ocrFailureCode: 'OCR_UNAVAILABLE' })],
    }))

    const wrapper = await mountView()
    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await flushPromises()
    await buttonWith(wrapper, '开始识别')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('可稍后重试')
    // 文案不能写成"稍后会自动重试"：没有调度器，不点按钮就不会再跑一次。
    expect(wrapper.text()).not.toContain('自动重试')
    expect(buttonWith(wrapper, '重试识别')).toBeDefined()
  })

  it('识别出候选题目后才能进入校对', async () => {
    // 刚建完导入、还没识别：文档必须是 PENDING。用 NEEDS_REVIEW 的话「开始识别」本身就是禁用的
    // （canProcess 只放行 PENDING / 失败待重试），点不动，测的就不是这条断言了。
    mocks.create.mockResolvedValue(paperImport({ documents: [document()] }))
    const wrapper = await mountView()
    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await flushPromises()

    expect(buttonWith(wrapper, '进入校对')!.attributes('disabled')).toBeDefined()

    mocks.process.mockResolvedValue(paperImport({
      documents: [document({ status: 'NEEDS_REVIEW' })], candidates: [candidate()],
    }))
    await buttonWith(wrapper, '开始识别')!.trigger('click')
    await flushPromises()

    await buttonWith(wrapper, '进入校对')!.trigger('click')
    expect(mocks.push).toHaveBeenCalledWith('/teacher/paper-imports/501/review')
  })

  it('警告逐条给出可读文案', async () => {
    mocks.create.mockResolvedValue(paperImport({
      documents: [document()], warnings: ['LOW_CONFIDENCE', 'EXAM_PAPER:OCR_INPUT_INVALID'],
    }))
    const wrapper = await mountView()
    await fillCreateForm(wrapper)
    await buttonWith(wrapper, '建立导入')!.trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('识别置信度偏低，请核对文字是否准确')
    expect(wrapper.text()).toContain('空白试卷：OCR_INPUT_INVALID')
  })

  it('列出进行中的导入并能继续处理', async () => {
    mocks.listInProgress.mockResolvedValue([
      { id: 909, classId: 101, title: '上次的测验', status: 'OCR_REVIEW', version: 0, questionIds: [] },
    ])
    mocks.get.mockResolvedValue(paperImport({ assignmentId: 909, title: '上次的测验' }))
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('上次的测验')

    await buttonWith(wrapper, '继续处理')!.trigger('click')
    await flushPromises()

    expect(mocks.get).toHaveBeenCalledWith(909)
    expect(wrapper.text()).toContain('#909')
  })
})
