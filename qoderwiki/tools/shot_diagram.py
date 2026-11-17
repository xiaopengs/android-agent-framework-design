import pathlib
import asyncio,pathlib
from playwright.async_api import async_playwright
H=pathlib.Path(__file__).resolve().parent.parent / "nanoMuse-android-架构知识库.html"
async def m():
    async with async_playwright() as p:
        b=await p.chromium.launch(executable_path="/usr/bin/chromium",args=["--no-sandbox","--disable-dev-shm-usage"])
        pg=await b.new_page(viewport={"width":1500,"height":1400})
        await pg.goto(H.as_uri(),wait_until="load",timeout=90000)
        await pg.wait_for_timeout(2000)
        await pg.evaluate("go(0)")
        await pg.wait_for_timeout(2500)
        el=pg.locator("#ch0 .mm").first
        await el.scroll_into_view_if_needed()
        await pg.wait_for_timeout(600)
        await el.screenshot(path=str(pathlib.Path(__file__).resolve().parent.parent / "shots" / "diagram-global.png"))
        await b.close()
asyncio.run(m())
