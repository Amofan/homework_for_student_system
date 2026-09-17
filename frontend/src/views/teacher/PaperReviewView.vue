<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { api, errorCode, errorMessage, type ApiResponse } from '../../api/client'
import {
  confirmPaperImport, documentKindLabel, documentState, getPaperImport, paperWarningText,
  patchPaperRegion, type PaperCandidateDraft, type PaperRegionPatch,
} from '../../api/paperImport'
import type {
  KnowledgePoint, PaperCandidate, PaperDocumentKind, PaperImport, PaperRegion, RubricItem,
} from '../../api/types'
import PageThumbnailStrip from '../../components/document/PageThumbnailStrip.vue'
import PrivateImage from '../../components/document/PrivateImage.vue'
import RegionOverlay from '../../components/document/RegionOverlay.vue'
import { isLowConfidence, regionTypeLabel, type RegionRect } from '../../document/regions'
import MathText from '../../math/MathText.vue'

const route = useRoute()
const router = useRouter()

const assignmentId = Number(route.params.assignmentId)
const data = ref<PaperImport>()
const points = ref<KnowledgePoint[]>([])
const loading = ref(true)
const busy = ref(false)
const activeDocumentKind = ref<PaperDocumentKind>('EXAM_PAPER')
const activePageId = ref<number>()
const activeRegionId = ref<number>()
const activeCandidateId = ref<number>()
/** 移动端用选项卡代替三栏：把三栏硬挤到 400px 宽里，区域框会小到点不中。 */
const tab = ref<'candidate' | 'page'>('candidate')

const draft = reactive<{
  questionCode: string
  questionType?: PaperCandidate['questionType']
  content: string
  standardAnswer: string
  acceptedText: string
  rubricItems: RubricItem[]
  totalScore?: number
  difficulty?: PaperCandidate['difficulty']
  primaryKnowledgePointId?: number
  assetRegionIds: number[]
}>({
  questionCode: '', questionType: undefined, content: '', standardAnswer: '', acceptedText: '',
  rubricItems: [], totalScore: undefined, difficulty: undefined,
  primaryKnowledgePointId: undefined, assetRegionIds: [],
})

const candidatePool = computed(() => (data.value?.candidates ?? [])
  .filter(candidate => candidate.documentKind === activeDocumentKind.value))

const activeDocument = computed(() => data.value?.documents.find(
  document => document.documentKind === activeDocumentKind.value))

const pages = computed(() => activeDocument.value?.pages ?? [])

const currentPage = computed(() => pages.value.find(page => page.pageId === activePageId.value)
  ?? pages.value[0])

const activeCandidate = computed(() => candidatePool.value.find(
  candidate => candidate.id === activeCandidateId.value))

const activeRegion = computed(() => pages.value
  .flatMap(page => page.regions)
  .find(region => region.regionId === activeRegionId.value))

/** 未被任何候选认领的区域。识别漏检时它们就是"被并进上一题的那道题"。 */
const unassignedRegions = computed(() => (currentPage.value?.regions ?? [])
  .filter(region => !region.candidateId))

const flaggedPageIds = computed(() => pages.value
  .filter(page => page.regions.some(isLowConfidence))
  .map(page => page.pageId))

/** 答案卷里与当前候选同题号的答案，用于教师比对。 */
const referenceAnswer = computed(() => {
  const code = activeCandidate.value?.questionCode
  if (!code) return undefined
  return (data.value?.candidates ?? []).find(candidate =>
    candidate.documentKind === 'ANSWER_KEY' && candidate.questionCode === code)
})

const regionBelongsToActive = computed(() => activeRegion.value?.candidateId === activeCandidateId.value)

function candidateLabel(candidate: PaperCandidate): string {
  return `${candidate.orderNo}. ${candidate.questionCode ?? '未编号'}`
}

