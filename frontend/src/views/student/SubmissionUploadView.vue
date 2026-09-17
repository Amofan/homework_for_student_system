<!--
  学生答卷上传页（手机优先）。

  这一页只认 URL 里的 versionId，不认"当前那一版"：刷新、从聊天软件切回来、多开一个标签页，
  看到的都是同一版草稿。版本本身由"我的答卷"页负责建 —— 那样只是翻一页看看，不会凭空多出版本行。

  文件字节不进 localStorage：刷新后页面从服务端重新拉一遍，队列里只留"还没传上去的那些"。
  把草稿存在浏览器里，学生换台手机就看不到自己交过什么，而清除站点数据等于把作业弄丢。
-->
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { errorCode, errorMessage } from '../../api/client'
import {
  SUBMISSION_ALLOWED_EXTENSIONS, SUBMISSION_MAX_FILE_BYTES, SUBMISSION_MAX_PAGES,
  deleteSubmissionPage, getSubmissionVersion, reorderSubmissionPages, rotateSubmissionPage,
  submitSubmissionVersion, uploadSubmissionFile, submissionVersionStatusLabel,
} from '../../api/studentSubmission'
import type { SubmissionPage, SubmissionVersion } from '../../api/types'
import StudentPageOrganizer from '../../components/document/StudentPageOrganizer.vue'
import UploadProgressItem from '../../components/document/UploadProgressItem.vue'
import { formatBytes, type UploadQueueItem } from '../../components/document/uploadQueue'

const route = useRoute()
const versionId = Number(route.params.versionId)

const version = ref<SubmissionVersion>()
const loading = ref(true)
const loadError = ref('')
/** 页面级操作错误：上传失败、整理失败、提交被拦。逐项错误在队列里，不在这里。 */
const actionError = ref('')
const busy = ref(false)
const submitted = ref(false)

/** 还没落到服务端的文件。传成功一项就从队列里消失——它已经变成上面那一页了。 */
const queue = ref<UploadQueueItem[]>([])
const submitPanel = ref(false)
const acknowledged = ref(false)

let nextQueueId = 1
let uploading = false

const pages = computed<SubmissionPage[]>(() => version.value?.pages ?? [])
const blockingPages = computed(() => pages.value.filter(page => page.qualityStatus === 'BLOCKING'))
const warningPages = computed(() => pages.value.filter(page => page.qualityStatus === 'WARNING'))

