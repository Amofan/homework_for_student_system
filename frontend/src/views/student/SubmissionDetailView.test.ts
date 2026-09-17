import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { StudentAssignment, SubmissionVersionSummary } from '../../api/types'

const mocks = vi.hoisted(() => ({
  getAssignment: vi.fn(),
  history: vi.fn(),
  startDraft: vi.fn(),
  push: vi.fn(),
}))

vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { assignmentId: '501' } }),
  useRouter: () => ({ push: mocks.push }),
}))

vi.mock('../../api/client', () => ({
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
  getMyAssignment: mocks.getAssignment,
}))

vi.mock('../../api/studentSubmission', async () => {
  const actual = await vi.importActual<typeof import('../../api/studentSubmission')>(
    '../../api/studentSubmission')
  return { ...actual, getSubmissionHistory: mocks.history, startSubmissionDraft: mocks.startDraft }
})

import SubmissionDetailView from './SubmissionDetailView.vue'

const stubs = { RouterLink: true }

function assignment(overrides: Partial<StudentAssignment> = {}): StudentAssignment {
  return {
    id: 501,
    title: '第一单元作业',
    className: '七年级一班',
    teacherName: '林老师',
    status: 'PUBLISHED',
    dueAt: '2030-01-01T00:00:00Z',
    questionCount: 12,
    submissionStatus: 'NOT_SUBMITTED',
    ...overrides,
  }
}

function summary(overrides: Partial<SubmissionVersionSummary> = {}): SubmissionVersionSummary {
  return {
    id: 91,
    versionNo: 1,
    status: 'UPLOADED',
    current: false,
    editable: true,
    submittedAt: '2026-09-16T10:00:00Z',
    pageCount: 3,
    ...overrides,
  }
}

function history(
  versions: SubmissionVersionSummary[],
  canStartNewVersion = true,
) {
  return { assignmentId: 501, canStartNewVersion, versions }
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(SubmissionDetailView, { global: { stubs } })
  await flushPromises()
  return wrapper
}

function button(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(item => item.text().includes(label))
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.getAssignment.mockResolvedValue(assignment())
  mocks.history.mockResolvedValue(history([summary()]))
  mocks.startDraft.mockResolvedValue({ id: 92 })
})

describe('SubmissionDetailView', () => {
  it('显示作业信息与最新一版的状态', async () => {
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('第一单元作业')
    expect(wrapper.text()).toContain('七年级一班')
    expect(wrapper.text()).toContain('林老师')
    expect(wrapper.text()).toContain('12 道题')
    expect(wrapper.text()).toContain('第 1 版')
    expect(wrapper.text()).toContain('3 页')
    // 中文标签由标签表统一给出，页面上不该出现英文枚举。
    expect(wrapper.text()).not.toContain('UPLOADED')
  })

  /**
   * 只是翻一页看看的学生不该因为浏览而多出一行草稿：版本在上传页要用的那一刻才建。
   * 这条用例守着这个边界 —— 进来了不等于开始作答了。
   */
  it('只看不建版本', async () => {
    await mountView()

    expect(mocks.startDraft).not.toHaveBeenCalled()
  })

  it('还没有交过时给出开始上传，点了才建版本', async () => {
    mocks.history.mockResolvedValue(history([]))
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('还没有交过这份作业')
    await button(wrapper, '开始上传')?.trigger('click')
    await flushPromises()

    expect(mocks.startDraft).toHaveBeenCalledWith(501)
    expect(mocks.push).toHaveBeenCalledWith('/student/submissions/92/edit')
  })

  /** 手上还有一版能改，就该去续传；再点一次"新建"会平白多出一版。 */
  it('有可编辑的版本时续传，不建新版本', async () => {
    const wrapper = await mountView()

    await button(wrapper, '继续上传')?.trigger('click')
    await flushPromises()

    expect(mocks.startDraft).not.toHaveBeenCalled()
    expect(mocks.push).toHaveBeenCalledWith('/student/submissions/91/edit')
  })

  /** 被退回来的那一版不是"当前提交"，但学生回来后要找的正是退回原因。 */
  it('退回的版本显示原因，并引导再交一版', async () => {
    mocks.history.mockResolvedValue(history([
      summary({ status: 'RETURNED', editable: false, returnedAt: '2026-09-16T12:00:00Z', returnReason: '第三页没拍全' }),
    ]))
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('第三页没拍全')
    expect(wrapper.text()).toContain('退回')

    await button(wrapper, '再交一版')?.trigger('click')
    await flushPromises()

    expect(mocks.startDraft).toHaveBeenCalledWith(501)
    expect(mocks.push).toHaveBeenCalledWith('/student/submissions/92/edit')
  })

  /**
   * 批改已经开始。新建按钮必须消失：亮着但被服务端拒绝的按钮，
   * 学生会以为是网络问题而反复点。
   */
  it('已开始批改时不给新建入口，说明要找老师退回', async () => {
    mocks.history.mockResolvedValue(history([
      summary({ status: 'LOCKED', editable: false, current: true }),
    ], false))
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('联系老师退回')
    expect(button(wrapper, '再交一版')).toBeUndefined()
    expect(button(wrapper, '重新交一版')).toBeUndefined()
  })

  it('提交记录列出每一版并标出当前提交', async () => {
    mocks.history.mockResolvedValue(history([
      summary({ id: 93, versionNo: 3, status: 'PROCESSING', current: true, editable: false }),
      summary({ id: 92, versionNo: 2, status: 'SUPERSEDED', editable: false }),
      summary({ id: 91, versionNo: 1, status: 'RETURNED', editable: false, returnReason: '第一页反了' }),
    ]))
    const wrapper = await mountView()

    expect(wrapper.text()).toContain('第 3 版')
    expect(wrapper.text()).toContain('第 1 版')
    expect(wrapper.text()).toContain('当前提交')
    expect(wrapper.text()).toContain('第一页反了')
    expect(wrapper.text()).toContain('已被取代')

    await button(wrapper, '查看')?.trigger('click')
    await flushPromises()
    expect(mocks.push).toHaveBeenCalledWith('/student/submissions/93/edit')
  })

  it('载入失败时给出原因', async () => {
    mocks.history.mockRejectedValue(new Error('作业不存在或不属于你'))

    const wrapper = await mountView()

    expect(wrapper.find('[role="alert"]').text()).toContain('作业不存在或不属于你')
  })
})
