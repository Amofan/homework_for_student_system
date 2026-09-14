<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import { api, errorMessage, type ApiResponse } from '../api/client'
import type { Classroom, Student } from '../api/types'

const classes = ref<Classroom[]>([])
const students = ref<Student[]>([])
const selectedId = ref<number>()
const loading = ref(true)
const error = ref('')
const classDialog = ref(false)
const studentDialog = ref(false)
const classForm = reactive({ classCode: '', name: '', grade: 7, semester: '2026-2027-1' })
const studentForm = reactive({ studentNo: '', name: '' })

async function loadClasses() {
  const response = await api.get<ApiResponse<Classroom[]>>('/classes')
  classes.value = response.data.data
  if (!selectedId.value && classes.value.length) selectedId.value = classes.value[0].id
}
async function loadStudents() {
  if (!selectedId.value) { students.value = []; return }
  const response = await api.get<ApiResponse<Student[]>>(`/classes/${selectedId.value}/students`)
  students.value = response.data.data
}
async function createClass() {
  try {
    await api.post('/classes', classForm)
    classDialog.value = false
    Object.assign(classForm, { classCode: '', name: '', grade: 7, semester: '2026-2027-1' })
    await loadClasses(); ElMessage.success('班级已创建')
  } catch (reason) { ElMessage.error(errorMessage(reason)) }
}
async function createStudent() {
  if (!selectedId.value) return
  try {
    await api.post(`/classes/${selectedId.value}/students`, studentForm)
    studentDialog.value = false
    Object.assign(studentForm, { studentNo: '', name: '' })
    await Promise.all([loadStudents(), loadClasses()]); ElMessage.success('学生已加入名册')
  } catch (reason) { ElMessage.error(errorMessage(reason)) }
}
watch(selectedId, () => loadStudents())
onMounted(async () => {
  try { await loadClasses(); await loadStudents() } catch (reason) { error.value = errorMessage(reason) }
  finally { loading.value = false }
})
</script>

<template>
  <section class="page">
    <header class="page-heading"><div><p class="kicker">班级名册</p><h1>班级与学生</h1><p>所有作业、成绩和画像都从可靠的学生名册开始。</p></div><button class="primary-button" @click="classDialog = true">新建班级</button></header>
    <div v-if="error" class="state-panel error-state">{{ error }}</div>
    <div v-else-if="loading" class="state-panel">正在载入班级…</div>
    <div v-else class="split-workspace">
      <aside class="class-index paper-card">
        <div class="card-title"><div><span>我的班级</span><h2>{{ classes.length }} 个班</h2></div></div>
        <button v-for="item in classes" :key="item.id" class="class-tab" :class="{ active: selectedId === item.id }" @click="selectedId = item.id">
          <span>{{ item.grade ? `${item.grade}年级` : '未分级' }}</span><b>{{ item.name }}</b><small>{{ item.studentCount }} 名学生 · {{ item.classCode }}</small>
        </button>
        <div v-if="classes.length === 0" class="empty-invite"><b>先创建一个班级</b><p>班级创建后即可录入学生。</p></div>
      </aside>
      <article class="paper-card roster-panel">
        <div class="card-title"><div><span>学生名册</span><h2>{{ classes.find(item => item.id === selectedId)?.name || '请选择班级' }}</h2></div><button class="secondary-button" :disabled="!selectedId" @click="studentDialog = true">添加学生</button></div>
        <div v-if="students.length === 0" class="empty-invite"><b>名册还是空的</b><p>逐个添加学生，或在作业管理中使用 Excel 模板。</p></div>
        <div v-else class="data-table-wrap"><table class="data-table"><thead><tr><th>序号</th><th>学号</th><th>姓名</th><th>状态</th></tr></thead><tbody><tr v-for="(student, index) in students" :key="student.id"><td>{{ index + 1 }}</td><td class="mono">{{ student.studentNo }}</td><td><b>{{ student.name }}</b></td><td><span class="status-pill good">在读</span></td></tr></tbody></table></div>
      </article>
    </div>
    <el-dialog v-model="classDialog" title="新建班级" width="480px"><el-form label-position="top"><el-form-item label="班级编码"><el-input v-model="classForm.classCode" placeholder="如 2026-7-1" /></el-form-item><el-form-item label="班级名称"><el-input v-model="classForm.name" placeholder="如 七年级一班" /></el-form-item><div class="form-grid"><el-form-item label="年级"><el-select v-model="classForm.grade"><el-option :value="7" label="七年级" /><el-option :value="8" label="八年级" /><el-option :value="9" label="九年级" /></el-select></el-form-item><el-form-item label="学期"><el-input v-model="classForm.semester" /></el-form-item></div></el-form><template #footer><button class="secondary-button" @click="classDialog = false">取消</button><button class="primary-button" @click="createClass">创建班级</button></template></el-dialog>
    <el-dialog v-model="studentDialog" title="添加学生" width="440px"><el-form label-position="top"><el-form-item label="学号"><el-input v-model="studentForm.studentNo" /></el-form-item><el-form-item label="姓名"><el-input v-model="studentForm.name" /></el-form-item></el-form><template #footer><button class="secondary-button" @click="studentDialog = false">取消</button><button class="primary-button" @click="createStudent">加入名册</button></template></el-dialog>
  </section>
</template>
