<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { api, errorMessage, type ApiResponse } from '../api/client'
import type { Assignment, Classroom, Question } from '../api/types'

const assignments = ref<Assignment[]>([])
const classes = ref<Classroom[]>([])
const questions = ref<Question[]>([])
const dialog = ref(false)
const busyId = ref<number>()
const fileByAssignment = reactive<Record<number, File | undefined>>({})
const form = reactive<{ classId?: number; title: string; questionIds: number[] }>({ title: '', questionIds: [] })
const ready = computed(() => classes.value.length > 0 && questions.value.length > 0)
async function load() {
  const [a, c, q] = await Promise.all([api.get<ApiResponse<Assignment[]>>('/assignments'), api.get<ApiResponse<Classroom[]>>('/classes'), api.get<ApiResponse<Question[]>>('/questions')])
  assignments.value = a.data.data; classes.value = c.data.data; questions.value = q.data.data
}
async function create() {
  try { await api.post('/assignments', form); dialog.value = false; Object.assign(form, { classId: undefined, title: '', questionIds: [] }); await load(); ElMessage.success('作业已创建') }
  catch (reason) { ElMessage.error(errorMessage(reason)) }
}
function chooseFile(event: Event, id: number) { fileByAssignment[id] = (event.target as HTMLInputElement).files?.[0] }
async function importAnswers(item: Assignment) {
  const file = fileByAssignment[item.id]; if (!file) { ElMessage.warning('请先选择 .xlsx 答案文件'); return }
  busyId.value = item.id
  try { const data = new FormData(); data.append('file', file); const response = await api.post<ApiResponse<{ importedRows: number; errors: { row: number; message: string }[] }>>(`/assignments/${item.id}/answers/import`, data); const result = response.data.data; result.errors.length ? ElMessage.error(`导入未执行：第 ${result.errors[0].row} 行 ${result.errors[0].message}`) : ElMessage.success(`已导入 ${result.importedRows} 行答案`); await load() }
  catch (reason) { ElMessage.error(errorMessage(reason)) } finally { busyId.value = undefined }
}
async function runGrading(item: Assignment) {
  busyId.value = item.id
  try { const response = await api.post<ApiResponse<{ ruleGraded: number; aiQueued: number; skipped: number }>>(`/grading/assignments/${item.id}/run`); const result = response.data.data; ElMessage.success(`规则评分 ${result.ruleGraded} 条，AI 待处理 ${result.aiQueued} 条`) }
  catch (reason) { ElMessage.error(errorMessage(reason)) } finally { busyId.value = undefined }
}
onMounted(() => load().catch(reason => ElMessage.error(errorMessage(reason))))
</script>

<template>
  <section class="page">
    <header class="page-heading"><div><p class="kicker">作业流转</p><h1>作业管理</h1><p>建立题目清单，导入结构化答案，然后启动可追溯评分。</p></div><button class="primary-button" :disabled="!ready" @click="dialog = true">创建作业</button></header>
    <div v-if="!ready" class="notice-strip"><b>创建前准备：</b>至少需要一个班级和一道题目。</div>
    <div v-if="assignments.length === 0" class="state-panel empty-invite"><b>还没有作业</b><p>准备好班级和题库后，即可创建第一份作业。</p></div>
    <div v-else class="assignment-board"><article v-for="item in assignments" :key="item.id" class="paper-card assignment-card"><div class="assignment-card-head"><div><span class="status-pill" :class="item.status === 'DRAFT' ? '' : 'good'">{{ item.status === 'DRAFT' ? '草稿' : '已录入答案' }}</span><h2>{{ item.title }}</h2><p>{{ classes.find(value => value.id === item.classId)?.name || `班级 ${item.classId}` }} · {{ item.questionIds.length }} 道题</p></div><div class="assignment-number">#{{ item.id }}</div></div><div class="import-zone"><label><span>答案工作簿</span><input type="file" accept=".xlsx" @change="chooseFile($event, item.id)"><b>{{ fileByAssignment[item.id]?.name || '选择 .xlsx 文件' }}</b></label><button class="secondary-button" :disabled="busyId === item.id" @click="importAnswers(item)">导入并校验</button></div><div class="assignment-actions"><button class="primary-button" :disabled="busyId === item.id" @click="runGrading(item)">启动辅助评分</button><RouterLink to="/review">进入教师复核 →</RouterLink></div></article></div>
    <el-dialog v-model="dialog" title="创建作业" width="600px"><el-form label-position="top"><el-form-item label="作业名称"><el-input v-model="form.title" placeholder="如 一元一次方程巩固作业" /></el-form-item><el-form-item label="班级"><el-select v-model="form.classId"><el-option v-for="item in classes" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="选择题目"><el-select v-model="form.questionIds" multiple filterable><el-option v-for="item in questions" :key="item.id" :label="`${item.questionCode} · ${item.content}`" :value="item.id" /></el-select></el-form-item></el-form><template #footer><button class="secondary-button" @click="dialog = false">取消</button><button class="primary-button" @click="create">创建作业</button></template></el-dialog>
  </section>
</template>