async function load(): Promise<void> {
  try {
    version.value = await getSubmissionVersion(versionId)
  } catch (reason) {
    loadError.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}

/**
 * 整理动作共用的一条路：落库失败就把服务端那份重新拉回来。
 *
 * 不拉的话界面会停在学生刚拖出来的顺序上，而他下一次点击会基于一个服务端并不认可的顺序；
 * 拉回来最多是"顺序跳回去了"，至少两边说的是同一件事。
 */
async function mutate(action: () => Promise<SubmissionVersion>): Promise<void> {
  busy.value = true
  actionError.value = ''
  try {
    version.value = await action()
  } catch (reason) {
    actionError.value = errorMessage(reason)
    await load()
  } finally {
    busy.value = false
  }
}

function reorder(pageIds: number[]): void {
  void mutate(() => reorderSubmissionPages(versionId, pageIds))
}

function rotate(pageId: number, degrees: number): void {
  void mutate(() => rotateSubmissionPage(versionId, pageId, degrees))
}

function removePage(pageId: number): void {
  void mutate(() => deleteSubmissionPage(versionId, pageId))
}

/**
 * 选文件。选同一个文件两次不会触发 `change`，所以读完就清空输入框。
 *
 * 这里只做"扩展名 + 大小 + 张数"的即时提示，真正的判据（文件签名、能否解码、像素上限）
 * 在服务端；前端校验是为了省掉一次"传完 25 MB 才被告知不行"的往返。
 */
function choose(event: Event): void {
  const input = event.target as HTMLInputElement
  const chosen = Array.from(input.files ?? [])
  input.value = ''
  if (chosen.length === 0) return

  actionError.value = ''
  const accepted: UploadQueueItem[] = []
  for (const file of chosen) {
    const name = file.name.toLowerCase()
    if (!SUBMISSION_ALLOWED_EXTENSIONS.some(extension => name.endsWith(extension))) {
      actionError.value = `只支持 PDF、PNG、JPG 三种格式：${file.name}`
      continue
    }
    if (file.size > SUBMISSION_MAX_FILE_BYTES) {
      actionError.value = `单个文件不能超过 ${formatBytes(SUBMISSION_MAX_FILE_BYTES)}：${file.name}`
      continue
    }
    accepted.push({ id: nextQueueId++, file, status: 'pending', percent: 0 })
  }
  if (accepted.length === 0) return

  if (pages.value.length + queue.value.length + accepted.length > SUBMISSION_MAX_PAGES) {
    actionError.value = `一份答卷最多 ${SUBMISSION_MAX_PAGES} 页，请先删掉不需要的页面`
    return
  }
  queue.value = [...queue.value, ...accepted]
  void runQueue()
}

/** 串行上传：并发上传时进度条会互相争抢，而学生看到的"40%"属于哪一个文件说不清。 */
async function runQueue(): Promise<void> {
  if (uploading) return
  uploading = true
  busy.value = true
  try {
    for (const item of queue.value) {
      if (item.status !== 'pending') continue
      item.status = 'uploading'
      item.percent = 0
      try {
        version.value = await uploadSubmissionFile(versionId, item.file,
          percent => { item.percent = percent })
        queue.value = queue.value.filter(entry => entry.id !== item.id)
      } catch (reason) {
        // 只把失败落在这一项上：其余文件照常往下传，学生只需要重试这一张。
        item.status = 'failed'
        item.error = errorMessage(reason)
      }
    }
  } finally {
    uploading = false
    busy.value = false
  }
}

function retry(id: number): void {
  const item = queue.value.find(entry => entry.id === id)
  if (!item) return
  item.status = 'pending'
  item.error = undefined
  void runQueue()
}

function removeQueued(id: number): void {
  queue.value = queue.value.filter(entry => entry.id !== id)
}

function openSubmitPanel(): void {
  actionError.value = ''
  if (pages.value.length === 0) {
    actionError.value = '还没有上传任何页面'
    return
  }
  if (blockingPages.value.length > 0) {
    actionError.value = `第 ${blockingPages.value.map(page => page.pageNo).join('、')} 页照片不合格，请重新拍摄后再提交`
    return
  }
  acknowledged.value = false
  submitPanel.value = true
}

/**
 * 提交。
 *
 * 逐页确认只送质量警告的那些页面：没被警告的页面也一起送过去，
 * 等于把"我确认过每一页"这句话盖在一堆学生根本没看过的页面上。
 */
async function confirmSubmit(): Promise<void> {
  if (warningPages.value.length > 0 && !acknowledged.value) return
  busy.value = true
  actionError.value = ''
  try {
    version.value = await submitSubmissionVersion(versionId, warningPages.value.map(page => page.id))
    submitPanel.value = false
    queue.value = []
    submitted.value = true
  } catch (reason) {
    submitPanel.value = false
    actionError.value = errorMessage(reason)
    // 冲突的唯一出路是重新读一遍服务端：警告页、已锁、作业已结束都只有服务端知道。
    const code = errorCode(reason)
    if (code === 'SUBMISSION_QUALITY_WARNING' || code === 'SUBMISSION_LOCKED'
      || code === 'SUBMISSION_QUALITY_BLOCKING' || code === 'SUBMISSION_CLOSED') {
      await load()
    }
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="page">
    <header class="page-heading">
      <div>
        <p class="kicker">我的答卷</p>
        <h1>上传答卷</h1>
        <p>可以一次选好几张照片，也可以选 PDF。识别文字由老师校对，你只要保证页面齐全、方向正确。</p>
      </div>
    </header>

    <div v-if="loadError" class="state-panel error-state" role="alert">{{ loadError }}</div>
    <div v-else-if="loading" class="state-panel">正在载入答卷…</div>

    <template v-else-if="version">
      <!--
        页面级错误放在最上面，而不是动作旁边。
        一次被拒的提交往往会让界面整体变样（提交被锁 → 整页转只读），错误信息跟在
        只读面板后面就一起被换掉了 —— 学生只看到页面"跳"了一下，不知道刚才发生了什么。
      -->
      <div v-if="actionError" class="state-panel error-state" role="alert">{{ actionError }}</div>

      <div v-if="submitted" class="state-panel success-state" role="status">
        <b>已提交</b>
        <p>老师会先校对识别出的文字，再开始批改。</p>
        <RouterLink
          class="primary-button"
          :to="`/student/assignments/${version.assignmentId}/submission`"
        >查看我的答卷</RouterLink>
      </div>

      <!--
        只读不是错误，所以不用 error-state：学生会往"是我操作错了吗"想。
        退回的那一版要说清原因 —— 那句话正是学生回来要找的东西。
      -->
      <div v-else-if="!version.editable" class="state-panel">
        <b>{{ version.status === 'RETURNED' ? '这一版已被老师退回' : '这一版已经提交，不能再修改' }}</b>
        <p>{{ submissionVersionStatusLabel(version.status) }}<template v-if="version.returnReason">：{{ version.returnReason }}</template></p>
        <p>要重新交的话，回「我的答卷」看看能不能再交一版。</p>
        <RouterLink
          class="secondary-button"
          :to="`/student/assignments/${version.assignmentId}/submission`"
        >回到我的答卷</RouterLink>
      </div>

      <template v-else>
        <article class="paper-card submission-card">
          <div class="assignment-card-head">
            <div>
              <span class="status-pill">第 {{ version.versionNo }} 版 · 草稿</span>
              <h2>上传页面</h2>
              <p>已上传 {{ pages.length }} 页，最多 {{ SUBMISSION_MAX_PAGES }} 页。</p>
            </div>
          </div>

          <label class="upload-picker">
            <span>选择照片或 PDF</span>
            <input
              type="file" :accept="SUBMISSION_ALLOWED_EXTENSIONS.join(',')" multiple
              :disabled="busy" @change="choose"
            >
          </label>
          <p class="field-hint">
            支持 PDF、PNG、JPG，单个文件不超过 {{ formatBytes(SUBMISSION_MAX_FILE_BYTES) }}。
            一份 PDF 会按里面的页拆开，每一页都能单独旋转或删除。
          </p>

          <ol v-if="queue.length > 0" class="upload-queue" aria-label="待上传的文件">
            <UploadProgressItem
              v-for="item in queue" :key="item.id" :item="item" :disabled="busy"
              @retry="retry" @remove="removeQueued"
            />
          </ol>
        </article>

        <article v-if="pages.length > 0" class="paper-card submission-card">
          <h2>页面顺序</h2>
          <p class="field-hint">
            一页就是一张照片。顺序不对用「上移 / 下移」，拍歪了用「向右转」——
            手机上直接拖动是不生效的。
          </p>
          <StudentPageOrganizer
            :pages="pages" :disabled="busy"
            @reorder="reorder" @rotate="rotate" @remove="removePage"
          />
        </article>

        <!--
          一页都没传时按钮不禁用：禁用掉的按钮不会说话，学生只会盯着一个灰按钮猜原因。
          点一下得到"还没有上传任何页面"比灰着不解释有用。
        -->
        <div class="submit-area">
          <button
            class="primary-button" type="button" :disabled="busy"
            @click="openSubmitPanel"
          >提交答卷</button>
          <p class="field-hint">
            提交后这一版就不能再改了（除非老师退回）。识别出的文字由老师确认，
            你不需要、也不能在这里改它。
          </p>
        </div>

        <!--
          确认不是走弹窗：手机上弹出的对话框会盖住它要解释的那几页，
          而学生需要一边看"第几页可能不清楚"一边决定。
        -->
        <div v-if="submitPanel" class="state-panel submit-confirm" role="dialog" aria-label="提交确认">
          <b>确认提交这一版？</b>
          <p>提交后你不能修改，老师退回才可以重交。识别文字由老师校对。</p>

          <template v-if="warningPages.length > 0">
            <p class="danger">第 {{ warningPages.map(page => page.pageNo).join('、') }} 页可能拍得不清楚，老师可能看不清。</p>
            <label class="confirm-check">
              <input v-model="acknowledged" type="checkbox">
              <span>我知道这几页可能不清楚，仍然提交</span>
            </label>
          </template>

          <div class="submit-confirm-actions">
            <button
              class="primary-button" type="button"
              :disabled="busy || (warningPages.length > 0 && !acknowledged)"
              @click="confirmSubmit"
            >确认提交</button>
            <button class="secondary-button" type="button" :disabled="busy" @click="submitPanel = false">
              再看看
            </button>
          </div>
        </div>
      </template>
    </template>
  </section>
</template>