/** 表单与选中候选同步。每次服务端返回后都要重来一遍：服务端才是唯一真相。 */
function syncDraft(): void {
  const candidate = activeCandidate.value
  draft.questionCode = candidate?.questionCode ?? ''
  draft.questionType = candidate?.questionType
  draft.content = candidate?.content ?? ''
  draft.standardAnswer = candidate?.standardAnswer ?? ''
  draft.acceptedText = (candidate?.acceptedAnswers ?? []).join('，')
  draft.rubricItems = (candidate?.rubricItems ?? []).map(item => ({ ...item }))
  draft.totalScore = candidate?.totalScore
  draft.difficulty = candidate?.difficulty
  draft.primaryKnowledgePointId = candidate?.primaryKnowledgePointId
  draft.assetRegionIds = [...(candidate?.assetRegionIds ?? [])]
}

function selectCandidate(candidateId: number): void {
  activeCandidateId.value = candidateId
  syncDraft()
  tab.value = 'candidate'
}

function selectPage(pageId: number): void {
  activePageId.value = pageId
  tab.value = 'page'
}

function selectRegion(regionId: number): void {
  activeRegionId.value = regionId
  const owner = allRegions.value.find(region => region.regionId === regionId)?.candidateId
  if (owner && owner !== activeCandidateId.value) {
    activeCandidateId.value = owner
    syncDraft()
  }
}

/** 所有文档的所有区域。题图来源区域可能落在答案卷上，所以不能只看当前文档。 */
const allRegions = computed(() => (data.value?.documents ?? [])
  .flatMap(document => document.pages)
  .flatMap(page => page.regions))

function regionOf(regionId: number): PaperRegion | undefined {
  return allRegions.value.find(region => region.regionId === regionId)
}

/** 从来源区域列表点进某块区域：先切到它所在的那份文档与那一页，再选中它。 */
function focusRegion(regionId: number): void {
  for (const document of data.value?.documents ?? []) {
    const page = document.pages.find(item => item.regions.some(region => region.regionId === regionId))
    if (page) {
      activeDocumentKind.value = document.documentKind
      activePageId.value = page.pageId
      break
    }
  }
  selectRegion(regionId)
}

function candidatePayload(): PaperCandidateDraft {
  const solution = draft.questionType === 'SOLUTION'
  return {
    questionCode: draft.questionCode.trim(),
    questionType: draft.questionType,
    content: draft.content,
    standardAnswer: draft.standardAnswer,
    acceptedAnswers: solution ? [] : draft.acceptedText.split(/[，,\n]/).map(v => v.trim()).filter(Boolean),
    rubricItems: solution ? draft.rubricItems : [],
    totalScore: draft.totalScore,
    difficulty: draft.difficulty,
    primaryKnowledgePointId: draft.primaryKnowledgePointId,
    assetRegionIds: draft.assetRegionIds,
  }
}

/**
 * 提交一次编辑。
 *
 * 版本冲突不是"操作失败"而是"你手里的数据过期了"，所以除了提示还必须重新拉一份：
 * 只提示不刷新的话，教师点第二次还是会撞同一个冲突，因为本地版本号始终没变。
 */
async function patch(regionId: number, body: PaperRegionPatch, successMessage?: string): Promise<void> {
  if (!data.value) return
  busy.value = true
  try {
    data.value = await patchPaperRegion(data.value.assignmentId, regionId, body)
    syncDraft()
    if (successMessage) ElMessage.success(successMessage)
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
    if (errorCode(reason) === 'OCR_REVIEW_CONFLICT') await reload()
  } finally {
    busy.value = false
  }
}

/** 编辑区域的锚点：候选题的编辑都通过它的第一块来源区域提交（与接口形状一致）。 */
function anchorRegionId(candidate: PaperCandidate): number | undefined {
  return candidate.sourceRegionIds[0]
}

async function saveCandidate(): Promise<void> {
  const candidate = activeCandidate.value
  const anchor = candidate ? anchorRegionId(candidate) : undefined
  if (!candidate || anchor === undefined) return
  await patch(anchor, {
    candidateId: candidate.id, version: candidate.version, candidate: candidatePayload(),
  }, '已保存')
}

/** 把区域从原候选上摘下来，成为未归属区域。 */
async function detachRegion(): Promise<void> {
  const region = activeRegion.value
  if (!region) return
  await patch(region.regionId, { candidateId: null }, '已移出该题')
}

/** 把区域并入当前候选；它在别的候选里时会自动从那边摘掉。 */
async function mergeIntoActive(): Promise<void> {
  const region = activeRegion.value
  const candidate = activeCandidate.value
  if (!region || !candidate) return
  await patch(region.regionId, { candidateId: candidate.id, version: candidate.version }, '已并入当前题')
}

