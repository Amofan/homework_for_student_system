/**
 * 错因枚举的中文标签与教师复核的可选项。
 *
 * 后端 `backend/.../grading/ErrorType.java` 是唯一来源，共 7 项：模型能用的 6 项
 * （`ErrorType.aiCodes()`，不含 ANSWER_MISMATCH）加上客观题规则判定的 ANSWER_MISMATCH。
 * 客观题答错恰恰是最常见的错因，早先这里只有 5 个标签，把英文枚举直接显示给了教师。
 *
 * 选项、标签表和已知集合都从下面这一份 `ERROR_TYPE_OPTIONS` 派生，不再手写第二份对象，
 * 否则「后端加了取值、前端漏补一项」这类漂移会重现。`errorTypes.test.ts` 盯着集合是否齐全。
 */
export const ERROR_TYPE_OPTIONS = [
  { value: 'CORRECT', label: '正确' },
  { value: 'ANSWER_MISMATCH', label: '答案不一致' },
  { value: 'CALCULATION_ERROR', label: '计算错误' },
  { value: 'METHOD_ERROR', label: '方法错误' },
  { value: 'CONCEPT_ERROR', label: '概念错误' },
  { value: 'INCOMPLETE', label: '过程不完整' },
  { value: 'OTHER', label: '其他' },
] as const

/** 复核接口只接受这 7 个编码之一，教师端不再提交自由文本。 */
export type ErrorType = typeof ERROR_TYPE_OPTIONS[number]['value']

const ERROR_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  ERROR_TYPE_OPTIONS.map(option => [option.value, option.label]),
)

/**
 * 认不出的取值原样返回。显示英文总比显示"其他"好：既不丢信息，
 * 也让新增取值在界面上立刻看得出来（并有测试兜底）。
 *
 * 形参刻意保留 `string` 而不是 `ErrorType`：服务端历史行里可能存在集合外的值，
 * 本函数必须能如实显示它，而不是被类型断言骗过去。
 */
export function errorTypeLabel(errorType: string): string {
  return ERROR_TYPE_LABELS[errorType] ?? errorType
}

/** 已知取值集合，供测试核对后端产出是否都在标签表里。 */
export const KNOWN_ERROR_TYPES: string[] = ERROR_TYPE_OPTIONS.map(option => option.value)
