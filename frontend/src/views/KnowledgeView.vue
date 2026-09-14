<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'

import { api, errorMessage, type ApiResponse } from '../api/client'
import type { KnowledgePoint } from '../api/types'

const points = ref<KnowledgePoint[]>([])
const loading = ref(true)
const dialog = ref(false)
const form = reactive<{ parentId?: number; code: string; name: string; grade: number; active: boolean }>({ code: '', name: '', grade: 7, active: true })
const byGrade = computed(() => [7, 8, 9].map(grade => ({ grade, items: points.value.filter(item => item.grade === grade) })))
async function load() { const response = await api.get<ApiResponse<KnowledgePoint[]>>('/knowledge-points'); points.value = response.data.data }
async function create() {
  try { await api.post('/knowledge-points', form); dialog.value = false; Object.assign(form, { parentId: undefined, code: '', name: '', grade: 7, active: true }); await load(); ElMessage.success('知识点已添加') }
  catch (reason) { ElMessage.error(errorMessage(reason)) }
}
onMounted(async () => { try { await load() } catch (reason) { ElMessage.error(errorMessage(reason)) } finally { loading.value = false } })
</script>

<template>
  <section class="page">
    <header class="page-heading"><div><p class="kicker">课程结构</p><h1>知识点体系</h1><p>用教材章节与知识点给每一道题建立明确坐标。</p></div><button class="primary-button" @click="dialog = true">添加知识点</button></header>
    <div v-if="loading" class="state-panel">正在载入知识点…</div>
    <div v-else class="knowledge-board">
      <article v-for="group in byGrade" :key="group.grade" class="paper-card grade-column"><div class="grade-heading"><span>GRADE {{ group.grade }}</span><h2>{{ ['七', '八', '九'][group.grade - 7] }}年级</h2><b>{{ group.items.length }}</b></div><div v-if="!group.items.length" class="empty-invite compact"><p>暂无知识点</p></div><div v-for="item in group.items" :key="item.id" class="knowledge-item"><span class="mono">{{ item.code }}</span><b>{{ item.name }}</b><i :class="{ off: !item.active }">{{ item.active ? '启用' : '停用' }}</i></div></article>
    </div>
    <el-dialog v-model="dialog" title="添加知识点" width="500px"><el-form label-position="top"><div class="form-grid"><el-form-item label="编码"><el-input v-model="form.code" placeholder="ALG-EQ" /></el-form-item><el-form-item label="年级"><el-select v-model="form.grade"><el-option :value="7" label="七年级" /><el-option :value="8" label="八年级" /><el-option :value="9" label="九年级" /></el-select></el-form-item></div><el-form-item label="名称"><el-input v-model="form.name" placeholder="一元一次方程" /></el-form-item><el-form-item label="上级知识点（可选）"><el-select v-model="form.parentId" clearable><el-option v-for="item in points" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item></el-form><template #footer><button class="secondary-button" @click="dialog = false">取消</button><button class="primary-button" @click="create">保存知识点</button></template></el-dialog>
  </section>
</template>