/** 把区域从原候选拆出来，单独组成一道新题。 */
async function splitRegion(): Promise<void> {
  const region = activeRegion.value
  const candidate = activeCandidate.value
  if (!region || !candidate) return
  await patch(region.regionId, {
    candidateId: candidate.id, version: candidate.version, split: true,
  }, '已拆分为一道新题，请填写题号与分值')
}

async function cropRegion(regionId: number): Promise<void> {
  const candidate = candidatePool.value.find(
    item => item.id === regionOf(regionId)?.candidateId) ?? activeCandidate.value
  if (!candidate) return
  await patch(regionId, {
    candidateId: candidate.id, version: candidate.version, createCrop: true,
  }, '题图已裁剪')
}

/** 拖动区域框后提交新几何。不带候选字段，避免把表单里未保存的改动一起写进去。 */
async function moveRegion(regionId: number, rect: RegionRect): Promise<void> {
  const owner = regionOf(regionId)?.candidateId
  const candidate = candidatePool.value.find(item => item.id === owner)
  await patch(regionId, {
    candidateId: candidate?.id ?? null,
    version: candidate?.version,
    ...rect,
  })
}

/** 切换文档时页码要一起重置：两份文档的 pageId 完全不同。 */
function switchDocument(kind: PaperDocumentKind): void {
  activeDocumentKind.value = kind
  activePageId.value = pages.value[0]?.pageId
  activeRegionId.value = undefined
  const first = candidatePool.value[0]
  activeCandidateId.value = first?.id
  syncDraft()
}

async function moveCandidate(delta: number): Promise<void> {
  const candidate = activeCandidate.value
  const anchor = candidate ? anchorRegionId(candidate) : undefined
  if (!candidate || anchor === undefined) return
  const target = candidate.orderNo + delta
  if (target < 1) return
  await patch(anchor, {
    candidateId: candidate.id, version: candidate.version,
    candidate: { orderNo: target },
  }, '顺序已调整')
}

function toggleAsset(regionId: number): void {
  draft.assetRegionIds = draft.assetRegionIds.includes(regionId)
    ? draft.assetRegionIds.filter(id => id !== regionId)
    : [...draft.assetRegionIds, regionId].sort((a, b) => a - b)
}

function addRubric(): void {
  draft.rubricItems = [...draft.rubricItems,
    { orderNo: draft.rubricItems.length + 1, title: '', criteria: '', maxScore: 1 }]
}

