import { AxiosError, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'

import { api, errorCode } from './client'
import {
  KNOWN_SUBMISSION_VERSION_STATUSES, getSubmissionHistory, pageQualityState,
  reorderSubmissionPages, rotateSubmissionPage, startSubmissionDraft, submitSubmissionVersion,
  submissionVersionStatusLabel, submissionVersionStatusTone, uploadSubmissionFile,
} from './studentSubmission'
import type { SubmissionVersion } from './types'

const originalAdapter = api.defaults.adapter

/** 最近一次请求，供断言 URL、方法、请求体。 */
let lastRequest: InternalAxiosRequestConfig

function version(): SubmissionVersion {
  return {
    id: 91,
    assignmentId: 501,
    studentId: 1001,
    versionNo: 2,
    status: 'UPLOADED',
    current: false,
    editable: true,
    createdAt: '2026-09-16T00:00:00Z',
    pages: [],
  }
}

/** 用自定义 axios 适配器伪造响应，避免引入 MockAdapter 等新依赖。 */
function respondWith(status: number, data: unknown): void {
  const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
    lastRequest = config
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

/** 服务端的成功响应外壳。 */
function ok<T>(data: T): { success: boolean; data: T; timestamp: string } {
  return { success: true, data, timestamp: '2026-09-16T00:00:00Z' }
}

beforeEach(() => {
  lastRequest = undefined as unknown as InternalAxiosRequestConfig
})

afterEach(() => {
  api.defaults.adapter = originalAdapter
})

describe('学生答卷接口', () => {
  /**
   * 开始作答是 POST 而不是 GET：它会建出版本。幂等，所以手机上刷新、返回再进来、
   * 连点两下都拿到同一版草稿 —— 这条由服务端保证，前端只负责走对路径。
   */
  it('开始作答按作业建出版本', async () => {
    respondWith(200, ok(version()))

    await expect(startSubmissionDraft(501)).resolves.toMatchObject({ id: 91, versionNo: 2 })

    expect(lastRequest.method).toBe('post')
    expect(lastRequest.url).toBe('/student/assignments/501/submission')
  })

  it('版本历史按作业维度取，含草稿', async () => {
    respondWith(200, ok({
      assignmentId: 501, currentVersionId: 91, canStartNewVersion: true,
      versions: [{ id: 91, versionNo: 2, status: 'RETURNED', current: false, editable: true, pageCount: 3 }],
    }))

    const history = await getSubmissionHistory(501)

    expect(lastRequest.url).toBe('/student/assignments/501/submission/history')
    expect(history.canStartNewVersion).toBe(true)
    expect(history.versions[0].pageCount).toBe(3)
  })
})

describe('上传页面', () => {
  /**
   * 字段名 `files` 是服务端契约（`@RequestParam("files")`），写错会得到 400 而不是"上传成功"。
   */
  it('一个文件放在 files 字段里发出去', async () => {
    respondWith(200, ok(version()))
    const file = new File(['page'], 'IMG_0001.jpg', { type: 'image/jpeg' })

    await uploadSubmissionFile(91, file)

    expect(lastRequest.url).toBe('/student/submissions/91/pages')
    const body = lastRequest.data as FormData
    expect(body).toBeInstanceOf(FormData)
    expect(body.get('files')).toBe(file)
  })

  /**
   * 进度按上传字节算。服务端收完才开始落库与渲染 PDF 页，那一段没有可上报的阶段，
   * 所以界面在 100% 之后还要显示"服务器正在处理"，而不是停在 99% 假装还在传。
   */
  it('把上传字节百分比报给调用方', async () => {
    const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
      lastRequest = config
      config.onUploadProgress?.({ loaded: 20, total: 80, bytes: 20, lengthComputable: true })
      config.onUploadProgress?.({ loaded: 80, total: 80, bytes: 60, lengthComputable: true })
      return { data: ok(version()), status: 200, statusText: '200', headers: {}, config } as AxiosResponse
    }
    api.defaults.adapter = adapter
    const percents: number[] = []

    await uploadSubmissionFile(91, new File(['a'], 'a.png'), percent => percents.push(percent))

    expect(percents).toEqual([25, 100])
  })

  /** 拿不到总字节时不报进度：报一个恒定的 0% 比不报更像卡住了。 */
  it('没有总长度时不报进度', async () => {
    const adapter: AxiosAdapter = async (config: InternalAxiosRequestConfig) => {
      config.onUploadProgress?.({ loaded: 10, bytes: 10, lengthComputable: false })
      return { data: ok(version()), status: 200, statusText: '200', headers: {}, config } as AxiosResponse
    }
    api.defaults.adapter = adapter
    const percents: number[] = []

    await uploadSubmissionFile(91, new File(['a'], 'a.png'), percent => percents.push(percent))

    expect(percents).toEqual([])
  })
})

describe('整理与提交', () => {
  it('重排把完整顺序原样送上', async () => {
    respondWith(200, ok(version()))

    await reorderSubmissionPages(91, [3, 1, 2])

    expect(lastRequest.url).toBe('/student/submissions/91/pages/order')
    expect(lastRequest.method).toBe('patch')
    expect(JSON.parse(lastRequest.data as string)).toEqual({ pageIds: [3, 1, 2] })
  })

  /** 传目标角度而不是增量：客户端不需要知道当前多少度，也不会因为丢响应转成 180 度。 */
  it('旋转送的是目标角度', async () => {
    respondWith(200, ok(version()))

    await rotateSubmissionPage(91, 7, 270)

    expect(lastRequest.url).toBe('/student/submissions/91/pages/7/rotation')
    expect(JSON.parse(lastRequest.data as string)).toEqual({ degrees: 270 })
  })

  it('提交时把逐页确认一起送出，不确认也要送空数组', async () => {
    respondWith(200, ok({ ...version(), status: 'PROCESSING', editable: false }))

    await submitSubmissionVersion(91, [7])
    expect(JSON.parse(lastRequest.data as string)).toEqual({ acknowledgedPageIds: [7] })

    await submitSubmissionVersion(91)
    expect(JSON.parse(lastRequest.data as string)).toEqual({ acknowledgedPageIds: [] })
  })

  /**
   * 锁住是学生侧唯一需要换文案再加一个动作的状态：换文案之外还得把上传入口藏掉，
   * 否则学生只会看到一个永远失败的按钮。所以错误码要能取到，不能只看提示文本。
   */
  it('已开始批改的错误码能被调用方识别', async () => {
    respondWith(409, {
      success: false,
      data: null,
      error: { code: 'SUBMISSION_LOCKED', message: '这一版已经开始批改，不能再修改或提交；要改请联系老师退回' },
      timestamp: '2026-09-16T00:00:00Z',
    })

    const error = await submitSubmissionVersion(91).catch((reason: unknown) => reason)

    expect(errorCode(error)).toBe('SUBMISSION_LOCKED')
  })
})

describe('状态标签', () => {
  /** 后端加了状态而前端漏补一项，全都源于两处清单；这条用例就是那份清单的哨兵。 */
  it('后端产出的每个版本状态都有中文标签', () => {
    expect(KNOWN_SUBMISSION_VERSION_STATUSES).toHaveLength(9)
    for (const status of KNOWN_SUBMISSION_VERSION_STATUSES) {
      expect(submissionVersionStatusLabel(status)).not.toBe(status)
    }
    // 学生只需要看懂两件事：这一版还用不用管，以及老师退回来的是什么。
    expect(submissionVersionStatusLabel('RETURNED')).toContain('退回')
    expect(submissionVersionStatusTone('RETURNED')).toBe('danger')
    expect(submissionVersionStatusTone('LOCKED')).toBe('good')
  })

  it('认不出的取值原样显示，而不是显示未知', () => {
    expect(submissionVersionStatusLabel('ARCHIVED')).toBe('ARCHIVED')
    expect(submissionVersionStatusTone('ARCHIVED')).toBe('idle')
  })

  /** 默认值也是 OK：没做过质量评估的页面按放行处理，显示成警告会让全班都在纠结一张没问题的照片。 */
  it('认不出的页面质量按清晰处理', () => {
    expect(pageQualityState('WARNING')).toEqual({ label: '可能不清楚', tone: 'warn' })
    expect(pageQualityState('BLOCKING').tone).toBe('danger')
    expect(pageQualityState('PENDING')).toEqual(pageQualityState('OK'))
  })
})
