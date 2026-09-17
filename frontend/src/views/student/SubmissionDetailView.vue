<!--
  一份作业的答卷总览（手机优先）。

  这一页按 assignmentId 组织，因为"这个学生在这份作业上做过什么"是作业维度的事：
  第几版、哪版是当前提交、老师退回了哪一版。上传页反过来只认 versionId ——
  "开始作答"这个动作放在这里，只是翻一页看看的学生不会凭空多出一行草稿。

  新建版本与续传分开：能续传就直接进编辑页，不发请求；只有真要开新一版时才建行。
  后端把 canStartNewVersion 算好给过来，前端不自己判断"什么时候还能再交一版"。
-->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { errorMessage, getMyAssignment } from '../../api/client'
import {
  getMyResult, getSubmissionHistory, startSubmissionDraft, submissionVersionStatusLabel,
  submissionVersionStatusTone,
} from '../../api/studentSubmission'
import type {
  StudentAssignment, StudentResult, SubmissionHistory, SubmissionVersionSummary,
} from '../../api/types'
import MathText from '../../math/MathText.vue'

const route = useRoute()
const router = useRouter()
const assignmentId = Number(route.params.assignmentId)

const assignment = ref<StudentAssignment>()
const history = ref<SubmissionHistory>()
/** 老师已经批完的那部分成绩；还没批完时服务端不给分数，这里就是空的。 */
const result = ref<StudentResult>()
const loading = ref(true)
const error = ref('')
const busy = ref(false)

/** 最新的一版：服务端按版本号倒序给出，学生最关心的就是最后交的那一版。 */
const latest = computed<SubmissionVersionSummary | undefined>(() => history.value?.versions[0])
/** 手上还有一版能改的草稿/未提交版本，那就该去续传，而不是再开一版。 */
const editableVersion = computed(() => history.value?.versions.find(item => item.editable))
const versions = computed<SubmissionVersionSummary[]>(() => history.value?.versions ?? [])

/**
 * 新建那一版的按钮文案。
 *
 * <p>"再交一版"和"重新交一版"在不同的处境下是两件事：被退回时是老师让重做，
 * 而自己还没等到批改就想改，得先知道原来那一版会被取代 —— 否则学生会以为两版都会交给老师。
 */
const startLabel = computed(() => {
  if (!latest.value) return '开始上传'
  return latest.value.status === 'RETURNED' ? '再交一版' : '重新交一版'
})

const startHint = computed(() => (latest.value
  ? '新的一版提交后，现在这一版会变成"已被取代"，老师按新版批改。'
  : '可以一次选好几张照片，也可以选 PDF。'))

/** 有成绩可看才显示成绩卡：一道都没批完时服务端不给分数，卡片会是一片空白。 */
const gradedResult = computed<StudentResult | undefined>(
  () => (result.value && result.value.items.length > 0 ? result.value : undefined))

/** 还有几道题没批完。分母是作答的题数，服务端已经算好。 */
const pendingQuestionCount = computed(() => (
  result.value ? result.value.questionCount - result.value.gradedQuestionCount : 0))

/**
 * 老师手上已经有这份答卷了吗。
 *
 * <p>只认 `CONFIRMED` 与 `LOCKED`：这两个状态意味着老师已经确认过答题内容、正在批或准备批。
 * 更早的状态（识别中、等校对）说明答卷还没进入批改，此时说"等老师批改"是催错了人。
 */
const gradingStarted = computed(() => (
  latest.value ? ['CONFIRMED', 'LOCKED'].includes(latest.value.status) : false))

function editPath(versionId: number): string {
  return `/student/submissions/${versionId}/edit`
}

function formatTime(value?: string): string {
  if (!value) return ''
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'short',
  }).format(new Date(value))
}

function versionMeta(item: SubmissionVersionSummary): string {
  const parts = [`${item.pageCount} 页`]
  if (item.submittedAt) parts.push(`提交于 ${formatTime(item.submittedAt)}`)
  if (item.returnedAt) parts.push(`退回于 ${formatTime(item.returnedAt)}`)
  return parts.join(' · ')
}

