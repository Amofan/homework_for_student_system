import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ExerciseItem, ExerciseSet } from '../api/types'
import ExerciseView from './ExerciseView.vue'

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), warning: vi.fn() },
  apiGet: vi.fn(),
  listExercises: vi.fn(),
  generateExercise: vi.fn(),
  approveExercise: vi.fn(),
  downloadExerciseDocx: vi.fn(),
}))

// 只替换 ElMessage，保留其余导出：模板里的 Element Plus 组件由按需导入插件注入，
// 整包 mock 掉会让 ElSelect/ElOption 变成 undefined，组件还没渲染就报错。
// 组件本身在本文件里被 stubs 换成占位组件，保留真实导出不会影响断言。
vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('../api/client', () => ({
  api: { get: mocks.apiGet },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
  listExercises: mocks.listExercises,
  generateExercise: mocks.generateExercise,
  approveExercise: mocks.approveExercise,
  downloadExerciseDocx: mocks.downloadExerciseDocx,
}))

/** 下拉框由 Element Plus 提供，这里换成占位组件：本文件验证的是页面行为，不是下拉框本身。 */
const stubs = { ElSelect: true, ElOption: true }

function item(tier: ExerciseItem['tier'], sortOrder: number, questionCode: string): ExerciseItem {
  return {
    tier, sortOrder, questionId: sortOrder, questionCode, content: '解方程 $2x+1=5$', totalScore: 5,
    difficulty: 'BASIC', knowledgePointId: 301, knowledgePointName: '一元一次方程',
    standardAnswer: 'x=2', rubricItems: [],
  }
}

function exercise(overrides: Partial<ExerciseSet> = {}): ExerciseSet {
  return {
    id: 7, classId: 101, className: '七年级一班', sourceAssignmentId: 501,
    sourceAssignmentTitle: '第一单元作业', title: '七年级一班 · 第一单元作业 分层练习',
    status: 'DRAFT', createdAt: '2026-03-04T05:06:07Z',
    items: [item('FOUNDATION', 1, 'Q-1'), item('CORRECTION', 1, 'Q-2'), item('IMPROVEMENT', 1, 'Q-3')],
    notices: [],
    ...overrides,
  }
}

function buttonWith(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(button => button.text().includes(label))
}

async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(ExerciseView, { global: { stubs } })
  // 两次 flush：第一次让班级与作业加载完，第二次让 classId 变化触发的练习题加载跑完
  await flushPromises()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.apiGet.mockImplementation((url: string) => {
    if (url === '/classes') {
      return Promise.resolve({ data: { data: [{ id: 101, classCode: 'C-1', name: '七年级一班', studentCount: 30 }] } })
    }
    if (url === '/assignments') {
      return Promise.resolve({
        data: { data: [{ id: 501, classId: 101, title: '第一单元作业', status: 'DRAFT', questionIds: [1] }] },
      })
    }
    return Promise.reject(new Error(`未预期的请求 ${url}`))
  })
})

