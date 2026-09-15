/**
 * 错因枚举的中文标签。
 *
 * 后端有两处会产出 `error_type`，合起来才是完整取值集合：
 * - `backend/.../grading/ai/AiGradingTaskWorker.java` 的 `ERROR_TYPES`：CORRECT、
 *   CALCULATION_ERROR、METHOD_ERROR、CONCEPT_ERROR、INCOMPLETE、OTHER；
 * - `backend/.../grading/ObjectiveRuleGrader.java`：客观题只给 CORRECT 或 ANSWER_MISMATCH。
 * 合计 7 个，ANSWER_MISMATCH 不属于模型那套，是客观题规则判定的结果。
 *
 * 这里原本写在 `AnalyticsView.vue` 里且只有 5 个，漏了 ANSWER_MISMATCH，
 * 于是"高频问题"面板把英文枚举直接显示给了教师——而客观题答错恰恰是最常见的错因。
 * 标签只留这一份，`errorTypes.test.ts` 盯着集合是否齐全。
 */
const ERROR_TYPE_LABELS: Record<string, string> = {
  CORRECT: '正确',
  ANSWER_MISMATCH: '答案不一致',
  CALCULATION_ERROR: '计算错误',
  METHOD_ERROR: '方法错误',
  CONCEPT_ERROR: '概念错误',
  INCOMPLETE: '过程不完整',
  OTHER: '其他',
}

/**
 * 认不出的取值原样返回。显示英文总比显示"其他"好：既不丢信息，
 * 也让新增取值在界面上立刻看得出来（并有测试兜底）。
 */
export function errorTypeLabel(errorType: string): string {
  return ERROR_TYPE_LABELS[errorType] ?? errorType
}

/** 已知取值集合，供测试核对后端产出是否都在标签表里。 */
export const KNOWN_ERROR_TYPES: string[] = Object.keys(ERROR_TYPE_LABELS)
