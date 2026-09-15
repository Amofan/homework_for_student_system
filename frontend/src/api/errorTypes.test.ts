import { describe, expect, it } from 'vitest'

import { ERROR_TYPE_OPTIONS, errorTypeLabel, KNOWN_ERROR_TYPES } from './errorTypes'

/**
 * 后端能产出的错因标签全集：模型那套 6 个（AiGradingTaskWorker.ERROR_TYPES）
 * 加客观题规则判定的 ANSWER_MISMATCH。这个清单就是"漏一个标签"这类 bug 的护栏，
 * 后端加取值时这里会失败，提醒去补标签。
 */
const BACKEND_ERROR_TYPES = [
  'CORRECT',
  'ANSWER_MISMATCH',
  'CALCULATION_ERROR',
  'METHOD_ERROR',
  'CONCEPT_ERROR',
  'INCOMPLETE',
  'OTHER',
]

describe('错因标签', () => {
  it('后端每个取值都有中文标签', () => {
    for (const errorType of BACKEND_ERROR_TYPES) {
      expect(KNOWN_ERROR_TYPES, `${errorType} 缺少标签`).toContain(errorType)
    }
  })

  it('标签是中文，不会把英文枚举直接显示给教师', () => {
    for (const errorType of BACKEND_ERROR_TYPES) {
      const label = errorTypeLabel(errorType)
      expect(label).not.toBe(errorType)
      expect(label).toMatch(/[一-龥]/)
    }
  })

  it('认不出的取值原样返回，不静默丢信息', () => {
    expect(errorTypeLabel('SOMETHING_NEW')).toBe('SOMETHING_NEW')
  })

  it('复核下拉包含且只包含七个后端取值', () => {
    // 顺序与后端 ErrorType 声明一致，教师看到的是稳定顺序而不是随机的集合迭代顺序
    expect(ERROR_TYPE_OPTIONS.map(option => option.value)).toEqual(BACKEND_ERROR_TYPES)
  })

  it('每个选项都带中文标签且编码不重复', () => {
    const values = ERROR_TYPE_OPTIONS.map(option => option.value)
    expect(new Set(values).size).toBe(values.length)
    for (const option of ERROR_TYPE_OPTIONS) {
      expect(option.label).toMatch(/[一-龥]/)
      expect(option.label).not.toBe(option.value)
    }
  })
})
