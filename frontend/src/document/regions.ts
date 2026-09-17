/**
 * 区域坐标的几何运算。
 *
 * <p>坐标是归一化到 0..1 的值，所以这里的每一次加减都同时是"像素位移 ÷ 页面尺寸"。
 * 把运算抽出来单独成模块，是因为拖动与拉伸的 DOM 接线很难测，而真正会出错的是边界处理：
 * 越界、反向拉伸、缩到看不见。这些在这里是可测的纯函数。
 */

/**
 * 画得出来的区域：一个 id、一块归一化矩形、置信度与归属。
 *
 * <p>刻意写成结构类型而不是直接用它那两个来源类型：整卷的 `PaperRegion` 与答卷的
 * `AnswerRegion` 字段名不同（`reviewStatus` / `pageNo` 各只有一边有），
 * 但区域框层真正用到的就是下面这几个。共用一份"画框需要的形状"，
 * 比让两个接口类型互相将就要稳。
 */
export interface DrawableRegion {
  regionId: number
  regionType: string
  x: number; y: number; width: number; height: number
  confidence?: number
  candidateId?: number
}

/**
 * 低于这个置信度就在界面上标出来。
 *
 * <p>必须与后端 `PaperImportService.LOW_CONFIDENCE_THRESHOLD` 保持一致：
 * 后端用它决定候选上要不要挂 `LOW_CONFIDENCE` 警告，前端用它给区域加标签。
 * 两边不一致时会出现"候选说没问题、每个区域却都标着低置信度"这种自相矛盾的界面。
 */
export const LOW_CONFIDENCE_THRESHOLD = 0.85

/** 区域的最小边长。再小就框不住任何东西，拖动时还会一松手就消失。 */
export const MIN_REGION_SIZE = 0.01

export interface RegionRect { x: number; y: number; width: number; height: number }

export function clampUnit(value: number): number {
  if (!Number.isFinite(value)) return 0
  return Math.min(1, Math.max(0, value))
}

/**
 * 平移。
 *
 * <p>越界时**整体贴边**而不是把框压扁：教师的本意是"把框挪回来"，
 * 压扁会改变框的大小，而他并不知道自己改变了什么。
 */
export function moveRegion(rect: RegionRect, dx: number, dy: number): RegionRect {
  const width = clampUnit(rect.width)
  const height = clampUnit(rect.height)
  return {
    x: clampUnit(Math.min(rect.x + dx, 1 - width)),
    y: clampUnit(Math.min(rect.y + dy, 1 - height)),
    width,
    height,
  }
}

/**
 * 右下角拉伸。
 *
 * <p>只保证边长不小于 {@link MIN_REGION_SIZE}，不做"整体缩放"：
 * 拉伸手柄的语义就是"固定左上角、只动右下角"，改成整体缩放会让框在靠近边缘时突然跳开。
 */
export function resizeRegion(rect: RegionRect, dWidth: number, dHeight: number): RegionRect {
  const x = clampUnit(rect.x)
  const y = clampUnit(rect.y)
  return {
    x,
    y,
    width: Math.max(MIN_REGION_SIZE, Math.min(rect.width + dWidth, 1 - x)),
    height: Math.max(MIN_REGION_SIZE, Math.min(rect.height + dHeight, 1 - y)),
  }
}

/** 置信度缺失时不标低置信度：没有依据的警告比没有警告更糟，它会让教师不再信任这些标记。 */
export function isLowConfidence(region: { confidence?: number }): boolean {
  return typeof region.confidence === 'number' && region.confidence < LOW_CONFIDENCE_THRESHOLD
}

const REGION_TYPE_LABEL: Record<string, string> = {
  TEXT_BLOCK: '文本块',
  FORMULA: '公式',
  FIGURE: '插图',
  ANSWER_BLOCK: '作答区',
  OPTION: '选项',
}

/** 未知类型原样显示：识别链路新增一种区域类型时，界面至少还能把码摆出来。 */
export function regionTypeLabel(regionType: string): string {
  return REGION_TYPE_LABEL[regionType] ?? regionType
}
