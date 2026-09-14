import { createRouter, createWebHistory } from 'vue-router'

import { useAuthStore } from '../stores/auth'
import { pinia } from '../stores/pinia'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    {
      path: '/', component: () => import('../layouts/AppLayout.vue'), meta: { requiresAuth: true },
      children: [
        { path: '', name: 'dashboard', component: () => import('../views/DashboardView.vue') },
        { path: 'classrooms', name: 'classrooms', component: () => import('../views/ClassroomView.vue') },
        { path: 'knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') },
        { path: 'questions', name: 'questions', component: () => import('../views/QuestionView.vue') },
        { path: 'assignments', name: 'assignments', component: () => import('../views/AssignmentView.vue') },
        { path: 'review', name: 'review', component: () => import('../views/ReviewView.vue') },
        { path: 'analytics', name: 'analytics', component: () => import('../views/AnalyticsView.vue') },
      ],
    },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore(pinia)
  if (to.meta.requiresAuth && !auth.authenticated) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.name === 'login' && auth.authenticated) return { name: 'dashboard' }
  if (auth.authenticated && !auth.profile) {
    try { await auth.loadProfile() } catch {
      auth.logout()
      if (to.name !== 'login') return { name: 'login' }
    }
  }
  return true
})
