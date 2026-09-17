import { api, type ApiResponse } from './client'
import type {
  Assignment, PaperCandidate, PaperDocument, PaperDocumentKind, PaperImport, QuestionDifficulty,
  StateTone,
} from './types'

/**
 * 整卷导入接口。
 *
 * <p>单独成模块而不放进 `client.ts`：这一组接口是成体系的（创建 → 上传 → 识别 → 校对 → 确认），
 * 和 `client.ts` 里那些零散的导出混在一起后，"哪些调用属于整卷导入"就得靠命名自己认。
 */

export type PaperImportDocumentKind = PaperDocumentKind

/** 创建导入。班级归属由服务端从作业反查，后续接口都不再传班级。 */
export async function createPaperImport(classId: number, title: string): Promise<PaperImport> {
  const response = await api.post<ApiResponse<PaperImport>>('/teacher/paper-imports', { classId, title })
  return response.data.data
}

/** 上传空白试卷或参考答案。扩展名、签名、解码与大小校验全部由服务端执行，前端只做即时提示。 */
export async function uploadPaperFile(
  assignmentId: number, kind: PaperDocumentKind, file: File,
): Promise<PaperImport> {
  const data = new FormData()
  data.append('kind', kind)
  data.append('file', file)
  const response = await api.post<ApiResponse<PaperImport>>(
    `/teacher/paper-imports/${assignmentId}/files`, data)
  return response.data.data
}

/** 开始识别。服务端按文档幂等建任务，重复调用不会产生第二份识别结果。 */
export async function processPaperImport(assignmentId: number): Promise<PaperImport> {
  const response = await api.post<ApiResponse<PaperImport>>(
    `/teacher/paper-imports/${assignmentId}/process`)
  return response.data.data
}

export async function getPaperImport(assignmentId: number): Promise<PaperImport> {
  const response = await api.get<ApiResponse<PaperImport>>(`/teacher/paper-imports/${assignmentId}`)
  return response.data.data
}

/** 校对表单提交的候选题字段。全部可空——校对是渐进过程，完整性校验在确认时统一执行。 */
export interface PaperCandidateDraft {
  questionCode?: string
  questionType?: PaperCandidate['questionType']
  content?: string
  standardAnswer?: string
  acceptedAnswers?: string[]
  rubricItems?: PaperCandidate['rubricItems']
  totalScore?: number
  difficulty?: QuestionDifficulty
  primaryKnowledgePointId?: number
  assetRegionIds?: number[]
  orderNo?: number
}

export interface PaperRegionPatch {
  /** 目标候选题；为空表示把区域从原候选上摘下来（成为未归属区域）。 */
  candidateId?: number | null
  /** 候选题当前版本；`candidateId` 非空时必填。 */
  version?: number
  regionType?: string
  x?: number
  y?: number
  width?: number
  height?: number
  ocrText?: string
  reviewStatus?: string
  createCrop?: boolean
  /** 把该区域从原候选题拆出来，单独组成一道新题。 */
  split?: boolean
  candidate?: PaperCandidateDraft
}

/**
 * 编辑一块区域（以及它所属的候选题）。
 *
 * <p>`version` 必须来自页面上读到的那一份候选：服务端用 `where version = :expectedVersion`
 * 做乐观锁，版本对不上返回 `OCR_REVIEW_CONFLICT`，提示教师刷新。
 * 不带版本号会被拒（`PAPER_CANDIDATE_VERSION_REQUIRED`），
 * 而不是被当成 0 静默通过——刚识别完的候选版本恰好就是 0。
 */
export async function patchPaperRegion(
  assignmentId: number, regionId: number, patch: PaperRegionPatch,
): Promise<PaperImport> {
  const response = await api.patch<ApiResponse<PaperImport>>(
    `/teacher/paper-imports/${assignmentId}/regions/${regionId}`, patch)
  return response.data.data
}

/** 确认入库。重复调用返回同一份作业，不会重复建题。 */
export async function confirmPaperImport(assignmentId: number): Promise<Assignment> {
  const response = await api.post<ApiResponse<Assignment>>(
    `/teacher/paper-imports/${assignmentId}/confirm`)
  return response.data.data
}

/**
 * 还在校对中的整卷导入。
 *
 * <p>没有单独的列表接口：导入就是 `OCR_REVIEW` 状态的作业，用作业列表过滤即可。
 * 服务端暂时不提供"按状态过滤"的参数，所以过滤放在这里——
 * 如果以后导入数量大到需要服务端分页，这个函数就是唯一要改的地方。
 */
