<script setup lang="ts">
// 只注册本页真正用到的图表能力：整包引入 echarts 会把所有图表类型都打进这个分包。
// 下面这四项是按 setOption 里实际出现的配置挑的——option 里没有 title 也没有 tooltip，
// 所以不注册 TitleComponent 和 TooltipComponent；注册用不到的部分只会白白撑大分包。
import * as echarts from 'echarts/core'
import { BarChart } from 'echarts/charts'
import { GridComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'

echarts.use([BarChart, GridComponent, CanvasRenderer])

import { api, errorMessage, type ApiResponse } from '../api/client'
import { errorTypeLabel } from '../api/errorTypes'
import type { Assignment, Classroom, ErrorRanking, KnowledgeMastery } from '../api/types'

const classes = ref<Classroom[]>([])
const assignments = ref<Assignment[]>([])
const classId = ref<number>()
const assignmentId = ref<number>()
const mastery = ref<KnowledgeMastery[]>([])
const errors = ref<ErrorRanking[]>([])
const chartElement = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
async function loadData() {
  if (!classId.value || !assignmentId.value) return
  try {
    const [m, e] = await Promise.all([api.get<ApiResponse<KnowledgeMastery[]>>(`/analytics/classes/${classId.value}/mastery`, { params: { assignmentId: assignmentId.value } }), api.get<ApiResponse<ErrorRanking[]>>(`/analytics/assignments/${assignmentId.value}/errors`)])
    mastery.value = m.data.data; errors.value = e.data.data; await nextTick(); renderChart()
  } catch (reason) { ElMessage.error(errorMessage(reason)) }
}
function renderChart() {
  if (!chartElement.value) return
  chart ??= echarts.init(chartElement.value)
  chart.setOption({ grid: { left: 135, right: 32, top: 18, bottom: 28 }, xAxis: { type: 'value', max: 100, axisLabel: { formatter: '{value}%' }, splitLine: { lineStyle: { color: '#e5e9f2' } } }, yAxis: { type: 'category', data: mastery.value.map(item => item.name), axisTick: { show: false }, axisLine: { show: false } }, series: [{ type: 'bar', data: mastery.value.map(item => Math.round(item.masteryRatio * 100)), barWidth: 20, itemStyle: { color: (params: { value: number }) => params.value >= 80 ? '#1f9d8a' : params.value >= 60 ? '#2f5cff' : '#e65c4f', borderRadius: [0, 5, 5, 0] }, label: { show: true, position: 'right', formatter: '{c}%' } }] })
}
watch([classId, assignmentId], loadData)
onMounted(async () => { try { const [c, a] = await Promise.all([api.get<ApiResponse<Classroom[]>>('/classes'), api.get<ApiResponse<Assignment[]>>('/assignments')]); classes.value = c.data.data; assignments.value = a.data.data; classId.value = classes.value[0]?.id; assignmentId.value = assignments.value.find(item => item.classId === classId.value)?.id } catch (reason) { ElMessage.error(errorMessage(reason)) } })
onBeforeUnmount(() => chart?.dispose())
</script>

<template>
  <section class="page">
    <header class="page-heading"><div><p class="kicker">教学证据</p><h1>学情分析</h1><p>只使用教师已确认的正式结果，查看班级知识点掌握情况。</p></div><div class="filter-pair"><el-select v-model="classId" placeholder="选择班级"><el-option v-for="item in classes" :key="item.id" :label="item.name" :value="item.id" /></el-select><el-select v-model="assignmentId" placeholder="选择作业"><el-option v-for="item in assignments.filter(value => !classId || value.classId === classId)" :key="item.id" :label="item.title" :value="item.id" /></el-select></div></header>
    <div v-if="!assignmentId" class="state-panel empty-invite"><b>请选择班级和作业</b><p>有已确认的复核结果后，画像会自动出现。</p></div>
    <div v-else class="analytics-grid"><article class="paper-card mastery-chart-card"><div class="card-title"><div><span>掌握度 = 已确认得分 / 对应总分</span><h2>知识点掌握度</h2></div></div><div v-if="mastery.length === 0" class="empty-invite"><b>暂无已确认数据</b><p>请先完成教师复核。</p></div><div v-else ref="chartElement" class="mastery-chart"></div></article><article class="paper-card error-ranking"><div class="card-title"><div><span>确认后的错因</span><h2>高频问题</h2></div></div><div v-if="errors.length === 0" class="empty-invite compact"><p>暂无错误记录</p></div><div v-for="(item, index) in errors" :key="item.errorType" class="error-row"><i>{{ index + 1 }}</i><span><b>{{ errorTypeLabel(item.errorType) }}</b><small>{{ item.count }} 次</small></span><div><em :style="{ width: `${Math.min(100, item.count * 18)}%` }"></em></div></div></article></div>
  </section>
</template>
