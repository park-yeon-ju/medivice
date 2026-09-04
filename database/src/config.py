"""
작성자 : 박기준
작성목적 : 식약처 공공 API 수집 대상과 엔드포인트를 한 곳에 모아 둔다.
          수집 스크립트는 이 딕셔너리만 보고 돌아가므로, 엔드포인트가 바뀌어도 여기만 고치면 된다.
작성일 : 2026-09-02

주의 : operation(엔드포인트 뒤 경로)과 버전 접미사(02/03 …)는 공공데이터포털이 개편할 때마다 바뀐다.
      반드시 '마이페이지 > 오픈API > 활용신청 상세'의 '참고문서'에 적힌 경로와 대조한 뒤 실행할 것.
      경로가 틀리면 HTTP 200에 resultCode가 '99'인 응답이 오므로, collect 단계에서 이를 잡아 준다.
"""
import os
import socket
import sys
from pathlib import Path

# 한글 Windows 의 PostgreSQL 은 오류 메시지를 CP949 로 보낸다.
# psycopg2 는 UTF-8 로 읽으려다 UnicodeDecodeError 를 내고, 그러면 '진짜 원인'이 가려진다.
# 접속 전에 클라이언트 인코딩을 못박아 두면 대부분 해결된다.
os.environ.setdefault("PGCLIENTENCODING", "UTF8")

BASE_DIR = Path(__file__).resolve().parent.parent
RAW_DIR = BASE_DIR / "data" / "raw"
NORM_DIR = BASE_DIR / "data" / "normalized"

# .env 에서 읽는다. 키를 코드에 직접 적지 않는다(GitHub 커밋 사고 방지).
SERVICE_KEY = os.getenv("DATA_GO_KR_SERVICE_KEY", "")

DB = {
    "host": os.getenv("PGHOST", "localhost"),
    "port": int(os.getenv("PGPORT", 5432)),
    "dbname": os.getenv("PGDATABASE", "medivice_db"),
    "user": os.getenv("PGUSER", "postgres"),
    "password": os.getenv("PGPASSWORD", ""),
}

# -----------------------------------------------------------------------------
# 수집 대상 — 메디바이스 Use-Case에 실제로 쓰이는 것만 고른다.
#
#   공공데이터포털 데이터셋
#     15059486  의약품안전사용서비스(DUR) 품목정보   → 오퍼레이션 6종
#     15095677  의약품 제품 허가정보                → 성분·함량
#     15075057  의약품개요정보(e약은요)             → 효능·주의사항
#
#   urls : 엔드포인트 후보 목록.
#          포털이 개편될 때마다 서비스명·오퍼레이션의 버전 접미사(…03, …06)가 바뀌는데,
#          어느 것이 살아 있는지는 호출해 봐야 안다. 그래서 후보를 나열해 두고
#          src/check_api.py 가 실제로 찔러 본 뒤 살아 있는 것을 data/endpoints.json 에 기록한다.
#          수집 스크립트는 그 파일을 읽으므로, 경로를 손으로 고칠 일이 없다.
#   dur_type : normalize 단계에서 dur_types.code 로 매핑되는 값
#   arity    : PAIR(성분 두 개) / SINGLE(성분 하나) / PERMIT(허가정보) / None(개요정보)
# -----------------------------------------------------------------------------
_B = "https://apis.data.go.kr/1471000"


def _dur(op):
    """DUR 품목정보(15059486) — 서비스명과 오퍼레이션에 같은 버전 접미사가 붙는다."""
    return [f"{_B}/DURPrdlstInfoService{v}/get{op}List{v}" for v in ("03", "02", "")]


def _dur_ingr(op):
    """DUR 성분정보(15056780) — 품목이 아니라 성분 단위 규칙.
       품목정보의 병용금기는 797,186건인데, 같은 규칙이 품목 조합 수만큼 반복된 결과다.
       성분 단위로 받으면 규칙 자체를 훨씬 적은 호출로 전부 확보할 수 있다."""
    return ([f"{_B}/DURIrdntInfoService03/get{op}List02"]
            + [f"{_B}/DURIrdntInfoService{v}/get{op}List{v}" for v in ("03", "02", "")]
            + [f"{_B}/DurIrdntInfoService{v}/get{op}List{v}" for v in ("03", "02", "")]
            + [f"{_B}/DURIrdntInfoService{v}/get{op}List" for v in ("03", "02", "")])


