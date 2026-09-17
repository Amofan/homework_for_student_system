<!--
  页面缩略图条。

  纯展示：它不取图片也不改数据，只把"当前在哪一页"这件事画出来并把点击抛给父组件。
  缩略图走 PrivateImage，所以图片本身仍然是带鉴权读取的。
-->
<script setup lang="ts">
import { type ThumbnailPage } from '../../document/pages'
import PrivateImage from './PrivateImage.vue'

defineProps<{
  /** 整卷页与答卷页都行：这里只用到"哪一页、拿哪张图、上面有没有东西"（见 `document/pages.ts`）。 */
  pages: ThumbnailPage[]
  activePageId?: number
  /** 当前页里还有多少块低置信度区域，用来在缩略图上做提示。 */
  flaggedPageIds?: number[]
}>()

defineEmits<{ (event: 'select', pageId: number): void }>()
</script>

<template>
  <ol class="page-strip" aria-label="页面缩略图">
    <li v-for="page in pages" :key="page.pageId">
      <button
        type="button"
        class="page-thumb"
        :class="{ active: page.pageId === activePageId, flagged: flaggedPageIds?.includes(page.pageId) }"
        :aria-current="page.pageId === activePageId ? 'page' : undefined"
        :aria-label="`第 ${page.pageNo} 页${flaggedPageIds?.includes(page.pageId) ? '，有低置信度区域' : ''}`"
        @click="$emit('select', page.pageId)"
      >
        <PrivateImage :file-id="page.thumbnailFileId ?? page.pageFileId" :alt="`第 ${page.pageNo} 页缩略图`" />
        <span class="page-thumb-no">第 {{ page.pageNo }} 页</span>
        <span v-if="page.regions.length === 0" class="page-thumb-note">未识别到内容</span>
      </button>
    </li>
  </ol>
</template>
