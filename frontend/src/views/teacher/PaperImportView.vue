<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRouter } from 'vue-router'

import { api, errorMessage, type ApiResponse } from '../../api/client'
import {
  createPaperImport, documentKindLabel, documentState, getPaperImport, listPaperImportsInProgress,
  paperWarningText, processPaperImport, uploadPaperFile,
} from '../../api/paperImport'
import type { Assignment, Classroom, PaperDocumentKind, PaperImport } from '../../api/types'
import DocumentUploadQueue from '../../components/document/DocumentUploadQueue.vue'

const router = useRouter()
const classes = ref<Classroom[]>([])
const inProgress = ref<Assignment[]>([])
const current = ref<PaperImport>()
const loading = ref(true)
const busy = ref(false)
const form = reactive<{ classId?: number; title: string }>({ classId: undefined, title: '' })

const POLL_START_MS = 1_500
const POLL_CAP_MS = 10_000

let pollTimer: ReturnType<typeof setTimeout> | undefined
let pollDelay = POLL_START_MS

/**
 * 还在识别的文档。
 *
 * `PENDING` 与 `RUNNING` 都要轮询：识别是在 `process` 请求里同步跑完的，
 * 但 OCR 服务慢的时候请求会挂住，界面必须能显示出"还在跑"而不是一片空白。
 * `RETRY_WAIT` 刻意不轮询——它不是"正在跑"，而是"上一次没成，等下一次点击"。
 */
const processing = computed(() => current.value?.documents.some(document =>
  document.ocrStatus === 'PENDING' || document.ocrStatus === 'RUNNING'
  || document.status === 'PROCESSING') ?? false)

const retryable = computed(() => current.value?.documents.some(
  document => document.ocrStatus === 'RETRY_WAIT' || document.ocrStatus === 'FAILED') ?? false)

const readyForReview = computed(() => Boolean(current.value)
  && !current.value!.confirmed && current.value!.candidates.length > 0)

const classNameOf = (classId: number) => classes.value.find(item => item.id === classId)?.name ?? `班级 ${classId}`

function stopPolling(): void {
  if (pollTimer !== undefined) {
    clearTimeout(pollTimer)
    pollTimer = undefined
  }
}

/**
 * 轮询识别结果。
 *
 * 退避从 1.5 秒翻倍到 10 秒封顶：识别一份整卷通常要几十秒，1.5 秒一次的固定间隔会白打
 * 二十多个请求；封顶 10 秒则保证结果出来后教师最多多等十秒。
 * 停止条件有三条——不再有进行中的文档、组件卸载、请求失败。
 * 组件卸载时必须停：继续跑会在已经销毁的组件上写 ref，而且会在教师已经离开页面后一直占着连接。
 */
function schedulePoll(): void {
  stopPolling()
  if (!processing.value || !current.value) return
  const target = current.value.assignmentId
  pollTimer = setTimeout(async () => {
    pollTimer = undefined
    try {
      current.value = await getPaperImport(target)
      pollDelay = Math.min(pollDelay * 2, POLL_CAP_MS)
    } catch (reason) {
      ElMessage.error(errorMessage(reason))
      return
    }
    schedulePoll()
  }, pollDelay)
}

async function load(): Promise<void> {
  const [classList, imports] = await Promise.all([
    api.get<ApiResponse<Classroom[]>>('/classes'),
    listPaperImportsInProgress(),
  ])
  classes.value = classList.data.data
  inProgress.value = imports
}

async function create(): Promise<void> {
  if (!form.classId || !form.title.trim()) {
    ElMessage.warning('请填写作业名称并选择班级')
    return
  }
  busy.value = true
  try {
    current.value = await createPaperImport(form.classId, form.title.trim())
    pollDelay = POLL_START_MS
    ElMessage.success('导入已建立，请上传空白试卷')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    busy.value = false
  }
}

async function upload(kind: PaperDocumentKind, file: File): Promise<void> {
  if (!current.value) return
  busy.value = true
  try {
    current.value = await uploadPaperFile(current.value.assignmentId, kind, file)
    ElMessage.success(`${documentKindLabel[kind]}已上传`)
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    busy.value = false
  }
}

