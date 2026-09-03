"""
작성자 : 박기준
작성목적 : 식약처 공공 API를 페이지 단위로 호출해 원본 JSON을 그대로 저장한다.
          이 단계에서는 절대 가공하지 않는다 — 원본 보존이 재현성의 전제다.
작성일 : 2026-09-02
실행    : python src/collect_dur_api.py                 (전체)
          python src/collect_dur_api.py dur_usjnt_taboo (일부)
          python src/collect_dur_api.py --yes            (확인 없이 진행)
전제    : python src/check_api.py 를 먼저 실행해 data/endpoints.json 이 있어야 한다.
출력    : data/raw/<dataset>/page_0001.json ...

호출 한도에 대하여
  개발계정은 보통 일 1,000회 제한이다. 병용금기만 797,186건이라 100건씩 받으면
  7,972회가 필요하다. 그래서 ① 페이지를 크게 요청하고(서버가 자르면 자동 조정)
  ② config.MAX_ROWS 로 데이터셋별 상한을 둔다.
  실행 전에 예상 호출 수를 먼저 보여 주고, 한도를 넘으면 경고한다.
"""
import json
import sys
import time
from datetime import datetime

import requests
from dotenv import load_dotenv

load_dotenv()
import config  # noqa: E402  (.env 로드 후에 읽어야 SERVICE_KEY가 채워진다)


def extract_items(payload: dict) -> list:
    """
    공공데이터포털 응답은 기관마다 감싸는 깊이가 다르다.
      A) {"header":..., "body":{"items":[...]}}
      B) {"response":{"header":..., "body":{"items":{"item":[...]}}}}
    둘 다 흡수한다. 단일 건일 때 items가 dict로 오는 경우도 리스트로 맞춘다.
    """
    node = payload.get("response", payload)
    body = node.get("body") or {}
    items = body.get("items")
    if items is None:
        return []
    if isinstance(items, dict):
        items = items.get("item", [])
    if isinstance(items, dict):
        items = [items]
    return items or []


def total_count(payload: dict) -> int:
    node = payload.get("response", payload)
    try:
        return int((node.get("body") or {}).get("totalCount") or 0)
    except (TypeError, ValueError):
        return 0


def result_code(payload: dict) -> str:
    node = payload.get("response", payload)
    return str((node.get("header") or {}).get("resultCode") or "00")


def result_msg(payload: dict) -> str:
    node = payload.get("response", payload)
    return str((node.get("header") or {}).get("resultMsg") or "")


# data.go.kr 표준 오류 코드 — 원인을 바로 알 수 있게 풀어 둔다
CODE_MEANING = {
    "10": "잘못된 요청 파라미터 (numOfRows 등 값이 허용 범위를 벗어남)",
    "11": "필수 요청 파라미터 없음 — 페이지 크기가 허용치를 넘으면 이 코드가 오기도 한다",
    "12": "폐기되었거나 존재하지 않는 서비스",
    "20": "서비스 접근 거부 — 활용신청 승인 상태 확인",
    "22": "일일 호출 한도 초과",
    "30": "등록되지 않은 인증키",
    "31": "활용기간 만료",
    "99": "기타 오류 — 엔드포인트 경로 확인",
}


def fetch_page(url: str, page: int, rows: int) -> dict:
    params = {
        "serviceKey": config.SERVICE_KEY,   # requests가 알아서 인코딩한다
        "pageNo": page,
        "numOfRows": rows,
        "type": "json",
    }
    last_err = None
    for attempt in range(1, config.MAX_RETRY + 1):
        try:
            res = requests.get(url, params=params, timeout=60)
            res.raise_for_status()
            try:
                return res.json()
            except ValueError:
                raise RuntimeError(f"JSON이 아닌 응답:\n{res.text[:300]}")
        except Exception as e:      # noqa: BLE001
            last_err = e
            time.sleep(1.5 * attempt)
    raise RuntimeError(f"{url} page={page} 실패: {last_err}")


