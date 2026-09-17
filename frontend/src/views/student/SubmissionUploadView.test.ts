import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { SubmissionPage, SubmissionVersion } from '../../api/types'

const mocks = vi.hoisted(() => ({
  getVersion: vi.fn(),
  upload: vi.fn(),
  reorder: vi.fn(),
  rotate: vi.fn(),
  removePage: vi.fn(),
  submit: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { versionId: '91' } }),
}))

vi.mock('../../api/client', () => ({
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
  errorCode: (error: unknown) => (error as { code?: string } | undefined)?.code,
}))

/**
 * 只替换发请求的那几个函数，标签表与常量走真实实现。
 *
 * <p>把 `SUBMISSION_MAX_PAGES` 之类的常量也拷一份到测试里，测试就只会证明"我抄的那份和我抄的
 * 另一份一致"；页数上限改了，用例还是绿的。
 */
vi.mock('../../api/studentSubmission', async () => {
  const actual = await vi.importActual<typeof import('../../api/studentSubmission')>(
    '../../api/studentSubmission')
  return {
    ...actual,
    getSubmissionVersion: mocks.getVersion,
    uploadSubmissionFile: mocks.upload,
    reorderSubmissionPages: mocks.reorder,
    rotateSubmissionPage: mocks.rotate,
    deleteSubmissionPage: mocks.removePage,
    submitSubmissionVersion: mocks.submit,
  }
})

import SubmissionUploadView from './SubmissionUploadView.vue'

/** PrivateImage 要取 Blob、建对象 URL，与这一页的判断无关；RouterLink 在单测里没有路由。 */
const stubs = { RouterLink: true, PrivateImage: true }

function page(overrides: Partial<SubmissionPage> = {}): SubmissionPage {
  return {
    id: 11,
    pageNo: 1,
    documentId: 1,
    documentPageNo: 1,
    fileName: 'IMG_0001.jpg',
    pageFileId: 101,
    thumbnailFileId: 111,
    rotatedFileId: 101,
    rotationDegrees: 0,
    qualityStatus: 'OK',
    ...overrides,
  }
}

function version(overrides: Partial<SubmissionVersion> = {}): SubmissionVersion {
  return {
    id: 91,
    assignmentId: 501,
    studentId: 1001,
    versionNo: 1,
    status: 'UPLOADED',
    current: false,
    editable: true,
    createdAt: '2026-09-16T00:00:00Z',
    pages: [page()],
    ...overrides,
  }
}

function file(name: string, bytes = 'page'): File {
  return new File([bytes], name, { type: 'image/jpeg' })
}

/** 服务端返回的错误：调用方按 `code` 决定分支，文案只给人看。 */
function apiError(code: string, message: string): Error {
  return Object.assign(new Error(message), { code })
}

function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(settle => { resolve = settle })
  return { promise, resolve }
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(SubmissionUploadView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

function button(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(item => item.text().includes(label))
}

/**
 * 选文件。
 *
 * <p>jsdom 的 `input.files` 是只读的，只能用 defineProperty 放一个类数组进去 ——
 * 页面里 `Array.from(input.files ?? [])` 走的就是这条路。
 */
async function selectFiles(wrapper: VueWrapper, files: File[]): Promise<void> {
  const input = wrapper.find('input[type="file"]')
  Object.defineProperty(input.element, 'files', { value: files, configurable: true })
  await input.trigger('change')
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getVersion.mockResolvedValue(version())
  mocks.upload.mockResolvedValue(version())
  mocks.reorder.mockResolvedValue(version())
  mocks.rotate.mockResolvedValue(version())
  mocks.removePage.mockResolvedValue(version())
  mocks.submit.mockResolvedValue(version({ status: 'PROCESSING', editable: false }))
})

