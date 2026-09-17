<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'

import { errorCode, errorMessage } from '../../api/client'
import { submissionVersionStatusTone } from '../../api/studentSubmission'
import {
  alignmentState, confirmAnswers, getAnswerReview, getReferenceAnswers, getSubmissionQueue,
  listReviewAssignments, patchAnswerCandidate, processAnswers, teacherSubmissionStatusLabel,
  answerWarningText,
} from '../../api/teacherAnswerReview'
import type {
  AnswerCandidate, AnswerCorrection, AnswerRegion, AnswerReview, Assignment, Question,
  SubmissionQueue, SubmissionQueueItem,
} from '../../api/types'
import PageThumbnailStrip from '../../components/document/PageThumbnailStrip.vue'
import PrivateImage from '../../components/document/PrivateImage.vue'
import RegionOverlay from '../../components/document/RegionOverlay.vue'
import type { DrawableRegion } from '../../document/regions'
import { isLowConfidence, regionTypeLabel } from '../../document/regions'
import MathText from '../../math/MathText.vue'

/**
 * 教师答卷校对工作台。
 *
 * <p>一个页面同时是三件事：选作业（左上）、挑一份答卷（左侧队列）、校对这道题（右侧）。
 * 合成一个页面而不是做成三个路由，是因为教师的动作是一条不中断的流水线 ——
 * 选作业、点开第一份、逐题校对、确认入库、点开下一份，任何一步跳走再回来都要重新定位。
 *
 * <p>三种状态由查询参数承载（`?assignmentId=&versionId=`），两者都可缺省，
 * 所以这个页面永远可打开、可收藏、可分享给同事："这份答卷的第 3 题帮我看一眼"。
 */

const route = useRoute()
const router = useRouter()

const assignments = ref<Assignment[]>([])
const queue = ref<SubmissionQueue>()
const review = ref<AnswerReview>()
const loading = ref(true)
const busy = ref(false)
const activePageId = ref<number>()
const activeRegionId = ref<number>()
const activeCandidateId = ref<number>()
/** 标准答案默认不显示：对着标准答案看学生写的字，会不自觉地"看出"那个答案。 */
const referenceOpen = ref(false)
const references = ref<Question[]>([])
/** 移动端用选项卡代替三栏：三栏硬挤进 400px，区域框会小到点不中。 */
const tab = ref<'candidate' | 'page'>('candidate')

/** 表单。服务端返回后一律重灌一遍：服务端才是唯一真相。 */
const draft = reactive<{ answerText: string; answerLatex: string; blank: boolean }>({
  answerText: '', answerLatex: '', blank: false,
})

const assignmentId = computed(() => {
  const value = Number(route.query.assignmentId)
  return Number.isFinite(value) && value > 0 ? value : undefined
})

const versionId = computed(() => {
  const value = Number(route.query.versionId)
  return Number.isFinite(value) && value > 0 ? value : undefined
})

const pages = computed(() => review.value?.pages ?? [])

const currentPage = computed(() => pages.value.find(page => page.submissionPageId === activePageId.value)
  ?? pages.value[0])

const candidates = computed(() => review.value?.candidates ?? [])

const activeCandidate = computed(() => candidates.value.find(
  candidate => candidate.candidateId === activeCandidateId.value))

/** 还没被任何候选认领的区域。识别把作答分错题时，它们就是"被漏掉的那道题"。 */
const unassignedRegions = computed(() => (currentPage.value?.regions ?? [])
  .filter(region => !region.candidateId))

const pendingCount = computed(() => candidates.value.filter(
  candidate => candidate.reviewStatus !== 'CONFIRMED').length)

/** 已经有题号但还没有候选的题：物化时漏掉的，确认时会被服务端拦下。 */
const confirmedCount = computed(() => candidates.value.length - pendingCount.value)

/**
 * 改派的候选目标。
 *
 * <p>排除自己：把自己换给自己是一次没有意义的写，还会白白推高版本号。
 * 做成 computed 而不是在模板里 `candidates.filter(...)`：模板里的 filter 每次重渲染都算一遍。
 */
const reassignTargets = computed(() => candidates.value.filter(
  candidate => candidate.candidateId !== activeCandidateId.value))

function candidateLabel(candidate: AnswerCandidate): string {
  return `${candidate.orderNo ?? candidate.questionOrder}. ${candidate.questionCode ?? '未编号'}`
}

