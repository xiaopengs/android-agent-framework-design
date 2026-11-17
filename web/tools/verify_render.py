#!/usr/bin/env python3
"""Verify the built HTML actually renders: every mermaid diagram becomes an SVG,
no console errors, navigation works. Uses a real headless Chromium.
"""
import asyncio
import pathlib
import sys
from playwright.async_api import async_playwright

HTML = pathlib.Path(__file__).resolve().parent.parent / "nanoMuse-desktop-架构知识库.html"
SHOTS = pathlib.Path(__file__).resolve().parent.parent / "shots"
SHOTS.mkdir(exist_ok=True)

async def main():
    errors, failed = [], []
    async with async_playwright() as p:
        browser = await p.chromium.launch(
            executable_path="/usr/bin/chromium",
            args=["--no-sandbox", "--disable-dev-shm-usage"],
        )
        page = await browser.new_page(viewport={"width": 1500, "height": 1000})
        page.on("console", lambda m: errors.append(f"{m.type}: {m.text}") if m.type == "error" else None)
        page.on("pageerror", lambda e: errors.append(f"pageerror: {e}"))

        await page.goto(HTML.as_uri(), wait_until="load", timeout=90000)
        await page.wait_for_timeout(2500)

        n_chapters = await page.locator(".chapter").count()
        print(f"chapters in DOM: {n_chapters}")

        total_svg = total_err = 0
        for i in range(n_chapters):
            await page.evaluate(f"go({i})")
            # let mermaid finish this chapter
            for _ in range(60):
                await page.wait_for_timeout(500)
                pending = await page.evaluate(
                    f"document.querySelectorAll('#ch{i} .mm:not([data-done])').length"
                )
                if pending == 0:
                    break
            mm = await page.evaluate(f"document.querySelectorAll('#ch{i} .mm').length")
            svg = await page.evaluate(f"document.querySelectorAll('#ch{i} .mm svg').length")
            err = await page.evaluate(f"document.querySelectorAll('#ch{i} .mmerr').length")
            total_svg += svg
            total_err += err
            status = "OK" if err == 0 and svg == mm else "FAIL"
            print(f"  ch{i}: diagrams={mm} rendered_svg={svg} errors={err}  [{status}]")
            if status == "FAIL":
                texts = await page.evaluate(
                    f"[...document.querySelectorAll('#ch{i} .mmerr')].map(e=>e.textContent.slice(0,300))"
                )
                failed.extend(texts)
            await page.screenshot(path=str(SHOTS / f"ch{i}.png"), full_page=False)

        # sanity: sidebar + search + table rendering
        toc_links = await page.locator(".navtoc a").count()
        tables = await page.locator("#wrap table").count()
        pres = await page.locator("#wrap pre").count()
        print(f"toc links={toc_links}  tables={tables}  code blocks={pres}")

        await page.evaluate("go(0)")
        await page.wait_for_timeout(800)
        await page.screenshot(path=str(SHOTS / "overview-top.png"))
        await browser.close()

    print(f"\nTOTAL rendered SVG: {total_svg}   render failures: {total_err}")
    if failed:
        print("\n--- failure details ---")
        for f in failed[:10]:
            print(f)
    real_errors = [e for e in errors if "favicon" not in e.lower()]
    print(f"\nconsole/page errors: {len(real_errors)}")
    for e in real_errors[:10]:
        print("  " + e)
    return 1 if (total_err or real_errors) else 0


sys.exit(asyncio.run(main()))