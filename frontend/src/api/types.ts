import type { AssignmentStatus } from './assignmentStatus'
import type { ErrorType } from './errorTypes'

/**
 * 当前登录主体。
 *
 * <p>用判别联合而不是「一个带可选字段的接口」：角色不适用的字段在联合里根本不存在，
 * 于是 `profile.teacherId` 只有在 `role === 'TEACHER'` 分支里才可访问，
 * 类型检查器替我们挡住「把学生的 studentId 当教师 ID 用」这类错误，
 * 不需要 `as TeacherProfile` 之类的断言。
 */
export type Profile =
  | {
    role: 'TEACHER'
    userId: number
    teacherId: number
    displayName: string
    schoolName?: string
    passwordChangeRequired: false
  }
  | {
    role: 'STUDENT'
    userId: number
    studentId: number
    displayName: string
    passwordChangeRequired: boolean
  }

export type Role = Profile['role']
export type TeacherProfile = Extract<Profile, { role: 'TEACHER' }>
export type StudentProfile = Extract<Profile, { role: 'STUDENT' }>

export type AccountStatus = 'ACTIVE' | 'PASSWORD_CHANGE_REQUIRED' | 'DISABLED'

export interface ProvisionedStudentAccount {
  studentId: number
  studentNo: string
  name: string
  username: string
  /** 只在开通或重置的那一次响应里出现；幂等重放时为 undefined。 */
  temporaryPassword?: string
  newlyProvisioned: boolean
}

export interface Classroom { id: number; classCode: string; name: string; grade?: number; semester?: string; studentCount: number }
export interface Student {
  id: number; classId: number; studentNo: string; name: string
  /** 未开通账号时整组不存在（服务端省略 null 字段），据此决定按钮文案。 */
  accountUsername?: string
  accountStatus?: AccountStatus
}
export interface KnowledgePoint { id: number; parentId?: number; code: string; name: string; grade: number; active: boolean }
export interface RubricItem { id?: number; orderNo: number; title: string; criteria: string; maxScore: number }

/**
 * 题目配图的用途。
 *
 * `STEM_FIGURE` 进题干；`SOURCE_CROP` 是"这道题从原卷哪一块识别出来的"的回溯图；
 * `REFERENCE_IMAGE` 来自参考答案卷，只在教师校对时展示。
 */
export type QuestionAssetRole = 'STEM_FIGURE' | 'SOURCE_CROP' | 'REFERENCE_IMAGE'

export interface QuestionAsset { id: number; fileId: number; role: QuestionAssetRole; sortOrder: number }

export type QuestionDifficulty = 'BASIC' | 'MEDIUM' | 'ADVANCED'
export interface Question {
  id: number; questionCode: string; type: 'SINGLE_CHOICE' | 'FILL_BLANK' | 'SOLUTION'; content: string
  standardAnswer?: string; totalScore: number; difficulty?: QuestionDifficulty; primaryKnowledgePointId: number
  acceptedAnswers: string[]; rubricItems: RubricItem[]
  /**
   * 题目配图。服务端**总是**返回这个字段（没有配图时是空数组），
   * 所以这里是必填项：用可选字段会让"后端漏发字段"变成运行时的静默空白，
   * 而类型检查本来能在第一时间发现它。
   *
   * 图片本身只能通过带鉴权的 `/teacher/files/{id}` 读取，这里只有 `fileId`，没有 URL。
   */
  assets: QuestionAsset[]
}
/**
 * 教师视角的作业。
 *
 * `version` 是乐观锁版本号：发布作业时必须把它原样回传，服务端据此发现
 * 「你看到的版本已经被别人改过」，而不是让后一次发布静默覆盖前一次的截止时间。
 * `publishedAt` 缺省表示还没有发布过，学生看不到这份作业。
 */
export interface Assignment {
  id: number
  classId: number
  title: string
  status: AssignmentStatus
  publishedAt?: string
  dueAt?: string
  version: number
  questionIds: number[]
}