async function load(): Promise<void> {
  try {
    // 两个请求互不依赖，一起发：手机上多一次往返就是多一秒白屏。
    const [detail, record] = await Promise.all([
      getMyAssignment(assignmentId),
      getSubmissionHistory(assignmentId),
    ])
    assignment.value = detail
    history.value = record
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}

function openEdit(versionId: number): void {
  void router.push(editPath(versionId))
}

/** 续传不建版本：URL 里的那一版已经是学生要的了。 */
function continueEditing(): void {
  if (editableVersion.value) openEdit(editableVersion.value.id)
}

/**
 * 开新一版。
 *
 * <p>接口是幂等的：手机上连点两下、或者网络超时后重试，都只会得到同一版草稿，
 * 不会把版本号推到第 7 版。所以这里不需要前端加锁去防重复提交。
 */
async function startNewVersion(): Promise<void> {
  busy.value = true
  error.value = ''
  try {
    const draft = await startSubmissionDraft(assignmentId)
    openEdit(draft.id)
  } catch (reason) {
    error.value = errorMessage(reason)
    await load()
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-heading">
      <div>
        <p class="kicker">我的作业</p>
        <h1>{{ assignment?.title ?? '我的答卷' }}</h1>
        <p v-if="assignment">
          {{ assignment.className }} · {{ assignment.teacherName }} · {{ assignment.questionCount }} 道题
        </p>
      </div>
    </header>

    <div v-if="error" class="state-panel error-state" role="alert">{{ error }}</div>
    <div v-else-if="loading" class="state-panel">正在载入答卷…</div>

    <template v-else-if="history">
      <div v-if="versions.length === 0" class="state-panel empty-invite">
        <b>还没有交过这份作业</b>
        <p>拍下写好的作业，一页一张照片；也可以直接传老师发的 PDF。</p>
        <button
          v-if="history.canStartNewVersion" class="primary-button" type="button" :disabled="busy"
          @click="startNewVersion"
        >{{ startLabel }}</button>
        <p v-else class="field-hint">这份作业已经结束，不能再提交了。</p>
      </div>

      <article v-else-if="latest" class="paper-card submission-card">
        <div class="assignment-card-head">
          <div>
            <span class="status-pill" :class="submissionVersionStatusTone(latest.status)">
              {{ submissionVersionStatusLabel(latest.status) }}
            </span>
            <h2>第 {{ latest.versionNo }} 版</h2>
            <p>{{ versionMeta(latest) }}</p>
            <p v-if="latest.current" class="field-hint">这一版就是老师正在处理的那一版。</p>
          </div>
        </div>

        <!-- 退回原因要显眼：学生回到这一页就是为了找这句话。 -->
        <p v-if="latest.returnReason" class="return-reason">
          <b>老师退回的原因：</b>{{ latest.returnReason }}
        </p>

        <div class="submission-actions">
          <button
            v-if="editableVersion" class="primary-button" type="button" :disabled="busy"
            @click="continueEditing"
          >继续上传</button>
          <button
            v-else-if="history.canStartNewVersion" class="primary-button" type="button" :disabled="busy"
            @click="startNewVersion"
          >{{ startLabel }}</button>
          <p v-else class="field-hint">老师已经开始批改，这一版不能再改了。确实需要重交，请联系老师退回。</p>
        </div>

        <p v-if="editableVersion || history.canStartNewVersion" class="field-hint">{{ startHint }}</p>
      </article>

      <article v-if="versions.length > 0" class="paper-card submission-card">
        <h2>提交记录</h2>
        <p class="field-hint">每次提交都是一版。老师批改的是最新提交的那一版。</p>
        <ol class="version-list">
          <li v-for="item in versions" :key="item.id" class="version-item">
            <div class="version-item-main">
              <span class="status-pill" :class="submissionVersionStatusTone(item.status)">
                {{ submissionVersionStatusLabel(item.status) }}
              </span>
              <b>第 {{ item.versionNo }} 版</b>
              <span v-if="item.current" class="version-current">当前提交</span>
              <p>{{ versionMeta(item) }}</p>
              <p v-if="item.returnReason" class="return-reason">{{ item.returnReason }}</p>
            </div>
            <button
              type="button" class="link-button"
              @click="openEdit(item.id)"
            >{{ item.editable ? '继续上传' : '查看' }}</button>
          </li>
        </ol>
      </article>
    </template>
  </section>
</template>
