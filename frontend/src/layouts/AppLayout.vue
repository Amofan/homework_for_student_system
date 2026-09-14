<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const mobileOpen = ref(false)

const nav = [
  { to: '/', mark: '∑', label: '教学工作台', caption: '今日概览' },
  { to: '/classrooms', mark: '班', label: '班级与学生', caption: '名册管理' },
  { to: '/knowledge', mark: '点', label: '知识点体系', caption: '章节结构' },
  { to: '/questions', mark: '题', label: '数学题库', caption: '答案与评分项' },
  { to: '/assignments', mark: '作', label: '作业管理', caption: '导入与启动评分' },
  { to: '/review', mark: '阅', label: '教师复核', caption: '确认正式成绩' },
  { to: '/analytics', mark: '析', label: '学情分析', caption: '知识点画像' },
]

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
          class="nav-item" :class="{ active: route.path === item.to }"
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
          <strong>{{ auth.profile?.displayName ?? '数学教师' }}</strong>
        </div>
        <div class="teacher-chip">
          <span class="status-dot"></span>
          {{ auth.profile?.schoolName || '教师工作空间' }}
        </div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
