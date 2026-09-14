import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { api, TOKEN_STORAGE_KEY, type ApiResponse } from '../api/client'
import type { TeacherProfile } from '../api/types'

export const useAuthStore = defineStore('auth', () => {
  const token = ref(localStorage.getItem(TOKEN_STORAGE_KEY) ?? '')
  const profile = ref<TeacherProfile | null>(null)
  const authenticated = computed(() => Boolean(token.value))

  async function login(username: string, password: string) {
    const response = await api.post<ApiResponse<{ accessToken: string }>>('/auth/login', { username, password })
    token.value = response.data.data.accessToken
    localStorage.setItem(TOKEN_STORAGE_KEY, token.value)
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
    localStorage.removeItem(TOKEN_STORAGE_KEY)
  }

  return { token, profile, authenticated, login, loadProfile, logout }
})
