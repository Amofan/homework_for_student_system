import { api, type ApiResponse } from './client'
import { submissionVersionStatusLabel } from './studentSubmission'
import type {
  AnswerCorrection, AnswerReview, Assignment, Question, StateTone, SubmissionQueue,
} from './types'

/**
 * 教师答卷校对接口。
 *
 * <p>单独成模块而不放进 `client.ts`，理由与 `paperImport.ts` 相同：这一组接口是成体系的
 * （看队列 → 识别 → 逐条校对 → 确认入库），混进 `client.ts` 那堆零散导出里，
 * "哪些调用属于答卷校对"就得靠命名自己认。
 *
 * <p>校对链路刻意分成四个调用而不是一个"保存"：识别可能很慢（要调 OCR 服务），
 * 改一条候选是很小的写，确认入库是不可逆的分界线。三件事的失败代价与重试方式完全不同，
 * 合成一个就意味着"改一个字也要重跑一次识别"。
 */

/**
 * 可以进去校对答卷的作业。
 *
 * <p>用作业列表而不是另开一个"待校对作业"接口：答卷校对是**从作业进入**的
 * （点开一份作业，把全班的答卷过一遍），所以候选集就是作业本身。
 * 过滤放在这里而不是服务端，与 `paperImport.ts` 的 `listPaperImportsInProgress` 同一个理由：
 * 服务端暂时不给按状态过滤的参数，真要分页时这里就是唯一要改的地方。
 *
 * <p>只留`已发布`之后的作业：还在整卷校对（`OCR_REVIEW`）的作业连题目都还没入库，
 * 学生根本交不了答卷，摆进下拉框只会让教师点进去发现是空的。
 */
export async function listReviewAssignments(): Promise<Assignment[]> {
  const response = await api.get<ApiResponse<Assignment[]>>('/teacher/assignments')
  return response.data.data.filter(item => item.status !== 'OCR_REVIEW' && item.status !== 'DRAFT')
}

/** 一份作业下的待处理提交，按学号排——教师对着名单核对时就是这个顺序。 */
export async function getSubmissionQueue(assignmentId: number): Promise<SubmissionQueue> {
  const response = await api.get<ApiResponse<SubmissionQueue>>('/teacher/submissions', {
    params: { assignmentId },
  })
  return response.data.data
}

export async function getAnswerReview(versionId: number): Promise<AnswerReview> {
  const response = await api.get<ApiResponse<AnswerReview>>(`/teacher/submissions/${versionId}/answers`)
  return response.data.data
}

/**
 * 跑识别并物化答案候选。
 *
 * <p>POST 而不是 GET：它会让服务端去调 OCR 引擎。重复点不会出问题（幂等）：
 * 已经识别完的不会重跑，教师改过的候选也不会被覆盖。
 */
export async function processAnswers(versionId: number): Promise<AnswerReview> {
  const response = await api.post<ApiResponse<AnswerReview>>(
    `/teacher/submissions/${versionId}/process`)
  return response.data.data
}

/**
 * 改一条候选：改题目映射、改识别文字、标为空白、标记这条校对完成。
 *
 * <p>`version` 必须来自页面上读到的那一份候选：服务端用它做乐观锁，对不上返回
 * `OCR_REVIEW_CONFLICT`（别人改过）或 `ANSWER_CANDIDATE_VERSION_REQUIRED`（没带版本号）。
 * 缺版本号不会被当成 0 静默通过——刚物化完的候选版本恰好就是 0。
 */
export async function patchAnswerCandidate(
  versionId: number, candidateId: number, patch: AnswerCorrection,
): Promise<AnswerReview> {
  const response = await api.patch<ApiResponse<AnswerReview>>(
    `/teacher/submissions/${versionId}/answers/${candidateId}`, patch)
  return response.data.data
}

/**
 * 确认整份答卷的作答入库。
 *
 * <p>这是分界线：成功之后 `student_answer` 里就有这套答案了，成绩与学情统计以它为准。
 * 可重复调用——教师点完之后网络断了再点一次，不该得到冲突，也不该写第二套答案。
 */
export async function confirmAnswers(versionId: number): Promise<AnswerReview> {
  const response = await api.post<ApiResponse<AnswerReview>>(
    `/teacher/submissions/${versionId}/confirm`)
  return response.data.data
}

