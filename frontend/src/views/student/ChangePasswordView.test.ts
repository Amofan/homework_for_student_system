import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  changePassword: vi.fn(),
  replace: vi.fn(),
  homePath: '/student/assignments',
}))

vi.mock('../../stores/auth', () => ({
  useAuthStore: () => ({ changePassword: mocks.changePassword, homePath: mocks.homePath }),
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ replace: mocks.replace }),
}))

vi.mock('../../api/client', () => ({
  errorMessage: (error: unknown) => (error instanceof Error ? error.message : '操作未完成，请稍后重试。'),
}))

import ChangePasswordView from './ChangePasswordView.vue'

function inputs() {
  return mount(ChangePasswordView).findAll('input')
}

async function fill(wrapper: ReturnType<typeof mount>, current: string, next: string, confirm: string) {
  const fields = wrapper.findAll('input')
  await fields[0].setValue(current)
  await fields[1].setValue(next)
  await fields[2].setValue(confirm)
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.homePath = '/student/assignments'
})

describe('ChangePasswordView', () => {
  it('三个输入框都填好之前不能提交', async () => {
    const wrapper = mount(ChangePasswordView)
    const submit = wrapper.find('button[type="submit"]')

    expect(submit.attributes('disabled')).toBeDefined()
    await inputs()[0].setValue('Temp1234Abcd5678')
    await flushPromises()
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
  })

  it('两次输入不一致时给出提示且不允许提交', async () => {
    const wrapper = mount(ChangePasswordView)
    await fill(wrapper, 'Temp1234Abcd5678', 'NewPass!2345', 'NewPass!2346')
    await flushPromises()

    expect(wrapper.text()).toContain('两次输入的新密码不一致')
    expect(wrapper.find('button[type="submit"]').attributes('disabled')).toBeDefined()
    expect(mocks.changePassword).not.toHaveBeenCalled()
  })

  it('提交成功后跳到 store 给出的落点', async () => {
    mocks.changePassword.mockResolvedValue(undefined)
    const wrapper = mount(ChangePasswordView)
    await fill(wrapper, 'Temp1234Abcd5678', 'NewPass!2345', 'NewPass!2345')
    await flushPromises()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(mocks.changePassword).toHaveBeenCalledWith('Temp1234Abcd5678', 'NewPass!2345')
    expect(mocks.replace).toHaveBeenCalledWith('/student/assignments')
  })

  it('服务端拒绝时展示原因并保留已填内容', async () => {
    mocks.changePassword.mockRejectedValue(new Error('新密码需包含大写字母、小写字母、数字、符号中的至少三类'))
    const wrapper = mount(ChangePasswordView)
    await fill(wrapper, 'Temp1234Abcd5678', 'NewPass!2345', 'NewPass!2345')
    await flushPromises()

    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('至少三类')
    expect((wrapper.findAll('input')[1].element as HTMLInputElement).value).toBe('NewPass!2345')
    expect(mocks.replace).not.toHaveBeenCalled()
  })
})
