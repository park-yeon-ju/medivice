<!--
  BrandLockup.vue
  Medivice의 기본 심볼·브랜드명 또는 화면 용도에 맞는 이미지 로고를 하나의 로고 묶음으로 표시한다.

  인증·설문 시작은 세로형 이미지를, 메뉴바와 특이사항 문진은 배경에 맞는 가로형 이미지를, 나머지는 기본 마크업을 사용한다.
  관련 UC: UC1, UC3, UC6 / 화면: SCR-AUTH-001, SCR-ONB-001, SCR-MAIN-001
-->
<script setup>
import darkLogoUrl from '@/assets/medivice-logo-dark.png'
import horizontalLogoUrl from '@/assets/medivice-logo-horizontal.png'
import verticalLogoUrl from '@/assets/medivice-logo-vertical.png'

defineProps({
  compact: { type: Boolean, default: false },
  showTagline: { type: Boolean, default: true },
  imageLogo: { type: Boolean, default: false },
  lightHorizontalImage: { type: Boolean, default: false },
  verticalImage: { type: Boolean, default: false },
})
</script>

<template>
  <div
    class="brand-lockup"
    :class="{
      compact,
      'image-lockup': imageLogo || lightHorizontalImage,
      'vertical-image-lockup': verticalImage,
    }"
  >
    <!-- 흰 글자의 가로 조합은 어두운 메뉴바에서만 사용해 브랜드명이 배경과 충분히 대비되게 한다. -->
    <img v-if="imageLogo" class="brand-image" :src="darkLogoUrl" alt="" aria-hidden="true" />
    <!-- 특이사항 문진은 실제 투명 배경의 짙은 글자 로고를 사용해 페이지 배경과 자연스럽게 이어지게 한다. -->
    <img
      v-else-if="lightHorizontalImage"
      class="brand-image"
      :src="horizontalLogoUrl"
      alt="Medivice"
    />
    <!-- 세로 조합은 인증·설문 시작 화면에서만 사용해 단계형 문진과 메뉴바 정렬에는 영향을 주지 않는다. -->
    <img
      v-else-if="verticalImage"
      class="brand-image vertical-brand-image"
      :src="verticalLogoUrl"
      alt="Medivice 성분 중심 복약 안전"
    />
    <template v-else>
      <span class="brand-mark" aria-hidden="true"><i></i><i></i><i></i></span>
      <span class="brand-copy">
        <b>Medivice</b>
        <small v-if="showTagline">성분 중심 복약 안전 도우미</small>
      </span>
    </template>
  </div>
</template>
