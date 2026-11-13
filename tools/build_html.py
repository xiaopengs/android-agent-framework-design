#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 DESIGN.md 与 REQUIREMENTS-SRS.docx 渲染成自包含 HTML。"""
import html
import io
import os
import re
import mammoth
import markdown

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, 'docs')

CSS = """
:root{
  --ink:#0E1A22; --paper:#F7F8F7; --rule:#C9D2D6; --blue:#12556F;
  --amber:#B37A2A; --teal:#1F6B5E; --soft:#F0F2F1; --soft2:#F4F7F8;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;background:var(--paper);color:var(--ink);
  font-family:-apple-system,BlinkMacSystemFont,"Noto Sans SC","PingFang SC","Microsoft YaHei",sans-serif;
  font-size:15px;line-height:1.75}
.topbar{border-bottom:1px solid var(--rule);background:#fff}
.topbar-in{max-width:1280px;margin:0 auto;padding:14px 28px;display:flex;align-items:baseline;gap:18px;flex-wrap:wrap}
.topbar .brand{font-weight:700;color:var(--blue);font-size:15px}
.topbar a{color:var(--ink);text-decoration:none;font-size:13px}
.topbar a:hover{color:var(--blue)}
.wrap{max-width:1280px;margin:0 auto;padding:28px;display:grid;grid-template-columns:230px 1fr;gap:36px}
aside{position:sticky;top:24px;align-self:start;max-height:calc(100vh - 48px);overflow:auto}
aside .nav-title{font-size:12px;letter-spacing:.12em;color:#5F7380;margin:0 0 10px}
aside ul{list-style:none;margin:0;padding:0}
aside li{margin:2px 0}
aside a{display:block;padding:5px 10px;border-left:2px solid transparent;color:#33474F;text-decoration:none;font-size:13.5px;line-height:1.4}
aside a:hover{border-left-color:var(--blue);color:var(--blue);background:var(--soft2)}
aside a.lvl2{padding-left:22px;font-size:12.5px;color:#5F7380}
main{min-width:0}
article{max-width:880px}
h1{font-size:25px;line-height:1.35;margin:8px 0 18px;padding-bottom:10px;border-bottom:2px solid var(--blue)}
h2{font-size:19px;margin:30px 0 12px;color:var(--blue)}
h3{font-size:15.5px;margin:22px 0 8px}
h4{font-size:14px;margin:16px 0 6px}
p{margin:10px 0}
ul,ol{margin:10px 0;padding-left:22px}
li{margin:5px 0}
strong{color:#10262E}
a{color:var(--blue)}
code{font-family:ui-monospace,Menlo,Consolas,monospace;font-size:13px;background:var(--soft);padding:1px 5px;border-radius:4px}
pre{background:var(--soft);border:1px solid var(--rule);border-radius:8px;padding:14px;overflow-x:auto;line-height:1.6}
pre code{background:none;padding:0;font-size:13px}
pre.mermaid{background:#fff;border:1px dashed var(--rule);text-align:center;padding:18px}
blockquote{margin:14px 0;padding:10px 16px;border-left:3px solid var(--blue);background:var(--soft2);border-radius:0 8px 8px 0;color:#33474F}
table{width:100%;border-collapse:collapse;margin:14px 0;font-size:13.5px;table-layout:fixed}
th,td{border:1px solid var(--rule);padding:8px 10px;vertical-align:top;word-break:break-word}
th{background:#F2F2F2;font-weight:600;text-align:left}
tr:nth-child(even) td{background:#FBFCFC}
img{max-width:100%;height:auto;border:1px solid var(--rule);border-radius:8px}
.meta{color:#5F7380;font-size:13px;margin-bottom:20px}
@media (max-width:920px){
  .wrap{grid-template-columns:1fr;padding:18px}
  aside{position:static;max-height:none;margin-bottom:10px}
}
"""

FAV = "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='7' fill='%2312556F'/%3E%3Ctext x='16' y='22' font-size='17' font-family='sans-serif' fill='white' text-anchor='middle' font-weight='700'%3EA%3C/text%3E%3C/svg%3E"


def slug_nav(body):
    """给 h1/h2/h3 补 id，返回 (body, nav_html)。"""
    items = []
    counter = [0]

    def repl(m):
        level, inner = m.group(1), m.group(2)
        counter[0] += 1
        hid = 'h-%d' % counter[0]
        text = re.sub(r'<[^>]+>', '', inner).strip()
        items.append((level, hid, text))
        return '<h%s id="%s">%s</h%s>' % (level, hid, inner, level)

    body = re.sub(r'<h([123])(?:\s+id="[^"]*")?>(.*?)</h\1>', repl, body, flags=re.S)
    nav = ['<nav><p class="nav-title">目录</p><ul>']
    for level, hid, text in items:
        cls = '' if level == '1' else ' lvl2'
        nav.append('<li><a class="%s" href="#%s">%s</a></li>' % (cls, hid, html.escape(text)))
    nav.append('</ul></nav>')
    return body, ''.join(nav)