/**
 * 说"哪道题"时用的名字。
 *
 * <p>与列表里那个 `candidateLabel` 不同：列表要同时给出顺序与题号（教师靠顺序找位置），
 * 而"换成第几题""与第几题互换"是教师嘴里的说法，他只说题号。
 * 题号是识别没认出来的东西之一，所以还得能回落到顺序。
 */
function questionLabel(candidate: AnswerCandidate): string {
  return candidate.questionCode ?? String(candidate.orderNo ?? candidate.questionOrder)
}

function queueLabel(item: SubmissionQueueItem): string {
  return `${item.studentNo} ${item.studentName}`
}

function statusLabel(status: string): string {
  return teacherSubmissionStatusLabel(status)
}

/** 区域框上的一句话。读屏与悬停都用它：这里的 `candidateId` 是候选 id，不是题号。 */
function describeRegion(region: DrawableRegion): string {
  const owner = candidates.value.find(candidate => candidate.candidateId === region.candidateId)
  const parts = [`第 ${currentPage.value?.pageNo ?? 1} 页的${regionTypeLabel(region.regionType)}区域`]
  if (isLowConfidence(region)) parts.push('低置信度')
  parts.push(owner ? `属于第 ${candidateLabel(owner)} 题` : '尚未归属任何题目')
  return parts.join('，')
}

function regionOf(regionId: number): AnswerRegion | undefined {
  return pages.value.flatMap(page => page.regions).find(region => region.regionId === regionId)
}

/**
 * 出现这些码说明**手里这份数据已经过期**，光提示不重拉的话教师再点一次还是同一个错。
 *
 * 与 `PaperReviewView` 只认 `OCR_REVIEW_CONFLICT` 不同，这里多认三个：
 * 候选被重跑识别时整批换过（`ANSWER_CANDIDATE_NOT_FOUND`）、
 * 别的教师/另一个标签页已经把它确认入库（`SUBMISSION_ANSWERS_CONFIRMED`）、
 * 以及漏传版本号（客户端 bug，重拉一次至少让界面回到一致状态）。
 */
const STALE_CODES = new Set([
  'OCR_REVIEW_CONFLICT', 'ANSWER_CANDIDATE_NOT_FOUND', 'SUBMISSION_ANSWERS_CONFIRMED',
  'ANSWER_CANDIDATE_VERSION_REQUIRED',
])

/** 确认被拦下时，这几类错的修法就是"去看那道还没校对的题"，所以顺手跳过去。 */
const POINTS_AT_CANDIDATE = new Set([
  'ANSWER_REVIEW_PENDING', 'ANSWER_CANDIDATE_INCOMPLETE', 'ANSWER_CONTENT_EMPTY',
])

function syncDraft(): void {
  const candidate = activeCandidate.value
  draft.answerText = candidate?.answerText ?? ''
  draft.answerLatex = candidate?.answerLatex ?? ''
  draft.blank = candidate?.blank ?? false
}

function selectCandidate(candidateId: number): void {
  activeCandidateId.value = candidateId
  syncDraft()
  tab.value = 'candidate'
}

function selectRegion(regionId: number): void {
  activeRegionId.value = regionId
  // 点区域框等于"我要看这道题"：区域自己不是编辑对象，归属它的候选才是。
  const owner = regionOf(regionId)?.candidateId
  if (owner && owner !== activeCandidateId.value) {
    activeCandidateId.value = owner
    syncDraft()
  }
}

/** 从"未归属区域"点进来：先翻到它所在的那一页，再选中它。 */
function focusRegion(regionId: number): void {
  const page = pages.value.find(item => item.regions.some(region => region.regionId === regionId))
  if (page) activePageId.value = page.submissionPageId
  selectRegion(regionId)
  tab.value = 'page'
}

/** 跳到第一条还没校对的候选。确认被拦下时用它，教师不用自己一条条找。 */
function focusFirstPending(): void {
  const pending = candidates.value.find(candidate => candidate.reviewStatus !== 'CONFIRMED')
  if (pending) selectCandidate(pending.candidateId)
}

async function patch(candidateId: number, body: AnswerCorrection,
                     successMessage?: string): Promise<void> {
  const current = review.value
  if (!current) return
  busy.value = true
  try {
    review.value = await patchAnswerCandidate(current.versionId, candidateId, body)
    syncDraft()
    if (successMessage) ElMessage.success(successMessage)
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
    if (STALE_CODES.has(errorCode(reason) ?? '')) await reloadReview()
  } finally {
    busy.value = false
  }
}