def collect(name: str, resume: bool = False) -> int:
    spec = config.DATASETS[name]
    url = config.resolved_url(name)          # check_api.py 가 확정해 둔 엔드포인트
    limit = config.MAX_ROWS.get(name)        # None = 전량
    out_dir = config.RAW_DIR / name
    out_dir.mkdir(parents=True, exist_ok=True)

    rows = config.page_size(name)     # check_api.py 가 실측한 서버 상한

    # 전 구간 계통추출: 앞에서부터 자르면 규칙 몇 개만 잡히므로 페이지를 건너뛰며 받는다.
    stride = 1
    if name in config.SAMPLE_ACROSS and limit:
        total_known = config.known_total(name) or 0
        if total_known > limit:
            total_pages = -(-total_known // rows)
            want_pages = max(1, -(-limit // rows))
            stride = max(1, total_pages // want_pages)
            print(f"    · 전 구간 계통추출: 전체 {total_pages:,}페이지를 {stride}페이지 간격으로 "
                  f"{want_pages}회 수집합니다")

    page, saved, fetched = 1, 0, 0
    while True:
        # --resume : 이미 받아 둔 페이지는 건너뛴다. 한도에 걸려 중단됐을 때 호출을 아낀다.
        existing = out_dir / f"page_{page:04d}.json"
        if resume and existing.exists():   # 파일명은 실제 pageNo 를 그대로 쓴다
            try:
                cached = json.loads(existing.read_text(encoding="utf-8"))
                got = len(extract_items(cached))
                saved += got
                if got < rows:
                    break
                page += 1
                continue
            except Exception:      # noqa: BLE001
                pass

        payload = fetch_page(url, page, rows)
        code = result_code(payload)

        # 파라미터 오류는 페이지 크기가 원인인 경우가 많다. 한 단계 낮춰 다시 시도한다.
        if code in ("10", "11") and rows > config.SAFE_PAGE_SIZE:
            smaller = next((c for c in config.PAGE_SIZE_CANDIDATES if c < rows),
                           config.SAFE_PAGE_SIZE)
            print(f"    · 페이지 {rows}건이 거부되었습니다(resultCode={code}). "
                  f"{smaller}건으로 낮춰 재시도합니다")
            rows = smaller
            continue

        if code not in ("00", "0"):
            hint = CODE_MEANING.get(code, "원인 불명")
            raw = out_dir / f"_error_page_{page:04d}.json"
            raw.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
            raise RuntimeError(
                f"[{name}] resultCode={code} — {hint}\n"
                f"  메시지: {result_msg(payload)}\n"
                f"  요청: numOfRows={rows}, pageNo={page}\n"
                f"  응답 원문: {raw}")

        items = extract_items(payload)
        if not items:
            break

        (out_dir / f"page_{page:04d}.json").write_text(
            json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8"
        )
        saved += len(items)
        fetched += 1
        total = total_count(payload)

        # 서버가 numOfRows 를 잘라서 응답했는지 첫 페이지에서 확인하고 맞춘다.
        # (식약처 API는 서비스마다 상한이 100인 것과 1000인 것이 섞여 있다)
        if page == 1 and len(items) < rows and total > len(items):
            rows = len(items)
            print(f"    · 서버가 페이지 크기를 {rows}건으로 제한합니다")

        shown = f"{saved:,}" + (f" / {total:,}" if total else "")
        print(f"    page {page:>4}  {shown}")

        if limit and saved >= limit:
            print(f"    · 수집 상한 {limit:,}건에 도달해 중단합니다 (config.MAX_ROWS)")
            break
        if total and saved >= total:
            break
        if len(items) < rows:            # totalCount 를 안 주는 API의 종료 조건
            break
        page += stride
        if total and (page - 1) * rows >= total:
            break
        time.sleep(config.REQUEST_INTERVAL_SEC)
    if stride > 1:
        print(f"    · {fetched}개 페이지를 {stride} 간격으로 수집했습니다 (전 구간 분포)")
    return saved


def plan(targets):
    """수집 계획과 예상 호출 수를 보여 준다."""
    print(f"{'데이터셋':<26} {'전체':>12} {'수집':>12} {'예상 호출':>10}")
    print("-" * 66)
    est_total = 0
    for name in targets:
        spec = config.DATASETS[name]
        total = config.known_total(name)
        limit = config.MAX_ROWS.get(name)
        take = min(total, limit) if (total and limit) else (limit or total)
        rows = config.page_size(name)
        calls = -(-take // rows) if take else 1
        est_total += calls
        print(f"{spec['desc']:<26} {total or '?':>12} {take or '전량':>12} {calls:>10}")
    print("-" * 66)
    print(f"{'합계':<26} {'':>12} {'':>12} {est_total:>10}")
    sizes = {config.page_size(n) for n in targets}
    print(f"\n서버 실측 페이지 크기 {'/'.join(str(x) for x in sorted(sizes))}건 기준입니다.")
    if est_total > config.DAILY_CALL_BUDGET:
        print(f"! 일일 한도({config.DAILY_CALL_BUDGET}회)를 넘습니다. "
              f"config.MAX_ROWS 값을 줄이세요.")
    else:
        print(f"일일 한도({config.DAILY_CALL_BUDGET}회) 안에 들어옵니다.")
    if est_total > config.DAILY_CALL_BUDGET:
        cut = max(1000, int(config.MAX_ROWS.get("dur_usjnt_taboo", 50000)
                            * config.DAILY_CALL_BUDGET / est_total / 1000) * 1000)
        print(f"  → config.py 의 MAX_ROWS['dur_usjnt_taboo'] 를 {cut:,} 정도로 낮추면 들어옵니다.")
    print()
    return est_total


def main() -> None:
    if not config.SERVICE_KEY:
        sys.exit("DATA_GO_KR_SERVICE_KEY 가 비어 있습니다. .env 를 확인하세요.")
    if not config.ENDPOINTS_FILE.exists():
        sys.exit("! data/endpoints.json 이 없습니다. 먼저 진단을 돌리세요:\n"
                 "    python src/check_api.py")

    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    auto = "--yes" in sys.argv
    resume = "--resume" in sys.argv
    targets = args or list(config.DATASETS)

    print("[수집 계획]")
    plan(targets)

    if not auto:
        try:
            if input("진행할까요? [y/N] ").strip().lower() not in ("y", "yes"):
                print("중단했습니다.")
                return
        except EOFError:
            pass
    print()

    import json as _json
    ready = set(_json.loads(config.ENDPOINTS_FILE.read_text(encoding="utf-8")))
    for name in targets:
        spec = config.DATASETS[name]
        if name not in ready:
            mark = "선택 항목" if spec.get("optional") else "!"
            print(f"[건너뜀] {spec['desc']} — 사용 가능한 엔드포인트가 없습니다 ({mark})")
            if spec.get("optional"):
                print(f"          쓰려면 https://www.data.go.kr/data/{spec['dataset']}/openapi.do "
                      f"에서 활용신청 후 check_api 를 다시 돌리세요\n")
            continue
        print(f"[수집] {spec['desc']}")
        print(f"  → {collect(name, resume):,}건\n")

    # 실제 공공 API에서 받은 데이터임을 기록한다 (표본과 구분하기 위해)
    config.RAW_DIR.mkdir(parents=True, exist_ok=True)
    (config.RAW_DIR / "_SOURCE.txt").write_text(
        f"REAL\n수집일시: {datetime.now():%Y-%m-%d %H:%M}\n"
        f"수집상한: {config.MAX_ROWS}\n", encoding="utf-8")
    print("완료. 이어서:  python src/normalize.py")


if __name__ == "__main__":
    main()