/**
 * 学生视角的作业。
 *
 * 这是安全投影，只有这几个字段：标准答案、评分项、其他学生的信息都不在其中。
 * 字段的增删对应后端 `StudentAssignmentView`，改动前先看那边是不是也改了。
 */
export interface StudentAssignment {
  id: number
  title: string
  className: string
  teacherName: string
  status: AssignmentStatus
  dueAt?: string
  questionCount: number
  /** 当前学生自己的提交摘要；从未提交时为 `NOT_SUBMITTED`。 */
  submissionStatus: string
}
/**
 * 待复核答案名下的一张答案图。
 *
 * `pageNo` 与归一化坐标是"这句话从哪来"：跨页续写时教师要知道这几张图分别是哪一页的哪一块。
 * 它们可缺省——区域会随模板重新配准被替换掉，但答案图本身是批改依据，仍然在。
 */
export interface AnswerAsset {
  fileId: number; role: string; pageNo?: number
  x?: number; y?: number; width?: number; height?: number; sortOrder: number
}
export interface ReviewQueueItem {
  resultId: number; answerId: number; studentNo: string; studentName: string; questionCode: string
  questionContent: string; answerContent: string; source: 'RULE' | 'AI'; suggestedScore: number; totalScore: number
  errorType: ErrorType; teacherExplanation?: string; studentFeedback?: string; scoreDetails: string
  answerAssets: AnswerAsset[]
}
export interface KnowledgeMastery {
  knowledgePointId: number; code: string; name: string; earnedScore: number; possibleScore: number; masteryRatio: number; answerCount: number
}
export interface ErrorRanking { errorType: string; count: number }

export type ExerciseTier = 'FOUNDATION' | 'CORRECTION' | 'IMPROVEMENT'
export type ExerciseStatus = 'DRAFT' | 'APPROVED'
export interface ExerciseItem {
  tier: ExerciseTier; sortOrder: number; questionId: number; questionCode: string; content: string
  totalScore: number; difficulty: QuestionDifficulty; knowledgePointId: number; knowledgePointName: string
  standardAnswer?: string; rubricItems: RubricItem[]
}
export interface ExerciseSet {
  id: number; classId: number; className: string; sourceAssignmentId: number; sourceAssignmentTitle: string
  title: string; status: ExerciseStatus; createdAt: string; approvedAt?: string
  items: ExerciseItem[]; notices: string[]
}

/**
 * 整卷导入。
 *
 * 一次导入就是一份 `OCR_REVIEW` 状态的作业，所以 `assignmentId` 既是导入标识也是作业标识——
 * 发布、学生提交这些后续环节不需要第二套 id。
 */
export type PaperDocumentKind = 'EXAM_PAPER' | 'ANSWER_KEY'
export type PaperDocumentStatus = 'PENDING' | 'PROCESSING' | 'NEEDS_REVIEW' | 'CONFIRMED' | 'FAILED'
export type OcrTaskStatus = 'PENDING' | 'RUNNING' | 'RETRY_WAIT' | 'NEEDS_REVIEW' | 'CONFIRMED' | 'FAILED'
export type RegionReviewStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED'

/**
 * OCR 识别出的一块区域，坐标是归一化到 0..1 的值。
 *
 * 归一化而不是像素：页面缩略图、原图、裁剪图是三种尺寸，用像素坐标就必须在每个使用点
 * 乘以不同的缩放比，而只要有一处忘了乘，框就会静默错位。
 */
export interface PaperRegion {
  regionId: number
  regionType: string
  x: number; y: number; width: number; height: number
  ocrText?: string
  ocrLatex?: string
  confidence?: number
  cropFileId?: number
  reviewStatus: RegionReviewStatus
  /** 归属的候选题；未归属时不存在（服务端省略 null 字段）。 */
  candidateId?: number
}

