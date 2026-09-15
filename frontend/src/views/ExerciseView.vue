<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import {
  api, approveExercise, downloadExerciseDocx, errorMessage, generateExercise, listExercises,
  type ApiResponse,
} from '../api/client'
import type { Assignment, Classroom, ExerciseSet, ExerciseTier } from '../api/types'
import MathText from '../math/MathText.vue'

const tierOrder: ExerciseTier[] = ['FOUNDATION', 'CORRECTION', 'IMPROVEMENT']
const tierLabel: Record<ExerciseTier, string> = {
  FOUNDATION: '基础巩固层', CORRECTION: '方法纠错层', IMPROVEMENT: '综合提升层',
}
const tierHint: Record<ExerciseTier, string> = {
  FOUNDATION: '掌握度低于 60% 的知识点',
  CORRECTION: '掌握度 60% 至 80% 的知识点',
  IMPROVEMENT: '掌握度高于 80% 的知识点',
}

const classes = ref<Classroom[]>([])
const assignments = ref<Assignment[]>([])
const classId = ref<number>()
const assignmentId = ref<number>()
const exercises = ref<ExerciseSet[]>([])
const current = ref<ExerciseSet>()
const loading = ref(true)
const generating = ref(false)

const candidateAssignments = computed(() => assignments.value.filter(item => !classId.value || item.classId === classId.value))
const itemsOf = (tier: ExerciseTier) => current.value?.items.filter(item => item.tier === tier) ?? []

async function loadPickers() {
  const [c, a] = await Promise.all([
    api.get<ApiResponse<Classroom[]>>('/classes'),
    api.get<ApiResponse<Assignment[]>>('/assignments'),
  ])
  classes.value = c.data.data
  assignments.value = a.data.data
  classId.value = classes.value[0]?.id
  assignmentId.value = candidateAssignments.value[0]?.id
}

async function loadExercises() {
  if (!classId.value) {
    exercises.value = []
    current.value = undefined
    loading.value = false
    return
  }
  loading.value = true
  try {
    exercises.value = await listExercises(classId.value)
    current.value = exercises.value[0]
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    loading.value = false
  }
}

/** 生成后按 id 重新取一次，让预览用的是服务端读回的数据，而不是本地拼的结果。 */
async function show(exerciseId: number) {
  if (!classId.value) return
  exercises.value = await listExercises(classId.value)
  current.value = exercises.value.find(item => item.id === exerciseId) ?? current.value
}

async function generate() {
  if (!classId.value || !assignmentId.value) { ElMessage.warning('请先选择来源班级与作业'); return }
  generating.value = true
  try {
    const created = await generateExercise({ classId: classId.value, sourceAssignmentId: assignmentId.value })
    await show(created.id)
    ElMessage.success('已生成分层练习草稿')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    generating.value = false
  }
}

async function approve() {
  if (!current.value) return
  try {
    const approved = await approveExercise(current.value.id)
    await show(approved.id)
    ElMessage.success('练习单已确认，可以导出 Word')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}

async function download() {
  if (!current.value) return
  try {
    const fallbackCodes = await downloadExerciseDocx(current.value.id)
    ElMessage.success('已开始下载 Word 文档')
    // 下载成功也要说清哪些题没能完整转换：文档里的红色标记只在 Word 里看得见，
    // 不在这里点名，教师打印前不会知道要核对哪几道题。
    if (fallbackCodes.length) {
      const named = fallbackCodes.filter(code => code !== '...')
      const suffix = fallbackCodes.includes('...') ? '，等' : ''
      ElMessage.warning(
        `这些题目的公式没能完整转成 Word 格式，文档中已标红：${named.join('、')}${suffix}`,
      )
    }
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}

watch(classId, loadExercises)
onMounted(async () => {
  try {
    await loadPickers()
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
    loading.value = false
  }
})
</script>

<template>
  <section class="page">
    <header class="page-heading">
      <div>
        <p class="kicker">因材施教</p>
        <h1>分层练习</h1>
        <p>只按教师已确认的画像分层组题，题目全部来自现有题库；确认后导出可打印的 Word 练习单。</p>
      </div>
    </header>

    <div class="toolbar paper-card">
      <div class="filter-pair">
        <el-select v-model="classId" placeholder="选择班级">
          <el-option v-for="item in classes" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="assignmentId" placeholder="选择来源作业">
          <el-option v-for="item in candidateAssignments" :key="item.id" :label="item.title" :value="item.id" />
        </el-select>
      </div>
      <span>{{ exercises.length }} 张练习单</span>
      <button class="primary-button" type="button" :disabled="generating" @click="generate">
        {{ generating ? '正在组题…' : '生成分层练习' }}
      </button>
    </div>

    <div v-if="loading" class="state-panel">正在载入练习单…</div>
    <div v-else-if="exercises.length === 0" class="state-panel empty-invite">
      <b>还没有练习单</b>
      <p>选好来源班级与作业后点击“生成分层练习”。</p>
    </div>
    <div v-else class="split-workspace">
      <aside class="paper-card review-list">
        <button
          v-for="item in exercises" :key="item.id"
          type="button" :class="{ active: item.id === current?.id }"
          @click="current = item"
        >
          <span>{{ item.status === 'APPROVED' ? '已确认' : '草稿' }}</span>
          <b>{{ item.title }}</b>
          <small>{{ item.items.length }} 道题</small>
        </button>
      </aside>

      <div v-if="current">
        <div v-if="current.notices.length" class="notice-strip">
          <p v-for="notice in current.notices" :key="notice">{{ notice }}</p>
        </div>

        <div class="assignment-board">
          <article v-for="tier in tierOrder" :key="tier" class="paper-card assignment-card">
            <div class="assignment-card-head">
              <div>
                <h2>{{ tierLabel[tier] }}</h2>
                <p>{{ tierHint[tier] }}</p>
              </div>
              <span class="assignment-number">{{ itemsOf(tier).length }}</span>
            </div>
            <div v-if="itemsOf(tier).length === 0" class="empty-invite compact">
              <p>该层暂无可用题目。</p>
            </div>
            <div v-for="item in itemsOf(tier)" :key="item.questionId" class="question-card">
              <div class="question-meta">
                <b>{{ item.sortOrder }}</b>
                <span class="mono">{{ item.questionCode }}</span>
                <span>{{ item.totalScore }} 分</span>
              </div>
              <p class="formula-text"><MathText :text="item.content" /></p>
              <div class="question-foot"><span>知识点：{{ item.knowledgePointName }}</span></div>
            </div>
          </article>
        </div>

        <div class="paper-card confirm-review">
          <div>
            <b>{{ current.status === 'APPROVED' ? '练习单已确认' : '确认后才可以导出 Word' }}</b>
            <p>{{ current.status === 'APPROVED'
              ? '导出的文档含题目正文与文末的教师参考区。'
              : '草稿可以继续调整选题，确认后即固定下来。' }}</p>
          </div>
          <div class="assignment-actions">
            <button class="primary-button" type="button" :disabled="current.status === 'APPROVED'" @click="approve">
              确认练习单
            </button>
            <button class="secondary-button" type="button" :disabled="current.status !== 'APPROVED'" @click="download">
              导出 Word
            </button>
          </div>
        </div>
      </div>
    </div>
  </section>
</template>
