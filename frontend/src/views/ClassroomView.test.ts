import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { ProvisionedStudentAccount, Student } from '../api/types'

const mocks = vi.hoisted(() => ({
  messages: { error: vi.fn(), success: vi.fn(), info: vi.fn(), warning: vi.fn() },
  apiGet: vi.fn(),
  apiPost: vi.fn(),
  provision: vi.fn(),
  reset: vi.fn(),
  download: vi.fn(),
}))

vi.mock('element-plus', async (importOriginal) => ({
  ...(await importOriginal<typeof import('element-plus')>()),
  ElMessage: mocks.messages,
}))

vi.mock('../api/client', () => ({
  api: { get: mocks.apiGet, post: mocks.apiPost },
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
  provisionStudentAccounts: mocks.provision,
  resetStudentPassword: mocks.reset,
  downloadStudentCredentials: mocks.download,
}))

import ClassroomView from './ClassroomView.vue'

const unprovisioned: Student = { id: 1001, classId: 101, studentNo: '001', name: '张三' }
const provisioned: Student = {
  id: 1002, classId: 101, studentNo: '002', name: '李四',
  accountUsername: 'stu_abcdefghijkl', accountStatus: 'ACTIVE',
}

function credential(studentId: number, name: string, password: string): ProvisionedStudentAccount {
  return {
    studentId,
    studentNo: String(studentId).slice(-3),
    name,
    username: 'stu_abcdefghijkl',
    temporaryPassword: password,
    newlyProvisioned: true,
  }
}

function buttonWith(wrapper: VueWrapper, label: string) {
  return wrapper.findAll('button').find(button => button.text().includes(label))
}

async function mountView(students: Student[]): Promise<VueWrapper> {
  mocks.apiGet.mockImplementation((url: string) => {
    if (url === '/classes') {
      return Promise.resolve({ data: { data: [{ id: 101, classCode: 'C-1', name: '七年级一班', studentCount: students.length }] } })
    }
    if (url === '/classes/101/students') {
      return Promise.resolve({ data: { data: students } })
    }
    return Promise.reject(new Error(`未预期的请求 ${url}`))
  })
  const wrapper = mount(ClassroomView)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('ClassroomView 学生账号', () => {
  it('按是否已开通显示不同状态与操作', async () => {
    const wrapper = await mountView([unprovisioned, provisioned])

    expect(wrapper.text()).toContain('未开通')
    expect(wrapper.text()).toContain('stu_abcdefghijkl')
    expect(buttonWith(wrapper, '开通账号')).toBeDefined()
    // “重置密码”只对已开通学生出现，否则按钮点了必然报错。
    expect(wrapper.findAll('button').filter(button => button.text().includes('重置密码'))).toHaveLength(1)
  })

  it('开通成功后展示一次性临时密码', async () => {
    const wrapper = await mountView([unprovisioned])
    mocks.provision.mockResolvedValue([credential(1001, '张三', 'Temp1234Abcd5678')])

    await buttonWith(wrapper, '开通账号')!.trigger('click')
    await flushPromises()

    expect(mocks.provision).toHaveBeenCalledWith(101, [1001])
    expect(wrapper.text()).toContain('Temp1234Abcd5678')
    expect(wrapper.text()).toContain('关闭本窗口后无法再次查看')
  })

  it('已开通学生再次开通时不展示任何密码', async () => {
    const wrapper = await mountView([provisioned])
    mocks.provision.mockResolvedValue([{
      studentId: 1002, studentNo: '002', name: '李四',
      username: 'stu_abcdefghijkl', newlyProvisioned: false,
    }])

    await buttonWith(wrapper, '开通账号')!.trigger('click')
    await flushPromises()

    expect(mocks.messages.info).toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('关闭本窗口后无法再次查看')
  })

  /**
   * 关闭弹窗必须丢掉上一批明文。
   *
   * <p>这里不去断言 DOM 里"密码文字消失了"——Element Plus 关闭弹窗只隐藏而不移除内容，
   * 那样的断言会假通过。真正要守住的是：导出文件里不能混进上一次的密码。
   */
  it('关闭弹窗后再开通，导出只包含本次的明文', async () => {
    const second: Student = { id: 1003, classId: 101, studentNo: '003', name: '王五' }
    const wrapper = await mountView([unprovisioned, second])

    mocks.provision.mockResolvedValueOnce([credential(1001, '张三', 'Temp1111Abcd5678')])
    await buttonWith(wrapper, '开通账号')!.trigger('click')
    await flushPromises()

    mocks.provision.mockResolvedValueOnce([credential(1003, '王五', 'Temp2222Efgh5678')])
    // 第二条“开通账号”按钮对应第二位学生。
    const provisionButtons = wrapper.findAll('button').filter(button => button.text().includes('开通账号'))
    await provisionButtons[1].trigger('click')
    await flushPromises()

    await buttonWith(wrapper, '下载 Excel')!.trigger('click')
    await flushPromises()

    const [, exported] = mocks.download.mock.calls.at(-1)!
    expect(exported.map((item: ProvisionedStudentAccount) => item.temporaryPassword)).toEqual(['Temp2222Efgh5678'])
  })

  it('重置密码走独立接口并再次弹出一次性密码', async () => {
    const wrapper = await mountView([provisioned])
    mocks.reset.mockResolvedValue(credential(1002, '李四', 'Temp9999Abcd5678'))

    await buttonWith(wrapper, '重置密码')!.trigger('click')
    await flushPromises()

    expect(mocks.reset).toHaveBeenCalledWith(1002)
    expect(wrapper.text()).toContain('Temp9999Abcd5678')
  })
})
