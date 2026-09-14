import { createApp } from 'vue'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { setUnauthorizedHandler } from './api/client'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { pinia } from './stores/pinia'
import './styles.css'

const app = createApp(App).use(pinia).use(router).use(ElementPlus)

// 令牌失效时清空登录状态并回到登录页，避免用户停留在只反复弹出错误提示的页面上。
setUnauthorizedHandler(() => {
  useAuthStore(pinia).logout()
  const current = router.currentRoute.value
  if (current.name !== 'login') router.push({ name: 'login', query: { redirect: current.fullPath } })
})

app.mount('#app')
