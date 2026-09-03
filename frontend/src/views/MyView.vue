<script setup>
import { onMounted } from 'vue'
import { useRouter } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import { useMediviceStore } from '@/stores/medivice'

const props = defineProps({
  section: { type: String, default: 'overview' },
})

const router = useRouter()
const store = useMediviceStore()

const sectionMeta = {
  overview: { title: '마이페이지', crumb: '' },
  profile: { title: '특이사항 관리', crumb: '마이페이지 /' },
  account: { title: '계정 관리', crumb: '마이페이지 /' },
  symptoms: { title: '증상 기록 모음', crumb: '마이페이지 /' },
}

onMounted(() => store.loadDashboard())

function logout() {
  router.push('/login')
}

function removeSymptom(id) {
  if (window.confirm('이 증상 기록을 삭제할까요?')) {
    store.symptoms = store.symptoms.filter((entry) => entry.id !== id)
  }
}
</script>

<template>
  <AppShell :title="sectionMeta[props.section].title" :crumb="sectionMeta[props.section].crumb">
    <template v-if="section === 'overview'">
      <section class="profile-hero content-card">
        <span class="avatar">김</span>
        <div><p class="eyebrow">MY PROFILE</p><h2>{{ store.user?.name ?? '김민서' }}</h2><p>{{ store.user?.sex ?? '여성' }} · {{ store.user?.birthDate ?? '1974-03-08' }} ({{ store.user?.age ?? 52 }}세) · {{ store.user?.username ?? 'minseo_k' }}</p><div class="inline-chips"><span v-for="condition in store.user?.conditions" :key="condition" class="chip rx">{{ condition }}</span><span class="chip neutral">알레르기 {{ store.user?.allergies?.length ?? 0 }}건</span></div></div>
        <button class="button ghost" type="button" @click="logout">로그아웃</button>
      </section>
      <section class="tile-grid">
        <RouterLink to="/my/profile"><span class="tile-icon">01</span><b>특이사항 관리</b><p>지병·알레르기·키·몸무게와 선택 항목을 확인하고 수정합니다.</p><small>SCR-MY-002 →</small></RouterLink>
        <RouterLink to="/my/account"><span class="tile-icon">02</span><b>계정 관리</b><p>아이디·비밀번호 변경과 가족 계정 연동을 관리합니다.</p><small>SCR-MY-003 →</small></RouterLink>
        <RouterLink to="/my/symptoms"><span class="tile-icon">03</span><b>증상 기록 모음</b><p>날짜순 카드로 조회·수정·삭제합니다.</p><small>{{ store.symptoms.length }}건 · SCR-MY-004 →</small></RouterLink>
        <RouterLink to="/report/new"><span class="tile-icon">04</span><b>진료용 보고서</b><p>기간과 표시 언어를 골라 복약 보고서를 생성합니다.</p><small>SCR-RPT-001 →</small></RouterLink>
      </section>
      <section class="content-card language-card"><div><b>표시 언어</b><p>화면 언어를 바꾸고 성분명은 영문명으로 함께 표시합니다.</p></div><div class="language-switch large"><button type="button" :class="{ active: store.language === 'KO' }" @click="store.setLanguage('KO')">한국어</button><button type="button" :class="{ active: store.language === 'EN' }" @click="store.setLanguage('EN')">English</button></div></section>
    </template>

    <section v-else-if="section === 'profile'" class="content-card">
      <div class="section-title"><div><p class="eyebrow">SCR-MY-002 · UC24</p><h2>온보딩 입력값</h2></div><button class="button primary" type="button">저장</button></div>
      <div class="profile-table">
        <div class="profile-row"><span>지병</span><div><span v-for="condition in store.user?.conditions" :key="condition" class="chip rx">{{ condition }}</span></div><small>금기·주의 규칙 대조</small><button class="text-button" type="button">수정</button></div>
        <div class="profile-row"><span>알레르기</span><div><span v-for="allergy in store.user?.allergies" :key="allergy" class="chip neutral">{{ allergy }}</span></div><small>신규 등록 시 성분 대조</small><button class="text-button" type="button">수정</button></div>
        <div class="profile-row"><span>키 · 몸무게</span><b>{{ store.user?.height }} cm · {{ store.user?.weight }} kg</b><small>용량 확인 참고</small><button class="text-button" type="button">수정</button></div>
        <div class="profile-row"><span>연령</span><b>{{ store.user?.age }}세</b><small>생년월일에서 자동 계산</small><span></span></div>
      </div>
      <div class="optional-block profile-options"><div><b>선택 항목</b><span>수집 이유를 안내한 뒤 입력합니다.</span></div><div class="profile-row"><span>임신 · 수유 여부</span><b>입력 안 함</b><small>임부금기 규칙과 연결</small><button class="text-button" type="button">입력</button></div><div class="profile-row"><span>과거 약물 이상반응</span><b>{{ store.user?.adverseHistory || '입력 안 함' }}</b><small>성분 코드로 재발 주의</small><button class="text-button" type="button">수정</button></div><div class="profile-row muted-row"><span>신장 · 간 기능 이상</span><b>향후 지원</b><small>예 / 아니오 / 모름으로 받을 예정</small><span></span></div></div>
    </section>

    <template v-else-if="section === 'account'">
      <section class="content-card account-card"><div class="section-title"><div><p class="eyebrow">SCR-MY-003 · UC25</p><h2>계정 정보</h2></div></div><div class="field-grid two"><label class="form-field"><span>아이디</span><input :value="store.user?.username ?? 'minseo_k'" disabled /><small>아이디는 변경할 수 없습니다.</small></label><label class="form-field"><span>비밀번호</span><div class="input-action"><input value="••••••••••" disabled /><button class="button ghost" type="button">변경</button></div></label><label class="form-field"><span>성별</span><select :value="store.user?.sex"><option>여성</option><option>남성</option><option>선택 안 함</option></select></label><label class="form-field"><span>생년월일</span><input :value="store.user?.birthDate" type="date" /></label></div></section>
      <section class="content-card family-card"><div><span class="chip neutral">향후 확장</span><h2>가족 계정 연동</h2><p>초대·수락으로 계정을 연결합니다. 증상 메모 등 민감한 기록의 공개 범위는 추후 합의가 필요합니다.</p></div><div class="input-action"><input placeholder="연동할 가족의 아이디" disabled /><button class="button ghost" type="button" disabled>초대 보내기</button></div></section>
      <section class="dangerless-card"><div><b>로그아웃</b><p>공용 기기에서는 현재 기기의 세션을 종료해주세요.</p></div><button class="button ghost" type="button" @click="logout">로그아웃</button></section>
    </template>

    <template v-else>
      <div class="page-intro row-intro"><div><p class="eyebrow">SCR-MY-004 · UC22</p><h2>최근 30일 · {{ store.symptoms.length }}건</h2></div><RouterLink class="button primary" to="/main/symptom">기록 추가</RouterLink></div>
      <section class="symptom-list">
        <article v-for="entry in store.symptoms" :key="entry.id" class="symptom-log">
          <time :datetime="`${entry.date}T${entry.time}`"><b>{{ entry.date.slice(5).replace('-', '.') }}</b><span>{{ entry.time }}</span></time>
          <div><div class="inline-chips"><span v-for="symptom in entry.symptoms" :key="symptom" class="chip symptom">{{ symptom }}</span></div><p>{{ entry.note }}</p><small v-if="entry.medicationSnapshot.length">복용 스냅샷 {{ entry.medicationSnapshot.length }}건 · {{ entry.medicationSnapshot.map((item) => item.name).slice(0, 4).join(' · ') }}{{ entry.medicationSnapshot.length > 4 ? ` 외 ${entry.medicationSnapshot.length - 4}` : '' }}</small><small v-else>연결된 복용 항목 없음 · 해당 날짜에 등록된 항목이 없습니다.</small></div>
          <div class="row-actions"><button class="text-button" type="button">수정</button><button class="text-button" type="button" @click="removeSymptom(entry.id)">삭제</button></div>
        </article>
      </section>
    </template>
  </AppShell>
</template>