/**
 * 标准答案参考面板，单独一次请求。
 *
 * <p>刻意不并进校对视图：对着标准答案看学生写的字，会不自觉地"看出"那个答案，
 * 判分就不再独立。要看标准答案得教师自己点开，这是一个保留的摩擦。
 */
export async function getReferenceAnswers(versionId: number): Promise<Question[]> {
  const response = await api.get<ApiResponse<Question[]>>(
    `/teacher/submissions/${versionId}/reference-answers`)
  return response.data.data
}

/**
 * 答卷警告码的可读文案。
 *
 * <p>与整卷导入的警告不同，这里的警告一半是**整份答卷**的问题（第 3 页没交、
 * 第 2 页和第 5 页都对上了模板第 2 页），所以服务端每条都带一句说明文字。
 * 下面这张表只兜住"服务端只给了码"的那些，认不出就原样显示码——
 * 识别链路以后新增一种警告时，界面至少还能把它摆出来，而不是显示成"没有问题"。
 */
export const answerWarningLabel: Record<string, string> = {
  TEMPLATE_MISSING: '这份作业还没有可用的试卷模板，区域只能按识别到的题号归属',
  TEMPLATE_MISMATCH: '这一页对不上模板的任何一页，区域需要手工归类',
  PAGE_MISSING: '模板上有学生没交的页，那些题会落成空白',
  PAGE_DUPLICATE: '有两页对上了同一张模板页，请确认学生是不是拍重了',
  ANSWER_OUT_OF_BOX: '有识别出的作答落在所有题框之外，需要手工归类',
  QUESTION_AMBIGUOUS: '这块区域压在两个题框上，归属不确定',
  CROSS_PAGE_ANSWER: '这道题的作答跨了页，别只批一半',
  ANSWER_BLANK: '这本题学生没有作答',
  LOW_CONFIDENCE: '识别置信度偏低，请核对文字是否准确',
  LOW_CONFIDENCE_FORMULA: '公式识别置信度偏低，请核对 LaTeX 是否准确',
  ANSWER_CROP_FAILED: '这块区域太窄，自动裁剪失败，可以手工重裁',
}

/** 认不出的码原样返回：宁可显示一个英文码，也不要显示成"没有问题"。 */
export function answerWarningText(code: string): string {
  return answerWarningLabel[code] ?? code
}

/**
 * 配准结果的一句话。
 *
 * <p>没对上模板与对上模板的置信度低是两种不同的处境：前者区域全部未归属，
 * 教师得手工归类；后者能看到框但位置未必准。所以配准失败要显式说出来，
 * 而不是显示成"置信度 0%"——那看起来像"对上了但很差"。
 */
export function alignmentState(page: {
  templatePageNo?: number
  alignmentConfidence?: number
}): { label: string; tone: StateTone } {
  if (!page.templatePageNo) return { label: '没对上模板', tone: 'danger' }
  const percent = Math.round((page.alignmentConfidence ?? 0) * 100)
  return { label: `对上了模板第 ${page.templatePageNo} 页（${percent}%）`, tone: percent >= 85 ? 'good' : 'warn' }
}

/**
 * 一版答卷在队列里的状态文案。
 *
 * <p>状态**取值清单**只有一份，在学生侧的 `studentSubmission.ts`（`SUBMISSION_VERSION_STATUS_OPTIONS`）：
 * 后端加一个状态而前端漏补，历来都是"两处清单"造成的。所以这里只覆盖
 * **师生说法不同**的那几个，其余一律回落到那张共享表。
 *
 * <p>师生说法确实不同的原因是主语：学生看到的是"老师已确认我的答题内容"，
 * 教师看到同一版要说"答案已入库"。但"待校对""已被取代"这些两边是同一个意思，
 * 不该复制一遍——复制出来的那份早晚会与共享表分叉。
 */
const TEACHER_STATUS_WORDING: Record<string, string> = {
  PROCESSING: '已提交，待识别',
  NEEDS_REVIEW: '已识别，待校对',
  CONFIRMED: '答案已入库',
  LOCKED: '正在批改，答卷已锁定',
  SUPERSEDED: '已被学生重交取代',
  RETURNED: '已退回，等学生重交',
  FAILED: '识别失败，需重试',
}

/** 色调不分师生：同一版答卷在两端都该是同一个颜色。 */
export function teacherSubmissionStatusLabel(status: string): string {
  return TEACHER_STATUS_WORDING[status] ?? submissionVersionStatusLabel(status)
}
