import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { api, type ApiResponse } from '../api/client'
import type { TeacherProfile } from '../api/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem('homework_access_token') ?? '')
  const profile = ref<TeacherProfile | null>(null)
  const authenticated = computed(() => Boolean(token.value))

  async function login(username: string, password: string) {
    const response = await api.post<ApiResponse<{ accessToken: string }>>('/auth/login', { username, password })
    token.value = response.data.data.accessToken
    localStorage.setItem('homework_access_token', token.value)
    await loadProfile()
  }

  async function loadProfile() {
    if (!token.value) return
    const response = await api.get<ApiResponse<TeacherProfile>>('/auth/me')
    profile.value = response.data.data
  }

  function logout() {
    token.value = ''
    profile.value = null
    localStorage.removeItem('homework_access_token')
  }

  return { token, profile, authenticated, login, loadProfile, logout }
})
