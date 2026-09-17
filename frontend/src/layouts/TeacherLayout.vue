<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const mobileOpen = ref(false)

const nav = [
  { to: '/teacher', mark: '∑', label: '教学工作台', caption: '今日概览' },
  { to: '/teacher/classrooms', mark: '班', label: '班级与学生', caption: '名册与账号' },
  { to: '/teacher/knowledge', mark: '点', label: '知识点体系', caption: '章节结构' },
  { to: '/teacher/questions', mark: '题', label: '数学题库', caption: '答案与评分项' },
  { to: '/teacher/paper-imports', mark: '卷', label: '整卷导入', caption: '识别与校对' },
  { to: '/teacher/assignments', mark: '作', label: '作业管理', caption: '导入与启动评分' },
  { to: '/teacher/submissions', mark: '答', label: '答卷校对', caption: '识别与答案入库' },
  { to: '/teacher/review', mark: '阅', label: '教师复核', caption: '确认正式成绩' },
  { to: '/teacher/analytics', mark: '析', label: '学情分析', caption: '知识点画像' },
  { to: '/teacher/exercises', mark: '练', label: '分层练习', caption: '生成与导出' },
]

// 用角色收窄而不是类型断言：学生误入教师布局时（理论上被守卫挡住）也只显示占位文案，
// 不会因为读取不存在的字段而抛错。
const displayName = computed(() => (auth.profile?.role === 'TEACHER' ? auth.profile.displayName : '数学教师'))
const schoolName = computed(() => (auth.profile?.role === 'TEACHER' ? auth.profile.schoolName : undefined))

/**
 * 高亮当前导航。
 *
 * 不能只比相等：校对页的路径是 `/teacher/paper-imports/12/review`，
 * 严格相等会让教师在校对时侧栏看起来"什么都没选中"。工作台是唯一例外——
 * 它是 `/teacher` 本身，用前缀匹配会把所有子页面都算成它。
 */
function isActive(to: string): boolean {
  if (to === '/teacher') return route.path === to
  return route.path === to || route.path.startsWith(`${to}/`)
}

const today = computed(() => new Intl.DateTimeFormat('zh-CN', {
  month: 'long', day: 'numeric', weekday: 'long', timeZone: 'Asia/Shanghai',
}).format(new Date()))

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-shell">
    <button class="mobile-menu" type="button" aria-label="打开导航" @click="mobileOpen = !mobileOpen">菜单</button>
    <aside class="side-rail" :class="{ open: mobileOpen }">
      <div class="brand-block">
        <div class="brand-symbol">x²</div>
        <div>
          <strong>知析作业</strong>
          <span>初中数学 · 教师端</span>
        </div>
      </div>
      <nav aria-label="主要导航">
        <RouterLink
          v-for="item in nav" :key="item.to" :to="item.to"
          class="nav-item" :class="{ active: isActive(item.to) }"
          @click="mobileOpen = false"
        >
          <span class="nav-mark">{{ item.mark }}</span>
          <span><b>{{ item.label }}</b><small>{{ item.caption }}</small></span>
        </RouterLink>
      </nav>
      <div class="rail-note">
        <span>AI 使用原则</span>
        <p>模型只给建议。教师确认后，成绩才进入学情分析。</p>
      </div>
      <button class="logout-button" type="button" @click="logout">退出登录</button>
    </aside>

    <main class="workspace">
      <header class="topbar">
        <div>
          <span class="date-line">{{ today }}</span>
          <strong>{{ displayName }}</strong>
        </div>
        <div class="teacher-chip">
          <span class="status-dot"></span>
          {{ schoolName || '教师工作空间' }}
        </div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
