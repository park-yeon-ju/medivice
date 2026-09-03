"""
작성자 : 박기준
작성목적 : 수집한 원본 JSON(플랫한 한 장짜리 표)을 1NF → 2NF → 3NF 순으로 분해해
          테이블별 CSV로 떨어뜨린다. 정규화의 '근거'가 코드에 그대로 보이도록 단계를 나눠 적었다.
작성일 : 2026-09-02
실행    : python src/normalize.py
출력    : data/normalized/*.csv  (자연키 기준. 대체키(id)는 적재 단계에서 부여한다)

원본 응답 한 행의 문제 (DUR 병용금기 기준)
  ITEM_SEQ | ITEM_NAME | ENTP_NAME | INGR_CODE | INGR_KOR_NAME | MIXTURE_INGR_CODE | ... | PROHBT_CONTENT
  ① 같은 품목이 금기 쌍 개수만큼 반복 → 제품명·업체명 중복 (갱신 이상)
  ② INGR_CODE → INGR_KOR_NAME 이행 종속 → 3NF 위반
  ③ 복합제의 성분이 한 칸에 묶여 옴 → 1NF 위반, 성분 단위 합산 불가
  ④ (A,B)와 (B,A)가 모두 내려옴 → 쌍 규칙 100% 중복
"""
import csv
import hashlib
import json
import re
from collections import OrderedDict
from pathlib import Path

import config

# -----------------------------------------------------------------------------
# 공통 유틸
# -----------------------------------------------------------------------------
def load_raw(name: str):
    """data/raw/<name>/*.json 을 모두 읽어 item 리스트로 펼친다."""
    d = config.RAW_DIR / name
    if not d.exists():
        return []
    rows = []
    for f in sorted(d.glob("page_*.json")):
        payload = json.loads(f.read_text(encoding="utf-8"))
        node = payload.get("response", payload)
        items = (node.get("body") or {}).get("items") or []
        if isinstance(items, dict):
            items = items.get("item", [])
        if isinstance(items, dict):
            items = [items]
        rows.extend(items)
    return rows


def g(row: dict, *keys):
    """API가 대소문자·표기를 섞어 쓰므로 후보 키를 순서대로 찾는다."""
    for k in keys:
        for cand in (k, k.upper(), k.lower()):
            v = row.get(cand)
            if v not in (None, "", "null"):
                return str(v).strip()
    return None


# 성분명 키가 데이터셋마다 다르다.
#   병용금기 : INGR_KOR_NAME / MIXTURE_INGR_KOR_NAME
#   그 외 DUR: INGR_NAME
# 이걸 놓치면 국문명 자리에 성분코드가 그대로 들어간다(실제로 그렇게 깨졌었다).
NAME_KEYS = ("INGR_KOR_NAME", "INGR_NAME")
MIX_NAME_KEYS = ("MIXTURE_INGR_KOR_NAME", "MIXTURE_INGR_NAME")

AMOUNT_RE = re.compile(r"([\d,]+(?:\.\d+)?)\s*(mg|mcg|㎎|㎍|g|IU|iu|mL|ml|밀리그램)")
AGE_RE = re.compile(r"(?:만\s*)?(\d+)\s*(세|개월)\s*(미만|이상|이하|초과)?")


def parse_amount(text):
    """'아세트아미노펜 500mg' → (500.0, 'mg'). 못 읽으면 (None, None)."""
    if not text:
        return None, None
    m = AMOUNT_RE.search(text)
    if not m:
        return None, None
    unit = {"㎎": "mg", "㎍": "mcg", "밀리그램": "mg", "ml": "mL", "iu": "IU"}.get(m.group(2), m.group(2))
    return float(m.group(1).replace(",", "")), unit


def parse_date(text):
    """'20180131' → '2018-01-31'"""
    if not text:
        return None
    t = re.sub(r"\D", "", text)
    return f"{t[:4]}-{t[4:6]}-{t[6:8]}" if len(t) == 8 else None


