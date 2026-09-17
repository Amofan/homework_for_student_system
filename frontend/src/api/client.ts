import axios, { type AxiosError } from 'axios'

import type { Assignment, ExerciseSet, ProvisionedStudentAccount, StudentAssignment } from './types'

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

/**
 * 取出服务端的业务错误码。
 *
 * <p>有些错误码需要触发**不同的处理动作**，而不只是换一句提示文案：例如
 * `OCR_REVIEW_CONFLICT` 意味着本地那份数据已经过期，除了提示之外还必须重新拉一遍，
 * 否则教师接着点第二次还是会撞同一个冲突。只靠 `errorMessage` 的文本判断是脆的——
 * 文案随时会被改，而错误码是接口契约。
 */
export function errorCode(error: unknown): string | undefined {
  if (axios.isAxiosError<ApiResponse<never>>(error)) {
    return error.response?.data?.error?.code
  }
  return undefined
}

export async function listExercises(classId: number): Promise<ExerciseSet[]> {
  const response = await api.get<ApiResponse<ExerciseSet[]>>('/exercises', { params: { classId } })
  return response.data.data
}

/**
 * 发布作业。
 *
 * <p>`version` 必须来自当前页面上读到的那一份作业：服务端用它做乐观锁，
 * 版本对不上会返回 `ASSIGNMENT_VERSION_CONFLICT`，提示教师刷新后重试，
 * 而不是让后一次发布覆盖前一次设定的截止时间。
 */
export async function publishAssignment(
  assignmentId: number, version: number, dueAt?: string,
): Promise<Assignment> {
  const response = await api.post<ApiResponse<Assignment>>(
    `/teacher/assignments/${assignmentId}/publish`, { version, dueAt: dueAt ?? null })
  return response.data.data
}

/** 学生自己的作业列表。响应里没有答案、评分项和同学的信息，只用于展示。 */
export async function listMyAssignments(): Promise<StudentAssignment[]> {
  const response = await api.get<ApiResponse<StudentAssignment[]>>('/student/assignments')
  return response.data.data
}

/**
 * 学生看一份作业。
 *
 * <p>答卷页只拿到作业 id（版本、提交记录都按作业维度组织），标题与截止时间得单独取一次。
 * 不从列表页用路由参数捎过来：学生从聊天软件里点开一条分享链接时没有那个列表，
 * 页面会显示成没有标题的半成品。
 */
export async function getMyAssignment(assignmentId: number): Promise<StudentAssignment> {
  const response = await api.get<ApiResponse<StudentAssignment>>(`/student/assignments/${assignmentId}`)
  return response.data.data
}

/**
 * 为班级中的学生开通账号。
 *
 * <p>返回的明文临时密码只存在于这一次响应里，调用方必须当场展示或导出；
 * 页面刷新或再次调用同一接口都拿不回来，服务端也不会补发。
 */
export async function provisionStudentAccounts(
  classId: number, studentIds: number[],
): Promise<ProvisionedStudentAccount[]> {
  const response = await api.post<ApiResponse<ProvisionedStudentAccount[]>>(
    `/teacher/classes/${classId}/student-accounts/provision`, { studentIds })
  return response.data.data
}

export async function resetStudentPassword(studentId: number): Promise<ProvisionedStudentAccount> {
  const response = await api.post<ApiResponse<ProvisionedStudentAccount>>(
    `/teacher/students/${studentId}/password/reset`)
  return response.data.data
}

/**
 * 把页面上还留着的明文凭据导出成 Excel。
 *
 * <p>服务端已经拿不到这些明文了（库里只有哈希），所以文件必须由持有明文的页面把数据送回去渲染。
 * 只提交含密码的行：让服务端再过滤一遍，避免以后有人改了页面就多出几行空白密码。
 */
export async function downloadStudentCredentials(
  classId: number, accounts: ProvisionedStudentAccount[],
): Promise<void> {
  const students = accounts
    .filter(account => Boolean(account.temporaryPassword))
    .map(account => ({
      studentNo: account.studentNo,
      name: account.name,
      username: account.username,
      temporaryPassword: account.temporaryPassword,
    }))
  const response = await api.post<Blob>('/teacher/student-accounts/credentials',
    { classId, students }, { responseType: 'blob' })
  saveBlob(response.data, dispositionFilename(
    response.headers['content-disposition'], `student-credentials-${classId}.xlsx`,
  ))
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
