import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior: () => ({ top: 0 }),
  routes: [
    { path: '/', redirect: '/login' },
    { path: '/login', name: 'login', component: () => import('@/views/AuthView.vue'), props: { mode: 'login' } },
    { path: '/signup', name: 'signup', component: () => import('@/views/AuthView.vue'), props: { mode: 'signup' } },
    { path: '/onboarding', name: 'onboarding-choice', component: () => import('@/views/OnboardingView.vue'), props: { step: 'choice' } },
    { path: '/onboarding/profile', name: 'onboarding-profile', component: () => import('@/views/OnboardingView.vue'), props: { step: 'profile' } },
    { path: '/onboarding/medications', name: 'onboarding-medications', component: () => import('@/views/OnboardingView.vue'), props: { step: 'medications' } },
    { path: '/main', name: 'main', component: () => import('@/views/MainView.vue') },
    { path: '/medilight', name: 'medilight', component: () => import('@/views/MedilightView.vue') },
    { path: '/medications', name: 'medications', component: () => import('@/views/MedicationsView.vue') },
    { path: '/main/register', name: 'register', component: () => import('@/views/RegistrationView.vue'), props: { mode: 'upload' } },
    { path: '/main/register-confirm', name: 'register-confirm', component: () => import('@/views/RegistrationView.vue'), props: { mode: 'confirm' } },
    { path: '/main/register-manual', name: 'register-manual', component: () => import('@/views/RegistrationView.vue'), props: { mode: 'manual' } },
    { path: '/main/symptom', name: 'symptom-new', component: () => import('@/views/SymptomEntryView.vue') },
    { path: '/my', name: 'my-overview', component: () => import('@/views/MyView.vue'), props: { section: 'overview' } },
    { path: '/my/profile', name: 'my-profile', component: () => import('@/views/MyView.vue'), props: { section: 'profile' } },
    { path: '/my/account', name: 'my-account', component: () => import('@/views/MyView.vue'), props: { section: 'account' } },
    { path: '/my/symptoms', name: 'my-symptoms', component: () => import('@/views/MyView.vue'), props: { section: 'symptoms' } },
    { path: '/report/new', name: 'report-new', component: () => import('@/views/ReportView.vue'), props: { mode: 'create' } },
    { path: '/report/latest', name: 'report-result', component: () => import('@/views/ReportView.vue'), props: { mode: 'result' } },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
  ],
})

export default router