async function reload(): Promise<void> {
  try {
    data.value = await getPaperImport(assignmentId)
    if (!activePageId.value) activePageId.value = pages.value[0]?.pageId
    if (!activeCandidateId.value) {
      const first = candidatePool.value[0]
      if (first) activeCandidateId.value = first.id
    }
    syncDraft()
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}

async function confirm(): Promise<void> {
  if (!data.value) return
  busy.value = true
  try {
    const assignment = await confirmPaperImport(data.value.assignmentId)
    ElMessage.success(`已确认入库，共 ${assignment.questionIds.length} 道题`)
    router.push('/teacher/paper-imports')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
    if (errorCode(reason) === 'OCR_REVIEW_CONFLICT') await reload()
  } finally {
    busy.value = false
  }
}

onMounted(async () => {
  try {
    const [imported, pointList] = await Promise.all([
      getPaperImport(assignmentId),
      api.get<ApiResponse<KnowledgePoint[]>>('/knowledge-points'),
    ])
    data.value = imported
    points.value = pointList.data.data
    for (const document of imported.documents) {
      if (document.documentKind === 'ANSWER_KEY') continue
      activeDocumentKind.value = document.documentKind
      break
    }
    activePageId.value = pages.value[0]?.pageId
    const first = candidatePool.value[0]
    if (first) activeCandidateId.value = first.id
    syncDraft()
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="page">
    <header class="page-heading">
      <div>
        <p class="kicker">整卷校对</p>
        <h1>{{ data?.title || '整卷校对' }}</h1>
        <p>
          识别结果只是候选。逐题补齐题号、分值与知识点，全部就绪后再一次性确认入库。
        </p>
      </div>
      <button class="primary-button" :disabled="busy || !data || data.confirmed" @click="confirm">
        确认入库
      </button>
    </header>

    <div v-if="loading" class="state-panel">正在载入候选…</div>
    <template v-else-if="data">
      <div v-if="data.warnings.length > 0" class="warning-list">
        <b>需要留意</b>
        <ul>
          <li v-for="code in data.warnings" :key="code">{{ paperWarningText(code) }}</li>
        </ul>
      </div>

      <div class="document-switch">
        <button
          v-for="document in data.documents" :key="document.documentId"
          type="button" class="document-tab"
          :class="{ active: document.documentKind === activeDocumentKind }"
          @click="switchDocument(document.documentKind)"
        >
          {{ documentKindLabel[document.documentKind] }}
          <small>{{ documentState(document).label }}</small>
        </button>
      </div>

      <p v-if="data.confirmed" class="state-inline" role="status">
        这份整卷已经确认入库，候选不再可编辑。
      </p>

      <div class="review-tabs" role="tablist">
        <button
          type="button" role="tab" :aria-selected="tab === 'candidate'"
          :class="{ active: tab === 'candidate' }" @click="tab = 'candidate'"
        >候选题</button>
        <button
          type="button" role="tab" :aria-selected="tab === 'page'"
          :class="{ active: tab === 'page' }" @click="tab = 'page'"
        >页面与区域</button>
      </div>

      <div class="paper-workspace" :data-tab="tab">
        <aside class="paper-pane thumbnails">
          <PageThumbnailStrip
            :pages="pages" :active-page-id="currentPage?.pageId"
            :flagged-page-ids="flaggedPageIds" @select="selectPage"
          />
        </aside>

        <section class="paper-pane page-view">
          <div v-if="currentPage" class="page-canvas">
            <PrivateImage :file-id="currentPage.pageFileId" eager :alt="`第 ${currentPage.pageNo} 页`" />
            <RegionOverlay
              :regions="currentPage.regions" :active-region-id="activeRegionId"
              :page-no="currentPage.pageNo"
              @select="selectRegion" @update="moveRegion"
            />
          </div>
          <p v-else class="field-hint">这一页还没有可校对的图像。</p>

          <div v-if="unassignedRegions.length > 0" class="unassigned-block">
            <b>未归属的区域</b>
            <p class="field-hint">这些区域不属于任何一道题。可以把它们并入某道题，或拆成新题。</p>
            <ul>
              <li v-for="region in unassignedRegions" :key="region.regionId">
                <button type="button" class="link-button" @click="focusRegion(region.regionId)">
                  {{ regionTypeLabel(region.regionType) }}
                  <template v-if="region.ocrText">：{{ region.ocrText.slice(0, 18) }}</template>
                  <template v-if="isLowConfidence(region)">（低置信度）</template>
                </button>
              </li>
            </ul>
          </div>
        </section>

        <aside class="paper-pane candidate-pane">
          <ol v-if="candidatePool.length > 0" class="candidate-list">
            <li v-for="candidate in candidatePool" :key="candidate.id">
              <button
                type="button" :class="{ active: candidate.id === activeCandidateId }"
                @click="selectCandidate(candidate.id)"
              >
                <span>{{ candidateLabel(candidate) }}</span>
                <small>
                  {{ candidate.totalScore ? `${candidate.totalScore} 分` : '未填分值' }}
                  · 第 {{ candidate.version }} 版
                </small>
                <small v-if="candidate.warnings.length > 0" class="warn-text">
                  {{ candidate.warnings.map(paperWarningText).join('；') }}
                </small>
              </button>
            </li>
          </ol>
          <p v-else class="field-hint">这份文档还没有候选题目。</p>

          <template v-if="activeCandidate">
            <div class="candidate-form">
              <div class="form-grid three">
                <label>题号
                  <input v-model="draft.questionCode" class="text-input" placeholder="如 11">
                </label>
                <label>题型
                  <el-select v-model="draft.questionType" placeholder="选择题型">
                    <el-option value="SINGLE_CHOICE" label="单选题" />
                    <el-option value="FILL_BLANK" label="填空题" />
                    <el-option value="SOLUTION" label="解答题" />
                  </el-select>
                </label>
                <label>总分
                  <el-input-number v-model="draft.totalScore" :min="1" />
                </label>
              </div>

              <label>题干
                <el-input v-model="draft.content" type="textarea" :rows="3" />
              </label>
              <div v-if="draft.content" class="formula-preview">
                <span class="field-hint">预览</span>
                <MathText :text="draft.content" />
              </div>

              <label>标准答案
                <el-input v-model="draft.standardAnswer" type="textarea" :rows="2" />
              </label>
              <p v-if="referenceAnswer?.standardAnswer" class="field-hint">
                参考答案卷：{{ referenceAnswer.standardAnswer }}
              </p>

              <label v-if="draft.questionType !== 'SOLUTION'">可接受答案（逗号分隔）
                <input v-model="draft.acceptedText" class="text-input" placeholder="如 2，x=2">
              </label>

              <div class="form-grid three">
                <label>难度
                  <el-select v-model="draft.difficulty" placeholder="选择难度">
                    <el-option value="BASIC" label="基础" />
                    <el-option value="MEDIUM" label="中等" />
                    <el-option value="ADVANCED" label="综合" />
                  </el-select>
                </label>
                <label>知识点
                  <el-select v-model="draft.primaryKnowledgePointId" filterable placeholder="选择知识点">
                    <el-option
                      v-for="point in points.filter(item => item.active)"
                      :key="point.id" :label="point.name" :value="point.id"
                    />
                  </el-select>
                </label>
                <label>顺序
                  <div class="order-buttons">
                    <button type="button" class="secondary-button" :disabled="busy" @click="moveCandidate(-1)">上移</button>
                    <button type="button" class="secondary-button" :disabled="busy" @click="moveCandidate(1)">下移</button>
                  </div>
                </label>
              </div>

              <div v-if="draft.questionType === 'SOLUTION'" class="rubric-editor">
                <div class="rubric-title">
                  <b>过程评分项</b>
                  <button type="button" @click="addRubric">+ 添加步骤</button>
                </div>
                <div v-for="(rubric, index) in draft.rubricItems" :key="index" class="rubric-row">
                  <span>{{ index + 1 }}</span>
                  <input v-model="rubric.title" class="text-input" placeholder="步骤名称">
                  <input v-model="rubric.criteria" class="text-input" placeholder="得分标准">
                  <el-input-number v-model="rubric.maxScore" :min="1" />
                </div>
                <small>各评分项分值之和必须等于题目总分。</small>
              </div>

              <div class="source-regions">
                <b>来源区域</b>
                <p class="field-hint">勾选"题图"的区域会在确认时裁剪成题目配图。</p>
                <ul>
                  <li v-for="regionId in activeCandidate.sourceRegionIds" :key="regionId">
                    <label class="asset-toggle">
                      <input
                        type="checkbox" :checked="draft.assetRegionIds.includes(regionId)"
                        @change="toggleAsset(regionId)"
                      >
                      题图
                    </label>
                    <button type="button" class="link-button" @click="focusRegion(regionId)">
                      {{ regionTypeLabel(regionOf(regionId)?.regionType ?? '') }}
                    </button>
                    <span v-if="regionOf(regionId)?.cropFileId" class="asset-badge">已裁剪</span>
                    <span v-else class="field-hint">未裁剪</span>
                    <button type="button" class="secondary-button" :disabled="busy" @click="cropRegion(regionId)">
                      裁剪
                    </button>
                  </li>
                </ul>
              </div>

              <div class="candidate-actions">
                <button
                  type="button" class="secondary-button"
                  :disabled="busy || !activeRegion" @click="detachRegion"
                >把选中区域移出</button>
                <button
                  type="button" class="secondary-button"
                  :disabled="busy || !activeRegion || regionBelongsToActive" @click="mergeIntoActive"
                >把选中区域并入本题</button>
                <button
                  type="button" class="secondary-button"
                  :disabled="busy || !activeRegion || !regionBelongsToActive" @click="splitRegion"
                >拆分为新题</button>
                <button type="button" class="primary-button" :disabled="busy" @click="saveCandidate">保存</button>
              </div>
            </div>
          </template>
        </aside>
      </div>
    </template>
  </section>
</template>
