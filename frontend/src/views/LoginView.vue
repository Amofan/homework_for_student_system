<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import { errorMessage } from '../api/client'
import { useAuthStore } from '../stores/auth'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(username.value.trim(), password.value)
    // 回跳地址原样交给路由守卫再判一次角色：教师误带学生路径时会被守卫改回自己的工作台，
    // 这里不需要复制一份角色判断。'/login' 自我回跳会造成无意义的一次跳转，直接忽略。
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    await router.replace(redirect && redirect !== '/login' ? redirect : auth.homePath)
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <section class="login-thesis">
      <div class="thesis-inner">
        <p class="kicker">TEACHER'S NOTEBOOK / 教师批改簿</p>
        <h1>看见答案背后的<br><em>知识缺口</em></h1>
        <p class="thesis-copy">把作业录入、辅助批改、教师复核和知识点画像连成一条可靠的教学证据链。</p>
        <div class="equation-strip" aria-hidden="true">
          <span>错因</span><i>→</i><span>知识点</span><i>→</i><span>分层练习</span>
        </div>
      </div>
      <div class="margin-note">AI ≠ 最终评分<br>教师确认才生效</div>
    </section>
    <section class="login-panel">
      <form class="login-card" @submit.prevent="submit">
        <div class="login-card-head">
          <span class="correction-mark">✓</span>
          <div><p>欢迎回来</p><h2>进入教学工作台</h2></div>
        </div>
        <label>教师账号<input v-model="username" autocomplete="username" placeholder="请输入账号" required></label>
        <label>登录密码<input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" required></label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="primary-button login-submit" type="submit" :disabled="loading">
          {{ loading ? '正在验证…' : '登录并开始批改' }}
        </button>
        <p class="privacy-note">学生姓名与学号不会发送给大模型。</p>
      </form>
    </section>
  </main>
</template>