/** 表单里那三项 + 版本号。每一条写请求都要带版本号，服务端靠它做乐观锁。 */
function draftPatch(): AnswerCorrection | undefined {
  const candidate = activeCandidate.value
  if (!candidate) return undefined
  return {
    version: candidate.version,
    answerText: draft.answerText,
    answerLatex: draft.answerLatex,
    blank: draft.blank,
  }
}

async function save(): Promise<void> {
  const candidate = activeCandidate.value
  const body = draftPatch()
  if (!candidate || !body) return
  await patch(candidate.candidateId, body, '已保存')
}

/** 确认这一条：文字与"认过了"一起提交，两次请求之间断网不会留下一半的改动。 */
async function confirmOne(): Promise<void> {
  const candidate = activeCandidate.value
  const body = draftPatch()
  if (!candidate || !body) return
  await patch(candidate.candidateId, { ...body, reviewStatus: 'CONFIRMED' }, '这道题已校对完成')
}

/**
 * 标记空白并确认。
 *
 * <p>"学生没作答"是教师的判断，不是系统的推断（识别不出文字也可能是拍糊了），
 * 所以它是一个显式动作，而且顺手把识别残文清掉——留着一段残文却又说"空白"，
 * 入库的就是自相矛盾的那一份。
 */
async function markBlankAndConfirm(): Promise<void> {
  const candidate = activeCandidate.value
  if (!candidate) return
  await patch(candidate.candidateId, {
    version: candidate.version, blank: true, answerText: '', answerLatex: '',
    reviewStatus: 'CONFIRMED',
  }, '已标为空白并确认')
}

/**
 * 把这条候选改派到另选的题上。
 *
 * <p>每道题恒有且只有一条候选，所以这是**互换**：那道题原来的候选换到本题来。
 * 文案必须说成互换而不是移动，否则教师会以为对面那道题现在没有候选了。
 */
async function reassign(target: AnswerCandidate): Promise<void> {
  const candidate = activeCandidate.value
  if (!candidate) return
  await patch(candidate.candidateId, { version: candidate.version, questionId: target.questionId },
    `已与第 ${questionLabel(target)} 题互换题目映射，两道题都要重新校对`)
}

async function recrop(): Promise<void> {
  const candidate = activeCandidate.value
  if (!candidate) return
  await patch(candidate.candidateId, { version: candidate.version, crop: true }, '答案图已重新裁剪')
}

async function recognise(): Promise<void> {
  const current = review.value
  if (!current) return
  busy.value = true
  try {
    review.value = await processAnswers(current.versionId)
    syncDraft()
    ElMessage.success('识别完成，请逐题校对')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  } finally {
    busy.value = false
  }
}

async function confirmAll(): Promise<void> {
  const current = review.value
  if (!current) return
  busy.value = true
  try {
    review.value = await confirmAnswers(current.versionId)
    await loadQueue()
    ElMessage.success('答案已入库，这一版可以开始批改了')
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
    const code = errorCode(reason) ?? ''
    if (STALE_CODES.has(code)) await reloadReview()
    if (POINTS_AT_CANDIDATE.has(code)) focusFirstPending()
  } finally {
    busy.value = false
  }
}

/**
 * 打开标准答案参考面板。
 *
 * <p>只在教师主动点开时拉一次并缓存：它是一份保留的摩擦，
 * 不是页面首屏就该铺在眼前的背景信息。
 */
async function toggleReference(): Promise<void> {
  const current = review.value
  if (referenceOpen.value) {
    referenceOpen.value = false
    return
  }
  referenceOpen.value = true
  if (references.value.length > 0 || !current) return
  try {
    references.value = await getReferenceAnswers(current.versionId)
  } catch (reason) {
    referenceOpen.value = false
    ElMessage.error(errorMessage(reason))
  }
}

/** 当前这条候选对应题目的标准答案。没有就是这道题还没录标准答案。 */
const referenceOf = computed(() => {
  const candidate = activeCandidate.value
  if (!candidate) return undefined
  return references.value.find(question => question.id === candidate.questionId)
})

