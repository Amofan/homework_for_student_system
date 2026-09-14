import axios from 'axios'

export interface ApiError {
  code: string
  message: string
}

export interface ApiResponse<T> {
  success: boolean
  data: T
  error?: ApiError
  timestamp: string
}

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 20_000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('homework_access_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError<ApiResponse<never>>(error)) {
    return error.response?.data?.error?.message ?? '服务暂时不可用，请检查后端是否已启动。'
  }
  return error instanceof Error ? error.message : '操作未完成，请稍后重试。'
}
