<!--
  BrandLockup.vue
  Medivice의 신호등 심볼과 영문 브랜드명, 선택적 서비스 설명을 하나의 로고 묶음으로 표시한다.

  로그인·온보딩·앱 메뉴가 같은 마크업을 재사용해 브랜드 정렬과 표기를 일관되게 유지한다.
  관련 UC: UC1, UC3, UC6 / 화면: SCR-AUTH-001, SCR-ONB-001, SCR-MAIN-001
-->
<script setup>
defineProps({
  compact: { type: Boolean, default: false },
  showTagline: { type: Boolean, default: true },
})
</script>

<template>
  <div class="brand-lockup" :class="{ compact }">
    <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
    <span class="brand-copy">
      <b>Medivice</b>
      <small v-if="showTagline">성분 중심 복약 안전 도우미</small>
    </span>
  </div>
</template>
