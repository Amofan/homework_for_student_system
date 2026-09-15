import axios, { type AxiosError } from 'axios'

import type { ExerciseSet } from './types'

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

export async function listExercises(classId: number): Promise<ExerciseSet[]> {
  const response = await api.get<ApiResponse<ExerciseSet[]>>('/exercises', { params: { classId } })
  return response.data.data
}

export async function generateExercise(
  command: { classId: number; sourceAssignmentId: number; title?: string },
): Promise<ExerciseSet> {
  const response = await api.post<ApiResponse<ExerciseSet>>('/exercises', command)
  return response.data.data
}

export async function approveExercise(exerciseId: number): Promise<ExerciseSet> {
  const response = await api.post<ApiResponse<ExerciseSet>>(`/exercises/${exerciseId}/approve`)
  return response.data.data
}

/**
 * 优先采用服务端 Content-Disposition 里的文件名，取不到时退回调用方给的兜底名。
 * 两种下载共用：文件名规则只该写一份，否则总有一个接口会漏掉转义或退回逻辑。
 */
export function dispositionFilename(disposition: unknown, fallback: string): string {
  const matched = typeof disposition === 'string' ? /filename="([^"]+)"/.exec(disposition) : null
  return matched?.[1] ?? fallback
}

/**
 * 把 Blob 存成文件。
 *
 * <p>对象 URL 必须在 finally 里撤销：不撤销会一直占着那份内存到页面关闭，
 * 连续导出几十次练习单就能看出来。撤销放在 click 之后是安全的，
 * 浏览器这时已经接过了下载。
 */
function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    URL.revokeObjectURL(url)
  }
}

/**
 * 下载已确认的练习单，返回没能转成 Word 格式的题目题号（没有降级时是空数组）。
 *
 * <p>必须走 axios 带上 Authorization 头再手动保存 Blob：把令牌拼进 URL
 * 会让它留在浏览器历史、代理日志和服务器访问日志里，等于泄露凭据。
 *
 * <p>响应头只在有降级时才出现，所以"头在不在"就足以区分两种结果；
 * 末尾的 `...` 由页面翻译成"等"，这里原样保留。
 */
export async function downloadExerciseDocx(exerciseId: number): Promise<string[]> {
  const response = await api.get<Blob>(`/exercises/${exerciseId}/export.docx`, { responseType: 'blob' })
  saveBlob(response.data, dispositionFilename(
    response.headers['content-disposition'], `exercise-${exerciseId}.docx`,
  ))
  const fallback = response.headers['x-formula-fallback']
  return typeof fallback === 'string'
    ? fallback.split(',').map(code => code.trim()).filter(Boolean)
    : []
}

/** 一次评测导出的完整性计数，用于告诉教师"导出了几条、又剔除了几条"。 */
export interface EvaluationDownloadSummary {
  reviewed: number
  exported: number
  skippedMissingAiError: number
}

/**
 * 导出某次作业的匿名评测样本，返回服务端给出的三个计数。
 *
 * <p>计数必须跟着文件一起回到页面：CSV 本身看不出少了样本，
 * 只写服务端日志的话教师会把导出条数当成全量样本量，论文的样本数就交代不清。
 */
export async function downloadEvaluationCases(assignmentId: number): Promise<EvaluationDownloadSummary> {
  const response = await api.get<Blob>(`/evaluation/assignments/${assignmentId}/grading-cases.csv`,
    { responseType: 'blob' })
  saveBlob(response.data, dispositionFilename(
    response.headers['content-disposition'], `grading-cases-${assignmentId}.csv`,
  ))
  return {
    reviewed: Number(response.headers['x-evaluation-reviewed'] ?? 0),
    exported: Number(response.headers['x-evaluation-exported'] ?? 0),
    skippedMissingAiError: Number(response.headers['x-evaluation-skipped-missing-ai-error'] ?? 0),
  }
}
