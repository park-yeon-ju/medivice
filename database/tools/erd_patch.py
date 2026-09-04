"""
작성목적 : erd/*.svg 는 좌표를 직접 잡아 만든 손수 제작 ERD라 스키마가 바뀌어도 자동으로 따라오지 않는다.
          실DB(information_schema)와 대조해 빠진 컬럼 행을 같은 스타일로 끼워 넣고,
          컬럼 수 배지·타입 표기를 맞춘다. 레이아웃(테이블 위치·색·범례)은 건드리지 않는다.
실행    : python tools/erd_patch.py --check      (차이만 출력, 파일 수정 없음)
          python tools/erd_patch.py --apply      (SVG 수정)
전제    : .env 의 PG* 접속 정보로 medivice 스키마를 읽는다.

SVG 규칙 (원본에서 실측):
  <g class="tbl"> 하나가 테이블 하나.
  박스 rect: y=BOX_Y, height = 30 + 16*행수 + 6
  행 i(0부터): 배지 rect y=BOX_Y+30+16i, 텍스트 baseline y=BOX_Y+38.8+16i
  홀수 행에 얼룩무늬 rect(y = 행top - 3)
"""
import argparse, os, re, sys
import psycopg2
from dotenv import load_dotenv

load_dotenv()

SVGS = ["erd/메디바이스_ERD_전체.svg",
        "erd/메디바이스_ERD_판정경로.svg",
        "erd/메디바이스_서비스ERD.svg"]

MONO = "'DejaVu Sans Mono','Noto Sans Mono CJK KR',monospace"
ROW_H = 16.0
HEAD_H = 30.0
PAD_B = 6.0


def db_columns():
    c = psycopg2.connect(host=os.getenv("PGHOST"), port=os.getenv("PGPORT"), dbname=os.getenv("PGDATABASE"),
                         user=os.getenv("PGUSER"), password=os.getenv("PGPASSWORD"))
    cur = c.cursor()
    cur.execute("""SELECT table_name, column_name, data_type, character_maximum_length,
                          numeric_precision, numeric_scale
                   FROM information_schema.columns
                   WHERE table_schema='medivice' AND table_name IN (
                     SELECT table_name FROM information_schema.tables
                     WHERE table_schema='medivice' AND table_type='BASE TABLE')
                   ORDER BY table_name, ordinal_position""")
    out = {}
    for t, col, dt, clen, np_, ns in cur.fetchall():
        out.setdefault(t, []).append((col, short_type(dt, clen, np_, ns)))
    return out


def short_type(dt, clen, np_, ns):
    """information_schema 타입을 ERD 표기(vc(20), num(12,3), tstz …)로 줄인다."""
    return {
        "character varying": f"vc({clen})",
        "character": f"char({clen})",
        "numeric": f"num({np_},{ns})",
        "timestamp with time zone": "tstz",
        "double precision": "float8",
    }.get(dt, {"integer": "int", "bigint": "bigint", "smallint": "smallint",
               "text": "text", "date": "date", "boolean": "boolean", "jsonb": "jsonb"}.get(dt, dt))


TBL_RE = re.compile(r'<g class="tbl"[^>]*>(.*?)</g>', re.S)
BOX_RE = re.compile(r'<rect x="([\d.]+)" y="([\d.]+)" width="250" height="([\d.]+)"')
NAME_RE = re.compile(r'font-size="12\.5" font-weight="700" fill="#FFFFFF">(?:★\s*)?([a-z_]+)</text>')
BADGE_RE = re.compile(r'(font-size="9\.5" fill="#FFFFFF" opacity="\.8">)(\d+)(</text>)')
ROW_RE = re.compile(r'font-size="10\.2" font-weight="[47]00" fill="#(?:12312B|3C5A53)">([a-z_][a-z0-9_]*)</text>')
TYPE_RE = re.compile(r'<text x="([\d.]+)" y="([\d.]+)" text-anchor="end" font-family="[^"]*" font-size="8\.8" fill="#7C948D">([^<]*)</text>')


