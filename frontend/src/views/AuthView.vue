<!-- UC1·2 로그인·회원가입 화면. Mock 모드에서는 테스트 계정을 명시해 실제 인증 화면과 혼동하지 않게 한다. -->
<script setup>
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { isMockApi } from '@/api/adapter'
import BrandLockup from '@/components/BrandLockup.vue'
import { useMediviceStore } from '@/stores/medivice'

const props = defineProps({
  mode: { type: String, default: 'login' },
})

const router = useRouter()
const store = useMediviceStore()
const username = ref('')
const password = ref('')
const passwordConfirm = ref('')
const sex = ref('여성')
const birthDate = ref('1974-03-08')
const submitted = ref(false)

const isSignup = computed(() => props.mode === 'signup')
const passwordMismatch = computed(
  () => isSignup.value && passwordConfirm.value && password.value !== passwordConfirm.value,
)
const submitError = ref('')
const submitting = ref(false)

async function submit() {
  submitted.value = true
  submitError.value = ''
  if (!username.value || !password.value || passwordMismatch.value) return
  submitting.value = true
  try {
    if (isSignup.value) {
      // 실제로 사용자를 만들고(또는 이미 있으면 그대로 이어서 쓰고), 이 아이디를 이후 모든 요청에
      // 실어 보내도록 저장한다 — 그래야 새로고침해도 방금 가입한 아이디로 계속 식별된다.
      await store.signup({
        loginId: username.value,
        password: password.value,
        sex: sex.value,
        birthDate: birthDate.value,
      })
      await router.push('/onboarding')
      return
    }
    await store.login({ loginId: username.value, password: password.value })
    await router.push('/main')
  } catch (caughtError) {
    submitError.value =
      caughtError instanceof Error ? caughtError.message : '요청을 처리하지 못했습니다.'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-stage">
    <section class="auth-card" :class="{ signup: isSignup }">
      <BrandLockup />
      <div class="auth-heading">
        <p class="eyebrow">{{ isSignup ? 'SCR-AUTH-002 · UC1' : 'SCR-AUTH-001 · UC2' }}</p>
        <h1>{{ isSignup ? '회원가입' : '다시 만나서 반가워요' }}</h1>
        <p>
          {{
            isSignup
              ? '가입을 마치면 자동으로 로그인됩니다.'
              : '복용 목록과 메디라이트를 확인하세요.'
          }}
        </p>
      </div>

      <div v-if="isMockApi && !isSignup" class="privacy-note">
        <b>개발용 Mock API</b> · 아이디 <code>minseo_k</code> · 비밀번호 <code>12345678</code>
      </div>

      <form class="form-stack" @submit.prevent="submit">
        <label class="form-field">
          <span>아이디 <b aria-hidden="true">*</b></span>
          <input
            v-model.trim="username"
            autocomplete="username"
            placeholder="아이디를 입력하세요"
          />
          <small v-if="isSignup && username">사용할 수 있는 아이디입니다.</small>
        </label>
        <div :class="isSignup ? 'field-grid two' : ''">
          <label class="form-field">
            <span>비밀번호 <b aria-hidden="true">*</b></span>
            <input
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="8자 이상 입력하세요"
            />
          </label>
          <label v-if="isSignup" class="form-field">
            <span>비밀번호 확인 <b aria-hidden="true">*</b></span>
            <input
              v-model="passwordConfirm"
              type="password"
              autocomplete="new-password"
              placeholder="한 번 더 입력하세요"
            />
          </label>
        </div>
        <p v-if="passwordMismatch" class="field-error">비밀번호가 일치하지 않습니다.</p>

        <div v-if="isSignup" class="field-grid two">
          <label class="form-field">
            <span>성별 <small>선택</small></span>
            <select v-model="sex">
              <option>여성</option>
              <option>남성</option>
              <option>선택 안 함</option>
            </select>
          </label>
          <label class="form-field">
            <span>생년월일 <small>선택</small></span>
            <input v-model="birthDate" type="date" />
          </label>
        </div>
        <div v-if="isSignup" class="privacy-note">
          성별과 생년월일은 연령·성별 기준 판정에만 사용합니다. 주민등록번호는 수집하지 않습니다.
        </div>
        <p v-if="submitted && (!username || !password)" class="field-error">
          필수 항목을 입력해주세요.
        </p>
        <p v-if="submitError" class="field-error">{{ submitError }}</p>

        <button class="button primary wide" type="submit" :disabled="submitting">
          {{ submitting ? '처리 중…' : isSignup ? '가입하고 시작하기' : '로그인' }}
        </button>
      </form>

      <p class="auth-switch">
        {{ isSignup ? '이미 계정이 있으신가요?' : '아직 계정이 없으신가요?' }}
        <RouterLink :to="isSignup ? '/login' : '/signup'">{{
          isSignup ? '로그인' : '회원가입'
        }}</RouterLink>
      </p>
    </section>
  </main>
</template>
