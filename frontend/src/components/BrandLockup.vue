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
      <b>메디바이스</b>
      <small v-if="showTagline">성분 중심 복약 안전 도우미</small>
    </span>
  </div>
</template>
