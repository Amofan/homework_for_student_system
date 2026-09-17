<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

import { errorMessage } from '../../api/client'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const router = useRouter()

const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const loading = ref(false)
const error = ref('')

/** 两次输入不一致时不必等后端：本地先拦住，避免白白消耗一次请求和一次密码校验。 */
const mismatch = computed(() => confirmPassword.value.length > 0 && newPassword.value !== confirmPassword.value)
const canSubmit = computed(() => currentPassword.value.length > 0
  && newPassword.value.length > 0 && !mismatch.value && !loading.value)

async function submit() {
  if (!canSubmit.value) return
  error.value = ''
  loading.value = true
  try {
    await auth.changePassword(currentPassword.value, newPassword.value)
    currentPassword.value = ''
    newPassword.value = ''
    confirmPassword.value = ''
    // 落点交给 store 计算：改密后令牌已换发，profile 也已刷新，不需要在这里再判角色。
    await router.replace(auth.homePath)
  } catch (reason) {
    error.value = errorMessage(reason)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="page narrow-page">
    <header class="page-heading">
      <div>
        <p class="kicker">账号安全</p>
        <h1>修改密码</h1>
        <p>临时密码只能用一次。设置新密码后即可进入我的作业。</p>
      </div>
    </header>

    <form class="paper-card password-card" @submit.prevent="submit">
      <label>
        当前密码
        <input v-model="currentPassword" type="password" autocomplete="current-password" required>
      </label>
      <label>
        新密码
        <input v-model="newPassword" type="password" autocomplete="new-password" required>
      </label>
      <p class="field-hint">10–72 个字符，且至少包含大写字母、小写字母、数字、符号中的三类。</p>
      <label>
        确认新密码
        <input v-model="confirmPassword" type="password" autocomplete="new-password" required>
      </label>
      <p v-if="mismatch" class="form-error" role="alert">两次输入的新密码不一致。</p>
      <p v-else-if="error" class="form-error" role="alert">{{ error }}</p>
      <button class="primary-button" type="submit" :disabled="!canSubmit">
        {{ loading ? '正在保存…' : '保存并继续' }}
      </button>
    </form>
  </section>
</template>
