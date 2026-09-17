<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { assignmentStatusLabel, isPublishedStatus } from '../api/assignmentStatus'
import { api, downloadEvaluationCases, errorMessage, publishAssignment, type ApiResponse } from '../api/client'
import type { Assignment, Classroom, Question } from '../api/types'

const assignments = ref<Assignment[]>([])
const classes = ref<Classroom[]>([])
const questions = ref<Question[]>([])
const dialog = ref(false)
const publishDialog = ref(false)
const publishTarget = ref<Assignment>()
const busyId = ref<number>()
const fileByAssignment = reactive<Record<number, File | undefined>>({})
const form = reactive<{ classId?: number; title: string; questionIds: number[] }>({ title: '', questionIds: [] })
const publishForm = reactive({ dueAtLocal: '' })
const ready = computed(() => classes.value.length > 0 && questions.value.length > 0)

/**
 * 截止时间已经过去就不让提交。
 *
 * <p>服务端也会拒绝（`ASSIGNMENT_DUE_AT_INVALID`），但在界面上先拦住可以省掉一次
 * 「点了才知道不行」的往返；两条规则必须一致，改一处要改两处。
 */
const dueAtInPast = computed(() => publishForm.dueAtLocal !== ''
  && new Date(publishForm.dueAtLocal).getTime() <= Date.now())

async function load() {
  const [a, c, q] = await Promise.all([api.get<ApiResponse<Assignment[]>>('/assignments'), api.get<ApiResponse<Classroom[]>>('/classes'), api.get<ApiResponse<Question[]>>('/questions')])
  assignments.value = a.data.data; classes.value = c.data.data; questions.value = q.data.data
}

function classNameOf(item: Assignment): string {
  return classes.value.find(value => value.id === item.classId)?.name || `班级 ${item.classId}`
}

function formatDue(dueAt: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'short',
  }).format(new Date(dueAt))
}

async function create() {
  try { await api.post('/assignments', form); dialog.value = false; Object.assign(form, { classId: undefined, title: '', questionIds: [] }); await load(); ElMessage.success('作业已创建') }
  catch (reason) { ElMessage.error(errorMessage(reason)) }
}

function openPublish(item: Assignment) {
  publishTarget.value = item
  publishForm.dueAtLocal = ''
  publishDialog.value = true
}

/**
 * 发布作业。
 *
 * <p>把页面上那一份 `version` 原样回传：这是并发下唯一能发现「你手里的作业已经不是最新版」
 * 的办法。成功后必须 `load()` 重新取一遍，否则本地的版本号还停在发布前，下一次操作会被
 * 服务端判成版本冲突。
 */
