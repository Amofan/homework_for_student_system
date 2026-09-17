import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

import type { Role } from '../api/types'
import { useAuthStore } from '../stores/auth'
import { pinia } from '../stores/pinia'

declare module 'vue-router' {
  interface RouteMeta {
    requiresAuth?: boolean
    /** 进入该分支所需的角色；缺省表示任何已登录主体都可进入。 */
    role?: Role
  }
}

const teacherRoutes: RouteRecordRaw[] = [
  { path: '', name: 'teacher-dashboard', component: () => import('../views/DashboardView.vue') },
  { path: 'classrooms', name: 'classrooms', component: () => import('../views/ClassroomView.vue') },
  { path: 'knowledge', name: 'knowledge', component: () => import('../views/KnowledgeView.vue') },
  { path: 'questions', name: 'questions', component: () => import('../views/QuestionView.vue') },
  { path: 'assignments', name: 'assignments', component: () => import('../views/AssignmentView.vue') },
  // 整卷导入：一次导入就是一份 OCR_REVIEW 状态的作业，所以校对页的 id 也是作业 id。
  { path: 'paper-imports', name: 'paper-imports', component: () => import('../views/teacher/PaperImportView.vue') },
  {
    path: 'paper-imports/:assignmentId/review',
    name: 'paper-review',
    component: () => import('../views/teacher/PaperReviewView.vue'),
  },
  // 作业与答卷两个参数都可缺省：这个页面自己就是"选作业 → 挑答卷 → 校对"的入口，
  // 缺了参数只是少显示一栏，不是错误页。挂成查询参数而不是路径段，正是为了让它可缺省。
  { path: 'submissions', name: 'submissions', component: () => import('../views/teacher/SubmissionReviewView.vue') },
  { path: 'review', name: 'review', component: () => import('../views/ReviewView.vue') },
  { path: 'analytics', name: 'analytics', component: () => import('../views/AnalyticsView.vue') },
  { path: 'exercises', name: 'exercises', component: () => import('../views/ExerciseView.vue') },
]

const studentRoutes: RouteRecordRaw[] = [
  { path: 'assignments', name: 'student-assignments', component: () => import('../views/student/StudentAssignmentsView.vue') },
  // 答卷分两层：按作业看"我交过什么"（第几版、退回原因），按版本改"这一版的内容"。
  // 合成一页的话，刷新上传页就得先知道是哪个作业，而从聊天软件里点开链接的学生没有那个上下文。
  {
    path: 'assignments/:assignmentId/submission',
    name: 'student-submission',
    component: () => import('../views/student/SubmissionDetailView.vue'),
  },
  {
    path: 'submissions/:versionId/edit',
    name: 'student-submission-edit',
    component: () => import('../views/student/SubmissionUploadView.vue'),
  },
  { path: 'change-password', name: 'student-change-password', component: () => import('../views/student/ChangePasswordView.vue') },
]

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    // 根路径交给守卫决定去向：已登录去自己的工作台，未登录去登录页。
    // 直接写死 redirect 会丢掉“学生首次登录必须改密”这一分支。
    { path: '/', redirect: '/login' },
    { path: '/login', name: 'login', component: () => import('../views/LoginView.vue') },
    {
      path: '/teacher',
      component: () => import('../layouts/TeacherLayout.vue'),
      meta: { requiresAuth: true, role: 'TEACHER' },
      children: teacherRoutes,
    },
    {
      path: '/student',
      component: () => import('../layouts/StudentLayout.vue'),
      meta: { requiresAuth: true, role: 'STUDENT' },
      children: studentRoutes,
    },
    { path: '/:pathMatch(.*)*', redirect: '/login' },
  ],
})

/**
 * 守卫顺序即优先级，任何一步提前返回都会跳过后续检查：
 *
 * 1. 未登录 → 登录页（带上回跳地址）；
 * 2. 有令牌但还没有资料 → 先拉 `/auth/me`，失败即登出，避免用空资料做角色判断；
 * 3. 角色不符 → 回自己的工作台，而不是抛 404 让用户以为页面不存在；
 * 4. 学生首次改密未完成 → 只能停在改密页。
 *
 * 角色来自 `/auth/me` 而不是前端解 JWT：令牌是服务端签的，前端解码只会多一份需要维护的
 * 解析逻辑，而且一旦令牌结构与声明改名，两边会静默失配。
 */
router.beforeEach(async (to) => {
  const auth = useAuthStore(pinia)

  if (!to.meta.requiresAuth) {
    if (to.name === 'login' && auth.authenticated) {
      if (!auth.profile) await auth.loadProfile().catch(() => auth.logout())
      return auth.homePath
    }
    return true
  }

  if (!auth.authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if (!auth.profile) {
    try {
      await auth.loadProfile()
    } catch {
      auth.logout()
      return { name: 'login' }
    }
  }

  const profile = auth.profile
  if (!profile) return { name: 'login' }
  if (to.meta.role && profile.role !== to.meta.role) {
    return auth.homePath
  }
  if (profile.role === 'STUDENT' && profile.passwordChangeRequired
    && to.name !== 'student-change-password') {
    return { name: 'student-change-password' }
  }
  return true
})
