import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { api, TOKEN_STORAGE_KEY, type ApiResponse } from '../api/client'
import type { Profile } from '../api/types'

/** 教师工作台入口。路由守卫与登录跳转共用同一个常量，避免两处各写一遍而漂移。 */
export const TEACHER_HOME = '/teacher'
export const STUDENT_HOME = '/student/assignments'
export const STUDENT_CHANGE_PASSWORD = '/student/change-password'

/**
 * 按当前主体决定登录后的落点。
 *
 * <p>学生的落点要看改密标志：临时密码还没换掉时只能去改密页。这条规则只在守卫和
 * 登录跳转两处使用，所以做成纯函数放在 store 里，页面不再各自判断角色。
 */
export function homePathFor(profile: Profile | null): string {
  if (!profile) return '/login'
  if (profile.role === 'TEACHER') return TEACHER_HOME
  return profile.passwordChangeRequired ? STUDENT_CHANGE_PASSWORD : STUDENT_HOME
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_STORAGE_KEY) ?? '')
  const profile = ref<Profile | null>(null)
  const authenticated = computed(() => Boolean(token.value))
  const homePath = computed(() => homePathFor(profile.value))

  /** 写令牌必须同时更新内存和 localStorage，否则刷新后两者的状态会不一致。 */
  function applyToken(value: string) {
    token.value = value
    if (value) localStorage.setItem(TOKEN_STORAGE_KEY, value)
    else localStorage.removeItem(TOKEN_STORAGE_KEY)
  }

  async function login(username: string, password: string) {
    const response = await api.post<ApiResponse<{ accessToken: string }>>('/auth/login', { username, password })
    applyToken(response.data.data.accessToken)
    await loadProfile()
  }

  async function loadProfile() {
    if (!token.value) return
    const response = await api.get<ApiResponse<Profile>>('/auth/me')
    profile.value = response.data.data
  }

  /**
   * 改密成功后换发新令牌。
   *
   * <p>旧令牌可能带着 passwordChangeRequired，直接用它会让学生改完密码仍被挡在改密页；
   * 这里换成服务端新发的令牌并重新拉一次资料，页头显示的名字也随之刷新。
   */
  async function changePassword(currentPassword: string, newPassword: string) {
    const response = await api.post<ApiResponse<{ accessToken: string }>>('/auth/password/change', {
      currentPassword,
      newPassword,
    })
    applyToken(response.data.data.accessToken)
    await loadProfile()
  }

  function logout() {
    applyToken('')
    profile.value = null
  }

  return { token, profile, authenticated, homePath, login, loadProfile, changePassword, logout }
})
