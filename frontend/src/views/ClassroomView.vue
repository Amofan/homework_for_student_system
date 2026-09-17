<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

import {
  api, downloadStudentCredentials, errorMessage, provisionStudentAccounts,
  resetStudentPassword, type ApiResponse,
} from '../api/client'
import type { Classroom, ProvisionedStudentAccount, Student } from '../api/types'

const classes = ref<Classroom[]>([])
const students = ref<Student[]>([])
const selectedId = ref<number>()
const loading = ref(true)
const error = ref('')
const classDialog = ref(false)
const studentDialog = ref(false)
const classForm = reactive({ classCode: '', name: '', grade: 7, semester: '2026-2027-1' })
const studentForm = reactive({ studentNo: '', name: '' })

/**
 * 一次性明文凭据。
 *
 * <p>只放在内存里，关掉弹窗或切换班级就清空：写进 localStorage 会把明文密码留到下次开机，
 * 而这批密码本来是"只显示一次"的。
 */
const credentials = ref<ProvisionedStudentAccount[]>([])
const credentialDialog = ref(false)
const workingStudentId = ref<number>()

const printableCredentials = computed(() => credentials.value.filter(item => Boolean(item.temporaryPassword)))

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

function accountLabel(student: Student): string {
  if (!student.accountUsername) return '未开通'
  return student.accountStatus === 'PASSWORD_CHANGE_REQUIRED' ? '待首次改密' : '已开通'
}

async function provision(student: Student) {
  if (!selectedId.value) return
  workingStudentId.value = student.id
  try {
    const accounts = await provisionStudentAccounts(selectedId.value, [student.id])
    const fresh = accounts.filter(item => Boolean(item.temporaryPassword))
    if (fresh.length === 0) {
      ElMessage.info('该学生已有账号，密码不会被重新显示')
    } else {
      credentials.value = fresh
      credentialDialog.value = true
      ElMessage.success('账号已开通，请立即保存临时密码')
    }
    await loadStudents()
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    workingStudentId.value = undefined
  }
}

async function reset(student: Student) {
  workingStudentId.value = student.id
  try {
    credentials.value = [await resetStudentPassword(student.id)]
    credentialDialog.value = true
    ElMessage.success('已生成新的临时密码，请立即保存')
    await loadStudents()
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    workingStudentId.value = undefined
  }
}

/** 关闭弹窗即丢弃明文。下载必须在关闭前完成，这是"只显示一次"的代价。 */
function closeCredentials() {
  credentialDialog.value = false
  credentials.value = []
}

async function downloadCredentials() {
  if (!selectedId.value) return
  try {
    await downloadStudentCredentials(selectedId.value, printableCredentials.value)
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}

watch(selectedId, () => { closeCredentials(); loadStudents() })
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
        <div v-else class="data-table-wrap"><table class="data-table">
          <thead><tr><th>序号</th><th>学号</th><th>姓名</th><th>登录账号</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="(student, index) in students" :key="student.id">
              <td>{{ index + 1 }}</td>
              <td class="mono">{{ student.studentNo }}</td>
              <td><b>{{ student.name }}</b></td>
              <td>
                <span class="status-pill" :class="student.accountUsername ? 'good' : 'muted'">{{ accountLabel(student) }}</span>
                <small v-if="student.accountUsername" class="mono account-name">{{ student.accountUsername }}</small>
              </td>
              <td>
                <button class="table-action" type="button" :disabled="workingStudentId === student.id" @click="provision(student)">开通账号</button>
                <button
                  v-if="student.accountUsername" class="table-action" type="button"
                  :disabled="workingStudentId === student.id" @click="reset(student)"
                >重置密码</button>
              </td>
            </tr>
          </tbody>
        </table></div>
      </article>
    </div>
    <el-dialog v-model="classDialog" title="新建班级" width="480px"><el-form label-position="top"><el-form-item label="班级编码"><el-input v-model="classForm.classCode" placeholder="如 2026-7-1" /></el-form-item><el-form-item label="班级名称"><el-input v-model="classForm.name" placeholder="如 七年级一班" /></el-form-item><div class="form-grid"><el-form-item label="年级"><el-select v-model="classForm.grade"><el-option :value="7" label="七年级" /><el-option :value="8" label="八年级" /><el-option :value="9" label="九年级" /></el-select></el-form-item><el-form-item label="学期"><el-input v-model="classForm.semester" /></el-form-item></div></el-form><template #footer><button class="secondary-button" @click="classDialog = false">取消</button><button class="primary-button" @click="createClass">创建班级</button></template></el-dialog>
    <el-dialog v-model="studentDialog" title="添加学生" width="440px"><el-form label-position="top"><el-form-item label="学号"><el-input v-model="studentForm.studentNo" /></el-form-item><el-form-item label="姓名"><el-input v-model="studentForm.name" /></el-form-item></el-form><template #footer><button class="secondary-button" @click="studentDialog = false">取消</button><button class="primary-button" @click="createStudent">加入名册</button></template></el-dialog>

    <el-dialog :model-value="credentialDialog" title="一次性临时密码" width="640px" @update:model-value="closeCredentials">
      <p class="credential-warning" role="alert">关闭本窗口后无法再次查看。请现在下载 Excel，或让学生当场记下后再关闭。</p>
      <div class="data-table-wrap"><table class="data-table">
        <thead><tr><th>学号</th><th>姓名</th><th>登录名</th><th>临时密码</th></tr></thead>
        <tbody>
          <tr v-for="item in printableCredentials" :key="item.studentId">
            <td class="mono">{{ item.studentNo }}</td>
            <td>{{ item.name }}</td>
            <td class="mono">{{ item.username }}</td>
            <td class="mono credential-secret">{{ item.temporaryPassword }}</td>
          </tr>
        </tbody>
      </table></div>
      <template #footer>
        <button class="secondary-button" @click="downloadCredentials">下载 Excel</button>
        <button class="primary-button" @click="closeCredentials">我已保存</button>
      </template>
    </el-dialog>
  </section>
</template>
