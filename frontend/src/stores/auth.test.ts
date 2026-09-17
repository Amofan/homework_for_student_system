import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'

import type { Profile } from '../api/types'
import { router } from '../router'
import { homePathFor, useAuthStore } from './auth'
import { pinia } from './pinia'

const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }))

// vi.mock 会被提升到所有 import 之前，因此这里可以照常静态导入被测模块。
vi.mock('../api/client', () => ({
  api: { get: mocks.get, post: mocks.post },
  TOKEN_STORAGE_KEY: 'homework_access_token',
}))

const teacherProfile: Profile = {
  role: 'TEACHER', userId: 1, teacherId: 11, displayName: '林老师',
  schoolName: '城南实验中学', passwordChangeRequired: false,
}
const studentProfile: Profile = {
  role: 'STUDENT', userId: 2, studentId: 1001, displayName: '张晨', passwordChangeRequired: false,
}
const firstLoginProfile: Profile = { ...studentProfile, passwordChangeRequired: true }

function setSession(profile: Profile | null, token = profile ? 'token' : '') {
  const auth = useAuthStore(pinia)
  auth.token = token
  auth.profile = profile
  return auth
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.clearAllMocks()
  setSession(null)
})

describe('登录后的落点', () => {
  it('教师进入教师工作台', () => {
    expect(homePathFor(teacherProfile)).toBe('/teacher')
  })

  it('已完成改密的学生进入我的作业', () => {
    expect(homePathFor(studentProfile)).toBe('/student/assignments')
  })

  // 临时密码没换掉就只能停在改密页，跳到别处也会被守卫弹回来。
  it('尚未改密的学生只能进入改密页', () => {
    expect(homePathFor(firstLoginProfile)).toBe('/student/change-password')
  })

  it('没有资料时回到登录页', () => {
    expect(homePathFor(null)).toBe('/login')
  })
})

describe('登录状态', () => {
  it('登录后写入令牌并载入角色资料', async () => {
    mocks.post.mockResolvedValue({ data: { data: { accessToken: 'new-token' } } })
    mocks.get.mockResolvedValue({ data: { data: studentProfile } })
    const auth = useAuthStore(pinia)

    await auth.login('stu_abc', 'Temp1234Abcd5678')

    expect(auth.token).toBe('new-token')
    expect(localStorage.getItem('homework_access_token')).toBe('new-token')
    expect(auth.profile).toEqual(studentProfile)
    expect(auth.homePath).toBe('/student/assignments')
  })

  // 改密返回的令牌里改密标志已归零，直接沿用它才能立刻离开改密页。
  it('改密后换用服务端新发的令牌并刷新资料', async () => {
    setSession(firstLoginProfile)
    mocks.post.mockResolvedValue({ data: { data: { accessToken: 'after-change' } } })
    mocks.get.mockResolvedValue({ data: { data: studentProfile } })
    const auth = useAuthStore(pinia)

    await auth.changePassword('Temp1234Abcd5678', 'NewPass!2345')

    expect(auth.token).toBe('after-change')
    expect(auth.profile).toEqual(studentProfile)
    expect(auth.homePath).toBe('/student/assignments')
    expect(mocks.post).toHaveBeenCalledWith('/auth/password/change', {
      currentPassword: 'Temp1234Abcd5678', newPassword: 'NewPass!2345',
    })
  })

  it('退出登录清空令牌、资料与本地存储', () => {
    const auth = setSession(teacherProfile)
    localStorage.setItem('homework_access_token', 'token')

    auth.logout()

    expect(auth.token).toBe('')
    expect(auth.profile).toBeNull()
    expect(auth.authenticated).toBe(false)
    expect(localStorage.getItem('homework_access_token')).toBeNull()
  })
})

describe('路由守卫', () => {
  /**
   * 每个用例都从未登录的登录页出发。
   *
   * <p>vue-router 会短路“跳到当前所在路由”的导航且不跑守卫；上一个用例停在
   * /student/assignments 时，下一个用例再往同一个地址跳就会拿不到重定向，断言假通过。
   */
  beforeEach(async () => {
    setSession(null)
    await router.replace('/login').catch(() => undefined)
  })

  async function navigate(to: string) {
    await router.push(to).catch(() => undefined)
    await router.isReady()
    return router.currentRoute.value
  }

  it('未登录访问教师区会被送到登录页并带回跳地址', async () => {
    const current = await navigate('/teacher/classrooms')

    expect(current.name).toBe('login')
    expect(current.query.redirect).toBe('/teacher/classrooms')
  })

  it('教师访问学生区会被送回教师工作台', async () => {
    setSession(teacherProfile)

    expect((await navigate('/student/assignments')).name).toBe('teacher-dashboard')
  })

  it('学生访问教师区会被送回我的作业', async () => {
    setSession(studentProfile)

    expect((await navigate('/teacher/review')).name).toBe('student-assignments')
  })

  it('未改密的学生进入任何学生页都会被送到改密页', async () => {
    setSession(firstLoginProfile)

    expect((await navigate('/student/assignments')).name).toBe('student-change-password')
  })

  it('已登录用户访问登录页会直接进入自己的工作台', async () => {
    setSession(teacherProfile)

    // 先离开登录页，否则“跳到登录页”会被 vue-router 当成重复导航短路掉，守卫根本不会执行。
    expect((await navigate('/teacher/review')).name).toBe('review')
    expect((await navigate('/login')).name).toBe('teacher-dashboard')
  })
})