# -----------------------------------------------------------------------------
# 제품 허가정보(15095677)의 MATERIAL_NAME 파서
# -----------------------------------------------------------------------------
# 원문은 성분 하나가 '|' 로 구분된 필드 묶음이고, 성분끼리는 ';' 로 이어진다.
#   총량 : 1정 중-|성분명 : 아세트아미노펜|분량 : 500|단위 : 밀리그램|규격 : KP|비고 : ;
# 복합제는 이 묶음이 여러 개 붙어 온다 — 전형적인 1NF 위반이고, 여기서 행으로 쪼갠다.
UNIT_MAP = {
    "밀리그램": "mg", "마이크로그램": "mcg", "그램": "g", "킬로그램": "kg",
    "밀리리터": "mL", "리터": "L", "국제단위": "IU", "마이크로리터": "uL",
    "㎎": "mg", "㎍": "mcg", "㎖": "mL",
}


def normalize_ingr_name(name):
    """성분명 대조용 정규화 — 공백·괄호·구두점을 걷어낸다."""
    if not name:
        return ""
    return re.sub(r"[\s()（）\[\],.·/'\"-]", "", name).lower()


def local_ingr_code(name):
    """DUR 성분코드로 매칭되지 않는 성분에 부여하는 내부 코드.
       코드가 없다는 것은 곧 '판정 근거가 없다'는 뜻이고, 커버리지 뷰가 이를 드러낸다."""
    h = hashlib.md5(normalize_ingr_name(name).encode("utf-8")).hexdigest()[:12]
    return f"LOCAL_{h}"


def parse_material_name(text):
    """MATERIAL_NAME 원문 → [{name, amount, unit}, ...]"""
    out = []
    if not text:
        return out
    for chunk in text.split(";"):
        if not chunk.strip():
            continue
        fields = {}
        for part in chunk.split("|"):
            if ":" not in part:
                continue
            k, _, v = part.partition(":")
            fields[k.strip()] = v.strip()
        name = fields.get("성분명")
        if not name:
            continue
        raw_unit = fields.get("단위") or ""
        qty = re.sub(r"[^\d.]", "", fields.get("분량") or "")
        try:
            amount = float(qty) if qty else None
        except ValueError:
            amount = None
        out.append({"name": name, "amount": amount,
                    "unit": UNIT_MAP.get(raw_unit, raw_unit or None)})
    return out


ETC_OTC_MAP = {"전문의약품": "ETC", "일반의약품": "OTC"}