DATASETS = {
    # ① 병용금기 — UC15의 핵심. 두 성분 조합 금기
    "dur_usjnt_taboo": {
        "urls": _dur("UsjntTabooInfo"),
        "dur_type": "USJNT_TABOO", "arity": "PAIR",
        "desc": "DUR 병용금기", "dataset": "15059486",
    },
    # ② 효능군중복 — 같은 계열이 겹칠 때 노랑
    "dur_efcy_dplct": {
        "urls": _dur("EfcyDplctInfo"),
        "dur_type": "EFCY_DPLCT", "arity": "EFFECT",
        "desc": "DUR 효능군중복", "dataset": "15059486",
    },
    # ③ 임부금기 — user_profiles.is_pregnant 와 대조
    "dur_pwnm_taboo": {
        "urls": _dur("PwnmTabooInfo"),
        "dur_type": "PWNM_TABOO", "arity": "SINGLE",
        "desc": "DUR 임부금기", "dataset": "15059486",
    },
    # ④ 특정연령대금기 — users.birth_date 에서 계산한 연령과 대조
    "dur_age_taboo": {
        "urls": _dur("SpcifyAgrdeTabooInfo"),
        "dur_type": "AGE_TABOO", "arity": "SINGLE",
        "desc": "DUR 특정연령대금기", "dataset": "15059486",
    },
    # ⑤ 용량주의 — ingredient_daily_limits 의 원천. 성분 중복 판정의 임계값
    "dur_cpcty_atent": {
        "urls": _dur("CpctyAtentInfo"),
        "dur_type": "CPCTY_ATENT", "arity": "SINGLE",
        "desc": "DUR 용량주의", "dataset": "15059486",
    },
    # ⑥ 노인주의
    "dur_odsn_atent": {
        "urls": _dur("OdsnAtentInfo"),
        "dur_type": "ODSN_ATENT", "arity": "SINGLE",
        "desc": "DUR 노인주의", "dataset": "15059486",
    },
    # ⑥-2 병용금기 (성분 단위) — 별도 활용신청 필요(15056780). 승인돼 있으면 이쪽이 훨씬 낫다.
    #     품목 단위 797,186건 대신 성분 단위 수천 건으로 같은 규칙을 전부 받는다.
    #     승인 전이면 check_api 가 KEY 로 표시하고, collect 는 이 데이터셋을 건너뛴다.
    "dur_ingr_usjnt": {
        "urls": _dur_ingr("UsjntTabooInfo"),
        "dur_type": "USJNT_TABOO", "arity": "PAIR",
        "desc": "DUR 병용금기(성분단위)", "dataset": "15056780", "optional": True,
    },
    # ⑦ 의약품개요정보(e약은요) — UC19 AI 설명의 근거 원문
    "drug_easy_info": {
        "urls": [f"{_B}/DrbEasyDrugInfoService/getDrbEasyDrugList",
                 f"{_B}/DrbEasyDrugInfoService01/getDrbEasyDrugList01",
                 f"{_B}/DrbEasyDrugInfoService02/getDrbEasyDrugList02"],
        "dur_type": None, "arity": None,
        "desc": "의약품개요정보(e약은요)", "dataset": "15075057",
    },
    # ⑧ 의약품 제품 허가정보 — 상세 응답의 MATERIAL_NAME이 성분/함량의 정식 출처다.
    #    이게 없으면 product_ingredients.amount 가 NULL 로 남고, 일일 합산량이 계산되지 않아
    #    해당 약의 용량 판정이 불가능하다. 목록 조회 응답에는 MATERIAL_NAME이 없으므로
    #    목록만 수집한 경우 제품 기본정보만 보강되고, 함량은 별도 상세 수집이 필요하다.
    "drug_permit_info": {
        # 목록 조회(Inq)를 상세 조회(DtlInq)보다 먼저 시도한다.
        # 상세 조회는 품목 단건 조회용이라 totalCount 를 주지 않고, 목록처럼 페이징하면
        # HTTP 400 이 난다(실제로 그렇게 실패했다).
        # 서비스 버전과 오퍼레이션 버전이 서로 다른 경우가 있어 조합을 넓게 시도한다.
        "urls": [f"{_B}/DrugPrdtPrmsnInfoService07/getDrugPrdtPrmsnInq07"]
                + [f"{_B}/DrugPrdtPrmsnInfoService0{sv}/getDrugPrdtPrmsn{op}Inq0{ov}"
                 for sv in (6, 5, 4) for ov in (6, 5, 4) for op in ("Dtl", "")]
                + [f"{_B}/DrugPrdtPrmsnInfoService/getDrugPrdtPrmsnInq"],
        "dur_type": None, "arity": "PERMIT", "optional": True,
        "desc": "의약품 제품 허가정보(목록; 함량은 상세 응답 필요)", "dataset": "15095677",
    },
    # ⑨ 의약품 제품 주성분 상세정보 — 제품별 주성분 코드·분량·단위를 목록으로 제공한다.
    #    제품 상세를 품목마다 호출하는 대신 126,768행을 500건씩 페이지 수집할 수 있다.
    "drug_permit_ingredients": {
        "urls": [f"{_B}/DrugPrdtPrmsnInfoService07/getDrugPrdtMcpnDtlInq07"],
        "dur_type": None, "arity": "PERMIT_INGREDIENT", "optional": True,
        "desc": "의약품 제품 주성분 상세정보(성분·함량)", "dataset": "15095677",
    },
}

