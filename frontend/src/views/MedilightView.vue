<!--
  MedilightView.vue
  메디라이트 규칙 분석의 상태·근거·성분별 합계를 상세히 보여주는 화면.

  신호등·상태색·텍스트 라벨을 함께 유지하며, 확인 범위 밖의 판단은 의료전문가 확인으로 연결한다.
  관련 UC: UC15, UC16, UC31 / 화면: SCR-ML-001
-->
<script setup>
import { computed, onMounted } from 'vue'
import AppShell from '@/layouts/AppShell.vue'
import SignalLamp from '@/components/SignalLamp.vue'
import { useMediviceStore } from '@/stores/medivice'

const store = useMediviceStore()

const primaryFinding = computed(() => store.medilight.findings[0])
const conflicts = computed(() => store.medilight.conflicts ?? [])
const statusLabel = computed(() => {
  if (store.medilight.status === 'CRIT') return '빨강 · 높은 주의'
  if (store.medilight.status === 'WARN') return '노랑 · 주의'
  return '초록 · 확인된 문제 없음'
})
const meterWidth = computed(() => `${Math.min((primaryFinding.value?.ratio ?? 0) * 100, 100)}%`)

onMounted(() => store.loadDashboard())
</script>

<template>
  <AppShell title="메디라이트 상세">
    <div class="status-page-heading">
      <div class="status-title">
        <SignalLamp :status="store.medilight.status" large />
        <div>
          <span>규칙 기반 분석 결과</span>
          <h2>{{ statusLabel }}</h2>
        </div>
      </div>
      <!-- 규칙 버전과 적용일은 분석 데이터로 유지하고, 사용자가 판단할 상태 헤더에서는 노출하지 않는다. -->
    </div>

    <section v-if="conflicts.length" class="content-section">
      <div class="section-title">
        <div>
          <span class="section-kicker">CONFLICTS</span>
          <h2>충돌 약과 성분</h2>
        </div>
        <span class="chip crit">{{ conflicts.length }}건 · 높은 주의</span>
      </div>
      <article
        v-for="(conflict, index) in conflicts"
        :key="`${conflict.type}-${conflict.medicationA}-${conflict.medicationB}-${index}`"
        class="finding-card"
        :class="conflict.level.toLowerCase()"
      >
        <header>
          <div>
            <span class="chip" :class="conflict.level === 'CRIT' ? 'crit' : 'warn'">
              {{ conflict.type }}
            </span>
            <h2>충돌 약과 성분</h2>
          </div>
          <b>{{ conflict.level === 'CRIT' ? '빨강 · 높은 주의' : '노랑 · 주의' }}</b>
        </header>
        <div class="evidence-list">
          <div>
            <span>{{ conflict.medicationA || '등록 약 정보 확인 필요' }}</span>
            <code>{{ conflict.ingredientA || '성분 정보 확인 필요' }}</code>
          </div>
          <div v-if="conflict.medicationB || conflict.ingredientB">
            <span>{{ conflict.medicationB || '등록 약 정보 확인 필요' }}</span>
            <code>{{ conflict.ingredientB || '성분 정보 확인 필요' }}</code>
          </div>
          <div class="evidence-total">
            <span>확인 근거</span>
            <code>{{
              conflict.detail || '현재 적재된 규칙에서 함께 확인이 필요한 조합입니다.'
            }}</code>
          </div>
        </div>
        <p class="safety-copy">
          출처 식약처 DUR · 확인일 {{ store.medilight.checkedAt }} · 복용을 임의로 변경하지 말고
          의사·약사에게 현재 복용 목록을 보여주세요.
        </p>
      </article>
    </section>

    <section
      v-if="primaryFinding"
      class="finding-card"
      :class="primaryFinding.status.toLowerCase()"
    >
      <header>
        <div>
          <span class="chip" :class="primaryFinding.status === 'CRIT' ? 'crit' : 'warn'">{{
            primaryFinding.reasonCode === 'DUPLICATE'
              ? `중복 ${primaryFinding.sources.length}건`
              : '기준 확인'
          }}</span>
          <h2>{{ primaryFinding.ingredient }}</h2>
        </div>
        <b>{{ primaryFinding.dailyTotal.toLocaleString() }} {{ primaryFinding.unit }} / 일</b>
      </header>
      <div class="formula">하루 성분 섭취량 = 단위당 함량 × 1회 복용 개수 × 하루 복용 횟수</div>
      <div class="evidence-list">
        <div v-for="source in primaryFinding.sources" :key="source.product">
          <span>{{ source.product }}</span
          ><code
            >{{ source.amount.toLocaleString() }} {{ primaryFinding.unit }} × {{ source.dose }} ×
            {{ source.timesPerDay }}회</code
          >
        </div>
        <div class="evidence-total">
          <span>하루 합계</span
          ><code>{{ primaryFinding.dailyTotal.toLocaleString() }} {{ primaryFinding.unit }}</code>
        </div>
      </div>
      <div v-if="primaryFinding.upperLimit" class="intake-meter">
        <div class="meter-track">
          <span :class="primaryFinding.status.toLowerCase()" :style="{ width: meterWidth }"></span
          ><i></i>
        </div>
        <div>
          <span>0 {{ primaryFinding.unit }}</span
          ><b>현재 {{ Math.round(primaryFinding.ratio * 100) }}%</b
          ><span
            >상한 {{ primaryFinding.upperLimit.toLocaleString() }} {{ primaryFinding.unit }}</span
          >
        </div>
      </div>
      <div class="ai-box">
        <span class="chip ai">AI 설명</span>
        <p>
          여러 제품에 같은 성분이 들어 있습니다. 합계는 현재 적용된 기준 안에 있지만, 중복 섭취
          중이라는 점을 확인하고 다음 상담 때 제품 목록을 보여주세요. 처방약이나 복용량을 임의로
          변경하지 마세요.
        </p>
        <small
          >출처 {{ primaryFinding.reference }} · 확인일 {{ store.medilight.checkedAt }} · 진단이나
          복용 중단 권고가 아닙니다.</small
        >
      </div>
    </section>

    <section v-else-if="!conflicts.length" class="empty-state compact-empty">
      <SignalLamp status="OK" large /><b>현재 규칙에서 확인된 문제 없음</b>
      <p>이 결과는 안전을 보장하지 않으며, 현재 적재된 성분·규칙 범위에 한정됩니다.</p>
    </section>

    <section class="content-card">
      <div class="section-title">
        <div>
          <span class="section-kicker">INGREDIENT TOTALS</span>
          <h2>성분별 분석 결과</h2>
        </div>
        <span class="chip neutral">{{ store.medilight.totals.length }}종</span>
      </div>
      <div class="table-wrap">
        <table class="data-table">
          <thead>
            <tr>
              <th>성분</th>
              <th>하루 합계</th>
              <th>적용 기준</th>
              <th>상태</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="total in store.medilight.totals" :key="total.ingredient">
              <td>
                <b>{{ total.ingredient }}</b>
              </td>
              <td class="numeric">{{ total.dailyTotal.toLocaleString() }} {{ total.unit }}</td>
              <td>
                {{
                  total.upperLimit
                    ? `상한 ${total.upperLimit.toLocaleString()} ${total.unit}`
                    : '적재된 기준 없음'
                }}
              </td>
              <td>
                <span
                  class="chip"
                  :class="total.status === 'OK' ? 'ok' : total.status === 'CRIT' ? 'crit' : 'warn'"
                  >{{
                    total.status === 'OK'
                      ? '확인된 문제 없음'
                      : total.status === 'CRIT'
                        ? '높은 주의'
                        : '주의'
                  }}</span
                >
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="safety-copy">
        ‘확인된 문제 없음’은 안전을 보장한다는 뜻이 아니라, 현재 적재된 성분·규칙 범위에서 문제가
        발견되지 않았다는 의미입니다.
      </p>
    </section>
  </AppShell>
</template>
