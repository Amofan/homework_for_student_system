<!--
  学生答卷的页面整理台。

  顺序是学生唯一能表达"这是我第几页"的手段，所以两条路都要通：
  - 桌面端拖动（HTML5 拖放），一次把页挪到远处；
  - 上移/下移按钮，手机上、键盘上、读屏里都能用。

  HTML5 拖放在触屏上根本不触发（`dragstart` 不来），所以手机上真正能用的就是按钮 ——
  这不是"顺带做的无障碍支持"，而是手机端的主路径。反过来，只有按钮没有拖动，
  二十页的答卷要挪一页就得点十几次。

  组件不自己发请求：它把"学生想要的新顺序"算好抛出去，由页面决定什么时候落库、失败了怎么办。
  留在组件里发请求的话，一次失败的拖动会留下一份与界面不一致的服务端顺序。
-->
<script setup lang="ts">
import { ref } from 'vue'

import { pageQualityState } from '../../api/studentSubmission'
import type { SubmissionPage } from '../../api/types'
import PrivateImage from './PrivateImage.vue'

const props = withDefaults(defineProps<{
  pages: SubmissionPage[]
  /** 提交中或版本已只读时整体禁用：按钮还在但点了没反应，比藏起来更让人困惑。 */
  disabled?: boolean
}>(), { disabled: false })

const emit = defineEmits<{
  /** 完整的新顺序，与重排接口的请求体一致。 */
  (event: 'reorder', pageIds: number[]): void
  /** 目标角度，不是增量。 */
  (event: 'rotate', pageId: number, degrees: number): void
  (event: 'remove', pageId: number): void
}>()

const draggingId = ref<number>()

function move(index: number, offset: number): void {
  const target = index + offset
  if (target < 0 || target >= props.pages.length) return
  const order = props.pages.map(page => page.id)
  const [moved] = order.splice(index, 1)
  order.splice(target, 0, moved)
  emit('reorder', order)
}

function onDragStart(pageId: number): void {
  draggingId.value = pageId
}

function onDrop(index: number): void {
  const from = props.pages.findIndex(page => page.id === draggingId.value)
  draggingId.value = undefined
  if (from < 0 || from === index) return
  const order = props.pages.map(page => page.id)
  const [moved] = order.splice(from, 1)
  order.splice(index, 0, moved)
  emit('reorder', order)
}

/** 向右转 90 度，按目标角度送出：客户端不必知道当前是多少度才能算下一次。 */
function turnRight(page: SubmissionPage): void {
  emit('rotate', page.id, (page.rotationDegrees + 90) % 360)
}
</script>

<template>
  <ol class="page-organizer" aria-label="答卷页面">
    <li
      v-for="(page, index) in pages" :key="page.id"
      class="organizer-page" :class="{ dragging: draggingId === page.id }"
      :draggable="!disabled" @dragstart="onDragStart(page.id)" @dragover.prevent
      @drop.prevent="onDrop(index)" @dragend="draggingId = undefined"
    >
      <div class="organizer-thumb">
        <PrivateImage
          :file-id="page.thumbnailFileId ?? page.rotatedFileId" audience="student"
          :alt="`第 ${page.pageNo} 页`"
        />
        <span class="organizer-page-no">第 {{ page.pageNo }} 页</span>
      </div>

      <div class="organizer-detail">
        <p class="organizer-file">{{ page.fileName }}</p>
        <p class="organizer-quality" :class="pageQualityState(page.qualityStatus).tone">
          {{ pageQualityState(page.qualityStatus).label }}
          <template v-if="page.rotationDegrees !== 0">（已旋转 {{ page.rotationDegrees }}°）</template>
        </p>
      </div>

      <div class="organizer-actions">
        <button
          type="button" class="link-button" :disabled="disabled || index === 0"
          :aria-label="`把第 ${page.pageNo} 页上移`" @click="move(index, -1)"
        >上移</button>
        <button
          type="button" class="link-button" :disabled="disabled || index === pages.length - 1"
          :aria-label="`把第 ${page.pageNo} 页下移`" @click="move(index, 1)"
        >下移</button>
        <button
          type="button" class="link-button" :disabled="disabled"
          :aria-label="`把第 ${page.pageNo} 页向右转 90 度`" @click="turnRight(page)"
        >向右转</button>
        <button
          v-if="page.rotationDegrees !== 0"
          type="button" class="link-button" :disabled="disabled"
          :aria-label="`把第 ${page.pageNo} 页转正`" @click="emit('rotate', page.id, 0)"
        >转正</button>
        <button
          type="button" class="link-button danger" :disabled="disabled"
          :aria-label="`删除第 ${page.pageNo} 页`" @click="emit('remove', page.id)"
        >删除</button>
      </div>
    </li>
  </ol>
</template>