# check_api.py 가 살아 있는 엔드포인트를 기록해 두는 파일
ENDPOINTS_FILE = BASE_DIR / "data" / "endpoints.json"


def resolved_url(name):
    """확정된 엔드포인트를 돌려준다. 아직 진단 전이면 첫 번째 후보를 쓴다."""
    import json
    if ENDPOINTS_FILE.exists():
        saved = json.loads(ENDPOINTS_FILE.read_text(encoding="utf-8"))
        v = saved.get(name)
        if isinstance(v, dict):
            return v.get("url") or DATASETS[name]["urls"][0]
        if v:
            return v
    return DATASETS[name]["urls"][0]


def page_size(name):
    """check_api.py 가 잰 서버의 실제 페이지 상한. 모르면 요청값을 그대로 쓴다."""
    import json
    if ENDPOINTS_FILE.exists():
        v = json.loads(ENDPOINTS_FILE.read_text(encoding="utf-8")).get(name)
        if isinstance(v, dict) and v.get("page_size"):
            return v["page_size"]
    return SAFE_PAGE_SIZE


def known_total(name):
    """check_api.py 가 기록해 둔 총건수. 수집 계획을 세우는 데 쓴다."""
    import json
    if ENDPOINTS_FILE.exists():
        v = json.loads(ENDPOINTS_FILE.read_text(encoding="utf-8")).get(name)
        if isinstance(v, dict):
            return v.get("total")
    return None


# -----------------------------------------------------------------------------
# 수집량 제어
# -----------------------------------------------------------------------------
# 개발계정은 보통 일 1,000회 호출 제한이 있다. 병용금기 하나만 797,186건이라
# 100건씩 받으면 7,972회가 필요해 한도를 훨씬 넘는다. 두 가지로 대응한다.
#   ① 페이지를 크게 요청한다. 서버가 잘라서 주면 collect 가 알아서 맞춘다.
#   ② 데이터셋별 수집 상한을 둔다.
#
# 상한을 두어도 설계 검증에는 지장이 없다. 병용금기 797,186건은 '품목 쌍' 기준이고,
# 정규화하면 '성분 쌍' 수천 건으로 접히기 때문이다(그 압축 자체가 이 프로젝트의 근거다).
# 전량이 필요하면 MAX_ROWS 값을 None 으로 바꾸고 며칠에 나눠 받으면 된다.
NUM_OF_ROWS = 1000              # 요청해 볼 최대 페이지 크기
# 큰 값부터 내려가며 실제로 되는 것을 찾는다.
# 허가정보처럼 한 행이 큰 API는 100건에서도 HTTP 400 이 나므로 더 낮은 값까지 시도한다.
PAGE_SIZE_CANDIDATES = (1000, 500, 200, 100, 50, 20, 10)
SAFE_PAGE_SIZE = 100            # 상한을 재지 못했을 때 쓰는 안전값.
                                # 여기서 NUM_OF_ROWS 로 폴백하면 거부당하는 값을 다시 쓰게 된다.
