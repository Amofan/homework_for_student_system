<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { api, errorMessage, type ApiResponse } from '../api/client'
import type { KnowledgePoint, Question } from '../api/types'

const questions = ref<Question[]>([])
const points = ref<KnowledgePoint[]>([])
const loading = ref(true)
const dialog = ref(false)
const filter = ref('')
const form = reactive({ questionCode: '', type: 'FILL_BLANK' as Question['type'], content: '', standardAnswer: '', totalScore: 5, primaryKnowledgePointId: undefined as number | undefined, acceptedText: '', rubricItems: [{ orderNo: 1, title: '关键步骤', criteria: '过程正确', maxScore: 5 }] })
const shown = computed(() => questions.value.filter(item => !filter.value || `${item.questionCode}${item.content}`.toLowerCase().includes(filter.value.toLowerCase())))
const typeLabel = (type: Question['type']) => ({ SINGLE_CHOICE: '单选题', FILL_BLANK: '填空题', SOLUTION: '解答题' }[type])
async function load() { const [q, p] = await Promise.all([api.get<ApiResponse<Question[]>>('/questions'), api.get<ApiResponse<KnowledgePoint[]>>('/knowledge-points')]); questions.value = q.data.data; points.value = p.data.data }
function addRubric() { form.rubricItems.push({ orderNo: form.rubricItems.length + 1, title: '', criteria: '', maxScore: 1 }) }
async function create() {
  if (!form.primaryKnowledgePointId) { ElMessage.warning('请选择主知识点'); return }
  const payload = { ...form, secondaryKnowledgePointIds: [], acceptedAnswers: form.type === 'SOLUTION' ? [] : form.acceptedText.split(/[，,\n]/).map(v => v.trim()).filter(Boolean), rubricItems: form.type === 'SOLUTION' ? form.rubricItems : [] }
  try { await api.post('/questions', payload); dialog.value = false; await load(); ElMessage.success('题目已加入题库') } catch (reason) { ElMessage.error(errorMessage(reason)) }
}
onMounted(async () => { try { await load() } catch (reason) { ElMessage.error(errorMessage(reason)) } finally { loading.value = false } })
</script>

<template>
  <section class="page">
    <header class="page-heading"><div><p class="kicker">题目资产</p><h1>数学题库</h1><p>客观题配置可接受答案，解答题拆成可核对的过程评分项。</p></div><button class="primary-button" @click="dialog = true">录入题目</button></header>
    <div class="toolbar paper-card"><el-input v-model="filter" clearable placeholder="按题号或题干检索" /><span>{{ shown.length }} 道题</span></div>
    <div v-if="loading" class="state-panel">正在载入题库…</div>
    <div v-else-if="shown.length === 0" class="state-panel empty-invite"><b>题库暂无内容</b><p>录入第一道题，并绑定主知识点。</p></div>
    <div v-else class="question-list"><article v-for="item in shown" :key="item.id" class="paper-card question-card"><div class="question-meta"><span class="mono">{{ item.questionCode }}</span><span class="type-pill">{{ typeLabel(item.type) }}</span><b>{{ item.totalScore }} 分</b></div><p class="formula-text">{{ item.content }}</p><div class="question-foot"><span>主知识点：{{ points.find(point => point.id === item.primaryKnowledgePointId)?.name || item.primaryKnowledgePointId }}</span><span v-if="item.type === 'SOLUTION'">{{ item.rubricItems.length }} 个评分步骤</span><span v-else>{{ item.acceptedAnswers.length }} 个可接受答案</span></div></article></div>
    <el-dialog v-model="dialog" title="录入数学题目" width="720px"><el-form label-position="top"><div class="form-grid three"><el-form-item label="题目编码"><el-input v-model="form.questionCode" /></el-form-item><el-form-item label="题型"><el-select v-model="form.type"><el-option value="SINGLE_CHOICE" label="单选题" /><el-option value="FILL_BLANK" label="填空题" /><el-option value="SOLUTION" label="解答题" /></el-select></el-form-item><el-form-item label="总分"><el-input-number v-model="form.totalScore" :min="1" /></el-form-item></div><el-form-item label="题干（支持 LaTeX 文本）"><el-input v-model="form.content" type="textarea" :rows="3" /></el-form-item><el-form-item label="主知识点"><el-select v-model="form.primaryKnowledgePointId" filterable><el-option v-for="item in points.filter(point => point.active)" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item><el-form-item label="标准答案"><el-input v-model="form.standardAnswer" type="textarea" :rows="2" /></el-form-item><el-form-item v-if="form.type !== 'SOLUTION'" label="可接受答案（逗号分隔）"><el-input v-model="form.acceptedText" placeholder="如 2, x=2" /></el-form-item><div v-else class="rubric-editor"><div class="rubric-title"><b>过程评分项</b><button type="button" @click="addRubric">+ 添加步骤</button></div><div v-for="rubric in form.rubricItems" :key="rubric.orderNo" class="rubric-row"><span>{{ rubric.orderNo }}</span><el-input v-model="rubric.title" placeholder="步骤名称" /><el-input v-model="rubric.criteria" placeholder="得分标准" /><el-input-number v-model="rubric.maxScore" :min="1" /></div><small>各评分项分值之和必须等于题目总分。</small></div></el-form><template #footer><button class="secondary-button" @click="dialog = false">取消</button><button class="primary-button" @click="create">保存题目</button></template></el-dialog>
  </section>
</template>
