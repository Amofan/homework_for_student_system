<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { assignmentStatusLabel, submissionStatusLabel } from '../../api/assignmentStatus'
import { errorMessage, listMyAssignments } from '../../api/client'
import type { StudentAssignment } from '../../api/types'

const assignments = ref<StudentAssignment[]>([])
const loading = ref(true)
const error = ref('')

function formatDue(dueAt: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'short',
  }).format(new Date(dueAt))
}

function isOverdue(item: StudentAssignment): boolean {
  return item.dueAt !== undefined && new Date(item.dueAt).getTime() < Date.now()
}

/** 提交过就不再提示截止，否则学生交完还会看到一排红色的“已截止”。 */
function dueText(item: StudentAssignment): string {
  if (!item.dueAt) return '不限截止时间'
  const prefix = isOverdue(item) && item.submissionStatus === 'NOT_SUBMITTED' ? '已截止 ' : '截止 '
  return prefix + formatDue(item.dueAt)
}

onMounted(async () => {
  try { assignments.value = await listMyAssignments() }
  catch (reason) { error.value = errorMessage(reason) }
  finally { loading.value = false }
})
</script>

<template>
  <section class="page">
    <header class="page-heading">
      <div>
        <p class="kicker">我的作业</p>
        <h1>待完成的作业</h1>
        <p>这里只显示老师已经发布的作业。识别文字由老师校对，你只要保证页面完整清晰。</p>
      </div>
    </header>

    <div v-if="error" class="state-panel error-state" role="alert">{{ error }}</div>
    <div v-else-if="loading" class="state-panel">正在载入作业…</div>
    <div v-else-if="assignments.length === 0" class="state-panel empty-invite">
      <b>暂时没有待完成的作业</b>
      <p>老师发布作业后，它会出现在这里。</p>
    </div>

    <div v-else class="assignment-board">
      <article v-for="item in assignments" :key="item.id" class="paper-card assignment-card">
        <div class="assignment-card-head">
          <div>
            <span class="status-pill" :class="item.submissionStatus === 'NOT_SUBMITTED' ? '' : 'good'">
              {{ submissionStatusLabel(item.submissionStatus) }}
            </span>
            <h2>{{ item.title }}</h2>
            <p>{{ item.className }} · {{ item.teacherName }} · {{ item.questionCount }} 道题</p>
            <p class="due-line" :class="{ overdue: isOverdue(item) && item.submissionStatus === 'NOT_SUBMITTED' }">
              {{ dueText(item) }}
            </p>
          </div>
          <div class="assignment-number">#{{ item.id }}</div>
        </div>
        <div class="assignment-actions">
          <!--
            入口是一个链接而不是按钮：点了就是换一页，用 <a> 才能长按复制、在新标签页里打开，
            手机上"用浏览器打开"也是这条路。禁用的按钮换成什么都不做更糟 —— 学生只看到点不动。
          -->
          <RouterLink
            class="primary-button"
            :to="`/student/assignments/${item.id}/submission`"
          >{{ item.submissionStatus === 'NOT_SUBMITTED' ? '上传答卷' : '查看答卷' }}</RouterLink>
          <span class="status-pill muted">{{ assignmentStatusLabel(item.status) }}</span>
        </div>
      </article>
    </div>
  </section>
</template>
