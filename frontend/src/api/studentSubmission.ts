import { api, type ApiResponse } from './client'
import type {
  PageQualityStatus, StateTone, StudentResult, SubmissionHistory, SubmissionVersion,
} from './types'

/**
 * 学生答卷接口。
 *
 * <p>单独成模块而不放进 `client.ts`：这一组接口是成体系的（开始作答 → 传页 → 整理 → 提交 → 看历史），
 * 与整卷导入同样的理由 —— 混进 `client.ts` 那堆零散导出里，"哪些调用属于学生答卷"就得靠命名自己认。
 *
 * <p>路径里一律显式带 `versionId`，而不是让服务端去猜"当前那一版"：学生在两个页面上同时作答时，
 * "当前"会给出两个答案，而其中一个是他没在看的那个。这也是 `startSubmissionDraft` 幂等的意义 ——
 * 刷新、返回上传页、连点两下都拿到同一版草稿，不会攒出一堆空版本。
 */

/** 开始或继续作答。返回手上那一版草稿；已提交过的版本不会再被它改回可编辑。 */
export async function startSubmissionDraft(assignmentId: number): Promise<SubmissionVersion> {
  const response = await api.post<ApiResponse<SubmissionVersion>>(
    `/student/assignments/${assignmentId}/submission`)
  return response.data.data
}

export async function getSubmissionVersion(versionId: number): Promise<SubmissionVersion> {
  const response = await api.get<ApiResponse<SubmissionVersion>>(`/student/submissions/${versionId}`)
  return response.data.data
}

/** 我在这份作业上交过哪几版。含草稿：学生要能看到"我正在改的那一版"也在列表里。 */
export async function getSubmissionHistory(assignmentId: number): Promise<SubmissionHistory> {
  const response = await api.get<ApiResponse<SubmissionHistory>>(
    `/student/assignments/${assignmentId}/submission/history`)
  return response.data.data
}

/**
 * 我在这次作业上的成绩：老师复核过的那几道题。
 *
 * <p>返回的是安全投影（没有建议分、评分明细、标准答案），所以这里不需要再挑字段 ——
 * 挑字段的写法迟早会漏掉一个，而漏掉的那次就是把批改依据发到学生端的那次。
 */
export async function getMyResult(assignmentId: number): Promise<StudentResult> {
  const response = await api.get<ApiResponse<StudentResult>>(
    `/student/assignments/${assignmentId}/result`)
  return response.data.data
}

/**
 * 追加上传一个文件（图片一页，PDF 可能多页），返回这一版的最新页面列表。
 *
 * <p>一次只传一个文件：进度条、失败重试、以及"哪一张没传上去"都以文件为单位。
 * 一次传十个文件的话，其中一个失败时只能整批重来，而学生要自己猜是哪一张。
 *
 * <p>`onProgress` 是上传字节百分比，不是"处理进度"：服务端收完才开始落库与渲染 PDF 页，
 * 这段等待没有可上报的阶段，界面在 100% 之后还要显示"服务器正在处理"。
 */
export async function uploadSubmissionFile(
  versionId: number, file: File, onProgress?: (percent: number) => void,
): Promise<SubmissionVersion> {
  const data = new FormData()
  // 字段名 `files` 对应服务端 `@RequestParam("files") List<MultipartFile>`；
  // 一次一个文件只是调用方的选择，接口本身仍接受多个。
  data.append('files', file)
  const response = await api.post<ApiResponse<SubmissionVersion>>(
    `/student/submissions/${versionId}/pages`, data, {
      onUploadProgress: (event) => {
        if (!onProgress || !event.total) return
        onProgress(Math.min(100, Math.round((event.loaded / event.total) * 100)))
      },
    })
  return response.data.data
}

/** 重排页面。列表必须恰好是当前全部页面、每个一次，服务端会核对。 */
export async function reorderSubmissionPages(
  versionId: number, pageIds: number[],
): Promise<SubmissionVersion> {
  const response = await api.patch<ApiResponse<SubmissionVersion>>(
    `/student/submissions/${versionId}/pages/order`, { pageIds })
  return response.data.data
}

/**
 * 旋转一页。
 *
 * <p>传的是目标角度而不是增量：客户端不需要知道当前是多少度才能算下一次该传什么，
 * 也不会因为丢掉一次响应就把页面转成 180 度。
 */
export async function rotateSubmissionPage(
  versionId: number, pageId: number, degrees: number,
): Promise<SubmissionVersion> {
  const response = await api.post<ApiResponse<SubmissionVersion>>(
    `/student/submissions/${versionId}/pages/${pageId}/rotation`, { degrees })
  return response.data.data
}

export async function deleteSubmissionPage(
  versionId: number, pageId: number,
): Promise<SubmissionVersion> {
  const response = await api.delete<ApiResponse<SubmissionVersion>>(
    `/student/submissions/${versionId}/pages/${pageId}`)
  return response.data.data
}

