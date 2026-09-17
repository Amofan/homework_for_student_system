<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const mobileOpen = ref(false)

const nav = [
  { to: '/student/assignments', mark: '作', label: '我的作业', caption: '上传与查看' },
]

const displayName = computed(() => (auth.profile?.role === 'STUDENT' ? auth.profile.displayName : '同学'))

/**
 * 首次改密未完成时藏起导航。
 *
 * <p>守卫本身会把学生弹回改密页，但保留可点的导航等于给了一条“点了没反应”的路径；
 * 直接不显示更诚实，也避免学生在改密前误以为系统坏了。
 */
const mustChangePassword = computed(
  () => auth.profile?.role === 'STUDENT' && auth.profile.passwordChangeRequired,
)

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <div class="app-shell student-shell">
    <button class="mobile-menu" type="button" aria-label="打开导航" @click="mobileOpen = !mobileOpen">菜单</button>
    <aside class="side-rail" :class="{ open: mobileOpen }">
      <div class="brand-block">
        <div class="brand-symbol">x²</div>
        <div>
          <strong>知析作业</strong>
          <span>初中数学 · 学生端</span>
        </div>
      </div>
      <nav v-if="!mustChangePassword" aria-label="主要导航">
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
        <span>提交提示</span>
        <p>识别出的文字由老师校对。你只需要保证页面完整、清晰、顺序正确。</p>
      </div>
      <button class="logout-button" type="button" @click="logout">退出登录</button>
    </aside>

    <main class="workspace">
      <header class="topbar">
        <div>
          <span class="date-line">学生空间</span>
          <strong>{{ displayName }}</strong>
        </div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