export async function listPaperImportsInProgress(): Promise<Assignment[]> {
  const response = await api.get<ApiResponse<Assignment[]>>('/teacher/assignments')
  return response.data.data.filter(item => item.status === 'OCR_REVIEW')
}

export const documentKindLabel: Record<PaperDocumentKind, string> = {
  EXAM_PAPER: '空白试卷',
  ANSWER_KEY: '参考答案',
}

/**
 * 文档当前状态的展示文案。
 *
 * <p>顺序即优先级，三条判断不能调换：
 * <ol>
 *   <li>已确认／已失败是终态，先判；</li>
 *   <li>`ocrStatus === 'RETRY_WAIT'` 必须排在 `status` 之前——可重试失败时文档状态停在
 *       `PENDING`，先看文档状态就会把"可以重试"显示成"还没开始识别"；</li>
 *   <li>其余按任务状态推断，最后才回落到文档状态。</li>
 * </ol>
 *
 * <p>文案写"可重试"而不是"稍后会自动重试"：`OcrTaskWorker` 只由 `process` 触发，
 * 项目里没有排空 OCR 任务的调度器，不点按钮它就不会再跑一次。
 */
export function documentState(document: PaperDocument): { label: string; tone: StateTone } {
  if (document.status === 'CONFIRMED') return { label: '已确认入库', tone: 'good' }
  if (document.status === 'FAILED') {
    return { label: `识别失败（${document.failureCode || '未知原因'}）`, tone: 'danger' }
  }
  if (document.ocrStatus === 'RETRY_WAIT') {
    return { label: '识别服务暂不可用，可稍后重试', tone: 'warn' }
  }
  if (document.ocrStatus === 'FAILED') {
    return { label: `识别失败（${document.ocrFailureCode || '未知原因'}）`, tone: 'danger' }
  }
  if (document.ocrStatus === 'RUNNING' || document.status === 'PROCESSING') {
    return { label: '正在识别…', tone: 'idle' }
  }
  if (document.ocrStatus === 'NEEDS_REVIEW' || document.status === 'NEEDS_REVIEW') {
    return { label: '识别完成，待校对', tone: 'warn' }
  }
  return { label: '待识别', tone: 'idle' }
}

/**
 * 警告码的可读文案。
 *
 * <p>逐条给文案而不是只显示一个感叹号：这些警告全部是"某处识别得不确定，需要人看一眼"，
 * 而不同警告对应的动作完全不同（补题号 vs 确认答案卷里多出的题 vs 重新框选边界）。
 * 界面上只留一个图标，教师就只能靠猜。
 */
export const paperWarningLabel: Record<string, string> = {
  LOW_CONFIDENCE: '识别置信度偏低，请核对文字是否准确',
  MISSING_QUESTION_CODE: '没有识别到题号，请手动填写',
  QUESTION_TYPE_UNKNOWN: '无法判断题型，请手动选择',
  SCORE_NOT_DETECTED: '没有识别到分值，请手动填写',
  OVERLAPPING_QUESTION_BOX: '两道题的框有重叠，可能是漏检或重复检测',
  AMBIGUOUS_CROSS_PAGE_GROUP: '这道题的边界跨了页，请确认是否该拆开',
  ANSWER_KEY_UNMATCHED: '答案卷里这道题在空白卷中找不到，可能是空白卷漏检',
  ANSWER_KEY_MISSING: '答案卷里没有这道题的答案，标准答案需要手动填写',
}

/**
 * 把警告码翻成文案。
 *
 * <p>文档级警告是 `KIND:FAILURE_CODE` 的形式（例如 `EXAM_PAPER:OCR_INPUT_INVALID`），
 * 未知码也原样显示而不是丢掉：识别链路以后新增一种警告时，
 * 界面至少还能把码摆出来，而不是让教师面对一份"没有问题"的卷子。
 */
export function paperWarningText(code: string): string {
  const known = paperWarningLabel[code]
  if (known) return known
  const separator = code.indexOf(':')
  if (separator > 0) {
    const kind = documentKindLabel[code.slice(0, separator) as PaperDocumentKind]
    if (kind) return `${kind}：${code.slice(separator + 1)}`
  }
  return code
}
