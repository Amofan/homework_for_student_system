/**
 * 作业生命周期状态的中文标签。
 *
 * 后端 `backend/.../assignment/AssignmentStatus.java` 是唯一来源，共 7 项。
 * 标签表、已知集合与类型都从下面这一份 `ASSIGNMENT_STATUS_OPTIONS` 派生，
 * 不再手写第二份对象——「后端加了状态、前端漏补一项」这类漂移都源于两处清单。
 *
 * 注意历史取值 `IMPORTED`：旧版教师端 Excel 导入答案时写入过它，后端读取时会映射成
 * `SUBMITTING`，所以前端只可能收到这 7 个取值之一，不需要为它留标签。
 */
export const ASSIGNMENT_STATUS_OPTIONS = [
  { value: 'DRAFT', label: '草稿' },
  { value: 'OCR_REVIEW', label: '待校对试卷' },
  { value: 'PUBLISHED', label: '已发布' },
  { value: 'SUBMITTING', label: '收卷中' },
  { value: 'GRADING', label: '批改中' },
  { value: 'REVIEWING', label: '待复核' },
  { value: 'COMPLETED', label: '已完成' },
] as const

export type AssignmentStatus = typeof ASSIGNMENT_STATUS_OPTIONS[number]['value']

const ASSIGNMENT_STATUS_LABELS: Record<string, string> = Object.fromEntries(
  ASSIGNMENT_STATUS_OPTIONS.map(option => [option.value, option.label]),
)

/**
 * 认不出的取值原样返回。
 *
 * 形参刻意是 `string` 而不是 `AssignmentStatus`：服务端可能比前端先加上新状态，
 * 显示英文总比显示"未知"好——既不丢信息，也让新取值在界面上立刻看得出来。
 */
export function assignmentStatusLabel(status: string): string {
  return ASSIGNMENT_STATUS_LABELS[status] ?? status
}

/** 已发布之后的状态都不允许教师再改动题目清单，界面据此禁用编辑入口。 */
export function isPublishedStatus(status: string): boolean {
  return status !== 'DRAFT' && status !== 'OCR_REVIEW'
}

const SUBMISSION_STATUS_LABELS: Record<string, string> = {
  NOT_SUBMITTED: '未提交',
  // 旧版教师端导入的答案，学生端只当作"已有答卷"展示。
  IMPORTED: '已提交',
}

/**
 * 学生自己的提交摘要标签。
 *
 * 目前只有后端在本阶段能产出的两个取值；提交版本状态（草稿、处理中、待校对、
 * 已退回等）属于后续任务的提交版本模型，届时在这里补齐而不是先写一批用不到的值。
 */
export function submissionStatusLabel(status: string): string {
  return SUBMISSION_STATUS_LABELS[status] ?? status
}

/** 已知取值集合，供测试核对后端产出是否都在标签表里。 */
export const KNOWN_ASSIGNMENT_STATUSES: string[] = ASSIGNMENT_STATUS_OPTIONS.map(option => option.value)
