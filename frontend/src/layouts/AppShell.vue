<script setup>
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import BrandLockup from '@/components/BrandLockup.vue'
import { useMediviceStore } from '@/stores/medivice'

defineProps({
  title: { type: String, required: true },
  crumb: { type: String, default: '' },
})

const route = useRoute()
const store = useMediviceStore()

const navItems = [
  { label: '메인', to: '/main', icon: 'home' },
  { label: '복용 목록', to: '/medications', icon: 'list' },
  { label: '증상 기록', to: '/main/symptom', icon: 'note' },
  { label: '진료 보고서', to: '/report/new', icon: 'report' },
  { label: '마이페이지', to: '/my', icon: 'user' },
]

const activeRoot = computed(() => {
  if (route.path.startsWith('/report')) return '/report/new'
  if (route.path.startsWith('/my')) return '/my'
  if (route.path === '/medications') return '/medications'
  if (route.path.includes('symptom')) return '/main/symptom'
  return '/main'
})
</script>

<template>
  <div class="app-shell">
    <aside class="side-rail">
      <RouterLink class="rail-brand-link" to="/main" aria-label="메디바이스 메인">
        <BrandLockup compact :show-tagline="false" />
      </RouterLink>
      <nav aria-label="주요 메뉴">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          :class="{ active: activeRoot === item.to }"
        >
          <svg v-if="item.icon === 'home'" viewBox="0 0 24 24" aria-hidden="true"><path d="M4 11 12 4l8 7v9h-6v-6h-4v6H4z" /></svg>
          <svg v-else-if="item.icon === 'list'" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 5h13v2H7zm0 6h13v2H7zm0 6h13v2H7zM3 5h2v2H3zm0 6h2v2H3zm0 6h2v2H3z" /></svg>
          <svg v-else-if="item.icon === 'note'" viewBox="0 0 24 24" aria-hidden="true"><path d="M5 3h14v18H5zm3 5h8V6H8zm0 4h8v-2H8zm0 4h6v-2H8z" /></svg>
          <svg v-else-if="item.icon === 'report'" viewBox="0 0 24 24" aria-hidden="true"><path d="M6 3h9l4 4v14H6zm8 1v4h4M9 12h6v2H9zm0 4h6v2H9z" /></svg>
          <svg v-else viewBox="0 0 24 24" aria-hidden="true"><path d="M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8m-7 9a7 7 0 0 1 14 0z" /></svg>
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
      <div class="rail-profile">
        <b>{{ store.user?.name ?? '김민서' }} 님</b>
        <span>{{ store.user?.conditions?.join(' · ') || '등록된 특이사항 없음' }}</span>
        <small>복용 중 {{ store.medications.length }}개</small>
      </div>
    </aside>

    <section class="app-pane">
      <header class="topbar">
        <div>
          <span v-if="crumb" class="breadcrumb">{{ crumb }}</span>
          <h1>{{ title }}</h1>
        </div>
        <div class="topbar-actions">
          <div class="language-switch" aria-label="표시 언어">
            <button type="button" :class="{ active: store.language === 'KO' }" @click="store.setLanguage('KO')">KO</button>
            <button type="button" :class="{ active: store.language === 'EN' }" @click="store.setLanguage('EN')">EN</button>
          </div>
          <span class="reference-date">2026-09-02 기준</span>
        </div>
      </header>
      <main class="page-canvas"><slot /></main>
    </section>
  </div>
</template>
