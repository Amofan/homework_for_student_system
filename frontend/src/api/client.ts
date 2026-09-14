import axios, { type AxiosError } from 'axios'

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

/** 登录令牌的存储键。请求拦截器写入、401 处理与退出登录都依赖同一个键，避免读写漂移。 */
export const TOKEN_STORAGE_KEY = 'homework_access_token'

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/api',
  timeout: 20_000,
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_STORAGE_KEY)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

let unauthorizedHandler: (() => void) | null = null

/**
 * 注册令牌失效后的处理动作，由应用入口接到路由和登录状态上。
 * 用回调注册而不是直接 import 路由或 Pinia，避免 client → store → client 的循环依赖。
 */
export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

/** 登录接口自身返回 401 代表账号密码错误，应当留在登录页展示提示。 */
function isLoginRequest(error: AxiosError): boolean {
  return (error.config?.url ?? '').includes('/auth/login')
}

api.interceptors.response.use(
  (response) => response,
  (error: unknown) => {
    if (axios.isAxiosError(error) && error.response?.status === 401 && !isLoginRequest(error)) {
      localStorage.removeItem(TOKEN_STORAGE_KEY)
      unauthorizedHandler?.()
    }
    return Promise.reject(error)
  },
)

export function errorMessage(error: unknown): string {
  if (axios.isAxiosError<ApiResponse<never>>(error)) {
    return error.response?.data?.error?.message ?? '服务暂时不可用，请检查后端是否已启动。'
  }
  return error instanceof Error ? error.message : '操作未完成，请稍后重试。'
}