async function confirmPublish() {
  const target = publishTarget.value
  if (!target || dueAtInPast.value) return
  busyId.value = target.id
  try {
    await publishAssignment(target.id, target.version,
      publishForm.dueAtLocal ? new Date(publishForm.dueAtLocal).toISOString() : undefined)
    publishDialog.value = false
    await load()
    ElMessage.success('作业已发布，学生可在“我的作业”中看到')
  } catch (reason) { ElMessage.error(errorMessage(reason)) } finally { busyId.value = undefined }
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

/**
 * 导出这次作业的匿名评测样本。
 *
 * 三条提示是分开的，不能合并成"导出成功"：导出 0 条时没有文件内容可核对，
 * 有样本被剔除时文件本身看不出少了行——这两种情况都必须让教师看见，
 * 否则会把导出条数当成全量样本量。
 */
async function exportEvaluation(item: Assignment) {
  busyId.value = item.id
  try {
    const result = await downloadEvaluationCases(item.id)
    if (result.exported === 0) ElMessage.warning('这份作业还没有已复核的 AI 样本')
    else ElMessage.success(`已导出 ${result.exported} 条评测样本`)
    if (result.skippedMissingAiError > 0) ElMessage.warning(`另有 ${result.skippedMissingAiError} 条历史样本因缺少模型原始错因未导出，样本量请以导出结果为准`)
  }
  catch (reason) { ElMessage.error(errorMessage(reason)) } finally { busyId.value = undefined }
}

onMounted(() => load().catch(reason => ElMessage.error(errorMessage(reason))))
</script>

<template>
  <section class="page">
    <header class="page-heading"><div><p class="kicker">作业流转</p><h1>作业管理</h1><p>建立题目清单，发布给班级，再导入结构化答案并启动可追溯评分。</p></div><div class="heading-actions"><RouterLink class="secondary-button" to="/teacher/paper-imports">上传整卷新建作业 →</RouterLink><button class="primary-button" :disabled="!ready" @click="dialog = true">创建作业</button></div></header>
    <div v-if="!ready" class="notice-strip"><b>创建前准备：</b>至少需要一个班级和一道题目。</div>
    <div v-if="assignments.length === 0" class="state-panel empty-invite"><b>还没有作业</b><p>准备好班级和题库后，即可创建第一份作业。</p></div>
    <div v-else class="assignment-board">
      <article v-for="item in assignments" :key="item.id" class="paper-card assignment-card">
        <div class="assignment-card-head">
          <div>
            <span class="status-pill" :class="isPublishedStatus(item.status) ? 'good' : ''">{{ assignmentStatusLabel(item.status) }}</span>
            <h2>{{ item.title }}</h2>
            <p>
              {{ classNameOf(item) }} · {{ item.questionIds.length }} 道题
              <template v-if="item.dueAt"> · 截止 {{ formatDue(item.dueAt) }}</template>
            </p>
          </div>
          <div class="assignment-number">#{{ item.id }}</div>
        </div>
        <div class="import-zone">
          <label><span>答案工作簿</span><input type="file" accept=".xlsx" @change="chooseFile($event, item.id)"><b>{{ fileByAssignment[item.id]?.name || '选择 .xlsx 文件' }}</b></label>
          <button class="secondary-button" :disabled="busyId === item.id" @click="importAnswers(item)">导入并校验</button>
        </div>
        <div class="assignment-actions">
          <button
            v-if="!isPublishedStatus(item.status)"
            class="primary-button" :disabled="busyId === item.id || item.questionIds.length === 0"
            :title="item.questionIds.length === 0 ? '作业至少需要一道题目才能发布' : '发布后学生即可看到这份作业'"
            @click="openPublish(item)"
          >发布给班级</button>
          <button class="primary-button" :disabled="busyId === item.id" @click="runGrading(item)">启动辅助评分</button>
          <button class="secondary-button" :disabled="busyId === item.id" @click="exportEvaluation(item)">导出评测样本</button>
          <RouterLink to="/teacher/review">进入教师复核 →</RouterLink>
        </div>
      </article>
    </div>
    <el-dialog v-model="dialog" title="创建作业" width="600px"><el-form label-position="top"><el-form-item label="作业名称"><el-input v-model="form.title" placeholder="如 一元一次方程巩固作业" /></el-form-item><el-form-item label="班级"><el-select v-model="form.classId"><el-option v-for="item in classes" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="选择题目"><el-select v-model="form.questionIds" multiple filterable><el-option v-for="item in questions" :key="item.id" :label="`${item.questionCode} · ${item.content}`" :value="item.id" /></el-select></el-form-item></el-form><template #footer><button class="secondary-button" @click="dialog = false">取消</button><button class="primary-button" @click="create">创建作业</button></template></el-dialog>

    <el-dialog v-model="publishDialog" title="发布作业" width="480px">
      <p class="field-hint">
        发布后 {{ publishTarget ? classNameOf(publishTarget) : '' }} 的学生会立刻在“我的作业”里看到它。
        这一份作业当前是第 {{ publishTarget?.version ?? 0 }} 版，发布后版本号会加一。
      </p>
      <el-form label-position="top">
        <el-form-item label="截止时间（可选）">
          <input v-model="publishForm.dueAtLocal" class="date-input" type="datetime-local">
        </el-form-item>
      </el-form>
      <p v-if="dueAtInPast" class="form-error" role="alert">截止时间不能早于当前时间。</p>
      <p v-else class="field-hint">留空表示不设截止时间。</p>
      <template #footer>
        <button class="secondary-button" @click="publishDialog = false">取消</button>
        <button class="primary-button" :disabled="busyId === publishTarget?.id || dueAtInPast" @click="confirmPublish">确认发布</button>
      </template>
    </el-dialog>
  </section>
</template>
