<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { api, errorMessage, type ApiResponse } from '../api/client'
import type { Assignment, Classroom } from '../api/types'

const classes = ref<Classroom[]>([])
const assignments = ref<Assignment[]>([])
const loading = ref(true)
const error = ref('')

onMounted(async () => {
  try {
    const [classResponse, assignmentResponse] = await Promise.all([
      api.get<ApiResponse<Classroom[]>>('/classes'),
      api.get<ApiResponse<Assignment[]>>('/assignments'),
    ])
    classes.value = classResponse.data.data
    assignments.value = assignmentResponse.data.data
  } catch (reason) { error.value = errorMessage(reason) }
  finally { loading.value = false }
})
</script>

<template>
  <section class="page dashboard-page">
    <header class="page-heading dashboard-heading">
      <div><p class="kicker">教学诊断台</p><h1>今天，从哪份作业开始？</h1><p>先确认批改结果，再用画像决定下一节课讲什么。</p></div>
      <RouterLink to="/teacher/assignments" class="primary-button">新建一份作业</RouterLink>
    </header>
    <div v-if="error" class="state-panel error-state">{{ error }}</div>
    <div v-else-if="loading" class="state-panel">正在读取教学数据…</div>
    <template v-else>
      <div class="metric-ledger">
        <article><span>在教班级</span><strong>{{ classes.length }}</strong><small>个班</small></article>
        <article><span>学生名册</span><strong>{{ classes.reduce((sum, item) => sum + item.studentCount, 0) }}</strong><small>人</small></article>
        <article><span>作业记录</span><strong>{{ assignments.length }}</strong><small>份</small></article>
        <article class="accent-metric"><span>待推进</span><strong>{{ assignments.filter(item => item.status !== 'COMPLETED').length }}</strong><small>份作业</small></article>
      </div>
      <div class="dashboard-grid">
        <article class="paper-card current-work">
          <div class="card-title"><div><span>最近作业</span><h2>批改进度</h2></div><RouterLink to="/teacher/assignments">查看全部</RouterLink></div>
          <div v-if="assignments.length === 0" class="empty-invite"><b>还没有作业</b><p>先建立题库，再创建第一份结构化作业。</p></div>
          <div v-for="item in assignments.slice(0, 4)" :key="item.id" class="assignment-row">
            <span class="assignment-status">{{ item.status === 'DRAFT' ? '草稿' : '处理中' }}</span>
            <div><b>{{ item.title }}</b><small>{{ item.questionIds.length }} 道题 · 班级编号 {{ item.classId }}</small></div>
            <RouterLink to="/teacher/review">继续</RouterLink>
          </div>
        </article>
        <article class="paper-card teaching-loop">
          <div class="card-title"><div><span>证据链</span><h2>一次作业的闭环</h2></div></div>
          <ol>
            <li><i>1</i><span><b>录入答案</b><small>Excel 结构化导入</small></span></li>
            <li><i>2</i><span><b>辅助评分</b><small>规则优先，AI 建议</small></span></li>
            <li><i>3</i><span><b>教师复核</b><small>确认正式成绩与错因</small></span></li>
            <li><i>4</i><span><b>形成画像</b><small>知识点掌握度可追溯</small></span></li>
          </ol>
        </article>
      </div>
    </template>
  </section>
</template>
