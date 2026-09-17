import { describe, expect, it } from 'vitest'

import { clampUnit, isLowConfidence, moveRegion, regionTypeLabel, resizeRegion } from './regions'

describe('区域几何', () => {
  it('平移越界时整体贴边，不改变框的大小', () => {
    const moved = moveRegion({ x: 0.9, y: 0.4, width: 0.2, height: 0.1 }, 0.5, 0)

    // 宽高保持不变：教师的本意是"把框挪回来"，压扁会改掉他自己也不知道改了什么的东西。
    expect(moved.width).toBeCloseTo(0.2)
    expect(moved.height).toBeCloseTo(0.1)
    expect(moved.x).toBeCloseTo(0.8)
  })

  it('向左上越界同样贴边而不是变成负数', () => {
    const moved = moveRegion({ x: 0.05, y: 0.05, width: 0.3, height: 0.3 }, -0.5, -0.5)

    expect(moved.x).toBe(0)
    expect(moved.y).toBe(0)
    expect(moved.width).toBeCloseTo(0.3)
  })

  it('拉伸不会小于最小边长', () => {
    const resized = resizeRegion({ x: 0.1, y: 0.1, width: 0.2, height: 0.2 }, -1, -1)

    // 缩到 0 的框在界面上点不中也看不见，等价于把这块区域弄丢了。
    expect(resized.width).toBeGreaterThanOrEqual(0.01)
    expect(resized.height).toBeGreaterThanOrEqual(0.01)
    expect(resized.x).toBeCloseTo(0.1)
  })

  it('拉伸不会越过页面右下角', () => {
    const resized = resizeRegion({ x: 0.6, y: 0.6, width: 0.2, height: 0.2 }, 0.9, 0.9)

    expect(resized.x + resized.width).toBeCloseTo(1)
    expect(resized.y + resized.height).toBeCloseTo(1)
  })

  it('夹取会把非法数值折成 0 而不是 NaN', () => {
    // NaN 与无穷都意味着上游算错了。放它们过去，框会凭空消失或跑到页面外，
    // 而界面上看不出是哪个环节坏的。
    expect(clampUnit(Number.NaN)).toBe(0)
    expect(clampUnit(Number.POSITIVE_INFINITY)).toBe(0)
    expect(clampUnit(-3)).toBe(0)
  })
})

describe('置信度标记', () => {
  it('低于阈值才算低置信度', () => {
    expect(isLowConfidence({ confidence: 0.62 } as never)).toBe(true)
    expect(isLowConfidence({ confidence: 0.85 } as never)).toBe(false)
    expect(isLowConfidence({ confidence: 0.97 } as never)).toBe(false)
  })

  it('没有置信度时不标记', () => {
    // 没有依据的警告比没有警告更糟：教师会连真正需要看的那些标记一起忽略。
    expect(isLowConfidence({ confidence: undefined } as never)).toBe(false)
  })
})

describe('区域类型文案', () => {
  it('已知类型给中文名', () => {
    expect(regionTypeLabel('FIGURE')).toBe('插图')
    expect(regionTypeLabel('TEXT_BLOCK')).toBe('文本块')
  })

  it('未知类型原样显示', () => {
    expect(regionTypeLabel('NEW_KIND')).toBe('NEW_KIND')
  })
})