export interface PaperPage {
  pageId: number; pageNo: number; pageFileId: number
  thumbnailFileId?: number
  /** 识别坐标所依据的那一份像素尺寸；缺了就无法把归一化坐标换算成像素位置。 */
  width?: number; height?: number
  regions: PaperRegion[]
}

export interface PaperDocument {
  documentId: number
  documentKind: PaperDocumentKind
  status: PaperDocumentStatus
  failureCode?: string
  /**
   * 最新一条 OCR 任务的状态。
   *
   * 必须与 `status` 一起看：可重试失败（识别服务暂时不可用）时任务处于 `RETRY_WAIT`，
   * 而文档状态仍是 `PENDING`。只看文档状态会把"稍后自动重试"显示成"还没开始"。
   */
  ocrStatus?: OcrTaskStatus
  ocrFailureCode?: string
  pageCount: number
  pages: PaperPage[]
}

/**
 * 一道候选题。
 *
 * 名字里的"候选"是关键：它可能来自 OCR，也可能来自教师的编辑，但在确认之前都不是正式题目。
 * `version` 是乐观锁版本号，编辑时必须原样回传，否则服务端返回 `OCR_REVIEW_CONFLICT`。
 */
export interface PaperCandidate {
  id: number
  documentId: number
  documentKind: PaperDocumentKind
  orderNo: number
  questionCode?: string
  questionType?: Question['type']
  content?: string
  standardAnswer?: string
  acceptedAnswers: string[]
  rubricItems: RubricItem[]
  totalScore?: number
  difficulty?: QuestionDifficulty
  primaryKnowledgePointId?: number
  /** 构成这道题的所有来源区域；合并会把多块并进来，拆分会把一块分出去，但都不会删除区域。 */
  sourceRegionIds: number[]
  /** 默认作为题图的来源区域。 */
  assetRegionIds: number[]
  /** 答案卷候选匹配到的空白卷题号；空白卷候选不返回此字段。 */
  matchedQuestionCode?: string
  confidence?: number
  /** 结构化警告码；界面必须逐条给出可读文案，而不是只显示一个图标。 */
  warnings: string[]
  reviewStatus: RegionReviewStatus
  version: number
}

export interface PaperImport {
  assignmentId: number
  classId: number
  title: string
  status: AssignmentStatus
  confirmed: boolean
  documents: PaperDocument[]
  candidates: PaperCandidate[]
  warnings: string[]
}

/**
 * 状态标签的色调。
 *
 * <p>放在这里而不是各自的接口模块里：它是界面词汇，不是某个接口的一部分。
 * 整卷导入与学生答卷两处各定义一份，迟早会出现"同一个词在两张页面上颜色不一样"。
 */
export type StateTone = 'good' | 'warn' | 'danger' | 'idle'

/**
 * 提交版本的状态。
 *
 * 与后端 `SubmissionVersionStatus` 一一对应。两处判断必须来自同一个地方：
 * 界面上"能不能改"由服务端算好的 `editable` 决定，这里的取值只用来显示标签与提示，
 * 不参与权限判断 —— 前端自己算一遍迟早与服务端分叉，表现是"按钮亮着但点了报错"。
 */
export type SubmissionVersionStatus =
  | 'DRAFT' | 'UPLOADED' | 'PROCESSING' | 'NEEDS_REVIEW' | 'CONFIRMED'
  | 'LOCKED' | 'SUPERSEDED' | 'RETURNED' | 'FAILED'

/**
 * 页面质量。与服务端 `document_page.quality_status` 同取值：
 * `BLOCKING` 直接拦下提交，`WARNING` 要学生逐页确认后才放行。
 */
export type PageQualityStatus = 'OK' | 'WARNING' | 'BLOCKING'

/**
 * 学生答卷上的一页。
 *
 * `fileName` 是这一页来自哪张上传文件，PDF 拆出的多页会带同一个名字 —— 手机相册里
 * 同一批照片的文件名往往只差一两个字符，页面编号才是身份，文件名是佐证。
 *
 * `pageFileId` 与 `rotatedFileId` 是两份图：前者是学生交的原样，后者是旋转后的展示图。
 * 展示用 `rotatedFileId`，想回到"学生交的是什么"时用 `pageFileId`。
 */
