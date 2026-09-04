from pathlib import Path
import re

for p in Path('erd').glob('*.svg'):
    print(f"=== {p.name} ===")
    s = p.read_text(encoding='utf-8')
    # Find all path elements with cubic bezier curves
    paths = re.findall(r'<path d="M([\d.]+) ([\d.]+) C([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+)"[^>]*>', s)
    print(f"  Total connectors: {len(paths)}")
    for x0, y0, cx1, cy1, cx2, cy2, x3, y3 in paths:
        # Check if coordinates match medications x=1350, 1600 or prescriptions x=1670, 1920 or x=1990, 2240
        coords = [(float(x0), float(y0)), (float(x3), float(y3))]
        for x, y in coords:
            if x in [1350, 1600, 1670, 1920, 1990, 2240, 110, 360]:
                # Print connector endpoints
                pass
