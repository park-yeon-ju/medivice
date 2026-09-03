# 메디바이스 (MediVice)

성분 중심 복약 안전 도우미. 영양제·상비약·처방약을 한 목록에 모으고, 성분 중복과 하루 섭취량을 **메디라이트**(초록·노랑·빨강)로 보여줍니다.

> SKALA Full-Stack Engineering — AI 웹 서비스 설계 Mini-project

```
복용 항목 등록 → 성분 표준화 → 규칙 기반 합산·중복 판정 → 메디라이트 표시
```

계산과 판정은 **규칙 엔진**이 결정론적으로 수행하고, AI는 이미 정해진 결과를 쉬운 말로 설명하는 역할만 맡습니다. 그래서 AI를 Mock으로 대체해도 핵심 흐름이 동작합니다.

---

## ⛔ 커밋하면 안 되는 것

| 대상 | 이유 |
| --- | --- |
| **강의 자료 PDF · 녹취록** | **대외비.** SK㈜ AX 저작물로 재배포 금지 |
| **API 키 · 비밀번호 · DB 접속정보** | `.env`에 두고 `.env.example`만 올릴 것 |
| **설계 문서** (기획서·화면 시안·스프린트 계획) | 이 레포에 두지 않음. 팀 내부에서 별도 공유 |

`.gitignore`가 위 항목을 막고 있습니다. **`.gitignore`를 지우거나 `git add -f`로 강제 추가하지 마세요.**

푸시 전에 무엇이 올라가는지 항상 확인하세요.

```bash
git status          # 올라갈 파일 확인
git diff --staged   # 내용 확인
```

---

## 협업 규칙 요약

전체 규칙은 **[`CONTRIBUTING.md`](CONTRIBUTING.md)**에 있습니다. 작업 전에 한 번 읽어주세요.
AI 에이전트를 쓸 때는 [`CLAUDE.md`](CLAUDE.md)를 세션에 읽히세요 (Claude Code는 자동).

### 브랜치

영역별 브랜치가 **이미 만들어져 있습니다.** 새로 만들지 말고 자기 영역에서 작업하세요.

```
main         항상 데모 가능한 상태 — 발표 때 이걸 띄움
├─ frontend  FE
├─ backend   BE
├─ db        DA
├─ docs      PM · 기획
└─ infra     DevOps
```

> ⚠️ **하루에 최소 한 번은 `main`으로 머지합니다.** 미루면 마지막 날 다섯 갈래가 한꺼번에 충돌합니다. 작업이 다 끝나지 않았어도 돌아가는 상태면 올리세요.

### 작업 흐름

```bash
git checkout main && git pull        # 1. 최신 받기
git checkout frontend && git merge main   # 2. 내 브랜치로 이동 + 최신화
#    ... 작업 ...
git add .                            # 3. 올릴 파일 담기
git status                           #    무엇이 올라가는지 확인
git commit -m "feat(frontend): 메디라이트 배너 추가"
git push                             # 4. 올리기
#    5. GitHub에서 PR 생성 → main으로 머지
```

`main`에 직접 커밋하지 않습니다. 머지 후 **`Delete branch` 버튼을 누르지 마세요** — 영역 브랜치는 계속 씁니다.

### 커밋 메시지

```
type(scope): 설명
```

- **type**: `feat` `fix` `refactor` `chore` `docs` `test` `style`
- **scope**: `frontend` `backend` `db` `docs` `infra` — 필수

```
feat(backend): 성분별 하루 총량 합산 API 추가
fix(frontend): 다크모드에서 상태 칩 대비 부족 수정
chore(db): 제품·성분 시드 데이터 10건 추가
```

**여러 영역을 한 커밋에 섞지 않습니다.** 프론트와 백엔드를 같이 고쳤으면 커밋을 나눕니다.

### PR

| 변경 | 절차 |
| --- | --- |
| 자기 소유 경로 안에서 끝남 | 셀프 머지 OK |
| **API 계약** 변경 (경로·요청·응답 필드) | 제목에 `[계약 변경]` + FE·BE 확인 |
| **엔티티/스키마** 변경 | 제목에 `[DB 변경]` + DA·BE 합의 |
| `docker-compose.yml`·포트·환경변수 | 팀 전체 공지 |

### 경로 소유권

자기 경로 안은 자유롭게, **남의 경로는 담당자에게 요청**합니다.

| 경로 | 소유 |
| --- | --- |
| `frontend/src/**` | FE |
| `backend/src/**/entity/**` | DA (BE와 공동) — ⚠️ 고치면 DB 스키마가 바뀜 |
| `backend/src/**` (entity 제외) | BE |
| `backend/src/**/ai/**` | API Architect |
| `README.md`, `CONTRIBUTING.md`, `docker-compose.yml` | PM / DevOps |

---

## 스프린트 범위

| | 범위 | 이번 과제 |
| --- | --- | --- |
| **Sprint 1** | 성분 코어 — 등록·합산·중복 판정·메디라이트 | 실제 구현 |
| **Sprint 2** | 촬영 등록·쉬운 설명·증상 기록·보고서 | 명세 + Mock |
| **Sprint 3** | 실제 모델 연동·공공 데이터·다국어·가족 계정 | 설계만 |

Sprint 1은 **AI 없이 완결**됩니다. AI가 필요한 기능은 전부 Sprint 2 이후에 있습니다.

## 기술 스택

| 영역 | 선택 | 포트 |
| --- | --- | --- |
| Frontend | Vue 3 (Vite) | `5173` |
| Backend | Java / Spring Boot | `8080` |
| Database | PostgreSQL | `5432` |
| AI | Mock API — 인터페이스 뒤에서 실제 모델로 교체 가능 | — |

## 폴더 구조

```
.
├── frontend/   Vue 프로젝트
└── backend/    Spring Boot 프로젝트
```

## 면책

교육용 프로젝트입니다. 의료 정보는 학습용 샘플이며 실제 진료·처방을 대신하지 않습니다. 서비스는 진단하지 않고, 증상과 약의 인과관계를 판정하지 않으며, 처방약의 중단이나 용량 변경을 지시하지 않습니다.