export interface SubmissionPage {
  id: number
  pageNo: number
  documentId: number
  documentPageNo: number
  fileName: string
  pageFileId: number
  thumbnailFileId?: number
  rotatedFileId: number
  rotationDegrees: number
  qualityStatus: PageQualityStatus
  width?: number
  height?: number
}

/**
 * 一个提交版本。
 *
 * `current` 与 `editable` 是两件事：`current` 是"已提交且占着当前提交位置"，
 * 学生手上的草稿两者都是 false；`editable` 是服务端算好的"现在还能不能改"。
 */
export interface SubmissionVersion {
  id: number
  assignmentId: number
  studentId: number
  versionNo: number
  status: SubmissionVersionStatus
  current: boolean
  editable: boolean
  submittedAt?: string
  lockedAt?: string
  returnedAt?: string
  returnReason?: string
  createdAt: string
  pages: SubmissionPage[]
}

export interface SubmissionVersionSummary {
  id: number
  versionNo: number
  status: SubmissionVersionStatus
  current: boolean
  editable: boolean
  submittedAt?: string
  returnedAt?: string
  returnReason?: string
  pageCount: number
}

/**
 * 某份作业下这个学生的全部版本。
 *
 * `canStartNewVersion` 由服务端给出：能不能新建版本要看当前版本是不是已经进入批改，
 * 前端自己判断就会变成"按钮亮着但提交被拒"。
 */
export interface SubmissionHistory {
  assignmentId: number
  currentVersionId?: number
  canStartNewVersion: boolean
  versions: SubmissionVersionSummary[]
}

/** 候选的校对状态。只有 `CONFIRMED` 的候选能进正式答案。 */
export type AnswerReviewStatus = 'PENDING' | 'CONFIRMED'

/**
 * 教师校对一版答卷时看到的整份视图。
 *
 * `warnings` 是**整份答卷**的问题（哪一页没交、哪两页对上了同一模板页），
 * 与候选自己那条 `warnings` 是两层：前者要重拍或补页，后者是"这道题的作答要人看一眼"。
 * 合成一层会让教师分不清"该找学生"还是"该改这道题"。
 *
 * `confirmed` 之后候选只读：答案已经进了 `student_answer`，再改候选不会回写到那里。
 */
export interface AnswerReview {
  versionId: number
  assignmentId: number
  studentId?: number
  studentName?: string
  versionNo: number
  status: SubmissionVersionStatus
  confirmed: boolean
  warnings: AnswerWarning[]
  pages: AnswerPage[]
  candidates: AnswerCandidate[]
}

/** 一条需要教师处理的问题：`code` 决定图标与色调，`detail` 直接显示给教师看。 */
export interface AnswerWarning {
  code: string
  detail: string
}

/**
 * 学生答卷上的一页。
 *
 * `pageNo` 是学生排定的顺序（教师嘴里说的"第几页"），`documentPageNo` 是它在原上传文件里的位置；
 * 图片恒为 1，PDF 拆页时才会有别的值。
 *
 * `regions` 是**这一页上识别出的全部区域**，不是"这道题的"：归属关系在每块区域自己的
 * `candidateId` 上（不存在就是还没归到题）。未归属的区域只在这里看得见。
 */
export interface AnswerPage {
  submissionPageId: number
  pageNo: number
  documentId: number
  documentPageNo: number
  fileName: string
  pageFileId: number
  rotatedFileId: number
  width?: number
  height?: number
  /** 配准到的模板页码；不存在表示这一页没对上模板（区域会留作未归属）。 */
  templatePageNo?: number
  alignmentConfidence?: number
  regions: AnswerRegion[]
}

