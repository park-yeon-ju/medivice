<!--
  AuthView.vue
  로그인과 회원가입 입력을 검증하고, 성공 시 메인 또는 첫 설문·문진 화면으로 이동한다.

  개발용 인증 정보는 화면에 노출하지 않고 회원가입 뒤 문진 흐름을 반드시 거치게 한다.
  관련 UC: UC1, UC2 / 화면: SCR-AUTH-001, SCR-AUTH-002
-->
<script setup>
import { computed, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
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
// 성별은 판정 기준에 쓰이는 필수값이므로 기본 선택 없이 사용자가 직접 고르게 한다.
const sex = ref('')
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
  if (
    !username.value ||
    !password.value ||
    passwordMismatch.value ||
    (isSignup.value && !sex.value)
  )
    return
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
      <div class="auth-brand-heading">
        <BrandLockup />
        <div class="auth-heading">
          <h1>{{ isSignup ? '회원가입' : '다시 만나서 반가워요' }}</h1>
          <p>
            {{
              isSignup
                ? '가입을 마치면 자동으로 로그인됩니다.'
                : '복용 목록과 메디라이트를 확인하세요.'
            }}
          </p>
        </div>
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
            <span>성별 <b aria-hidden="true">*</b></span>
            <select v-model="sex" required>
              <option disabled value="">성별을 선택하세요</option>
              <option>여성</option>
              <option>남성</option>
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
        <p v-if="submitted && (!username || !password || (isSignup && !sex))" class="field-error">
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
