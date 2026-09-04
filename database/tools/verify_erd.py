import re
from pathlib import Path

SVGS = ["erd/메디바이스_ERD_전체.svg",
        "erd/메디바이스_ERD_판정경로.svg",
        "erd/메디바이스_서비스ERD.svg"]

TBL_RE = re.compile(r'<g class="tbl"(?: transform="translate\(0,([\d.]+)\)")?[^>]*>(.*?)</g>', re.S)
BOX_RE = re.compile(r'<rect x="([\d.]+)" y="([\d.]+)" width="250" height="([\d.]+)"')
NAME_RE = re.compile(r'font-size="12\.5" font-weight="700" fill="#FFFFFF">(?:★\s*)?([a-z_]+)</text>')

for svg_path in SVGS:
    p = Path(svg_path)
    s = p.read_text(encoding='utf-8')
    w = float(re.search(r'\bwidth="([\d.]+)"', s).group(1))
    h = float(re.search(r'\bheight="([\d.]+)"', s).group(1))
    print(f"\n================ {p.name} (Canvas: {w} x {h}) ================")
    
    tables = []
    for m in TBL_RE.finditer(s):
        dy = float(m.group(1) or 0)
        blk = m.group(2)
        nm_m = NAME_RE.search(blk)
        bx_m = BOX_RE.search(blk)
        if nm_m and bx_m:
            name = nm_m.group(1)
            x = float(bx_m.group(1))
            y = float(bx_m.group(2)) + dy
            bw = 250.0
            bh = float(bx_m.group(3))
            tables.append({'name': name, 'x': x, 'y': y, 'w': bw, 'h': bh})
            
    print(f"Total parsed tables: {len(tables)}")
    
    # 1. Check canvas bounds
    out_of_bounds = []
    for t in tables:
        if t['y'] + t['h'] > h:
            out_of_bounds.append((t['name'], t['y'] + t['h'], h))
        if t['x'] + t['w'] > w:
            out_of_bounds.append((t['name'], t['x'] + t['w'], w))
    if out_of_bounds:
        print(f"  [WARN] Tables exceeding canvas bounds:")
        for name, actual, limit in out_of_bounds:
            print(f"    - {name}: actual={actual} > limit={limit} (overflow: {actual - limit}px)")
    else:
        print("  [OK] All tables are within canvas bounds.")

    # 2. Check table overlaps
    overlaps = []
    for i in range(len(tables)):
        for j in range(i + 1, len(tables)):
            t1 = tables[i]
            t2 = tables[j]
            # Check AABB collision
            x_overlap = max(0.0, min(t1['x'] + t1['w'], t2['x'] + t2['w']) - max(t1['x'], t2['x']))
            y_overlap = max(0.0, min(t1['y'] + t1['h'], t2['y'] + t2['h']) - max(t1['y'], t2['y']))
            if x_overlap > 0 and y_overlap > 0:
                overlaps.append((t1['name'], t2['name'], x_overlap, y_overlap))
                
    if overlaps:
        print(f"  [ERROR] Table overlaps detected:")
        for name1, name2, xo, yo in overlaps:
            print(f"    - {name1} and {name2} overlap by {xo}px horizontally, {yo}px vertically")
    else:
        print("  [OK] No table overlaps detected.")
        
    # 3. Check gaps between adjacent tables in the same column
    for x in sorted(list({t['x'] for t in tables})):
        col_tables = sorted([t for t in tables if abs(t['x'] - x) < 1], key=lambda t: t['y'])
        for k in range(len(col_tables) - 1):
            t_curr = col_tables[k]
            t_next = col_tables[k+1]
            gap = t_next['y'] - (t_curr['y'] + t_curr['h'])
            if gap < 10:
                print(f"  [WARN] Small gap between {t_curr['name']} and {t_next['name']}: {gap:.1f}px (at x={x})")