describe('载入与服务端为准', () => {
  it('展示每一页的来源文件与页号', async () => {
    mocks.getVersion.mockResolvedValue(version({
      pages: [
        page(),
        page({ id: 12, pageNo: 2, fileName: '附加页.pdf', documentPageNo: 2 }),
      ],
    }))

    const wrapper = await mountView()

    expect(mocks.getVersion).toHaveBeenCalledWith(91)
    expect(wrapper.text()).toContain('第 1 页')
    expect(wrapper.text()).toContain('附加页.pdf')
    expect(wrapper.text()).toContain('已上传 2 页')
  })

  it('载入失败时给出原因', async () => {
    mocks.getVersion.mockRejectedValue(new Error('答卷不存在'))

    const wrapper = await mountView()

    expect(wrapper.find('[role="alert"]').text()).toContain('答卷不存在')
  })

  /**
   * 已锁的一版不能再改。上传入口必须整个消失：留一个点了报错的按钮，
   * 学生只会以为是自己手机的问题，然后反复重试。
   */
  it('不能再改的版本不显示上传入口，并说明原因', async () => {
    mocks.getVersion.mockResolvedValue(version({
      status: 'RETURNED', editable: false, returnReason: '第三页没拍全',
    }))

    const wrapper = await mountView()

    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('已被老师退回')
    expect(wrapper.text()).toContain('第三页没拍全')
  })
})

describe('上传', () => {
  /**
   * 串行：并发上传时几个进度条互相争抢，"40%" 属于哪个文件说不清。
   * 这条用例断言第一个文件还在传时第二个请求根本没有发出去。
   */
  it('多个文件按选择顺序串行上传', async () => {
    const first = deferred<SubmissionVersion>()
    mocks.upload.mockReturnValueOnce(first.promise).mockResolvedValue(version())
    const wrapper = await mountView()

    await selectFiles(wrapper, [file('IMG_0001.jpg'), file('IMG_0002.jpg')])

    expect(mocks.upload).toHaveBeenCalledTimes(1)
    expect(mocks.upload.mock.calls[0][0]).toBe(91)
    expect(mocks.upload.mock.calls[0][1].name).toBe('IMG_0001.jpg')

    first.resolve(version())
    await flushPromises()

    expect(mocks.upload.mock.calls.map(call => call[1].name))
      .toEqual(['IMG_0001.jpg', 'IMG_0002.jpg'])
  })

  /**
   * 手机相册里连拍的照片常常同名（都是 IMG_0001.JPG 的情况只差序号，甚至是同一个名字）。
   * 队列按名字去重的话，学生选了三张只会传上去一张。
   */
  it('同名文件也会各传一次', async () => {
    const wrapper = await mountView()

    await selectFiles(wrapper, [file('IMG_0001.jpg'), file('IMG_0001.jpg', 'another')])

    expect(mocks.upload).toHaveBeenCalledTimes(2)
  })

  it('上传过程中显示进度，成功后变成页面', async () => {
    const first = deferred<SubmissionVersion>()
    mocks.upload.mockReturnValueOnce(first.promise)
    const wrapper = await mountView()

    await selectFiles(wrapper, [file('IMG_0009.jpg')])
    expect(wrapper.text()).toContain('上传中 0%')

    // 第三个参数就是进度回调，和 axios 的 onUploadProgress 接上之后报的就是这个值。
    mocks.upload.mock.calls[0][2](40)
    await nextTick()
    expect(wrapper.text()).toContain('上传中 40%')

    first.resolve(version({ pages: [page(), page({ id: 12, pageNo: 2, fileName: 'IMG_0009.jpg' })] }))
    await flushPromises()

    expect(wrapper.text()).not.toContain('上传中')
    expect(wrapper.text()).toContain('IMG_0009.jpg')
    expect(wrapper.text()).toContain('已上传 2 页')
  })

  /** 一个文件失败不该拖住其他的，也不该让学生把已经传上去的几张再传一遍。 */
  it('某个文件失败只影响它自己，可以单独重试', async () => {
    mocks.upload
      .mockRejectedValueOnce(new Error('网络中断'))
      .mockResolvedValueOnce(version())
      .mockResolvedValueOnce(version())
    const wrapper = await mountView()

    await selectFiles(wrapper, [file('IMG_0001.jpg'), file('IMG_0002.jpg')])

    expect(mocks.upload).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('网络中断')
    expect(button(wrapper, '重试这个文件')).toBeDefined()

    await button(wrapper, '重试这个文件')?.trigger('click')
    await flushPromises()

    expect(mocks.upload).toHaveBeenCalledTimes(3)
    expect(mocks.upload.mock.calls[2][1].name).toBe('IMG_0001.jpg')
    expect(wrapper.text()).not.toContain('网络中断')
  })

  it('选到不支持的文件时说清楚哪一个，不发请求', async () => {
    const wrapper = await mountView()

    await selectFiles(wrapper, [file('作业.docx')])

    expect(mocks.upload).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('作业.docx')
  })

  /** 一份答卷的页数上限由服务端执行，前端先拦一次是为了省掉"传完才被拒"的往返。 */
  it('超过页数上限时直接拦下', async () => {
    mocks.getVersion.mockResolvedValue(version({
      pages: Array.from({ length: 40 }, (_, index) => page({ id: 100 + index, pageNo: index + 1 })),
    }))
    const wrapper = await mountView()

    await selectFiles(wrapper, [file('IMG_0041.jpg')])

    expect(mocks.upload).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('最多 40 页')
  })
})