def parse_tables(svg):
    """{테이블명: {block, x, y, h, rows[(name,type)], badge}}"""
    out = {}
    for m in TBL_RE.finditer(svg):
        blk = m.group(1)
        nm = NAME_RE.search(blk)
        box = BOX_RE.search(blk)
        if not (nm and box):
            continue
        names = ROW_RE.findall(blk)
        types = [t[2] for t in TYPE_RE.findall(blk)]
        out[nm.group(1)] = dict(span=m.span(1), block=blk, box_head=f'<rect x="{box.group(1)}" y="{box.group(2)}" width="250"',
                                x=float(box.group(1)), y=float(box.group(2)), h=float(box.group(3)),
                                rows=list(zip(names, types)))
    return out


def row_markup(x, y_top, idx, name, typ):
    """행 하나. 홀수 행에는 원본과 같은 얼룩무늬를 깐다."""
    parts = []
    if idx % 2 == 1:
        parts.append(f'<rect x="{x+1:g}" y="{y_top-3:g}" width="248" height="16" fill="#F8FBFA"/>')
    base = y_top + 8.8
    parts.append(f'<text x="{x+31:g}" y="{base:g}" font-family="{MONO}" font-size="10.2" '
                 f'font-weight="400" fill="#3C5A53">{name}</text>')
    parts.append(f'<text x="{x+241:g}" y="{base:g}" text-anchor="end" font-family="{MONO}" '
                 f'font-size="8.8" fill="#7C948D">{typ}</text>')
    return "".join(parts)


# 서비스 ERD는 화면 설명용으로 컬럼을 의도적으로 추렸다. 전체 컬럼을 밀어 넣으면 그림의 목적이 깨지므로
# 이번 개선에서 새로 생겨 발표 내용의 근거가 되는 컬럼만 채운다.
STORY_COLS = {
    "rule_version", "source_ref", "reason_code", "as_needed", "amount_missing_count", "checked_at",
    "custom_type", "timing", "dose_unit", "duration_note"
}


CONN_RE = re.compile(
    r'<path d="M([\d.]+) ([\d.]+) C([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+)"([^>]*)/>'
    r'<circle cx="[\d.]+" cy="[\d.]+"([^>]*)/><circle cx="[\d.]+" cy="[\d.]+"([^>]*)/>'
    r'<text x="([\d.]+)" y="[\d.]+"([^>]*)>([^<]*)</text>'
    r'<text x="([\d.]+)" y="[\d.]+"([^>]*)>([^<]*)</text>')


MIN_GAP = 20.0  # 박스 사이 최소 간격


def column_shifts(boxes, growth):
    """같은 x(열)에서 위 박스가 커져 아래 박스를 침범할 때만, 침범한 만큼만 내린다.
       원본에는 박스 옆에 설명 주석이 붙어 있어 필요 이상으로 밀면 글자를 덮는다."""
    shift = {}
    for x in {b["x"] for b in boxes.values()}:
        col = sorted((n for n, b in boxes.items() if b["x"] == x), key=lambda n: boxes[n]["y"])
        prev_bottom = None
        for n in col:
            b = boxes[n]
            d = 0.0 if prev_bottom is None else max(0.0, prev_bottom + MIN_GAP - b["y"])
            shift[n] = d
            prev_bottom = b["y"] + d + b["h"] + growth.get(n, 0)
    return shift


def shift_connectors(svg, boxes, shift):
    """테이블을 내리면 그 테이블 변에 붙은 관계선 끝점도 같이 내려야 선이 어긋나지 않는다."""
    def dy_at(x, y):
        for n, b in boxes.items():
            if not shift.get(n):
                continue
            if abs(x - b["x"]) < 1 or abs(x - (b["x"] + 250)) < 1:
                if b["y"] - 1 <= y <= b["y"] + b["h"] + 1:
                    return shift[n]
        return 0.0

    def repl(m):
        g = m.groups()
        x0, y0, cx1, cy1, cx2, cy2, x3, y3 = (float(v) for v in g[:8])
        d0, d1 = dy_at(x0, y0), dy_at(x3, y3)
        if not d0 and not d1:
            return m.group(0)
        y0 += d0; cy1 += d0; cy2 += d1; y3 += d1
        return (f'<path d="M{x0:g} {y0:g} C{cx1:g} {cy1:g} {cx2:g} {cy2:g} {x3:g} {y3:g}"{g[8]}/>'
                f'<circle cx="{x0:g}" cy="{y0:g}"{g[9]}/><circle cx="{x3:g}" cy="{y3:g}"{g[10]}/>'
                f'<text x="{g[11]}" y="{y0-4:g}"{g[12]}>{g[13]}</text>'
                f'<text x="{g[14]}" y="{y3-4:g}"{g[15]}>{g[16]}</text>')

    return CONN_RE.sub(repl, svg)


