#!/usr/bin/env python3
"""Generic mermaid syntax checker: render every ```mermaid fence in given markdown
files in real headless Chromium and report which ones fail.

Usage: python3 check_mermaid.py <file.md> [more.md ...]
Needs: pip install playwright && playwright install chromium
"""
import asyncio
import json
import pathlib
import re
import sys

FENCE = re.compile(r"^```mermaid\s*\n(.*?)^```", re.M | re.S)
MERMAID = pathlib.Path(__file__).resolve().parent.parent / "vendor" / "mermaid.min.js"


async def main(paths):
    from playwright.async_api import async_playwright

    blocks = []
    for p in paths:
        text = pathlib.Path(p).read_text(encoding="utf-8")
        for i, m in enumerate(FENCE.finditer(text), 1):
            line = text[: m.start()].count("\n") + 1
            blocks.append({"src": f"{pathlib.Path(p).name}:{line}", "n": i, "code": m.group(1)})
    if not blocks:
        print("no mermaid fences found")
        return 0

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(
            executable_path="/usr/bin/chromium",
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        page = await browser.new_page()
        await page.set_content("<html><body><div id='out'></div></body></html>")
        await page.add_script_tag(content=MERMAID.read_text(encoding="utf-8"))
        await page.evaluate("mermaid.initialize({startOnLoad:false, securityLevel:'loose'})")
        results = await page.evaluate(
            """async (blocks) => {
                const out = [];
                for (const b of blocks) {
                    try {
                        const r = await mermaid.render('g' + out.length, b.code);
                        out.push({src: b.src, n: b.n, ok: true, svg: (r.svg||'').length});
                    } catch (e) {
                        out.push({src: b.src, n: b.n, ok: false, err: String(e && e.message || e).slice(0, 400)});
                    }
                }
                return out;
            }""",
            blocks,
        )
        await browser.close()

    bad = 0
    for r in results:
        if r["ok"]:
            print(f"  OK   {r['src']} block#{r['n']}  svg={r['svg']}B")
        else:
            bad += 1
            print(f"  FAIL {r['src']} block#{r['n']}\n       {r['err']}")
    print(f"\nTOTAL {len(results)} blocks, {bad} failures")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))