REQUEST_INTERVAL_SEC = 0.3 # 호출 간격 (트래픽 초과 방지)
MAX_RETRY = 3
DAILY_CALL_BUDGET = 1000   # 개발계정 일일 호출 한도 (경고 기준)

# 페이지 상한이 100건인 경우를 기준으로 잡은 값이다(합계 약 750회, 한도 1,000회 이내).
# check_api 가 1000건을 허용한다고 알려 주면 그만큼 여유가 생기므로 값을 올려도 된다.
# 전 구간 계통추출 대상.
#   DUR 병용금기 응답은 DUR_SEQ(규칙 단위) 순으로 정렬되어 있고, 규칙 하나가
#   해당하는 품목 조합 수만큼 폭발한다. 실제로 앞에서 20,000행을 잘라 보니
#   DUR_SEQ 가 7종밖에 안 잡혔다(= 규칙 7개). '머리부터 N건'은 표본이 아니다.
#   그래서 전체 페이지 범위에 걸쳐 일정 간격으로 페이지를 건너뛰며 받는다.
SAMPLE_ACROSS = {"dur_usjnt_taboo"}

MAX_ROWS = {
    "dur_usjnt_taboo":  20000,   # 797,186건 중 일부. 성분 쌍으로 접히므로 이 정도로도 근거가 선다
    "drug_permit_info": None,    # 42,984건, 500건/페이지라 전량도 호출 한도 안에 든다
    "drug_permit_ingredients": None, # 126,768건 전량. 약 254회 호출
    "dur_ingr_usjnt":   None,    # 성분 단위는 전량 받아도 몇 천 건이다
    # 나머지는 전량 (다 합쳐도 4만 건 미만)
}


# -----------------------------------------------------------------------------
# DB 접속 — 실패 원인을 스스로 진단한다
# -----------------------------------------------------------------------------
def server_reachable(timeout=3):
    """서버가 그 포트에서 듣고 있는지만 확인한다(인증 이전 단계)."""
    try:
        with socket.create_connection((DB["host"], DB["port"]), timeout):
            return True
    except OSError:
        return False


def connect_db(dbname=None, quiet=False):
    """psycopg2 접속. 실패하면 원인을 구분해 안내하고 종료한다.

    서버가 보내는 오류 메시지는 한글 Windows 에서 CP949 로 와 디코딩이 깨지는 일이 잦다.
    그래서 메시지에 기대지 않고, 포트가 열려 있는지를 직접 확인해 원인을 가른다.
    """
    import psycopg2
    params = dict(DB, dbname=dbname or DB["dbname"])
    try:
        return psycopg2.connect(**params)
    except (Exception, UnicodeDecodeError) as e:   # noqa: BLE001
        if quiet:
            raise
        print(f"\n접속 실패: {params['user']}@{params['host']}:{params['port']}/{params['dbname']}")
        if isinstance(e, UnicodeDecodeError):
            print("  (서버 오류 메시지가 한글 인코딩이라 그대로 읽을 수 없어 직접 진단합니다)")

        if not server_reachable():
            print("\n  원인: PostgreSQL 서버가 응답하지 않습니다. 포트가 닫혀 있습니다.")
            print("  확인:")
            print("    Get-Service postgresql* | Select Name, Status")
            print("  서비스가 Stopped 이면:")
            print("    Start-Service postgresql-x64-16")
            print("  서비스 자체가 없으면 (미설치):")
            print("    winget install -e --id PostgreSQL.PostgreSQL.16")
            print(f"  포트를 바꿔 설치했다면 .env 의 PGPORT 를 고치세요 (현재 {DB['port']}).")
        else:
            print("\n  원인: 서버는 살아 있습니다. 사용자/비밀번호가 맞지 않습니다.")
            print(f"  현재 설정: PGUSER={DB['user']}, PGPASSWORD={'설정됨' if DB['password'] else '비어 있음'}")
            print("  조치: .env 의 PGPASSWORD 를 PostgreSQL 설치 때 정한 값으로 고치세요.")
            print("        (설치 마법사에서 postgres 계정 비밀번호를 입력했던 그 값입니다)")
        sys.exit(1)