async function startProcessing(): Promise<void> {
  if (!current.value) return
  busy.value = true
  pollDelay = POLL_START_MS
  try {
    current.value = await processPaperImport(current.value.assignmentId)
    schedulePoll()
    if (current.value.candidates.length > 0) ElMessage.success('识别完成，请进入校对')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    busy.value = false
  }
}

async function resume(assignmentId: number): Promise<void> {
  busy.value = true
  try {
    current.value = await getPaperImport(assignmentId)
    schedulePoll()
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    busy.value = false
  }
}

function openReview(): void {
  if (!current.value) return
  stopPolling()
  router.push(`/teacher/paper-imports/${current.value.assignmentId}/review`)
}

onMounted(async () => {
  try {
    await load()
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="page">
    <header class="page-heading">
      <div>
        <p class="kicker">整卷导入</p>
        <h1>上传整卷</h1>
        <p>上传空白试卷与参考答案，识别出的题目先成为候选，教师确认后才进入题库。</p>
      </div>
    </header>

    <article class="paper-card create-import">
      <h2>新建一次导入</h2>
      <div class="form-grid">
        <el-form label-position="top">
          <el-form-item label="作业名称">
            <el-input v-model="form.title" placeholder="如 第一单元测验" />
          </el-form-item>
        </el-form>
        <el-form label-position="top">
          <el-form-item label="班级">
            <el-select v-model="form.classId" placeholder="选择班级">
              <el-option v-for="item in classes" :key="item.id" :label="item.name" :value="item.id" />
            </el-select>
          </el-form-item>
        </el-form>
      </div>
      <button class="primary-button" :disabled="busy" @click="create">建立导入</button>
      <p class="field-hint">导入期间这份作业处于「整卷校对中」，学生看不到它。</p>
    </article>

    <article v-if="current" class="paper-card current-import">
      <div class="current-import-head">
        <div>
          <span class="status-pill" :class="current.confirmed ? 'good' : ''">
            {{ current.confirmed ? '已确认入库' : '整卷校对中' }}
          </span>
          <h2>{{ current.title }}</h2>
          <p>{{ classNameOf(current.classId) }}</p>
        </div>
        <div class="assignment-number">#{{ current.assignmentId }}</div>
      </div>

      <DocumentUploadQueue
        :documents="current.documents" :busy="busy"
        @upload="upload" @process="startProcessing" @reject="ElMessage.error($event)"
      />

      <p v-if="processing" class="state-inline" role="status">正在识别，页面会自动刷新结果…</p>
      <p v-else-if="retryable" class="state-inline warn" role="status">
        上一次识别没有完成。修正服务后再点一次「重试识别」即可，已上传的文件无需重复上传。
      </p>

      <div v-if="current.warnings.length > 0" class="warning-list">
        <b>需要留意</b>
        <ul>
          <li v-for="code in current.warnings" :key="code">{{ paperWarningText(code) }}</li>
        </ul>
      </div>

      <div class="current-import-actions">
        <button class="primary-button" :disabled="busy || !readyForReview" @click="openReview">
          进入校对
        </button>
        <span v-if="!readyForReview" class="field-hint">识别完成并生成候选题后才能进入校对。</span>
      </div>
    </article>

    <h2 class="section-title">进行中的导入</h2>
    <div v-if="loading" class="state-panel">正在载入…</div>
    <div v-else-if="inProgress.length === 0" class="state-panel empty-invite">
      <b>没有进行中的导入</b>
      <p>上传一份空白试卷即可开始。</p>
    </div>
    <ul v-else class="import-list">
      <li v-for="item in inProgress" :key="item.id" class="paper-card import-row">
        <div>
          <b>{{ item.title }}</b>
          <small>{{ classNameOf(item.classId) }} · {{ item.questionIds.length }} 道题已确认</small>
        </div>
        <button class="secondary-button" :disabled="busy" @click="resume(item.id)">继续处理</button>
      </li>
    </ul>
  </section>
</template>
