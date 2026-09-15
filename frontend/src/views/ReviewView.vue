<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { api, errorMessage, type ApiResponse } from '../api/client'
import { ERROR_TYPE_OPTIONS, errorTypeLabel, type ErrorType } from '../api/errorTypes'
import type { Assignment, ReviewQueueItem } from '../api/types'
import MathText from '../math/MathText.vue'

const assignments = ref<Assignment[]>([])
const assignmentId = ref<number>()
const queue = ref<ReviewQueueItem[]>([])
const selected = ref<ReviewQueueItem>()
const loading = ref(false)
// 复核计时：交给前端是因为只有界面知道教师从哪一刻开始看这道题。
// 用 performance.now() 而不是 Date.now()：它是单调时钟，系统对时或夏令时回拨
// 都不会算出负数耗时。
const startedAt = ref<number>()
// 最终错因只接受固定编码：后端也按同一集合校验，自由文本会让论文的错因口径失控。
// 初值保留空串，好让"修改/驳回时还没选错因"这个状态可判定。
const form = reactive({ decision: 'ACCEPT', finalScore: undefined as number | undefined, errorType: '' as ErrorType | '', feedback: '', reason: '' })
const currentIndex = computed(() => selected.value ? queue.value.findIndex(item => item.resultId === selected.value?.resultId) + 1 : 0)
/** 采纳沿用模型建议，无需再选；修改或驳回必须由教师明确指定最终错因。 */
const submitBlocked = computed(() => form.decision !== 'ACCEPT' && !form.errorType)
async function loadQueue() {
  if (!assignmentId.value) { queue.value = []; selected.value = undefined; return }
  loading.value = true
  try { const response = await api.get<ApiResponse<ReviewQueueItem[]>>(`/grading/assignments/${assignmentId.value}/review-queue`); queue.value = response.data.data; select(queue.value[0]) }
  catch (reason) { ElMessage.error(errorMessage(reason)) } finally { loading.value = false }
}
function select(item?: ReviewQueueItem) { selected.value = item; startedAt.value = item ? performance.now() : undefined; if (item) Object.assign(form, { decision: 'ACCEPT', finalScore: item.suggestedScore, errorType: item.errorType, feedback: item.studentFeedback ?? '', reason: '' }) }
/** 复核耗时（秒，三位小数）。取不到计时就上交缺失，不交 0——0 秒会被统计成“瞬间批完”。 */
function elapsedSeconds(): number | undefined {
  if (startedAt.value === undefined) return undefined
  return Math.round(performance.now() - startedAt.value) / 1000
}
async function confirm() {
  if (!selected.value) return
  try { await api.post(`/grading/results/${selected.value.resultId}/review`, { ...form, teacherSeconds: elapsedSeconds() }); ElMessage.success('复核已确认，结果已进入学情分析'); await loadQueue() }
  catch (reason) { ElMessage.error(errorMessage(reason)) }
}
watch(assignmentId, loadQueue)
onMounted(async () => { try { const response = await api.get<ApiResponse<Assignment[]>>('/assignments'); assignments.value = response.data.data; assignmentId.value = assignments.value[0]?.id } catch (reason) { ElMessage.error(errorMessage(reason)) } })
</script>

<template>
  <section class="page review-page">
    <header class="page-heading"><div><p class="kicker">教师签字区</p><h1>教师复核</h1><p>AI 与规则结果都只是建议；你在这里确认正式得分和错因。</p></div><el-select v-model="assignmentId" placeholder="选择作业" class="assignment-picker"><el-option v-for="item in assignments" :key="item.id" :label="item.title" :value="item.id" /></el-select></header>
    <div v-if="loading" class="state-panel">正在读取待复核答案…</div>
    <div v-else-if="queue.length === 0" class="state-panel empty-invite"><span class="large-check">✓</span><b>这份作业没有待复核项</b><p>请先导入答案并启动辅助评分，或选择其他作业。</p></div>
    <div v-else class="review-workspace">
      <aside class="paper-card review-list"><div class="card-title"><div><span>待复核</span><h2>{{ queue.length }} 条答案</h2></div></div><button v-for="item in queue" :key="item.resultId" :class="{ active: selected?.resultId === item.resultId }" @click="select(item)"><span>{{ item.studentNo }}</span><b>{{ item.studentName }}</b><small>{{ item.questionCode }} · 建议 {{ item.suggestedScore }}/{{ item.totalScore }}</small></button></aside>
      <article v-if="selected" class="paper-card review-sheet">
        <div class="review-progress">第 {{ currentIndex }} / {{ queue.length }} 条 <span :class="['source-badge', selected.source.toLowerCase()]">{{ selected.source === 'AI' ? 'AI 建议' : '规则评分' }}</span></div>
        <div class="answer-section"><span>题目 {{ selected.questionCode }}</span><p class="formula-text"><MathText :text="selected.questionContent" /></p></div>
        <div class="student-answer"><span>学生作答</span><p><MathText :text="selected.answerContent || '（未作答）'" /></p></div>
        <div class="suggestion-block"><div><span>建议得分</span><strong>{{ selected.suggestedScore }}<small>/ {{ selected.totalScore }}</small></strong></div><div><span>建议错因</span><b>{{ errorTypeLabel(selected.errorType) }}</b><p><MathText :text="selected.teacherExplanation ?? ''" /></p></div></div>
        <div class="review-form"><div class="decision-tabs"><button v-for="choice in [['ACCEPT','采纳'],['MODIFY','修改'],['REJECT','驳回并人工评分']]" :key="choice[0]" :class="{ active: form.decision === choice[0] }" @click="form.decision = choice[0]">{{ choice[1] }}</button></div><div class="form-grid"><label>最终得分<el-input-number v-model="form.finalScore" :min="0" :max="selected.totalScore" /></label><label>最终错因<el-select v-model="form.errorType" placeholder="选择最终错因" class="error-type-select"><el-option v-for="option in ERROR_TYPE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" /></el-select></label></div><label>给学生的反馈<el-input v-model="form.feedback" type="textarea" :rows="2" /></label><label v-if="form.decision !== 'ACCEPT'">修改原因<el-input v-model="form.reason" placeholder="修改或驳回时必填" /></label><button class="primary-button confirm-review" :disabled="submitBlocked" @click="confirm">确认本条复核</button></div>
      </article>
    </div>
  </section>
</template>