describe('ExerciseView', () => {
  it('载入过程中显示载入提示，载入完成后消失', async () => {
    mocks.listExercises.mockResolvedValue([exercise()])

    const wrapper = mount(ExerciseView, { global: { stubs } })
    expect(wrapper.text()).toContain('正在载入练习单')

    await flushPromises()
    await flushPromises()
    expect(wrapper.text()).not.toContain('正在载入练习单')
  })

  it('没有练习单时给出空状态和生成入口', async () => {
    mocks.listExercises.mockResolvedValue([])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('还没有练习单')
    expect(buttonWith(wrapper, '生成分层练习')).toBeDefined()
  })

  it('按三个层级展示题目与知识点', async () => {
    mocks.listExercises.mockResolvedValue([exercise()])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('基础巩固层')
    expect(wrapper.text()).toContain('方法纠错层')
    expect(wrapper.text()).toContain('综合提升层')
    expect(wrapper.find('.katex').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('$2x+1=5$')
    expect(wrapper.text()).toContain('知识点：一元一次方程')
  })

  it('题库不足时把中文提示显示给教师', async () => {
    // 提示文案由后端决定，这里只验证"后端给什么就显示什么"，不复制后端的措辞。
    const notice = '基础巩固层仅生成 2 题（目标 5 题）：来源作业里该层知识点只有这些题'
    mocks.listExercises.mockResolvedValue([exercise({ notices: [notice] })])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain(notice)
  })

  it('生成失败时展示后端返回的原因', async () => {
    mocks.listExercises.mockResolvedValue([])
    mocks.generateExercise.mockRejectedValue(new Error('该来源作业还没有教师确认过的评分结果，无法生成分层练习'))
    const wrapper = await mountView()

    await buttonWith(wrapper, '生成分层练习')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.error).toHaveBeenCalledWith('该来源作业还没有教师确认过的评分结果，无法生成分层练习')
  })

  it('生成成功后刷新列表并提示草稿已生成', async () => {
    mocks.listExercises.mockResolvedValue([])
    mocks.generateExercise.mockResolvedValue(exercise({ id: 9 }))
    const wrapper = await mountView()
    mocks.listExercises.mockResolvedValue([exercise({ id: 9 })])

    await buttonWith(wrapper, '生成分层练习')!.trigger('click')
    await flushPromises()

    expect(mocks.generateExercise).toHaveBeenCalledWith({ classId: 101, sourceAssignmentId: 501 })
    expect(mocks.messages.success).toHaveBeenCalledWith('已生成分层练习草稿')
    expect(wrapper.text()).toContain('草稿')
  })

  it('草稿阶段确认可点、导出禁用；确认后反过来', async () => {
    mocks.listExercises.mockResolvedValue([exercise()])
    const wrapper = await mountView()

    expect(buttonWith(wrapper, '确认练习单')!.attributes('disabled')).toBeUndefined()
    expect(buttonWith(wrapper, '导出 Word')!.attributes('disabled')).toBeDefined()

    mocks.approveExercise.mockResolvedValue(exercise({ status: 'APPROVED' }))
    mocks.listExercises.mockResolvedValue([exercise({ status: 'APPROVED' })])
    await buttonWith(wrapper, '确认练习单')!.trigger('click')
    await flushPromises()

    expect(mocks.approveExercise).toHaveBeenCalledWith(7)
    expect(buttonWith(wrapper, '确认练习单')!.attributes('disabled')).toBeDefined()
    expect(buttonWith(wrapper, '导出 Word')!.attributes('disabled')).toBeUndefined()
  })

  it('已确认的练习单可以导出 Word', async () => {
    mocks.listExercises.mockResolvedValue([exercise({ status: 'APPROVED' })])
    mocks.downloadExerciseDocx.mockResolvedValue([])
    const wrapper = await mountView()

    await buttonWith(wrapper, '导出 Word')!.trigger('click')
    await flushPromises()

    expect(mocks.downloadExerciseDocx).toHaveBeenCalledWith(7)
  })

  it('导出存在公式降级时点名题目并说明文档已标红', async () => {
    mocks.listExercises.mockResolvedValue([exercise({ status: 'APPROVED' })])
    mocks.downloadExerciseDocx.mockResolvedValue(['Q-ALG-007', 'Q-GEO-005', '...'])
    const wrapper = await mountView()

    await buttonWith(wrapper, '导出 Word')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.warning).toHaveBeenCalledWith(
      '这些题目的公式没能完整转成 Word 格式，文档中已标红：Q-ALG-007、Q-GEO-005，等',
    )
  })

  it('没有公式降级时只提示下载成功', async () => {
    mocks.listExercises.mockResolvedValue([exercise({ status: 'APPROVED' })])
    mocks.downloadExerciseDocx.mockResolvedValue([])
    const wrapper = await mountView()

    await buttonWith(wrapper, '导出 Word')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.success).toHaveBeenCalledWith('已开始下载 Word 文档')
    expect(mocks.messages.warning).not.toHaveBeenCalled()
  })
})