async function loadAssignments(): Promise<void> {
  try {
    assignments.value = await listReviewAssignments()
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}

async function loadQueue(): Promise<void> {
  queue.value = undefined
  if (assignmentId.value === undefined) return
  try {
    queue.value = await getSubmissionQueue(assignmentId.value)
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
  }
}

async function reloadReview(): Promise<void> {
  if (versionId.value === undefined) {
    review.value = undefined
    return
  }
  try {
    review.value = await getAnswerReview(versionId.value)
  } catch (reason) {
    ElMessage.error(errorMessage(reason))
    return
  }
  if (!pages.value.some(page => page.submissionPageId === activePageId.value)) {
    activePageId.value = pages.value[0]?.submissionPageId
  }
  if (!candidates.value.some(candidate => candidate.candidateId === activeCandidateId.value)) {
    activeCandidateId.value = candidates.value[0]?.candidateId
  }
  syncDraft()
}

/**
 * 选择作业与答卷都写进查询参数。
 *
 * <p>用 `replace` 而不是 `push`：教师连看十份答卷之后按一次后退，
 * 期望的是回到进来之前的那个页面，而不是把刚才十次点击倒着走一遍。
 */
function openAssignment(id: number): void {
  activePageId.value = undefined
  activeCandidateId.value = undefined
  activeRegionId.value = undefined
  router.replace({ query: { assignmentId: String(id) } })
}

function openVersion(id: number): void {
  activePageId.value = undefined
  activeCandidateId.value = undefined
  activeRegionId.value = undefined
  router.replace({ query: { ...route.query, versionId: String(id) } })
}

/** 缩略图条用的是它自己那套最简字段（见 `document/pages.ts`），在这里映射一次。 */
const thumbnailPages = computed(() => pages.value.map(page => ({
  pageId: page.submissionPageId,
  pageNo: page.pageNo,
  pageFileId: page.pageFileId,
  regions: page.regions,
})))

/** 有低置信度区域的页：缩略图上要能一眼看出"哪几页要仔细看"。 */
const flaggedPageIds = computed(() => pages.value
  .filter(page => page.regions.some(isLowConfidence))
  .map(page => page.submissionPageId))

function selectPage(pageId: number): void {
  activePageId.value = pageId
  tab.value = 'page'
}

/**
 * 这一页是不是被学生转过角度。
 *
 * <p>区域框的坐标与答案图都是从**原始页面图**（`pageFileId`）来的 —— 识别跑在它上面。
 * 所以这里显示的也是原图，否则框会整体偏移。学生转过角度这件事必须说出来，
 * 不然教师会觉得"这些框怎么全都歪了"，然后开始手工挪框。
 *
 * <p>判据是服务端给的：没转过时 `rotatedFileId` 就等于 `pageFileId`
 * （见 `SubmissionVersionService`），转过才会存一张新的。
 */
function pageRotated(page: { pageFileId: number; rotatedFileId: number }): boolean {
  return page.rotatedFileId !== page.pageFileId
}

watch(assignmentId, () => { void loadQueue() })
watch(versionId, () => {
  references.value = []
  referenceOpen.value = false
  void reloadReview()
})

onMounted(async () => {
  await loadAssignments()
  await Promise.all([loadQueue(), reloadReview()])
  loading.value = false
})
</script>

<template>
  <section class="page">
    <header class="page-heading">
      <div>
        <p class="kicker">答卷校对</p>
        <h1>{{ queue?.title || '答卷校对' }}</h1>
        <p>
          识别结果只是候选。逐题核对作答文字与题目归属，全部认过之后再一次性入库。
        </p>
      </div>
      <button
        class="primary-button"
        :disabled="busy || !review || review.confirmed || candidates.length === 0"
        @click="confirmAll"
      >
        {{ review?.confirmed ? '答案已入库' : '确认入库' }}
      </button>
    </header>

    <div v-if="loading" class="state-panel">正在载入…</div>

    <template v-else>
      <div class="queue-picker">
        <label>作业
          <el-select
            :model-value="assignmentId" placeholder="选择一份作业" class="assignment-select"
            @update:model-value="openAssignment"
          >
            <el-option v-for="item in assignments" :key="item.id" :label="item.title" :value="item.id" />
          </el-select>
        </label>
        <span v-if="queue" class="field-hint">共 {{ queue.items.length }} 份提交</span>
      </div>

      <div v-if="!assignmentId" class="state-panel">
        先选一份作业，再挑一份答卷开始校对。
      </div>
      <template v-else-if="queue">
        <div v-if="queue.items.length === 0" class="empty-invite">
          <b>还没有学生提交</b>
          <p>这份作业的答卷要等学生从手机端交上来。</p>
        </div>
        <ul v-else class="submission-queue">
          <li v-for="item in queue.items" :key="item.versionId">
            <button
              type="button" :class="{ active: item.versionId === versionId }"
              @click="openVersion(item.versionId)"
            >
              <b>{{ queueLabel(item) }}</b>
              <small>{{ item.versionNo }} 版 · {{ statusLabel(item.status) }}</small>
              <small v-if="item.candidateCount > 0">
                还有 {{ item.pendingCount }}/{{ item.candidateCount }} 道题没校对
              </small>
              <small v-else>{{ item.pageCount }} 页，还没识别</small>
              <small v-if="!item.current" class="warn-text">已被后来的提交取代</small>
            </button>
          </li>
        </ul>
      </template>

      <div v-if="versionId === undefined" class="state-panel">
        选一份答卷，右边就会列出每道题的作答。
      </div>
      <div v-else-if="!review" class="state-panel">正在载入这份答卷…</div>

      <template v-else>
        <!-- 整份答卷的问题（哪一页没交、哪两页对上了同一模板页）与"这道题要人看一眼"是两层。 -->
        <div v-if="review.warnings.length > 0" class="warning-list">
          <b>整份答卷需要留意</b>
          <ul>
            <li v-for="warning in review.warnings" :key="warning.code">
              {{ warning.detail || answerWarningText(warning.code) }}
            </li>
          </ul>
        </div>

        <p v-if="review.confirmed" class="state-inline" role="status">
          答案已经入库，候选从这里起只读。学生重交之后再来确认，会换成新一版。
        </p>

        <div v-if="!review.confirmed && candidates.length === 0" class="state-inline warn" role="status">
          这一版还没有答案候选：先跑识别，才会按作业的题目逐题建出候选。
          <button class="secondary-button" :disabled="busy" @click="recognise">开始识别</button>
        </div>

        <div class="review-tabs" role="tablist">
          <button
            type="button" role="tab" :aria-selected="tab === 'candidate'"
            :class="{ active: tab === 'candidate' }" @click="tab = 'candidate'"
          >逐题校对</button>
          <button
            type="button" role="tab" :aria-selected="tab === 'page'"
            :class="{ active: tab === 'page' }" @click="tab = 'page'"
          >学生交的页面</button>
        </div>

        <div class="paper-workspace" :data-tab="tab">
          <aside class="paper-pane thumbnails">
            <PageThumbnailStrip
              :pages="thumbnailPages" :active-page-id="currentPage?.submissionPageId"
              :flagged-page-ids="flaggedPageIds" @select="selectPage"
            />
          </aside>

          <section class="paper-pane page-view">
            <div v-if="currentPage" class="page-canvas">
              <!-- 显示原图而不是学生转正的那一张：区域框与答案图都来自原图，换一张框就整体偏移。 -->
              <PrivateImage
                :file-id="currentPage.pageFileId" eager
                :alt="`第 ${currentPage.pageNo} 页`"
              />
              <RegionOverlay
                :regions="currentPage.regions" :active-region-id="activeRegionId"
                :page-no="currentPage.pageNo" readonly :describe-region="describeRegion"
                @select="selectRegion"
              />
            </div>
            <p v-else class="field-hint">这一版没有可看的页面。</p>

            <p v-if="currentPage" class="field-hint">
              {{ alignmentState(currentPage).label }}
              <template v-if="pageRotated(currentPage)">
                · 学生把这页转过角度，框是按原图标出来的，方向可能和大家看的不一样
              </template>
            </p>

            <div v-if="unassignedRegions.length > 0" class="unassigned-block">
              <b>没归到任何题的区域</b>
              <p class="field-hint">
                识别出来了却没算进任何一道题。点一下看它写了什么，再决定归给哪道题。
              </p>
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
            <p class="field-hint">
              已校对 {{ confirmedCount }}/{{ candidates.length }} 道题。
              <template v-if="!review.confirmed">全部认过之后才能入库。</template>
            </p>

            <ol v-if="candidates.length > 0" class="candidate-list">
              <li v-for="candidate in candidates" :key="candidate.candidateId">
                <button
                  type="button" :class="{ active: candidate.candidateId === activeCandidateId }"
                  @click="selectCandidate(candidate.candidateId)"
                >
                  <span>
                    {{ candidateLabel(candidate) }}
                    <template v-if="candidate.reviewStatus === 'CONFIRMED'">✓ 已校对</template>
                  </span>
                  <small>
                    {{ candidate.blank ? '学生没作答' : (candidate.answerText || candidate.answerLatex || '没有识别出内容') }}
                  </small>
                  <small v-if="candidate.warnings.length > 0" class="warn-text">
                    {{ candidate.warnings.map(answerWarningText).join('；') }}
                  </small>
                </button>
              </li>
            </ol>
            <p v-else class="field-hint">还没有候选。先跑识别。</p>

            <template v-if="activeCandidate">
              <div class="candidate-form">
                <div class="form-grid three">
                  <span class="field-hint">题号 {{ activeCandidate.questionCode ?? '未编号' }}</span>
                  <span class="field-hint">第 {{ activeCandidate.version }} 版</span>
                  <span v-if="activeCandidate.confidence !== undefined" class="field-hint">
                    置信度 {{ Math.round(activeCandidate.confidence * 100) }}%
                  </span>
                </div>

                <!-- 答案图是学生笔迹的唯一凭证：文字可以被教师改，图不能。 -->
                <div v-if="activeCandidate.regions.length > 0" class="answer-crops">
                  <b>学生写的</b>
                  <div v-for="region in activeCandidate.regions" :key="region.regionId" class="answer-crop">
                    <PrivateImage
                      v-if="region.cropFileId" :file-id="region.cropFileId"
                      :alt="`第 ${candidateLabel(activeCandidate)} 题的作答截图`"
                    />
                    <p v-else class="field-hint">
                      第 {{ region.pageNo }} 页这块区域没能自动裁出图，可以点"重新裁图"再试。
                    </p>
                  </div>
                </div>

                <label class="blank-toggle">
                  <input v-model="draft.blank" type="checkbox" :disabled="review.confirmed">
                  学生没有作答（这题入库为空白）
                </label>

                <label>识别到的作答文字
                  <el-input
                    v-model="draft.answerText" type="textarea" :rows="3"
                    :disabled="review.confirmed || draft.blank"
                  />
                </label>

                <label>公式（LaTeX）
                  <el-input
                    v-model="draft.answerLatex" :disabled="review.confirmed || draft.blank"
                  />
                </label>
                <div v-if="draft.answerLatex" class="formula-preview">
                  <span class="field-hint">预览</span>
                  <MathText :text="draft.answerLatex" />
                </div>

                <div class="candidate-actions">
                  <button
                    type="button" class="secondary-button"
                    :disabled="busy || review.confirmed" @click="recrop"
                  >重新裁图</button>
                  <button
                    type="button" class="secondary-button"
                    :disabled="busy || review.confirmed" @click="markBlankAndConfirm"
                  >标为空白并确认</button>
                  <button
                    type="button" class="secondary-button"
                    :disabled="busy || review.confirmed" @click="save"
                  >保存</button>
                  <button
                    type="button" class="primary-button"
                    :disabled="busy || review.confirmed" @click="confirmOne"
                  >确认这道题</button>
                </div>

                <!-- 改派是互换：每道题恒有且只有一条候选，那道题原来的候选换到本题来。 -->
                <div class="reassign-block">
                  <b>识别把作答分错了题？</b>
                  <p class="field-hint">
                    选答对的题号，两条候选**互换**题目映射 —— 对面那道题不会空着，
                    但它也要重新校对一遍。
                  </p>
                  <ul>
                    <li v-for="target in reassignTargets" :key="target.candidateId">
                      <button
                        type="button" class="link-button"
                        :disabled="busy || review.confirmed" @click="reassign(target)"
                      >换成第 {{ questionLabel(target) }} 题</button>
                    </li>
                  </ul>
                </div>

                <div class="reference-panel">
                  <button type="button" class="link-button" @click="toggleReference">
                    {{ referenceOpen ? '收起标准答案' : '看这道题的标准答案' }}
                  </button>
                  <p v-if="referenceOpen" class="field-hint">
                    对着标准答案看学生会不自觉地"看出"那个答案，判分就不独立了。看完记得收起。
                  </p>
                  <template v-if="referenceOpen && referenceOf">
                    <p><b>标准答案</b>{{ referenceOf.standardAnswer || '这道题还没录标准答案' }}</p>
                    <p v-if="referenceOf.acceptedAnswers.length > 0">
                      <b>可接受</b>{{ referenceOf.acceptedAnswers.join('，') }}
                    </p>
                  </template>
                </div>
              </div>
            </template>
          </aside>
        </div>
      </template>
    </template>
  </section>
</template>