# -----------------------------------------------------------------------------
# 1단계 : 1NF — 한 칸에 하나의 값. 복합제 성분·금기 상대를 각각의 행으로 쪼갠다.
# 2단계 : 2NF — 서로 다른 주체(성분 / 제조사 / 품목 / 규칙)를 각각의 딕셔너리로 모은다.
# 3단계 : 3NF — 코드 → 이름 이행 종속을 코드 테이블로 빼고, 사실 테이블에는 코드만 남긴다.
# -----------------------------------------------------------------------------
class Normalizer:
    def __init__(self):
        self.ingredients = OrderedDict()   # ingr_code -> {name_ko, name_en}
        self.manufacturers = OrderedDict() # name -> None
        self.products = OrderedDict()      # item_seq -> {...}
        self.product_ingredients = OrderedDict()  # (item_seq, ingr_code) -> {...}
        self.product_infos = OrderedDict()
        self.single_rules = OrderedDict()  # (dur_type, ingr_code, cmin, cmax) -> {...}
        self.pair_rules = OrderedDict()    # (dur_type, a, b) -> {...}
        self.daily_limits = OrderedDict()  # ingr_code -> {...}
        self.effect_groups = OrderedDict()  # effect_name -> series_name
        self.ingr_effects = OrderedDict()   # (ingr_code, effect_name) -> None
        self.name_index = {}               # 정규화한 성분명 -> ingr_code (허가정보 대조용)
        self.match_hit = 0                 # DUR 성분코드로 매칭된 건수
        self.match_miss = 0                # 매칭 실패 → LOCAL_ 코드 부여 (판정 근거 없음)
        self.stats = []

    # --- 2NF: 개체별 수집기 ---------------------------------------------------
    def put_ingredient(self, code, ko, en=None):
        if not code:
            return None
        cur = self.ingredients.setdefault(code, {"name_ko": ko or code, "name_en": None})
        if ko and not cur["name_ko"]:
            cur["name_ko"] = ko
        if en and not cur["name_en"]:
            cur["name_en"] = en
        if ko:
            self.name_index.setdefault(normalize_ingr_name(ko), code)
        return code

    def put_product(self, item_seq, name, entp, chart=None, ptype="ETC", image=None):
        if not item_seq:
            return None
        if entp:
            self.manufacturers.setdefault(entp, None)
        cur = self.products.setdefault(item_seq, {
            "name_ko": name, "manufacturer_name": entp,
            "product_type": ptype, "chart": chart, "image_url": image})
        for k, v in (("name_ko", name), ("manufacturer_name", entp),
                     ("chart", chart), ("image_url", image)):
            if v and not cur.get(k):
                cur[k] = v
        return item_seq

    def link_product_ingredient(self, item_seq, ingr_code, amount=None, unit=None):
        if not (item_seq and ingr_code):
            return
        key = (item_seq, ingr_code)
        cur = self.product_ingredients.setdefault(key, {"amount": amount, "unit": unit})
        if amount and cur["amount"] is None:
            cur.update(amount=amount, unit=unit)

    # --- DUR 규칙 -------------------------------------------------------------
    def add_pair_rule(self, dur_type, a, b, content, ndate):
        """④ 해결 : (A,B)와 (B,A)를 정렬해 한 쌍이 한 행만 갖게 만든다."""
        if not (a and b) or a == b:
            return
        lo, hi = sorted([a, b])
        key = (dur_type, lo, hi)
        if key not in self.pair_rules:
            self.pair_rules[key] = {"prohibit_content": content, "notification_date": ndate}

    def add_single_rule(self, dur_type, ingr, content, cmin, cmax, cunit, ndate):
        if not ingr:
            return
        key = (dur_type, ingr, cmin, cmax)
        if key not in self.single_rules:
            self.single_rules[key] = {"prohibit_content": content,
                                      "condition_unit": cunit, "notification_date": ndate}

    # --- 데이터셋별 파서 ------------------------------------------------------
    def parse_pair_dataset(self, name, spec):
        rows = load_raw(name)
        for r in rows:
            # ① 1NF: 한 행에 좌(ITEM/INGR)와 우(MIXTURE_*)가 붙어 있다 → 각각 개체로 분리
            a = self.put_ingredient(g(r, "INGR_CODE"), g(r, *NAME_KEYS), g(r, "INGR_ENG_NAME"))
            b = self.put_ingredient(g(r, "MIXTURE_INGR_CODE"), g(r, *MIX_NAME_KEYS),
                                    g(r, "MIXTURE_INGR_ENG_NAME"))
            amt, unit = parse_amount(g(r, "ITEM_NAME"))
            pa = self.put_product(g(r, "ITEM_SEQ"), g(r, "ITEM_NAME"), g(r, "ENTP_NAME"), g(r, "CHART"))
            pb = self.put_product(g(r, "MIXTURE_ITEM_SEQ"), g(r, "MIXTURE_ITEM_NAME"),
                                  g(r, "MIXTURE_ENTP_NAME"), g(r, "MIXTURE_CHART"))
            self.link_product_ingredient(pa, a, amt, unit)
            self.link_product_ingredient(pb, b, *parse_amount(g(r, "MIXTURE_ITEM_NAME")))
            self.add_pair_rule(spec["dur_type"], a, b,
                               g(r, "PROHBT_CONTENT"), parse_date(g(r, "NOTIFICATION_DATE")))
        self.stats.append((spec["desc"], len(rows), None))

    def parse_single_dataset(self, name, spec):
        rows = load_raw(name)
        for r in rows:
            ingr = self.put_ingredient(g(r, "INGR_CODE"), g(r, *NAME_KEYS), g(r, "INGR_ENG_NAME"))
            amt, unit = parse_amount(g(r, "ITEM_NAME"))
            p = self.put_product(g(r, "ITEM_SEQ"), g(r, "ITEM_NAME"), g(r, "ENTP_NAME"), g(r, "CHART"))
            self.link_product_ingredient(p, ingr, amt, unit)

            content = g(r, "PROHBT_CONTENT", "TYPE_NAME") or ""
            cmin = cmax = None
            cunit = None

            if spec["dur_type"] == "CPCTY_ATENT":
                # 용량주의 → 1일 최대 투여량. 판정 임계값 테이블의 원천
                qty, u = parse_amount(g(r, "MAX_QTY") or content)
                if qty:
                    cmax, cunit = qty, u or g(r, "MAX_QTY_UNIT") or "mg"
                    prev = self.daily_limits.get(ingr)
                    if not prev or qty < prev["max_qty"]:   # 보수적으로 가장 낮은 상한을 남긴다
                        self.daily_limits[ingr] = {"max_qty": qty, "unit": cunit, "age_group": "ADULT"}
            elif spec["dur_type"] == "AGE_TABOO":
                m = AGE_RE.search(content)
                if m:
                    age = float(m.group(1)) / (12 if m.group(2) == "개월" else 1)
                    cunit = "세"
                    if m.group(3) == "미만":
                        cmin, cmax = 0, age
                    else:
                        cmin, cmax = age, 200

            self.add_single_rule(spec["dur_type"], ingr, content, cmin, cmax, cunit,
                                 parse_date(g(r, "NOTIFICATION_DATE")))
        self.stats.append((spec["desc"], len(rows), None))

    def parse_easy_drug(self, name, spec):
        """e약은요 : 품목당 1행(1:1) → product_infos 로 분리"""
        rows = load_raw(name)
        for r in rows:
            p = self.put_product(g(r, "itemSeq"), g(r, "itemName"), g(r, "entpName"),
                                 ptype="OTC", image=g(r, "itemImage"))
            if not p:
                continue
            self.product_infos[p] = {
                "efficacy": g(r, "efcyQesitm"), "usage_method": g(r, "useMethodQesitm"),
                "warning": g(r, "atpnWarnQesitm"), "caution": g(r, "atpnQesitm"),
                "interaction": g(r, "intrcQesitm"), "side_effect": g(r, "seQesitm"),
                "storage": g(r, "depositMethodQesitm"),
            }
        self.stats.append((spec["desc"], len(rows), None))


    def parse_effect_dataset(self, name, spec):
        """DUR 효능군중복 — 성분 쌍이 아니라 '성분 → 효능군' 분류다.
        응답에 MIXTURE_* 필드가 아예 없어서, 쌍으로 파싱하면 규칙이 0건이 된다.
        (초안이 정확히 그렇게 실패했다. 중복 여부는 판정 시점에 뷰가 계산한다.)"""
        rows = load_raw(name)
        for r in rows:
            ingr = self.put_ingredient(g(r, "INGR_CODE"), g(r, *NAME_KEYS), g(r, "INGR_ENG_NAME"))
            eff = g(r, "EFFECT_NAME")
            if not (ingr and eff):
                continue
            cur = self.effect_groups.setdefault(eff, None)
            if cur is None:
                self.effect_groups[eff] = g(r, "SERS_NAME")
            self.ingr_effects.setdefault((ingr, eff), None)
            amt, unit = parse_amount(g(r, "ITEM_NAME"))
            p = self.put_product(g(r, "ITEM_SEQ"), g(r, "ITEM_NAME"), g(r, "ENTP_NAME"), g(r, "CHART"),
                                 ptype=ETC_OTC_MAP.get(g(r, "ETC_OTC_NAME") or "", "ETC"))
            self.link_product_ingredient(p, ingr, amt, unit)
        self.stats.append((spec["desc"], len(rows), None))

    def parse_permit_dataset(self, name, spec):
        """제품 허가정보 — 성분/함량의 정식 출처.
        DATASETS 에서 마지막에 처리되므로, 이 시점에는 DUR에서 온 성분 사전이 이미 채워져 있다.
        따라서 성분명으로 DUR 성분코드를 역인덱스 조회할 수 있다."""
        rows = load_raw(name)
        for r in rows:
            ptype = ETC_OTC_MAP.get(g(r, "ETC_OTC_CODE") or "", "ETC")
            p = self.put_product(g(r, "ITEM_SEQ"), g(r, "ITEM_NAME"), g(r, "ENTP_NAME"),
                                 g(r, "CHART"), ptype=ptype)
            if not p:
                continue
            # 원본에서 명시적으로 읽은 제형 구분이 DUR 추정값보다 우선한다
            self.products[p]["product_type"] = ptype

            for comp in parse_material_name(g(r, "MATERIAL_NAME")):
                code = self.name_index.get(normalize_ingr_name(comp["name"]))
                if code:
                    self.match_hit += 1
                else:
                    # DUR에 없는 성분 — 등록은 하되 판정 근거가 없음을 코드로 남긴다
                    self.match_miss += 1
                    code = local_ingr_code(comp["name"])
                    self.put_ingredient(code, comp["name"])
                self.link_product_ingredient(p, code, comp["amount"], comp["unit"])
        self.stats.append((spec["desc"], len(rows), None))

    def run(self):
        for name, spec in config.DATASETS.items():
            if spec["arity"] == "EFFECT":
                self.parse_effect_dataset(name, spec)
            elif spec["arity"] == "PAIR":
                self.parse_pair_dataset(name, spec)
            elif spec["arity"] == "SINGLE":
                self.parse_single_dataset(name, spec)
            elif spec["arity"] == "PERMIT":
                self.parse_permit_dataset(name, spec)
            else:
                self.parse_easy_drug(name, spec)

    # --- 저장 -----------------------------------------------------------------
    def write(self):
        config.NORM_DIR.mkdir(parents=True, exist_ok=True)

        def dump(fname, header, rows):
            path = config.NORM_DIR / fname
            with path.open("w", newline="", encoding="utf-8-sig") as f:
                w = csv.writer(f)
                w.writerow(header)
                w.writerows(rows)
            print(f"  {fname:<28} {len(rows):>6} 행")
            return len(rows)

        counts = {}
        counts["ingredients"] = dump("ingredients.csv", ["ingr_code", "name_ko", "name_en"],
            [[c, v["name_ko"], v["name_en"]] for c, v in self.ingredients.items()])
        counts["manufacturers"] = dump("manufacturers.csv", ["name"],
            [[n] for n in self.manufacturers])
        counts["products"] = dump("products.csv",
            ["item_seq", "name_ko", "manufacturer_name", "product_type", "chart", "image_url"],
            [[s, v["name_ko"], v["manufacturer_name"], v["product_type"], v["chart"], v["image_url"]]
             for s, v in self.products.items()])
        counts["product_ingredients"] = dump("product_ingredients.csv",
            ["item_seq", "ingr_code", "amount", "unit"],
            [[k[0], k[1], v["amount"], v["unit"]] for k, v in self.product_ingredients.items()])
        counts["product_infos"] = dump("product_infos.csv",
            ["item_seq", "efficacy", "usage_method", "warning", "caution", "interaction",
             "side_effect", "storage"],
            [[s, v["efficacy"], v["usage_method"], v["warning"], v["caution"],
              v["interaction"], v["side_effect"], v["storage"]] for s, v in self.product_infos.items()])
        counts["dur_single_rules"] = dump("dur_single_rules.csv",
            ["dur_code", "ingr_code", "prohibit_content", "condition_min", "condition_max",
             "condition_unit", "notification_date"],
            [[k[0], k[1], v["prohibit_content"], k[2], k[3], v["condition_unit"], v["notification_date"]]
             for k, v in self.single_rules.items()])
        counts["dur_pair_rules"] = dump("dur_pair_rules.csv",
            ["dur_code", "ingr_code_a", "ingr_code_b", "prohibit_content", "notification_date"],
            [[k[0], k[1], k[2], v["prohibit_content"], v["notification_date"]]
             for k, v in self.pair_rules.items()])
        counts["effect_groups"] = dump("effect_groups.csv", ["name", "series_name"],
            [[n, v] for n, v in self.effect_groups.items()])
        counts["ingredient_effect_groups"] = dump("ingredient_effect_groups.csv",
            ["ingr_code", "effect_name"], [[k[0], k[1]] for k in self.ingr_effects])
        counts["ingredient_daily_limits"] = dump("ingredient_daily_limits.csv",
            ["ingr_code", "max_qty", "unit", "age_group"],
            [[c, v["max_qty"], v["unit"], v["age_group"]] for c, v in self.daily_limits.items()])
        return counts


