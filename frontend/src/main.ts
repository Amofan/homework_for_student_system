import { createApp } from 'vue'
// 模板里用到的 Element Plus 组件由 vite.config.ts 的按需导入插件引入，这里不再整体注册。
// 但 ElMessage 是各页面显式 import 调用的（这样测试才能 mock 掉它），不走插件，
// 它的样式得单独引一次；这一行同时带入 element-plus 的 base 样式与 CSS 变量。
import 'element-plus/es/components/message/style/css'
// KaTeX 字体与排版样式。只在入口引一次：公式组件散落在多个懒加载页面里，
// 让组件各自引会把同一份 CSS 重复打进多个分包。
import 'katex/dist/katex.min.css'

import App from './App.vue'
import { setUnauthorizedHandler } from './api/client'
import { router } from './router'
import { useAuthStore } from './stores/auth'
import { pinia } from './stores/pinia'
import './styles.css'

const app = createApp(App).use(pinia).use(router)

// 令牌失效时清空登录状态并回到登录页，避免用户停留在只反复弹出错误提示的页面上。
setUnauthorizedHandler(() => {
  useAuthStore(pinia).logout()
  const current = router.currentRoute.value
  if (current.name !== 'login') router.push({ name: 'login', query: { redirect: current.fullPath } })
})

app.mount('#app')
