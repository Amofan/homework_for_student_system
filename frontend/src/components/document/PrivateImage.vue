<!--
  私有图片。

  图片只能通过带鉴权的 `/teacher/files/{id}`（或学生端对应路径）读取，所以不能直接写进
  `<img src>`：`<img>` 不会带 Authorization 头，把令牌拼进 URL 又会把它留在浏览器历史、
  代理日志与服务端访问日志里。唯一可行的做法是取一次 Blob，再用对象 URL 交给 `<img>`。

  对象 URL 必须自己回收：它持有的是一份内存引用，不撤销就会一直留到页面关闭。
  连续翻几十页试卷就能在任务管理器里看出来。
-->
<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'

import { api } from '../../api/client'

const props = withDefaults(defineProps<{
  fileId?: number
  alt?: string
  /** 教师端与学生端的归属规则不同，路径不能共用。 */
  audience?: 'teacher' | 'student'
  /** 首屏就要看到的图（例如当前页大图）用 eager，缩略图保持 lazy。 */
  eager?: boolean
}>(), { alt: '图片', audience: 'teacher', eager: false })

const url = ref<string>()
const failed = ref(false)

let issued: string | undefined
/**
 * 加载代次。
 *
 * <p>切换文件时前后两次请求可能乱序返回：先发的慢请求后到，会把已经过期的图片盖上去。
 * 每次加载领一个号，回来时代次不一致就丢弃，同时把那次申请的对象 URL 直接撤销掉。
 */
let generation = 0

function releaseIssued(): void {
  if (issued) {
    URL.revokeObjectURL(issued)
    issued = undefined
  }
}

async function load(fileId?: number): Promise<void> {
  const current = ++generation
  releaseIssued()
  url.value = undefined
  failed.value = false
  if (!fileId) return

  try {
    const response = await api.get<Blob>(`/${props.audience}/files/${fileId}`, { responseType: 'blob' })
    if (current !== generation) return
    issued = URL.createObjectURL(response.data)
    url.value = issued
  } catch {
    // 不在这里弹错误提示：一页缩略图里失败一两张是常见情况（历史数据、清理任务已回收），
    // 逐张弹窗会把真正的错误淹没。渲染成一个可见的占位即可。
    if (current === generation) failed.value = true
  }
}

watch(() => props.fileId, load, { immediate: true })
onBeforeUnmount(() => {
  generation += 1
  releaseIssued()
})
</script>

<template>
  <img
    v-if="url" class="private-image" :src="url" :alt="alt"
    :loading="eager ? 'eager' : 'lazy'" decoding="async"
  >
  <span v-else-if="failed" class="private-image placeholder" role="img" :aria-label="`${alt}加载失败`">
    图片加载失败
  </span>
  <span v-else class="private-image placeholder" role="img" :aria-label="`${alt}加载中`">图片加载中…</span>
</template>
