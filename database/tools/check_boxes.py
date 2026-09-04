import re
from pathlib import Path

for svg_path in ["erd/메디바이스_ERD_전체.svg", "erd/메디바이스_ERD_판정경로.svg", "erd/메디바이스_서비스ERD.svg"]:
    p = Path(svg_path)
    s = p.read_text(encoding='utf-8')
    print(f"=== {p.name} ===")
    # Find all top-level rects (backgrounds/groupings)
    rects = re.findall(r'<rect x="([\d.]+)" y="([\d.]+)" width="([\d.]+)" height="([\d.]+)"[^>]*rx="16"[^>]*>', s)
    for x, y, w, h in rects:
        print(f"  Group box: x={x}, y={y}, w={w}, h={h}, bottom={float(y)+float(h)}")
    
    # Check max y of tables
    import sys
    sys.path.insert(0, str(Path(__file__).parent))
    from verify_erd import TBL_RE, BOX_RE, NAME_RE
    max_y = 0
    lowest_tbl = ""
    for m in TBL_RE.finditer(s):
        dy = float(m.group(1) or 0)
        bx_m = BOX_RE.search(m.group(2))
        nm_m = NAME_RE.search(m.group(2))
        if bx_m and nm_m:
            by = float(bx_m.group(2)) + dy + float(bx_m.group(3))
            if by > max_y:
                max_y = by
                lowest_tbl = nm_m.group(1)
    print(f"  Lowest table bottom: {lowest_tbl} at y={max_y}")
