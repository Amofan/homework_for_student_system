<!--
  上传队列里的一行。

  纯展示 + 抛事件：排队、串行上传与重试都由上传页决定，这里只把"这个文件现在怎么样"画清楚。
  失败时必须给出重试按钮，否则学生只能整批重选一遍，而重选会把已经传上去的那几张再传一次。
-->
<script setup lang="ts">
import { computed } from 'vue'

import { formatBytes, uploadStatusLabel, type UploadQueueItem } from './uploadQueue'

const props = defineProps<{ item: UploadQueueItem; disabled?: boolean }>()

defineEmits<{
  (event: 'retry', id: number): void
  (event: 'remove', id: number): void
}>()

/** 进度条只在真正上传时出现；排队与已完成的条目各显示状态文案就够了。 */
const percent = computed(() => (props.item.status === 'uploading' ? props.item.percent : null))
</script>

<template>
  <li class="upload-item" :class="item.status">
    <div class="upload-item-main">
      <b class="upload-item-name">{{ item.file.name }}</b>
      <span class="upload-item-size">{{ formatBytes(item.file.size) }}</span>
    </div>

    <p class="upload-item-state" :class="{ danger: item.status === 'failed' }">
      {{ uploadStatusLabel(item) }}
    </p>

    <progress
      v-if="percent !== null"
      class="upload-item-progress" :value="item.percent" max="100"
      :aria-label="`${item.file.name} 上传进度`"
    >{{ item.percent }}%</progress>

    <div class="upload-item-actions">
      <button
        v-if="item.status === 'failed'"
        type="button" class="secondary-button" :disabled="disabled"
        @click="$emit('retry', item.id)"
      >重试这个文件</button>
      <button
        v-if="item.status === 'pending' || item.status === 'failed'"
        type="button" class="link-button" :disabled="disabled"
        :aria-label="`移除 ${item.file.name}`"
        @click="$emit('remove', item.id)"
      >移除</button>
    </div>
  </li>
</template>
