"""
작성목적 : erd/*.svg 를 발표·제출용 PNG(2배 해상도)와 PDF로 다시 뽑는다.
          Inkscape 없이 이미 깔려 있는 Chrome 헤드리스만 쓴다.
실행    : python tools/erd_export.py
주의    : PNG 창 크기는 SVG 의 width/height 를 그대로 읽어 맞춘다. 배율 2배 = 원본 산출물과 같은 화질.
"""
import re, subprocess, sys, tempfile, urllib.parse
from pathlib import Path

CHROME = Path(r"C:\Program Files\Google\Chrome\Application\chrome.exe")
ROOT = Path(__file__).resolve().parent.parent
PAIRS = [("메디바이스_ERD_전체.svg", "메디바이스_ERD_전체.png", "메디바이스_ERD.pdf"),
         ("메디바이스_ERD_판정경로.svg", "메디바이스_ERD_판정경로.png", None),
         ("메디바이스_서비스ERD.svg", "메디바이스_서비스ERD.png", "메디바이스_서비스ERD.pdf")]


def url(p):
    return "file:///" + urllib.parse.quote(str(p).replace("\\", "/"))


def run(args):
    return subprocess.run([str(CHROME), "--headless", "--disable-gpu", "--hide-scrollbars",
                           "--no-first-run", "--no-default-browser-check"] + args,
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def pdf_wrapper(svg_path, w, h):
    """Chrome 은 CLI 로 용지 크기를 못 받는다. @page size 를 준 HTML 로 감싸 도면 크기 그대로 1쪽에 뽑는다."""
    html = (f"<!doctype html><meta charset='utf-8'>"
            f"<style>@page{{size:{w:.0f}px {h:.0f}px;margin:0}}"
            f"html,body{{margin:0;padding:0}}img{{display:block;width:{w:.0f}px;height:{h:.0f}px}}</style>"
            f"<img src='{url(svg_path)}'>")
    f = Path(tempfile.gettempdir()) / (svg_path.stem + "_print.html")
    f.write_text(html, encoding="utf-8")
    return f


def main():
    if not CHROME.exists():
        sys.exit(f"Chrome 없음: {CHROME}")
    erd = ROOT / "erd"
    for svg, png, pdf in PAIRS:
        s = (erd / svg).read_text(encoding="utf-8")
        w = float(re.search(r'\bwidth="([\d.]+)"', s).group(1))
        h = float(re.search(r'\bheight="([\d.]+)"', s).group(1))
        run([f"--force-device-scale-factor=2", f"--window-size={w:.0f},{h - 12:.0f}",
             f"--screenshot={erd / png}", url(erd / svg)])
        print(f"  {png}")
        if pdf:
            run([f"--print-to-pdf={erd / pdf}", "--no-pdf-header-footer",
                 url(pdf_wrapper(erd / svg, w, h))])
            print(f"  {pdf}")


if __name__ == "__main__":
    main()