def shell(title, subtitle, body, nav, mermaid=False):
    mermaid_js = ''
    if mermaid:
        mermaid_js = """<script src="https://fastly.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"></script>
<script>mermaid.initialize({startOnLoad:true,theme:'neutral',securityLevel:'loose',flowchart:{htmlLabels:true}});</script>"""
    return """<!DOCTYPE html>
<html lang="zh-CN"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>%s</title>
<link rel="icon" href="%s">
<style>%s</style>
</head><body>
<div class="topbar"><div class="topbar-in">
  <span class="brand">Android 端侧 Agent 框架</span>
  <a href="PRD.html">产品需求文档 (PRD)</a>
  <a href="DESIGN.html">方案设计文档</a>
  <a href="REQUIREMENTS-SRS.html">需求规格说明书</a>
  <a href="https://github.com/xiaopengs/android-agent-framework-design">GitHub 仓库</a>
</div></div>
<div class="wrap">
<aside>%s</aside>
<main><article>
<h1>%s</h1>
<p class="meta">%s</p>
%s
</article></main>
</div>
%s
</body></html>""" % (html.escape(title), FAV, CSS, nav, html.escape(title), html.escape(subtitle), body, mermaid_js)


# ---------- 1. DESIGN.md ----------
md_text = open(os.path.join(DOCS, 'DESIGN.md'), encoding='utf-8').read()
mermaids = []

def stash(m):
    mermaids.append(m.group(1))
    return '\n\n@@MERMAID%d@@\n\n' % (len(mermaids) - 1)

md_text = re.sub(r'```mermaid\n(.*?)```', stash, md_text, flags=re.S)
body = markdown.markdown(md_text, extensions=['tables', 'fenced_code', 'sane_lists', 'attr_list', 'toc'], toc_depth=3)

for i, src in enumerate(mermaids):
    esc = html.escape(src, quote=False)
    body = body.replace('<p>@@MERMAID%d@@</p>' % i, '<pre class="mermaid">%s</pre>' % esc)

body = re.sub(r'^<h1[^>]*>.*?</h1>', '', body, count=1, flags=re.S)
body, nav = slug_nav(body)
open(os.path.join(DOCS, 'DESIGN.html'), 'w', encoding='utf-8').write(
    shell('方案设计文档 · V1.2 Runtime', '五层架构 · 三模式调度 · 10 个核心工具 · 接口契约与验收 · Runtime 落地状态', body, nav, mermaid=True))

# ---------- 2. REQUIREMENTS-SRS.docx ----------
buf = io.BytesIO(open(os.path.join(DOCS, 'REQUIREMENTS-SRS.docx'), 'rb').read())
res = mammoth.convert_to_html(buf)
body = res.value
body = re.sub(r'<p>（在 Word 中按 F9 或右键“更新域”生成目录）</p>', '', body)
body = re.sub(r'<p><strong>目\s*录</strong></p>', '', body)
body = re.sub(r'^\s*(?:<p>.*?</p>\s*)+(?=<table>)', '', body, flags=re.S)
body, nav = slug_nav(body)
open(os.path.join(DOCS, 'REQUIREMENTS-SRS.html'), 'w', encoding='utf-8').write(
    shell('需求规格说明书', '45 条功能需求 · 17 条非功能需求 · 8 类外部接口 · 验收标准', body, nav, mermaid=False))

# ---------- 3. PRD.md ----------
prd_text = open(os.path.join(DOCS, 'PRD.md'), encoding='utf-8').read()
# 移除手写目录区块：侧边栏已生成自动目录，避免手写锚点与自动 id 不一致
prd_text = re.sub(r'^## 目录\n.*?\n---\n', '', prd_text, count=1, flags=re.S | re.M)
prd_mermaids = []

def prd_stash(m):
    prd_mermaids.append(m.group(1))
    return '\n\n@@MERMAID%d@@\n\n' % (len(prd_mermaids) - 1)

prd_text = re.sub(r'```mermaid\n(.*?)```', prd_stash, prd_text, flags=re.S)
prd_body = markdown.markdown(prd_text, extensions=['tables', 'fenced_code', 'sane_lists', 'attr_list', 'toc'], toc_depth=3)
for i, src in enumerate(prd_mermaids):
    prd_body = prd_body.replace('<p>@@MERMAID%d@@</p>' % i, '<pre class="mermaid">%s</pre>' % html.escape(src, quote=False))
prd_body = re.sub(r'^<h1[^>]*>.*?</h1>', '', prd_body, count=1, flags=re.S)
prd_body, prd_nav = slug_nav(prd_body)
open(os.path.join(DOCS, 'PRD.html'), 'w', encoding='utf-8').write(
    shell('产品需求文档 (PRD) · V1.0', '反向设计 · 10 章模板适配 · 64 条功能需求 · 26 条非功能需求 · 18 个验收场景 · RTM 与需求追溯', prd_body, prd_nav, mermaid=True))

print('DESIGN.html', os.path.getsize(os.path.join(DOCS, 'DESIGN.html')))
print('SRS.html', os.path.getsize(os.path.join(DOCS, 'REQUIREMENTS-SRS.html')))
print('PRD.html', os.path.getsize(os.path.join(DOCS, 'PRD.html')))
print('mermaid blocks: DESIGN=%d PRD=%d' % (len(mermaids), len(prd_mermaids)))