SAMPLE_BANNER = """
  ############################################################
  #  이 CSV는 표본(SAMPLE)에서 만들어진 것입니다.             #
  #  실제 식약처 데이터가 아니므로 팀에 공유하지 마세요.      #
  #  실데이터를 만들려면:                                     #
  #    1) data/raw 를 지우고                                  #
  #    2) python src/check_api.py                             #
  #    3) python src/collect_dur_api.py                       #
  #    4) python src/normalize.py                             #
  ############################################################
"""


def source_kind():
    f = config.RAW_DIR / "_SOURCE.txt"
    return f.read_text(encoding="utf-8").splitlines()[0].strip() if f.exists() else "UNKNOWN"


def main():
    print("[정규화] 원본 → 테이블별 CSV")
    n = Normalizer()
    n.run()
    raw_total = sum(s[1] for s in n.stats)
    print(f"\n  원본 총 {raw_total}행 (데이터셋 {len(n.stats)}종)")
    for desc, cnt, _ in n.stats:
        print(f"    - {desc}: {cnt}행")
    if n.match_hit or n.match_miss:
        total = n.match_hit + n.match_miss
        print(f"\n  성분명 → DUR 성분코드 매칭: {n.match_hit}/{total} "
              f"({n.match_hit / total:.0%}) — 미매칭분은 LOCAL_ 코드로 등록되며 판정 근거가 없다")

    unnamed = sum(1 for c, v in n.ingredients.items() if v["name_ko"] == c)
    if n.ingredients:
        print(f"  성분 국문명 확보: {len(n.ingredients) - unnamed}/{len(n.ingredients)} "
              f"({1 - unnamed / len(n.ingredients):.0%}) — 코드가 이름 자리에 남으면 파싱 키를 확인할 것")

    filled = sum(1 for v in n.product_ingredients.values() if v["amount"] is not None)
    if n.product_ingredients:
        print(f"  함량(amount) 확보: {filled}/{len(n.product_ingredients)} "
              f"({filled / len(n.product_ingredients):.0%}) — 함량이 없으면 일일 합산량을 계산할 수 없다")

    print("\n[출력]")
    counts = n.write()

    # 표본인지 실데이터인지를 CSV 옆에 남긴다 — 팀 공유 사고를 막는 장치
    kind = source_kind()
    src = config.RAW_DIR / "_SOURCE.txt"
    (config.NORM_DIR / "_SOURCE.txt").write_text(
        src.read_text(encoding="utf-8") if src.exists() else "UNKNOWN\n", encoding="utf-8")
    out_total = sum(counts.values())
    print(f"\n  정규화 후 총 {out_total}행 / {len(counts)}개 테이블")
    if raw_total:
        print(f"  원본 대비 {out_total / raw_total:.2f}배 "
              f"(중복 제거와 개체 분리가 동시에 일어나므로 1보다 크거나 작을 수 있음)")
    if kind == "SAMPLE":
        print(SAMPLE_BANNER)
    elif kind == "REAL":
        print("\n  실제 식약처 데이터입니다. data/normalized/ 를 커밋해 팀에 공유하세요.")


if __name__ == "__main__":
    main()
