"""
작성자 : 박기준
작성목적 : 정규화된 CSV를 PostgreSQL medivice 스키마에 적재한다.
          CSV는 자연키(ingr_code, item_seq)만 갖고 있으므로, 적재하면서 대체키(id)로 치환한다.
          자연키 ↔ 대체키 분리는 공공데이터 코드 체계가 바뀌어도 FK가 흔들리지 않게 하는 장치다.
작성일 : 2026-09-02
실행    : python src/load_postgres.py
전제    : sql/01_schema_ddl.sql, sql/02_seed_code.sql 이 먼저 실행되어 있어야 한다.

적재 순서는 FK 방향(부모 → 자식)을 따른다.
  manufacturers → ingredients → products → product_ingredients / product_infos
                              → dur_single_rules / dur_pair_rules / ingredient_daily_limits
"""
import csv
import sys
from pathlib import Path

import psycopg2
from psycopg2.extras import execute_batch
from dotenv import load_dotenv

load_dotenv()
import config  # noqa: E402


def read_csv(name):
    p = config.NORM_DIR / name
    if not p.exists():
        print(f"  ! {name} 없음 — 건너뜀")
        return []
    with p.open(encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


def nz(v):
    """빈 문자열을 NULL로."""
    return None if v in ("", None, "None") else v


def num(v):
    v = nz(v)
    return float(v) if v is not None else None


def load(conn):
    cur = conn.cursor()
    cur.execute("SET search_path TO medivice, public")

    # ── 1. manufacturers ────────────────────────────────────────────────────
    rows = read_csv("manufacturers.csv")
    execute_batch(cur,
        "INSERT INTO manufacturers(name) VALUES (%s) ON CONFLICT (name) DO NOTHING",
        [(r["name"],) for r in rows if nz(r["name"])], page_size=500)
    print(f"  manufacturers          {len(rows):>6}건 처리")

    # ── 2. ingredients ──────────────────────────────────────────────────────
    rows = read_csv("ingredients.csv")
    execute_batch(cur,
        """INSERT INTO ingredients(ingr_code, name_ko, name_en) VALUES (%s,%s,%s)
           ON CONFLICT (ingr_code) DO UPDATE
             SET name_en = COALESCE(ingredients.name_en, EXCLUDED.name_en)""",
        [(r["ingr_code"], r["name_ko"], nz(r["name_en"])) for r in rows], page_size=500)
    print(f"  ingredients            {len(rows):>6}건 처리")

    # 자연키 → 대체키 매핑을 한 번에 읽어 둔다
    cur.execute("SELECT ingr_code, ingredient_id FROM ingredients")
    ingr_map = dict(cur.fetchall())
    cur.execute("SELECT name, manufacturer_id FROM manufacturers")
    mfr_map = dict(cur.fetchall())

    # ── 3. products ─────────────────────────────────────────────────────────
    rows = read_csv("products.csv")
    execute_batch(cur,
        """INSERT INTO products(item_seq, name_ko, manufacturer_id, product_type, chart, image_url)
           VALUES (%s,%s,%s,%s,%s,%s)
           ON CONFLICT (item_seq) DO UPDATE
             SET name_ko = EXCLUDED.name_ko, updated_at = now()""",
        [(r["item_seq"], r["name_ko"], mfr_map.get(nz(r["manufacturer_name"])),
          r["product_type"] or "ETC", nz(r["chart"]), nz(r["image_url"])) for r in rows],
        page_size=500)
    print(f"  products               {len(rows):>6}건 처리")

    cur.execute("SELECT item_seq, product_id FROM products")
    prod_map = dict(cur.fetchall())

    # ── 4. product_ingredients (N:M) ────────────────────────────────────────
    rows = read_csv("product_ingredients.csv")
    data = [(prod_map[r["item_seq"]], ingr_map[r["ingr_code"]], num(r["amount"]), nz(r["unit"]))
            for r in rows if r["item_seq"] in prod_map and r["ingr_code"] in ingr_map]
    execute_batch(cur,
        """INSERT INTO product_ingredients(product_id, ingredient_id, amount, unit)
           VALUES (%s,%s,%s,%s)
           ON CONFLICT (product_id, ingredient_id) DO UPDATE
             SET amount = COALESCE(product_ingredients.amount, EXCLUDED.amount)""",
        data, page_size=1000)
    print(f"  product_ingredients    {len(data):>6}건 처리")

    # ── 5. product_infos (1:1) ──────────────────────────────────────────────
    rows = read_csv("product_infos.csv")
    data = [(prod_map[r["item_seq"]], nz(r["efficacy"]), nz(r["usage_method"]), nz(r["warning"]),
             nz(r["caution"]), nz(r["interaction"]), nz(r["side_effect"]), nz(r["storage"]))
            for r in rows if r["item_seq"] in prod_map]
    execute_batch(cur,
        """INSERT INTO product_infos(product_id, efficacy, usage_method, warning, caution,
                                     interaction, side_effect, storage)
           VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
           ON CONFLICT (product_id) DO UPDATE SET efficacy = EXCLUDED.efficacy""",
        data, page_size=500)
    print(f"  product_infos          {len(data):>6}건 처리")

    cur.execute("SELECT code, dur_type_id FROM dur_types")
    dur_map = dict(cur.fetchall())

    # ── 6. dur_single_rules ─────────────────────────────────────────────────
    rows = read_csv("dur_single_rules.csv")
    data = [(dur_map[r["dur_code"]], ingr_map[r["ingr_code"]], nz(r["prohibit_content"]),
             num(r["condition_min"]), num(r["condition_max"]), nz(r["condition_unit"]),
             nz(r["notification_date"]))
            for r in rows if r["dur_code"] in dur_map and r["ingr_code"] in ingr_map]
    execute_batch(cur,
        """INSERT INTO dur_single_rules(dur_type_id, ingredient_id, prohibit_content,
                                        condition_min, condition_max, condition_unit, notification_date)
           VALUES (%s,%s,%s,%s,%s,%s,%s) ON CONFLICT DO NOTHING""", data, page_size=1000)
    print(f"  dur_single_rules       {len(data):>6}건 처리")

    # ── 7. dur_pair_rules — CHECK(a<b)에 맞춰 id 기준으로 다시 정렬한다 ──────
    #     CSV는 ingr_code 사전순으로 정렬돼 있지만 DB 제약은 id 크기 기준이므로 여기서 맞춘다.
    rows = read_csv("dur_pair_rules.csv")
    data = []
    for r in rows:
        a, b = ingr_map.get(r["ingr_code_a"]), ingr_map.get(r["ingr_code_b"])
        if not (a and b) or a == b or r["dur_code"] not in dur_map:
            continue
        lo, hi = (a, b) if a < b else (b, a)
        data.append((dur_map[r["dur_code"]], lo, hi, nz(r["prohibit_content"]),
                     nz(r["notification_date"]), r.get("rule_version") or "MFDS-DUR",
                     r.get("source_ref") or "https://www.data.go.kr/data/15059486/openapi.do"))
    execute_batch(cur,
        """INSERT INTO dur_pair_rules(dur_type_id, ingredient_a_id, ingredient_b_id,
                                      prohibit_content, notification_date, rule_version, source_ref)
           VALUES (%s,%s,%s,%s,%s,%s,%s)
           ON CONFLICT (dur_type_id, ingredient_a_id, ingredient_b_id) DO UPDATE
             SET prohibit_content = COALESCE(EXCLUDED.prohibit_content, dur_pair_rules.prohibit_content),
                 notification_date = COALESCE(EXCLUDED.notification_date, dur_pair_rules.notification_date),
                 rule_version = EXCLUDED.rule_version,
                 source_ref = EXCLUDED.source_ref,
                 checked_at = now()""",
        data, page_size=1000)
    print(f"  dur_pair_rules         {len(data):>6}건 처리")

    # ── 7-1. effect_groups / ingredient_effect_groups ───────────────────────
    rows = read_csv("effect_groups.csv")
    execute_batch(cur,
        """INSERT INTO effect_groups(name, series_name) VALUES (%s,%s)
           ON CONFLICT (name) DO NOTHING""",
        [(r["name"], nz(r["series_name"])) for r in rows if nz(r["name"])], page_size=500)
    print(f"  effect_groups          {len(rows):>6}건 처리")

    cur.execute("SELECT name, effect_group_id FROM effect_groups")
    eff_map = dict(cur.fetchall())

    rows = read_csv("ingredient_effect_groups.csv")
    data = [(ingr_map[r["ingr_code"]], eff_map[r["effect_name"]])
            for r in rows if r["ingr_code"] in ingr_map and r["effect_name"] in eff_map]
    execute_batch(cur,
        """INSERT INTO ingredient_effect_groups(ingredient_id, effect_group_id)
           VALUES (%s,%s) ON CONFLICT DO NOTHING""", data, page_size=1000)
    print(f"  ingredient_effect_grps {len(data):>6}건 처리")

    # ── 8. ingredient_daily_limits ──────────────────────────────────────────
    rows = read_csv("ingredient_daily_limits.csv")
    data = [(ingr_map[r["ingr_code"]], num(r["max_qty"]), r["unit"], r["age_group"] or "ADULT")
            for r in rows if r["ingr_code"] in ingr_map and num(r["max_qty"])]
    execute_batch(cur,
        """INSERT INTO ingredient_daily_limits(ingredient_id, max_qty, unit, age_group)
           VALUES (%s,%s,%s,%s)
           ON CONFLICT (ingredient_id) DO UPDATE SET max_qty = EXCLUDED.max_qty""",
        data, page_size=500)
    print(f"  ingredient_daily_limits{len(data):>6}건 처리")

    conn.commit()


def verify(conn):
    """적재 검증 — 행수와 참조 무결성(고아 FK)을 확인한다."""
    cur = conn.cursor()
    cur.execute("SET search_path TO medivice, public")
    print("\n[검증] 테이블별 행수")
    for t in ("ingredients", "manufacturers", "products", "product_ingredients", "product_infos",
              "dur_types", "dur_single_rules", "dur_pair_rules", "effect_groups",
              "ingredient_effect_groups", "ingredient_daily_limits"):
        cur.execute(f"SELECT count(*) FROM {t}")
        print(f"  {t:<24} {cur.fetchone()[0]:>6}")

    print("\n[검증] 참조 무결성 / 제약")
    checks = [
        ("성분 없는 품목-성분 행", """SELECT count(*) FROM product_ingredients pi
             LEFT JOIN ingredients i ON i.ingredient_id = pi.ingredient_id WHERE i.ingredient_id IS NULL"""),
        ("양방향 중복으로 남은 쌍", """SELECT count(*) FROM dur_pair_rules r1 JOIN dur_pair_rules r2
             ON r1.dur_type_id = r2.dur_type_id
            AND r1.ingredient_a_id = r2.ingredient_b_id
            AND r1.ingredient_b_id = r2.ingredient_a_id"""),
        ("자기 자신과의 금기 쌍", "SELECT count(*) FROM dur_pair_rules WHERE ingredient_a_id = ingredient_b_id"),
    ]
    ok = True
    for label, sql in checks:
        cur.execute(sql)
        n = cur.fetchone()[0]
        mark = "OK" if n == 0 else "FAIL"
        ok &= (n == 0)
        print(f"  [{mark}] {label}: {n}")
    return ok


def main():
    f = config.NORM_DIR / "_SOURCE.txt"
    kind = f.read_text(encoding="utf-8").splitlines()[0].strip() if f.exists() else "UNKNOWN"
    if kind == "SAMPLE":
        print("\n  [주의] 지금 적재하는 것은 표본 데이터입니다. 판정 결과를 실제 근거로 쓰지 마세요.\n")
    elif kind == "UNKNOWN":
        print("\n  [주의] 이 CSV가 표본인지 실데이터인지 알 수 없습니다 (_SOURCE.txt 없음).\n")

    with config.connect_db() as conn:
        print(f"[적재] 정규화 CSV({kind}) → PostgreSQL")
        load(conn)
        if not verify(conn):
            sys.exit(1)
    print("\n완료.")


if __name__ == "__main__":
    main()