describe('页面整理', () => {
  function threePages(): SubmissionVersion {
    return version({
      pages: [
        page(),
        page({ id: 12, pageNo: 2, fileName: 'IMG_0002.jpg' }),
        page({ id: 13, pageNo: 3, fileName: 'IMG_0003.jpg' }),
      ],
    })
  }

  const aria = (wrapper: VueWrapper, label: string) =>
    wrapper.findAll('button').find(item => item.attributes('aria-label') === label)

  /**
   * 送的是完整的新顺序，不是"把 11 挪到 12 后面"这样的动作。
   * 服务端按整份顺序落库，两边对同一次拖动算出不同结果的机会就没有了。
   */
  it('上移下移把整份顺序送出去', async () => {
    mocks.getVersion.mockResolvedValue(threePages())
    const wrapper = await mountView()

    await aria(wrapper, '把第 1 页下移')?.trigger('click')
    await flushPromises()

    expect(mocks.reorder).toHaveBeenCalledWith(91, [12, 11, 13])
  })

  it('拖动也送整份顺序', async () => {
    mocks.getVersion.mockResolvedValue(threePages())
    const wrapper = await mountView()
    const items = wrapper.findAll('li.organizer-page')

    await items[2].trigger('dragstart')
    await items[0].trigger('drop')
    await flushPromises()

    expect(mocks.reorder).toHaveBeenCalledWith(91, [13, 11, 12])
  })

  /** 送目标角度而不是"再转 90 度"：重试一次不会变成转两圈。 */
  it('旋转送的是目标角度', async () => {
    mocks.getVersion.mockResolvedValue(version({ pages: [page({ rotationDegrees: 270 })] }))
    const wrapper = await mountView()

    await aria(wrapper, '把第 1 页向右转 90 度')?.trigger('click')
    await flushPromises()
    expect(mocks.rotate).toHaveBeenCalledWith(91, 11, 0)

    mocks.rotate.mockClear()
    mocks.getVersion.mockResolvedValue(version())
    const fresh = await mountView()
    await aria(fresh, '把第 1 页向右转 90 度')?.trigger('click')
    await flushPromises()
    expect(mocks.rotate).toHaveBeenCalledWith(91, 11, 90)
  })

  it('删除只影响那一页', async () => {
    mocks.getVersion.mockResolvedValue(threePages())
    const wrapper = await mountView()

    await aria(wrapper, '删除第 2 页')?.trigger('click')
    await flushPromises()

    expect(mocks.removePage).toHaveBeenCalledWith(91, 12)
  })

  /** 整理失败时以服务端为准重新渲染：界面留在学生拖出来的顺序上，下一次点击就会基于错的前提。 */
  it('整理失败时把服务端那一份重新拉回来', async () => {
    mocks.getVersion.mockResolvedValue(threePages())
    mocks.reorder.mockRejectedValue(new Error('顺序已被其他设备改动'))
    const wrapper = await mountView()

    await aria(wrapper, '把第 1 页下移')?.trigger('click')
    await flushPromises()

    expect(mocks.getVersion).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[role="alert"]').text()).toContain('顺序已被其他设备改动')
  })
})

