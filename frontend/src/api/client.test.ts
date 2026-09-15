import { AxiosError, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { api, dispositionFilename, downloadEvaluationCases, downloadExerciseDocx, errorMessage, setUnauthorizedHandler, TOKEN_STORAGE_KEY } from './client'

const originalAdapter = api.defaults.adapter

/** 用自定义 axios 适配器伪造响应，避免引入 MockAdapter 等新依赖。 */
function respondWith(status: number, data: unknown): void {
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    const response = {
      data,
      status,
      statusText: String(status),
      headers: {},
      config,
    } as AxiosResponse
    if (status >= 200 && status < 300) return response
    throw new AxiosError(`Request failed with status code ${status}`, String(status), config, null, response)
  }
  api.defaults.adapter = adapter
}

const authRequiredBody = {
  success: false,
  data: null,
  error: { code: 'AUTH_REQUIRED', message: '登录状态已失效，请重新登录' },
  timestamp: '2026-09-14T00:00:00Z',
}

describe('响应拦截器', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    setUnauthorizedHandler(null)
  })

  it('收到 401 时清除本地令牌并通知外部处理器跳转登录', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    localStorage.setItem(TOKEN_STORAGE_KEY, 'stale-token')
    respondWith(401, authRequiredBody)

    await expect(api.get('/classes')).rejects.toBeTruthy()

    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('登录接口自身返回 401 时保留现场，不跳转登录页', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    localStorage.setItem(TOKEN_STORAGE_KEY, 'existing-token')
    respondWith(401, authRequiredBody)

    await expect(api.post('/auth/login', { username: 'demo', password: 'wrong' })).rejects.toBeTruthy()

    expect(handler).not.toHaveBeenCalled()
  })

  it('非 401 失败不清除令牌', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    localStorage.setItem(TOKEN_STORAGE_KEY, 'good-token')
    respondWith(500, { success: false, error: { code: 'INTERNAL_ERROR', message: '服务器内部错误' } })

    await expect(api.get('/classes')).rejects.toBeTruthy()

    expect(localStorage.getItem(TOKEN_STORAGE_KEY)).toBe('good-token')
    expect(handler).not.toHaveBeenCalled()
  })
})

describe('errorMessage', () => {
  it('优先展示服务端返回的业务提示，而不是内部错误信息', async () => {
    respondWith(401, authRequiredBody)

    const error = await api.get('/classes').catch((reason: unknown) => reason)

    expect(errorMessage(error)).toBe('登录状态已失效，请重新登录')
  })

  it('连接不上后端时给出可操作的中文提示', () => {
    const networkError = new AxiosError('Network Error', 'ERR_NETWORK')

    expect(errorMessage(networkError)).toBe('服务暂时不可用，请检查后端是否已启动。')
  })

  it('普通异常回退到自身消息，非 Error 时给出兜底文案', () => {
    expect(errorMessage(new Error('导入文件格式不正确'))).toBe('导入文件格式不正确')
    expect(errorMessage('unexpected')).toBe('操作未完成，请稍后重试。')
  })
})

describe('downloadExerciseDocx', () => {
  /** 记录被点击的下载链接；jsdom 不会真的下载，只能看链接长什么样。 */
  let saved: { href: string; download: string }[] = []

  beforeEach(() => {
    saved = []
    localStorage.clear()
    localStorage.setItem(TOKEN_STORAGE_KEY, 'token-abc')
    // jsdom 没有实现 createObjectURL；补上桩函数，否则下载路径无从验证
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:mock'), revokeObjectURL: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved.push({ href: this.href, download: this.download })
    })
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    vi.restoreAllMocks()
  })

  it('带令牌以 Blob 方式取回文档，并按服务端给的文件名保存', async () => {
    let authorization: unknown
    let responseType: unknown
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      authorization = config.headers.Authorization
      responseType = config.responseType
      return {
        data: new Blob(['PK']),
        status: 200,
        statusText: '200',
        // 服务端只在有公式降级时才带这个头；省略号表示降级题目超过 20 个。
        headers: {
          'content-disposition': 'attachment; filename="exercise-7.docx"',
          'x-formula-fallback': 'Q-ALG-007,Q-GEO-005,...',
        },
        config,
      } as AxiosResponse
    }

    await expect(downloadExerciseDocx(7)).resolves.toEqual(['Q-ALG-007', 'Q-GEO-005', '...'])

    expect(authorization).toBe('Bearer token-abc')
    expect(responseType).toBe('blob')
    expect(saved).toEqual([{ href: 'blob:mock', download: 'exercise-7.docx' }])
  })

  it('没有降级响应头时返回空数组', async () => {
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => ({
      data: new Blob(['PK']),
      status: 200,
      statusText: '200',
      headers: { 'content-disposition': 'attachment; filename="exercise-8.docx"' },
      config,
    } as AxiosResponse)

    await expect(downloadExerciseDocx(8)).resolves.toEqual([])
  })

  it('服务端没给文件名时退回调用方给的兜底名', () => {
    expect(dispositionFilename(undefined, 'exercise-7.docx')).toBe('exercise-7.docx')
    expect(dispositionFilename('attachment', 'exercise-7.docx')).toBe('exercise-7.docx')
    expect(dispositionFilename('attachment; filename="custom.docx"', 'exercise-7.docx'))
      .toBe('custom.docx')
  })
})

describe('downloadEvaluationCases', () => {
  let saved: { href: string; download: string }[] = []

  beforeEach(() => {
    saved = []
    localStorage.clear()
    localStorage.setItem(TOKEN_STORAGE_KEY, 'token-abc')
    Object.assign(URL, { createObjectURL: vi.fn(() => 'blob:mock'), revokeObjectURL: vi.fn() })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      saved.push({ href: this.href, download: this.download })
    })
  })

  afterEach(() => {
    api.defaults.adapter = originalAdapter
    vi.restoreAllMocks()
  })

  it('取回 CSV 并把服务端的三个计数一起返回', async () => {
    let authorization: unknown
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => {
      authorization = config.headers.Authorization
      return {
        data: new Blob(['case_id']),
        status: 200,
        statusText: '200',
        headers: {
          'content-disposition': 'attachment; filename="grading-cases-501.csv"',
          'x-evaluation-reviewed': '12',
          'x-evaluation-exported': '10',
          'x-evaluation-skipped-missing-ai-error': '2',
        },
        config,
      } as AxiosResponse
    }

    await expect(downloadEvaluationCases(501)).resolves.toEqual({
      reviewed: 12, exported: 10, skippedMissingAiError: 2,
    })

    // 令牌仍只走 Authorization 头，绝不拼进 URL
    expect(authorization).toBe('Bearer token-abc')
    expect(saved).toEqual([{ href: 'blob:mock', download: 'grading-cases-501.csv' }])
  })

  it('响应头缺失时计数按 0 而不是 NaN', async () => {
    // 反向代理可能吃掉自定义响应头；读到 NaN 会让页面显示"已导出 NaN 条"
    api.defaults.adapter = async (config: InternalAxiosRequestConfig) => ({
      data: new Blob(['case_id']),
      status: 200,
      statusText: '200',
      headers: {},
      config,
    } as AxiosResponse)

    await expect(downloadEvaluationCases(501)).resolves.toEqual({
      reviewed: 0, exported: 0, skippedMissingAiError: 0,
    })
    expect(saved).toEqual([{ href: 'blob:mock', download: 'grading-cases-501.csv' }])
  })
})
