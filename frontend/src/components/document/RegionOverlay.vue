<!--
  区域框层：铺在页面图上面，画识别框、支持点选与拖动。

  低置信度**必须同时有文字标签**，不能只靠颜色：色觉差异、投影仪偏色、以及教师本来就
  不在看颜色，都会让"只有颜色"的提示等于没有提示。而低置信度正是最需要人看一眼的那一类区域。
-->
<script setup lang="ts">
import { ref } from 'vue'

import { type DrawableRegion, isLowConfidence, moveRegion, regionTypeLabel, resizeRegion, type RegionRect } from '../../document/regions'

const props = defineProps<{
  regions: DrawableRegion[]
  activeRegionId?: number
  /** 用于无障碍描述；页面上没有别的文字能说明"这是第几页"。 */
  pageNo?: number
  /**
   * 只画不改。
   *
   * <p>答卷复核页上的区域框是**证据**不是编辑对象：教师改的是识别结果与题目归属，
   * 改区域几何要走"重裁"那条路（它会重新切图并留下审计），
   * 所以这里不能让它随手拖。共用同一个组件而不是另写一份只读的，
   * 是为了让低置信度标记、题号标签这些会一起演进的东西只有一处实现。
   */
  readonly?: boolean
  /**
   * 自定义无障碍描述。
   *
   * <p>默认文案是给整卷识别页写的（"属于第 N 号候选题目"），
   * 而答卷页上的 `candidateId` 是候选主键、不是题号 —— 照默认念会念错。
   * 由调用方给出它自己那条链路上正确的说法。
   */
  describeRegion?: (region: DrawableRegion) => string
}>()

const emit = defineEmits<{
  (event: 'select', regionId: number): void
  (event: 'update', regionId: number, rect: RegionRect): void
}>()

/** 拖动过程中的临时矩形；松手才提交，避免每个 pointermove 都发一次请求。 */
const draft = ref<{ regionId: number; rect: RegionRect }>()

function rectOf(region: DrawableRegion): RegionRect {
  return draft.value?.regionId === region.regionId ? draft.value.rect : region
}

/** 无障碍名：框里只有几个字，读屏用户需要知道它属于哪一页、哪一道题。 */
function labelOf(region: DrawableRegion): string {
  if (props.describeRegion) return props.describeRegion(region)
  const parts = [`第 ${props.pageNo ?? 1} 页的${regionTypeLabel(region.regionType)}区域`]
  if (isLowConfidence(region)) parts.push('低置信度')
  parts.push(region.candidateId ? `属于第 ${region.candidateId} 号候选题目` : '尚未归属任何题目')
  return parts.join('，')
}

function start(event: PointerEvent, region: DrawableRegion, mode: 'move' | 'resize'): void {
  // 先选中再判断能不能拖动：布局还没完成时（宽度为 0）拖动没有意义，
  // 但"点一下选中它"仍然应该生效，否则页面首帧点不动任何东西。
  emit('select', region.regionId)
  // 只读：选中照旧（教师靠它把右边那道题的候选点出来），但不进入拖动。
  if (props.readonly) return

  const surface = (event.currentTarget as HTMLElement).closest('.region-surface')
  const box = surface?.getBoundingClientRect()
  if (!box || box.width === 0 || box.height === 0) return
  event.preventDefault()

  const startRect: RegionRect = { x: region.x, y: region.y, width: region.width, height: region.height }
  const fromX = event.clientX
  const fromY = event.clientY
  draft.value = { regionId: region.regionId, rect: startRect }

  const onMove = (moveEvent: PointerEvent) => {
    const dx = (moveEvent.clientX - fromX) / box.width
    const dy = (moveEvent.clientY - fromY) / box.height
    draft.value = {
      regionId: region.regionId,
      rect: mode === 'move' ? moveRegion(startRect, dx, dy) : resizeRegion(startRect, dx, dy),
    }
  }
  const onUp = () => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    const settled = draft.value
    draft.value = undefined
    // 没有实际位移就不提交：单击也会走 pointerdown/pointerup，
    // 每次都发 PATCH 会把候选版本号白白推高，让教师下一次编辑撞上版本冲突。
    if (settled && (settled.rect.x !== startRect.x || settled.rect.y !== startRect.y
      || settled.rect.width !== startRect.width || settled.rect.height !== startRect.height)) {
      emit('update', settled.regionId, settled.rect)
    }
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}
</script>

<template>
  <div class="region-surface">
    <button
      v-for="region in props.regions"
      :key="region.regionId"
      type="button"
      class="region-box"
      :class="{
        active: region.regionId === props.activeRegionId,
        low: isLowConfidence(region),
        readonly: props.readonly,
      }"
      :style="{
        left: `${rectOf(region).x * 100}%`,
        top: `${rectOf(region).y * 100}%`,
        width: `${rectOf(region).width * 100}%`,
        height: `${rectOf(region).height * 100}%`,
      }"
      :aria-label="labelOf(region)"
      @pointerdown="start($event, region, 'move')"
    >
      <span class="region-tag">{{ regionTypeLabel(region.regionType) }}</span>
      <!-- 文字标签而不是只改颜色：见文件头注释。 -->
      <span v-if="isLowConfidence(region)" class="region-tag low-tag">
        低置信度 {{ Math.round((region.confidence ?? 0) * 100) }}%
      </span>
      <span
        v-if="!props.readonly"
        class="region-handle"
        aria-hidden="true"
        @pointerdown.stop="start($event, region, 'resize')"
      ></span>
    </button>
  </div>
</template>