def patch(path, db, apply):
    curated = "서비스ERD" in path
    svg = open(path, encoding="utf-8").read()
    tables = parse_tables(svg)
    changes = []

    growth = {}
    for name, t in tables.items():
        if name not in db:
            continue
        shown = [r[0] for r in t["rows"]]
        miss = [c for c, _ in db[name] if c not in shown]
        if curated:
            miss = [c for c in miss if c in STORY_COLS]
        if miss:
            growth[name] = ROW_H * len(miss)
    shifts = column_shifts(tables, growth)

    if apply:
        svg = shift_connectors(svg, tables, shifts)
        tables = parse_tables(svg)
    for name, t in sorted(tables.items(), key=lambda kv: -kv[1]["span"][0]):  # 뒤에서부터 고쳐야 오프셋이 안 밀린다
        if name not in db:
            continue
        shown = [r[0] for r in t["rows"]]
        missing = [(c, ty) for c, ty in db[name] if c not in shown]
        if curated:
            missing = [(c, ty) for c, ty in missing if c in STORY_COLS]
        # 타입 표기가 달라진 컬럼(varchar 폭 확장 등)
        dbt = dict(db[name])
        retyped = [(c, ty, dbt[c]) for c, ty in t["rows"] if c in dbt and dbt[c] != ty]
        if not missing and not retyped:
            continue
        changes.append((name, [c for c, _ in missing], retyped))
        if not apply:
            continue

        blk = t["block"]
        for col, old, new in retyped:  # 폭 표기 교체
            i = shown.index(col)
            # 해당 행의 타입 텍스트만 바꾼다 — 같은 문자열이 여러 행에 있을 수 있어 위치로 찾는다
            ys = t["y"] + HEAD_H + ROW_H * i + 8.8
            blk = re.sub(r'(<text x="[\d.]+" y="%s" text-anchor="end"[^>]*>)%s(</text>)' % (re.escape(f"{ys:g}"), re.escape(old)),
                         r"\g<1>%s\g<2>" % new, blk)
        add = ""
        for k, (col, ty) in enumerate(missing):
            idx = len(shown) + k
            add += row_markup(t["x"], t["y"] + HEAD_H + ROW_H * idx, idx, col, ty)
        blk = blk + add
        n = len(shown) + len(missing)
        blk = blk.replace(f'height="{t["h"]:g}" rx="9"', f'height="{HEAD_H + ROW_H*n + PAD_B:g}" rx="9"', 1)
        blk = BADGE_RE.sub(lambda m: m.group(1) + str(n) + m.group(3), blk, count=1)
        svg = svg[:t["span"][0]] + blk + svg[t["span"][1]:]

    if apply:  # 박스 자체는 그룹 transform 으로 내린다 — 내부 좌표는 손대지 않는다
        for name, dy in shifts.items():
            if not dy:
                continue
            t = tables[name]
            i = svg.index(t["box_head"])
            j = svg.rindex('<g class="tbl"', 0, i)
            k = svg.index(">", j) + 1
            svg = svg[:j] + f'<g class="tbl" transform="translate(0,{dy:g})">' + svg[k:]

    if apply:
        svg = svg.replace("2026-09-02", "2026-09-03")  # 헤더 작성일
        open(path, "w", encoding="utf-8").write(svg)
    return changes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    a = ap.parse_args()
    db = db_columns()
    for p in SVGS:
        ch = patch(p, db, a.apply)
        print(f"== {os.path.basename(p)}")
        if not ch:
            print("   실DB와 일치")
        for name, missing, retyped in ch:
            bits = []
            if missing:
                bits.append("추가 " + ", ".join(missing))
            if retyped:
                bits.append("타입 " + ", ".join(f"{c} {o}→{n}" for c, o, n in retyped))
            print(f"   {name}: " + " | ".join(bits))
    print("\n적용됨" if a.apply else "\n--apply 를 붙이면 실제로 수정한다")


if __name__ == "__main__":
    main()
