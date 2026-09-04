"""
작성자 : 박기준
작성목적 : 수집을 돌리기 전에 인증키와 엔드포인트를 진단한다.
          공공데이터포털은 실패해도 HTTP 200을 주는 경우가 많아 원인별로 구분해 알려 주고,
          엔드포인트는 후보를 차례로 찔러 살아 있는 것을 찾아 data/endpoints.json 에 기록한다.
          (포털 개편으로 버전 접미사가 바뀌어도 코드를 손대지 않기 위한 장치)
작성일 : 2026-09-02
실행    : python src/check_api.py
출력    : data/endpoints.json  — collect_dur_api.py 가 이 파일을 읽는다
"""
import json
import sys
import time

import requests
from dotenv import load_dotenv

load_dotenv()
import config  # noqa: E402


def probe(url):
    """한 엔드포인트를 1행만 호출한다. → (판정, 설명, 총건수)
       판정 OK = 사용 가능 / KEY·QUOTA = 계정 문제(경로 탐색 중단) / 그 외 = 이 경로가 아님"""
    params = {"serviceKey": config.SERVICE_KEY, "pageNo": 1, "numOfRows": 1, "type": "json"}
    try:
        res = requests.get(url, params=params, timeout=20)
    except requests.exceptions.Timeout:
        return "TIMEOUT", "응답 없음", None
    except requests.exceptions.RequestException as e:
        return "NETWORK", type(e).__name__, None

    # HTTP 상태를 먼저 본다. 400 응답도 본문이 JSON 이면 통과시켜 버린 적이 있다.
    if res.status_code != 200:
        return "PATH", f"HTTP {res.status_code} — 이 경로/파라미터가 아님", None

    body = res.text.strip()

    if body.startswith("<"):
        if "SERVICE_KEY_IS_NOT_REGISTERED" in body:
            return "KEY", "등록되지 않은 인증키", None
        if "LIMITED_NUMBER_OF_SERVICE_REQUESTS" in body:
            return "QUOTA", "일일 호출 한도 초과", None
        if "SERVICE_ACCESS_DENIED" in body:
            return "KEY", "접근 거부 (활용신청 승인 확인)", None
        if "NODATA" in body or "NO_DATA" in body:
            return "NODATA", "데이터 없음", 0
        if res.status_code == 404 or "HTTP Status 404" in body:
            return "PATH", "404 — 이 경로가 아님", None
        return "PATH", f"XML 에러 (HTTP {res.status_code})", None

    try:
        payload = res.json()
    except ValueError:
        return "PATH", f"JSON 아님 (HTTP {res.status_code})", None

    node = payload.get("response", payload)
    header = node.get("header") or {}
    code = str(header.get("resultCode") or "00")
    if code not in ("00", "0"):
        msg = header.get("resultMsg") or ""
        kind = "KEY" if "KEY" in msg.upper() else "PATH"
        return kind, f"resultCode={code} {msg}".strip(), None

    total = (node.get("body") or {}).get("totalCount")
    return "OK", "정상", total


def resolve(name, spec):
    """후보 URL을 차례로 시도해 살아 있는 것을 찾는다.

    resultCode 가 정상이어도 totalCount 가 없으면 '목록 조회'가 아닐 수 있다.
    (실제로 허가정보의 상세조회 엔드포인트가 그랬다 — 1건은 되지만 페이징하면 HTTP 400)
    그래서 totalCount 가 있는 후보를 우선하고, 없는 것은 최후 수단으로만 쓴다."""
    last = ("PATH", "후보 없음", None)
    fallback = None
    attempts = []
    for i, url in enumerate(spec["urls"], 1):
        status, detail, total = probe(url)
        attempts.append((url, status, detail))
        if status == "OK":
            if total:
                return url, status, detail, total, i
            if fallback is None:
                fallback = (url, "OK", "totalCount 없음 — 목록 조회가 아닐 수 있음", None, i)
        elif status in ("KEY", "QUOTA", "NETWORK", "TIMEOUT"):
            # 계정·네트워크 문제는 경로를 바꿔도 똑같다. 더 시도하지 않는다.
            return None, status, detail, None, i
        else:
            last = (status, detail, total)
        time.sleep(0.2)
    if fallback:
        return fallback
    spec["_attempts"] = attempts      # 실패했을 때 무엇을 시도했는지 보여 주기 위해
    return None, last[0], last[1], None, len(spec["urls"])


def measure_page_size(url):
    """서버가 실제로 받아 주는 numOfRows 를 찾는다.
       큰 값부터 내려가며 '정상 응답 + 실제로 그만큼 왔는지'를 함께 본다.
       resultCode 를 보지 않고 건수만 세면, 거부당한 값을 '가능'으로 잘못 판정하게 된다.
       (실제로 그 실수 때문에 1000건 요청이 resultCode=11 로 튕겼다)"""
    for want in config.PAGE_SIZE_CANDIDATES:
        params = {"serviceKey": config.SERVICE_KEY, "pageNo": 1,
                  "numOfRows": want, "type": "json"}
        try:
            payload = requests.get(url, params=params, timeout=60).json()
        except Exception:      # noqa: BLE001
            continue
        node = payload.get("response", payload)
        code = str((node.get("header") or {}).get("resultCode") or "00")
        if code not in ("00", "0"):
            continue                      # 이 크기는 서버가 거부한다
        items = (node.get("body") or {}).get("items") or []
        if isinstance(items, dict):
            items = items.get("item", [])
        if isinstance(items, dict):
            items = [items]
        if items:
            return len(items)             # 실제로 받은 건수가 곧 상한
        time.sleep(0.2)
    return None