/**
 * 一块识别区域，以及它裁出来的答案图。
 *
 * 与 `PaperRegion` 是两个类型：答卷区域没有 `reviewStatus`（要不要确认是按**题**说的，
 * 不是按区域说的），多一个 `pageNo`（教师定位它时说的是学生排的页码）。
 */
export interface AnswerRegion {
  regionId: number
  pageNo: number
  regionType: string
  x: number; y: number; width: number; height: number
  ocrText?: string
  ocrLatex?: string
  confidence?: number
  cropFileId?: number
  /** 认领了这一块的题目候选；不存在表示它还没归到任何题。 */
  candidateId?: number
}

/**
 * 一道题的答案候选。
 *
 * `blank` 是物化阶段算出来的结论（"学生没有可识别作答"），与"识别文字为空"不是一件事：
 * 后者也可能是拍糊了。`reviewStatus` 是教师的判断，确认时两者必须一致才能入库。
 */
export interface AnswerCandidate {
  candidateId: number
  questionId: number
  questionCode?: string
  questionOrder: number
  orderNo?: number
  answerText?: string
  answerLatex?: string
  blank: boolean
  confidence?: number
  reviewStatus: AnswerReviewStatus
  /** 乐观锁版本号，改这条候选时必须原样回传。 */
  version: number
  regions: AnswerRegion[]
  warnings: string[]
}

/** 教师确认答案时提交的一次修改；没写的字段表示"这一项不动"。 */
export interface AnswerCorrection {
  version: number
  questionId?: number
  answerText?: string
  answerLatex?: string
  blank?: boolean
  reviewStatus?: AnswerReviewStatus
  /** 按当前几何重新裁剪这道题的答案图（区域太窄导致自动裁剪失败时用）。 */
  crop?: boolean
}

/**
 * 一份作业下的待处理提交。
 *
 * `candidateCount` / `pendingCount` 是教师决定先看哪一份的依据：状态都是"待校对"，
 * 而"12 道题里还有 11 道没看"与"只剩 1 道"的工作量差别不体现在状态上。
 */
export interface SubmissionQueue {
  assignmentId: number
  title: string
  items: SubmissionQueueItem[]
}

export interface SubmissionQueueItem {
  versionId: number
  versionNo: number
  studentId: number
  studentNo: string
  studentName: string
  status: SubmissionVersionStatus
  current: boolean
  submittedAt?: string
  returnedAt?: string
  returnReason?: string
  pageCount: number
  candidateCount: number
  pendingCount: number
}

/**
 * 学生看到的最终成绩的状态。
 *
 * `PENDING` 是"老师还没批完"，不是"0 分"：服务端在这种情况下不给分数，
 * 界面也就没有 0 可以显示——这是刻意的，见 `StudentResult`。
 */
export type StudentResultState = 'NOT_SUBMITTED' | 'PENDING' | 'PARTIAL' | 'GRADED'

/** 一道题的成绩。没有标准答案、没有评分项：那些是批改依据，不在学生端。 */
export interface StudentResultItem {
  questionCode: string
  questionContent: string
  confirmedScore: number
  totalScore: number
  /** 老师写给这道题的话；没写时不存在（服务端省略 null 字段）。 */
  feedback?: string
  confirmedAt?: string
}

/**
 * 我在一次作业上的成绩。
 *
 * <p>这是安全投影：AI 建议分、评分明细、模型原始错因、标准答案与老师改写原因都不在里面。
 * 分数只覆盖"已经批完的题"——`gradedScore` 是分母，不是整份作业的满分，
 * 所以显示时要用 `confirmedScore / gradedScore` 配成一组，并说明批了几道。
 */
export interface StudentResult {
  assignmentId: number
  state: StudentResultState
  /** 这些成绩属于第几版；整卷导入那条旧链路没有版本，字段不存在。 */
  versionNo?: number
  confirmedScore?: number
  gradedScore?: number
  gradedQuestionCount: number
  questionCount: number
  /** 老师批完最后一道题的时间；没批完时不存在。 */
  completedAt?: string
  items: StudentResultItem[]
}