/**
 * 确认提交。提交之后这一版只读。
 *
 * <p>重复提交按重试处理（服务端幂等）：客户端没收到响应时会再发一次，
 * 学生刚点的那一下不该看起来像失败。
 *
 * <p>`acknowledgedPageIds` 是学生逐页确认过"虽然可能不清楚，我就要这样交"的页面 id。
 * 只有质量检测给出警告（`SUBMISSION_QUALITY_WARNING`）的页面需要它；
 * 照片不合格（`SUBMISSION_QUALITY_BLOCKING`）的页面不在此列 —— 那些必须重拍。
 */
export async function submitSubmissionVersion(
  versionId: number, acknowledgedPageIds: number[] = [],
): Promise<SubmissionVersion> {
  const response = await api.post<ApiResponse<SubmissionVersion>>(
    `/student/submissions/${versionId}/submit`, { acknowledgedPageIds })
  return response.data.data
}

/** 允许上传的扩展名。与服务端 `UploadFileInspector` 接受的一致；真正的判据在服务端。 */
export const SUBMISSION_ALLOWED_EXTENSIONS = ['.pdf', '.png', '.jpg', '.jpeg']

/**
 * 单文件上限，与服务端 `app.storage.max-file-bytes` 保持一致。
 *
 * <p>前端只用来提前拦住明显超限的文件，省掉一次"选了 200 MB、等上传完才被告知不行"的往返；
 * 它不替代服务端校验 —— 服务端仍然要判文件签名、能否解码、页数与像素上限。
 */
export const SUBMISSION_MAX_FILE_BYTES = 26214400

/** 页面数上限，与服务端 `app.storage.max-pages` 一致。 */
export const SUBMISSION_MAX_PAGES = 40

/**
 * 版本状态的中文标签与色调。
 *
 * <p>一份清单派生标签表、已知集合与色调，理由同 `ASSIGNMENT_STATUS_OPTIONS`：
 * 后端加了状态而前端漏补一项，全都源于两处清单。
 *
 * <p>顺序就是学生看到的时间顺序：草稿 → 已上传 → 识别中 → 待老师校对 → 已确认 → 批改中，
 * 之后是两种结局（被取代 / 被退回）与一种失败。
 */
export const SUBMISSION_VERSION_STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿，还没提交', tone: 'idle' },
  { value: 'UPLOADED', label: '页面已上传，还没提交', tone: 'idle' },
  { value: 'PROCESSING', label: '已提交，正在识别', tone: 'idle' },
  { value: 'NEEDS_REVIEW', label: '等老师校对答题内容', tone: 'warn' },
  { value: 'CONFIRMED', label: '老师已确认答题内容', tone: 'good' },
  { value: 'LOCKED', label: '老师正在批改', tone: 'good' },
  { value: 'SUPERSEDED', label: '已被后来的提交取代', tone: 'idle' },
  { value: 'RETURNED', label: '老师已退回，需要重新提交', tone: 'danger' },
  { value: 'FAILED', label: '识别失败，等老师处理', tone: 'danger' },
] as const

const SUBMISSION_VERSION_STATUS_MAP: Record<string, { label: string; tone: StateTone }> =
  Object.fromEntries(SUBMISSION_VERSION_STATUS_OPTIONS.map(option => [option.value, option]))

/**
 * 版本状态的展示文案。
 *
 * <p>形参是 `string` 而不是联合类型：服务端可能比前端先加上新状态，
 * 原样显示英文总比显示"未知"好 —— 既不丢信息，也让新取值在界面上立刻看得出来。
 */
export function submissionVersionStatusLabel(status: string): string {
  return SUBMISSION_VERSION_STATUS_MAP[status]?.label ?? status
}

export function submissionVersionStatusTone(status: string): StateTone {
  return SUBMISSION_VERSION_STATUS_MAP[status]?.tone ?? 'idle'
}

/** 已知状态集合，供测试核对后端产出是否都在标签表里。 */
export const KNOWN_SUBMISSION_VERSION_STATUSES: string[] =
  SUBMISSION_VERSION_STATUS_OPTIONS.map(option => option.value)

const PAGE_QUALITY_MAP: Record<PageQualityStatus, { label: string; tone: StateTone }> = {
  OK: { label: '清晰', tone: 'good' },
  WARNING: { label: '可能不清楚', tone: 'warn' },
  BLOCKING: { label: '不合格，需要重拍', tone: 'danger' },
}

/**
 * 单页质量的展示文案。
 *
 * <p>认不出的取值按"清晰"处理：默认值也是 `OK`（没做过质量评估的页面按放行处理），
 * 把它显示成警告会让一整个部署环境的学生都在纠结一张其实没问题的照片。
 */
export function pageQualityState(status: string): { label: string; tone: StateTone } {
  return PAGE_QUALITY_MAP[status as PageQualityStatus] ?? PAGE_QUALITY_MAP.OK
}