ACTION = {
    "KEY":     "data.go.kr 마이페이지 > 오픈API > 활용신청 현황에서 [승인] 상태인지 확인",
    "QUOTA":   "오늘 호출 한도를 다 썼다. 내일 재시도하거나 운영계정을 신청",
    "PATH":    "활용신청 상세의 '참고문서'에서 실제 경로를 확인해 config.py 의 urls 에 추가",
    "TIMEOUT": "사내망·방화벽에서 apis.data.go.kr 접속이 막혔는지 확인",
    "NETWORK": "인터넷 연결 확인",
    "NODATA":  "경로는 맞으나 데이터가 비어 있다. 다른 후보를 확인",
}


def main():
    if not config.SERVICE_KEY:
        sys.exit("DATA_GO_KR_SERVICE_KEY 가 비어 있습니다. .env 파일을 확인하세요.")

    key = config.SERVICE_KEY
    print(f"인증키 {key[:8]}…{key[-4:]} (길이 {len(key)})")
    print("엔드포인트 후보를 차례로 확인합니다. 잠시 걸립니다.\n")
    print(f"{'데이터셋':<26} {'셋ID':<10} {'상태':<8} 설명")
    print("-" * 92)

    resolved, fails = {}, []
    for name, spec in config.DATASETS.items():
        url, status, detail, total, tried = resolve(name, spec)
        if url:
            page_size = measure_page_size(url)
            resolved[name] = {"url": url, "total": total, "page_size": page_size}
            note = f"총 {total:,}건" if isinstance(total, int) else detail
            ps = f", 페이지 {page_size}건" if page_size else ""
            extra = "" if tried == 1 else f"  (후보 {tried}번째)"
            print(f"{spec['desc']:<26} {spec['dataset']:<10} {'OK':<8} {note}{ps}{extra}")
        else:
            fails.append((spec, status, detail))
            print(f"{spec['desc']:<26} {spec['dataset']:<10} {status:<8} {detail}")
        time.sleep(config.REQUEST_INTERVAL_SEC)

    print("-" * 92)

    if resolved:
        config.ENDPOINTS_FILE.parent.mkdir(parents=True, exist_ok=True)
        config.ENDPOINTS_FILE.write_text(
            json.dumps(resolved, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\n확정된 엔드포인트 {len(resolved)}개를 {config.ENDPOINTS_FILE.name} 에 기록했습니다.")
        sizes = {v.get("page_size") for v in resolved.values() if v.get("page_size")}
        if sizes:
            print(f"  · 서버 페이지 상한 실측값: {'/'.join(str(x) for x in sorted(sizes))}건 "
                  f"— 수집 호출 수 계산에 반영됩니다.")
        if any(not v.get("page_size") for v in resolved.values()):
            print(f"  · 상한을 재지 못한 데이터셋은 안전값 {config.SAFE_PAGE_SIZE}건으로 계산합니다.")

    if not fails:
        print("\n전부 정상입니다. 이어서 실행하세요:")
        print("  python src/collect_dur_api.py")
        print("  (수집 계획과 예상 호출 수를 먼저 보여 준 뒤 진행합니다)")
        return 0

    required = [f for f in fails if not f[0].get("optional")]
    optional = [f for f in fails if f[0].get("optional")]
    if optional:
        print("\n[선택 항목 — 없어도 파이프라인은 돕니다]")
        for spec, status, detail in optional:
            print(f"  · {spec['desc']} ({status}) — {detail}")
            print(f"      → https://www.data.go.kr/data/{spec['dataset']}/openapi.do "
                  f"의 '활용신청 상세 > 참고문서'에서 실제 경로 확인")
            for u, st, dt in spec.get("_attempts", []):
                print(f"         [{st:<7}] {u.split('/1471000/')[-1]}  {dt}")
    if not required:
        print("\n필수 항목은 전부 정상입니다. 이어서 실행하세요:")
        print("  python src/collect_dur_api.py")
        return 0

    print(f"\n{len(required)}개 실패:")
    seen = set()
    for spec, status, detail in required:
        print(f"  · {spec['desc']} ({status}) — {detail}")
        for u, st, dt in spec.get("_attempts", []):
            print(f"      [{st:<7}] {u.split('/1471000/')[-1]}  {dt}")
        if status not in seen:
            print(f"      → {ACTION.get(status, '원인 확인 필요')}")
            seen.add(status)
    if any(s == "PATH" for _, s, _ in required):
        print("\n  경로 오류는 아래에서 실제 URL을 확인할 수 있습니다:")
        for spec, status, _ in fails:
            if status == "PATH":
                print(f"    https://www.data.go.kr/data/{spec['dataset']}/openapi.do")
    return 1


if __name__ == "__main__":
    sys.exit(main())