describe('提交', () => {
  /** 拍成一片模糊的照片提上去，老师批改时才发现，代价是老师的时间。 */
  it('严重质量问题的页面直接拦下，不发提交请求', async () => {
    mocks.getVersion.mockResolvedValue(version({
      pages: [page(), page({ id: 12, pageNo: 2, qualityStatus: 'BLOCKING' })],
    }))
    const wrapper = await mountView()

    await button(wrapper, '提交答卷')?.trigger('click')
    await flushPromises()

    expect(mocks.submit).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('第 2 页')
  })

  /**
   * 质量警告可以越过，但要逐页确认：点一下"确认提交"就替所有页面背书，
   * 等于这个确认框只是多一次点击。
   */
  it('有质量警告时必须勾选确认，且只确认被警告的那几页', async () => {
    mocks.getVersion.mockResolvedValue(version({
      pages: [page(), page({ id: 12, pageNo: 2, qualityStatus: 'WARNING' })],
    }))
    const wrapper = await mountView()

    await button(wrapper, '提交答卷')?.trigger('click')
    await flushPromises()

    const confirm = () => wrapper.findAll('button').find(item => item.text() === '确认提交')
    expect(wrapper.text()).toContain('第 2 页可能拍得不清楚')
    expect(confirm()?.attributes('disabled')).toBeDefined()
    expect(mocks.submit).not.toHaveBeenCalled()

    await wrapper.find('input[type="checkbox"]').setValue(true)
    await confirm()?.trigger('click')
    await flushPromises()

    expect(mocks.submit).toHaveBeenCalledWith(91, [12])
  })

  it('没有警告时直接提交，不勾任何页面', async () => {
    const wrapper = await mountView()

    await button(wrapper, '提交答卷')?.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '确认提交')?.trigger('click')
    await flushPromises()

    expect(mocks.submit).toHaveBeenCalledWith(91, [])
    expect(wrapper.text()).toContain('已提交')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })

  it('一页都没有时拦下提交', async () => {
    mocks.getVersion.mockResolvedValue(version({ pages: [] }))
    const wrapper = await mountView()

    await button(wrapper, '提交答卷')?.trigger('click')
    await flushPromises()

    expect(mocks.submit).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('还没有上传')
  })

  /**
   * 老师已经开始批改。这时唯一正确的做法是重新读服务端：它是唯一知道"这一版已经锁了"的一方，
   * 而学生看到的界面必须跟着变成只读，否则他还会继续找上传按钮。
   */
  it('提交时发现已经开始批改，就转成只读并说明原因', async () => {
    mocks.getVersion
      .mockResolvedValueOnce(version())
      .mockResolvedValueOnce(version({ status: 'LOCKED', editable: false }))
    mocks.submit.mockRejectedValue(apiError('SUBMISSION_LOCKED', '这一版已经开始批改，不能再修改或提交；要改请联系老师退回'))
    const wrapper = await mountView()

    await button(wrapper, '提交答卷')?.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '确认提交')?.trigger('click')
    await flushPromises()

    expect(mocks.getVersion).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('已经开始批改')
    expect(wrapper.text()).toContain('不能再修改')
    expect(wrapper.find('input[type="file"]').exists()).toBe(false)
  })

  /** 服务端说还有页面没确认（比如另一台设备上新传的一页），不能把学生的这次提交当成成功。 */
  it('服务端要求逐页确认时把面板打开让他补确认', async () => {
    mocks.submit.mockRejectedValue(apiError('SUBMISSION_QUALITY_WARNING', '有页面需要逐页确认后才能提交'))
    const wrapper = await mountView()

    await button(wrapper, '提交答卷')?.trigger('click')
    await flushPromises()
    await wrapper.findAll('button').find(item => item.text() === '确认提交')?.trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('有页面需要逐页确认')
    expect(wrapper.text()).not.toContain('已提交')
  })
})
