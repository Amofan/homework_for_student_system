<!--
  整卷上传队列。

  两个槽位：空白试卷必填、参考答案可选。校验分两层——这里只做"扩展名 + 大小"的即时提示，
  真正的判据（文件签名、能否解码、页数与像素上限）在服务端。前端校验的目的是省掉一次
  "选了 200 MB 的文件、等上传完才被告知不行"的往返，不是替代服务端校验。
-->
<script setup lang="ts">
import { computed, ref } from 'vue'

import { documentKindLabel, documentState } from '../../api/paperImport'
import type { PaperDocument, PaperDocumentKind } from '../../api/types'

const props = withDefaults(defineProps<{
  documents: PaperDocument[]
  busy?: boolean
  /** 与服务端 `app.storage.max-file-bytes` 保持一致；只用于提前拦住明显超限的文件。 */
  maxBytes?: number
}>(), { busy: false, maxBytes: 25 * 1024 * 1024 })

const emit = defineEmits<{
  (event: 'upload', kind: PaperDocumentKind, file: File): void
  (event: 'process'): void
  (event: 'reject', message: string): void
}>()

const ALLOWED_EXTENSIONS = ['.pdf', '.png', '.jpg', '.jpeg']
const KINDS: PaperDocumentKind[] = ['EXAM_PAPER', 'ANSWER_KEY']

/** 已选但还没上传的文件名，按槽位记；上传成功后由父组件刷新 documents，这里随之清空。 */
const picked = ref<Partial<Record<PaperDocumentKind, File>>>({})

const examPaper = computed(() => props.documents.find(item => item.documentKind === 'EXAM_PAPER'))
const canProcess = computed(() => Boolean(examPaper.value)
  && props.documents.every(item => item.status === 'PENDING' || item.status === 'FAILED'
    || item.ocrStatus === 'PENDING' || item.ocrStatus === 'RETRY_WAIT' || item.ocrStatus === 'FAILED'))

function documentOf(kind: PaperDocumentKind): PaperDocument | undefined {
  return props.documents.find(item => item.documentKind === kind)
}

function hasRetryableFailure(): boolean {
  return props.documents.some(item => item.ocrStatus === 'RETRY_WAIT')
}

function choose(event: Event, kind: PaperDocumentKind): void {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 选同一个文件两次不会触发 change，清空后教师才发现"点了没反应"。
  input.value = ''
  if (!file) return

  const name = file.name.toLowerCase()
  if (!ALLOWED_EXTENSIONS.some(extension => name.endsWith(extension))) {
    emit('reject', '只支持 PDF、PNG、JPG 三种格式')
    return
  }
  if (file.size > props.maxBytes) {
    emit('reject', `文件不能超过 ${Math.round(props.maxBytes / 1024 / 1024)} MB`)
    return
  }
  picked.value = { ...picked.value, [kind]: file }
}

function submit(kind: PaperDocumentKind): void {
  const file = picked.value[kind]
  if (!file) return
  emit('upload', kind, file)
  picked.value = { ...picked.value, [kind]: undefined }
}
</script>

<template>
  <div class="upload-queue">
    <div v-for="kind in KINDS" :key="kind" class="upload-slot">
      <div class="upload-slot-head">
        <b>{{ documentKindLabel[kind] }}</b>
        <span v-if="kind === 'EXAM_PAPER'" class="slot-required">必填</span>
        <span v-else class="slot-optional">可选</span>
      </div>

      <template v-if="documentOf(kind)">
        <p class="upload-state" :class="documentState(documentOf(kind)!).tone">
          {{ documentState(documentOf(kind)!).label }}
        </p>
        <p class="field-hint">已上传，不能替换。需要换一份请重新建立导入。</p>
      </template>

      <template v-else>
        <label class="upload-picker">
          <input type="file" accept=".pdf,.png,.jpg,.jpeg" :disabled="busy" @change="choose($event, kind)">
          <span>{{ picked[kind]?.name || '选择 PDF / PNG / JPG' }}</span>
        </label>
        <button
          type="button" class="secondary-button"
          :disabled="busy || !picked[kind]" @click="submit(kind)"
        >上传</button>
      </template>
    </div>

    <div class="upload-actions">
      <button
        type="button" class="primary-button"
        :disabled="busy || !canProcess"
        :title="examPaper ? (hasRetryableFailure() ? '识别服务上次不可用，可以再试一次' : '开始识别并生成候选题') : '请先上传空白试卷'"
        @click="emit('process')"
      >{{ hasRetryableFailure() ? '重试识别' : '开始识别' }}</button>
      <span v-if="!examPaper" class="field-hint">上传空白试卷后才能开始识别。</span>
    </div>
  </div>
</template>
