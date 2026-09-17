import { flushPromises, mount, RouterLinkStub, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { StudentAssignment } from '../../api/types'

const mocks = vi.hoisted(() => ({ listMine: vi.fn() }))

vi.mock('../../api/client', () => ({
  listMyAssignments: mocks.listMine,
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
}))

import StudentAssignmentsView from './StudentAssignmentsView.vue'

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

/**
 * `RouterLinkStub` 把链接渲染成一个 `<a>`，`to` 留在 props 里（它只 stub 掉跳转，
 * 不伪造 href）。所以"点了会去哪一个作业"要按 props 断言，而不是找 href 属性。
 */
async function mountView(): Promise<VueWrapper> {
  const wrapper = mount(StudentAssignmentsView, {
    global: { stubs: { RouterLink: RouterLinkStub } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('StudentAssignmentsView', () => {
  it('展示班级、教师、题量与截止时间', async () => {
    mocks.listMine.mockResolvedValue([assignment()])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('第一单元作业')
    expect(wrapper.text()).toContain('七年级一班')
    expect(wrapper.text()).toContain('林老师')
    expect(wrapper.text()).toContain('12 道题')
    expect(wrapper.text()).toContain('截止')
    // 中文标签由标签表统一给出，页面上不该出现英文枚举。
    expect(wrapper.text()).toContain('未提交')
    expect(wrapper.text()).not.toContain('NOT_SUBMITTED')
  })

  it('已提交的作业显示已提交状态', async () => {
    mocks.listMine.mockResolvedValue([assignment({ submissionStatus: 'IMPORTED' })])

    expect((await mountView()).text()).toContain('已提交')
  })

  it('没有作业时给出引导而不是空白页', async () => {
    mocks.listMine.mockResolvedValue([])

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('暂时没有待完成的作业')
  })

  it('加载失败时展示原因', async () => {
    mocks.listMine.mockRejectedValue(new Error('服务暂时不可用'))

    const wrapper = await mountView()

    expect(wrapper.text()).toContain('服务暂时不可用')
    expect(wrapper.find('[role="alert"]').exists()).toBe(true)
  })

  /**
   * 入口指向这一份作业的答卷页。
   *
   * <p>答卷按作业维度组织（第几版、老师退回了哪一版），所以链接带的是作业 id；
   * 拿当前那一版的 id 拼链接会让"还没开始作答"的学生直接掉进 404。
   */
  it('上传答卷进入这一份作业的答卷页', async () => {
    mocks.listMine.mockResolvedValue([assignment()])

    const wrapper = await mountView()
    const upload = wrapper.findComponent(RouterLinkStub)

    expect(upload.text()).toBe('上传答卷')
    expect(upload.props('to')).toBe('/student/assignments/501/submission')
  })

  /** 交过之后同一个入口要说"查看"：还写着"上传"，学生会以为上一次没交上去。 */
  it('已提交的作业入口改成查看答卷', async () => {
    mocks.listMine.mockResolvedValue([assignment({ submissionStatus: 'IMPORTED' })])

    const wrapper = await mountView()

    expect(wrapper.find('a.primary-button').text()).toBe('查看答卷')
  })
})
