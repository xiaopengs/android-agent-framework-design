#!/usr/bin/env python3
"""Build a single self-contained HTML knowledge base from the markdown parts.

All JS (marked + mermaid) and all chapter content are inlined, so the file
opens offline with no server and no network.
"""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
VENDOR = ROOT / "vendor"
PARTS = ROOT / "parts"
OUT = ROOT / "nanoMuse-ios-架构知识库.html"
TITLE = "nanoMuse iOS 端架构知识库"
BRAND = "nanoMuse · iOS 架构"
SUB = "基线 <code>067384b</code> · 2026-11-13<br>OpenMinis iOS 半 · iSH 沙箱"

CHAPTERS = [
        ("01-ios-architecture.md", "01 · iOS 架构与数据流", "架构"),
        ("02-ios-capabilities.md", "02 · iOS 能力全清单", "能力"),
]


def slug(text, used):
    s = re.sub(r"[^\w\u4e00-\u9fff]+", "-", text).strip("-").lower()[:60] or "sec"
    base, i = s, 2
    while s in used:
        s = f"{base}-{i}"
        i += 1
    used.add(s)
    return s


def build():
    parts, out = PARTS, OUT
    cfg = {"title": TITLE, "brand": BRAND, "sub": SUB, "chapters": CHAPTERS}
    docs, nav = [], []
    for idx, (fname, title, tag) in enumerate(cfg["chapters"]):
        md = (parts / fname).read_text(encoding="utf-8")
        cid = f"ch{idx}"
        used, toc = set(), []
        for line in md.split("\n"):
            m = re.match(r"^## (.+)$", line)
            if m:
                t = m.group(1).strip()
                toc.append({"id": slug(t, used), "text": t})
        docs.append({"id": cid, "title": title, "tag": tag, "md": md, "toc": toc})
        nav.append({"id": cid, "title": title, "tag": tag, "toc": toc})

    marked = (VENDOR / "marked.min.js").read_text(encoding="utf-8")
    mermaid = (VENDOR / "mermaid.min.js").read_text(encoding="utf-8")

    html = TEMPLATE.replace("__MARKED__", marked)
    html = html.replace("__MERMAID__", mermaid)
    html = html.replace("__DOCS__", json.dumps(docs, ensure_ascii=False))
    html = html.replace("__NAV__", json.dumps(nav, ensure_ascii=False))
    html = html.replace("__PAGETITLE__", cfg["title"])
    html = html.replace("__BRAND__", cfg["brand"])
    html = html.replace("__SUB__", cfg["sub"])
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(html, encoding="utf-8")
    print(f"wrote {out}  {out.stat().st_size/1024/1024:.2f} MiB")
    total_mermaid = sum(d["md"].count("```mermaid") for d in docs)
    print(f"chapters={len(docs)}  mermaid diagrams={total_mermaid}")


TEMPLATE = r"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>__PAGETITLE__</title>
<style>
:root{
  --bg:#0f1115; --panel:#161a21; --panel2:#1c2129; --line:#272d38;
  --fg:#dfe4ec; --dim:#9aa4b4; --accent:#6ea8fe; --accent2:#ffb454;
  --warn:#ff7b72; --ok:#7ee787; --code:#0b0d11;
}
*{box-sizing:border-box}
html{scroll-behavior:smooth}
body{margin:0;background:var(--bg);color:var(--fg);
  font:16px/1.75 -apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Hiragino Sans GB","Microsoft YaHei",sans-serif;}
#layout{display:flex;min-height:100vh}

/* ---------- sidebar ---------- */
#side{width:310px;flex:0 0 310px;background:var(--panel);border-right:1px solid var(--line);
  height:100vh;position:sticky;top:0;overflow-y:auto;padding:0 0 40px}
#brand{padding:20px 20px 14px;border-bottom:1px solid var(--line);position:sticky;top:0;background:var(--panel);z-index:5}
#brand h1{margin:0;font-size:16px;letter-spacing:.3px}
#brand .sub{color:var(--dim);font-size:12px;margin-top:6px;line-height:1.5}
#search{width:100%;margin-top:12px;padding:8px 10px;border-radius:8px;border:1px solid var(--line);
  background:var(--code);color:var(--fg);font-size:13px}
#search:focus{outline:none;border-color:var(--accent)}
.navchap{padding:10px 20px;cursor:pointer;border-left:3px solid transparent;font-size:14px;display:flex;gap:8px;align-items:baseline}
.navchap:hover{background:var(--panel2)}
.navchap.active{border-left-color:var(--accent);background:var(--panel2);color:#fff}
.navchap .tag{font-size:10px;color:var(--dim);border:1px solid var(--line);border-radius:4px;padding:1px 5px;flex:0 0 auto}
.navtoc{padding:2px 0 10px}
.navtoc a{display:block;padding:4px 20px 4px 34px;color:var(--dim);font-size:12.5px;text-decoration:none;
  border-left:3px solid transparent;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.navtoc a:hover{color:var(--fg);background:var(--panel2)}
.navtoc a.on{color:var(--accent);border-left-color:var(--accent)}

/* ---------- main ---------- */
#main{flex:1;min-width:0;padding:0 0 120px}
#top{position:sticky;top:0;background:rgba(15,17,21,.94);backdrop-filter:blur(8px);
  border-bottom:1px solid var(--line);padding:12px 40px;z-index:10;display:flex;gap:14px;align-items:center;flex-wrap:wrap}
#crumb{font-size:13px;color:var(--dim);flex:1;min-width:200px}
#crumb b{color:var(--fg)}
.btn{background:var(--panel2);border:1px solid var(--line);color:var(--fg);border-radius:7px;
  padding:6px 12px;font-size:12.5px;cursor:pointer}
.btn:hover{border-color:var(--accent);color:#fff}
#wrap{max-width:1080px;margin:0 auto;padding:32px 40px}
.chapter{display:none}
.chapter.show{display:block}

/* ---------- typography ---------- */
#wrap h1{font-size:29px;line-height:1.35;margin:8px 0 22px;padding-bottom:14px;border-bottom:2px solid var(--line)}
#wrap h2{font-size:22px;margin:44px 0 16px;padding:8px 0 8px 14px;border-left:4px solid var(--accent);
  background:linear-gradient(90deg,rgba(110,168,254,.09),transparent)}
#wrap h3{font-size:17.5px;margin:30px 0 12px;color:#fff}
#wrap h4{font-size:15.5px;margin:22px 0 10px;color:var(--accent2)}
#wrap p{margin:12px 0}
#wrap a{color:var(--accent)}
#wrap ul,#wrap ol{padding-left:26px;margin:12px 0}
#wrap li{margin:6px 0}
#wrap strong{color:#fff}
#wrap hr{border:0;border-top:1px solid var(--line);margin:34px 0}
#wrap blockquote{margin:16px 0;padding:12px 18px;background:var(--panel);
  border-left:4px solid var(--accent2);border-radius:0 8px 8px 0;color:#e8e2d4}
#wrap blockquote p{margin:6px 0}

/* ---------- code ---------- */
#wrap code{background:var(--code);border:1px solid var(--line);border-radius:4px;
  padding:1.5px 5px;font-size:13px;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;color:#ffd9a0}
#wrap pre{background:var(--code);border:1px solid var(--line);border-radius:10px;
  padding:16px 18px;overflow-x:auto;margin:16px 0;position:relative}
#wrap pre code{background:none;border:0;padding:0;color:#c9d1d9;font-size:13px;line-height:1.65}
.copy{position:absolute;top:8px;right:8px;background:var(--panel2);border:1px solid var(--line);
  color:var(--dim);border-radius:6px;padding:3px 9px;font-size:11px;cursor:pointer;opacity:0;transition:.15s}
#wrap pre:hover .copy{opacity:1}
.copy:hover{color:#fff;border-color:var(--accent)}

/* ---------- tables ---------- */
.tw{overflow-x:auto;margin:18px 0;border:1px solid var(--line);border-radius:10px}
#wrap table{border-collapse:collapse;width:100%;font-size:13.5px}
#wrap th{background:var(--panel2);text-align:left;padding:10px 13px;border-bottom:1px solid var(--line);
  color:#fff;font-weight:600;white-space:nowrap}
#wrap td{padding:9px 13px;border-bottom:1px solid var(--line);vertical-align:top}
#wrap tr:last-child td{border-bottom:0}
#wrap tbody tr:hover{background:rgba(110,168,254,.05)}
#wrap td code{font-size:12px;white-space:nowrap}

/* ---------- mermaid ---------- */
.mm{background:#fbfcfe;border:1px solid var(--line);border-radius:12px;margin:22px 0;
  padding:18px;overflow-x:auto;text-align:center;position:relative}
.mm svg{max-width:100%;height:auto}
.mm .zoom{position:absolute;top:8px;right:8px;background:#eef1f6;border:1px solid #ccd3de;color:#44506a;
  border-radius:6px;padding:3px 10px;font-size:11px;cursor:pointer;opacity:0;transition:.15s}
.mm:hover .zoom{opacity:1}
.mmerr{background:#2a1a1a;border:1px solid var(--warn);color:var(--warn);padding:12px;border-radius:8px;
  font-size:12px;font-family:monospace;white-space:pre-wrap;text-align:left}
#lb{position:fixed;inset:0;background:rgba(6,8,12,.96);z-index:999;display:none;
  overflow:auto;padding:30px;cursor:zoom-out}
#lb.show{display:block}
#lb svg{background:#fbfcfe;border-radius:10px;display:block;margin:0 auto}
#lbhint{position:fixed;top:12px;left:50%;transform:translateX(-50%);color:var(--dim);font-size:12px;z-index:1000}

/* ---------- misc ---------- */
#loading{padding:60px 40px;color:var(--dim);text-align:center}
.badge{display:inline-block;background:var(--panel2);border:1px solid var(--line);border-radius:20px;
  padding:3px 12px;font-size:12px;color:var(--dim);margin:0 6px 6px 0}
#progress{position:fixed;top:0;left:0;height:2px;background:var(--accent);z-index:1001;transition:width .2s}
#totop{position:fixed;right:24px;bottom:24px;width:42px;height:42px;border-radius:50%;
  background:var(--panel2);border:1px solid var(--line);color:var(--fg);cursor:pointer;font-size:17px;display:none}
#totop.show{display:block}
mark{background:rgba(255,180,84,.35);color:#fff;border-radius:3px}
@media(max-width:1000px){
  #side{position:fixed;left:-310px;z-index:100;transition:left .2s}
  #side.open{left:0}
  #wrap,#top{padding-left:18px;padding-right:18px}
  #menu{display:inline-block!important}
}
#menu{display:none}
</style>
</head>
<body>
<div id="progress"></div>
<div id="layout">
  <aside id="side">
    <div id="brand">
      <h1>__BRAND__</h1>
      <div class="sub">__SUB__</div>
      <input id="search" placeholder="搜索全部章节… (Esc 清空)">
    </div>
    <nav id="nav"></nav>
  </aside>
  <div id="main">
    <div id="top">
      <button class="btn" id="menu">☰</button>
      <div id="crumb"></div>
      <button class="btn" id="prev">← 上一章</button>
      <button class="btn" id="next">下一章 →</button>
    </div>
    <div id="loading">正在渲染图表…</div>
    <div id="wrap"></div>
  </div>
</div>
<div id="lb"><div id="lbhint">点击任意处关闭 · 可滚动查看大图</div><div id="lbbody"></div></div>
<button id="totop">↑</button>

<script>__MARKED__</script>
<script>__MERMAID__</script>
<script>
const DOCS = __DOCS__;
const NAV  = __NAV__;
let cur = 0;

/* ---- markdown setup: fence mermaid off before marked sees it ---- */
marked.setOptions({gfm:true, breaks:false, headerIds:false, mangle:false});

function renderMarkdown(md, cid){
  const blocks = [];
  // pull mermaid fences out so marked never touches them
  let src = md.replace(/```mermaid\r?\n([\s\S]*?)```/g, (m, code) => {
    blocks.push(code);
    return `\n\n<div class="mm" data-mm="${blocks.length-1}"></div>\n\n`;
  });
  let html = marked.parse(src);
  // wrap tables for horizontal scroll
  html = html.replace(/<table>/g, '<div class="tw"><table>').replace(/<\/table>/g, '</table></div>');
  // give h2 ids matching the TOC we precomputed
  const div = document.createElement('div');
  div.innerHTML = html;
  const toc = DOCS.find(d=>d.id===cid).toc;
  const h2s = div.querySelectorAll('h2');
  h2s.forEach((h,i)=>{ if(toc[i]) h.id = cid + '-' + toc[i].id; });
  // fill mermaid placeholders with escaped source
  div.querySelectorAll('.mm').forEach(el=>{
    const i = +el.dataset.mm;
    el.dataset.src = blocks[i];
    el.innerHTML = '<span style="color:#8894a8;font-size:12px">图表渲染中…</span>';
  });
  return div;
}

/* ---- build chapters ---- */
const wrap = document.getElementById('wrap');
const navEl = document.getElementById('nav');

DOCS.forEach((d,idx)=>{
  const sec = document.createElement('section');
  sec.className = 'chapter';
  sec.id = d.id;
  sec.appendChild(renderMarkdown(d.md, d.id));
  wrap.appendChild(sec);

  const head = document.createElement('div');
  head.className = 'navchap';
  head.dataset.idx = idx;
  head.innerHTML = `<span class="tag">${d.tag}</span><span>${d.title.replace(/^\d+\s·\s/,'')}</span>`;
  head.onclick = ()=>go(idx);
  navEl.appendChild(head);

  const toc = document.createElement('div');
  toc.className = 'navtoc';
  toc.dataset.for = idx;
  toc.style.display = idx===0 ? 'block' : 'none';
  d.toc.forEach(t=>{
    const a = document.createElement('a');
    a.href = '#' + d.id + '-' + t.id;
    a.textContent = t.text;
    a.title = t.text;
    a.onclick = e=>{
      e.preventDefault();
      go(idx, ()=>{
        const el = document.getElementById(d.id+'-'+t.id);
        if(el) el.scrollIntoView({behavior:'smooth', block:'start'});
      });
    };
    toc.appendChild(a);
  });
  navEl.appendChild(toc);
});

/* ---- mermaid ---- */
mermaid.initialize({
  startOnLoad:false, securityLevel:'loose', theme:'default',
  fontFamily:'-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif',
  flowchart:{htmlLabels:true, curve:'basis', useMaxWidth:true},
  sequence:{useMaxWidth:true, wrap:true, width:170},
  state:{useMaxWidth:true},
  themeVariables:{fontSize:'14px', primaryColor:'#eef2f8', primaryBorderColor:'#8fa3bf',
    lineColor:'#5a6b85', primaryTextColor:'#1b2430'}
});

async function drawDiagrams(sec){
  const nodes = [...sec.querySelectorAll('.mm')].filter(n=>!n.dataset.done);
  for(let i=0;i<nodes.length;i++){
    const n = nodes[i];
    const code = n.dataset.src || '';
    try{
      const id = 'svg_'+sec.id+'_'+i+'_'+Date.now();
      const {svg} = await mermaid.render(id, code);
      n.innerHTML = svg;
      n.dataset.done = '1';
      const z = document.createElement('button');
      z.className='zoom'; z.textContent='放大';
      z.onclick = ev=>{ ev.stopPropagation(); openLB(n.querySelector('svg')); };
      n.appendChild(z);
    }catch(err){
      n.innerHTML = '<div class="mmerr">图表渲染失败：'+(err && err.message ? err.message : err)
        + '\n\n--- 原始定义 ---\n' + code.replace(/[<>&]/g, c=>({'<':'&lt;','>':'&gt;','&':'&amp;'}[c])) + '</div>';
      n.dataset.done = 'err';
    }
  }
}

function openLB(svg){
  if(!svg) return;
  const c = svg.cloneNode(true);
  c.style.maxWidth='none';
  const vb = svg.getAttribute('viewBox');
  if(vb){ const p=vb.split(/\s+/); c.setAttribute('width', Math.max(1100, +p[2]*1.35)); c.removeAttribute('height'); }
  document.getElementById('lbbody').innerHTML='';
  document.getElementById('lbbody').appendChild(c);
  document.getElementById('lb').classList.add('show');
}
document.getElementById('lb').onclick = ()=>document.getElementById('lb').classList.remove('show');

/* ---- navigation ---- */
function go(idx, after){
  cur = Math.max(0, Math.min(DOCS.length-1, idx));
  document.querySelectorAll('.chapter').forEach((s,i)=>s.classList.toggle('show', i===cur));
  document.querySelectorAll('.navchap').forEach((h,i)=>h.classList.toggle('active', i===cur));
  document.querySelectorAll('.navtoc').forEach((t,i)=>t.style.display = i===cur ? 'block':'none');
  document.getElementById('crumb').innerHTML = `<b>${DOCS[cur].title}</b> <span style="opacity:.6">· ${cur+1} / ${DOCS.length}</span>`;
  history.replaceState(null,'','#'+DOCS[cur].id);
  document.getElementById('side').classList.remove('open');
  if(!after) window.scrollTo({top:0});
  drawDiagrams(document.getElementById(DOCS[cur].id));
  if(after) setTimeout(after, 60);
  // warm the next chapter's diagrams in the background
  if(cur+1 < DOCS.length) setTimeout(()=>drawDiagrams(document.getElementById(DOCS[cur+1].id)), 400);
}
document.getElementById('prev').onclick = ()=>go(cur-1);
document.getElementById('next').onclick = ()=>go(cur+1);
document.getElementById('menu').onclick = ()=>document.getElementById('side').classList.toggle('open');
document.getElementById('totop').onclick = ()=>window.scrollTo({top:0,behavior:'smooth'});

/* ---- copy buttons on code blocks ---- */
document.querySelectorAll('#wrap pre').forEach(pre=>{
  const b=document.createElement('button');
  b.className='copy'; b.textContent='复制';
  b.onclick=async()=>{
    try{ await navigator.clipboard.writeText(pre.querySelector('code').innerText);
      b.textContent='已复制'; setTimeout(()=>b.textContent='复制',1200);
    }catch(e){ b.textContent='失败'; setTimeout(()=>b.textContent='复制',1200); }
  };
  pre.appendChild(b);
});

/* ---- search across all chapters ---- */
const search = document.getElementById('search');
let searchTimer;
search.oninput = ()=>{ clearTimeout(searchTimer); searchTimer=setTimeout(doSearch, 220); };
search.onkeydown = e=>{ if(e.key==='Escape'){ search.value=''; doSearch(); } };

function doSearch(){
  const q = search.value.trim().toLowerCase();
  document.querySelectorAll('.navtoc a').forEach(a=>{
    a.style.display = !q || a.textContent.toLowerCase().includes(q) ? 'block':'none';
  });
  if(!q){
    document.querySelectorAll('.navtoc').forEach((t,i)=>t.style.display = i===cur?'block':'none');
    document.querySelectorAll('.navchap').forEach(h=>h.style.display='flex');
    return;
  }
  // show every toc group that has a hit, and mark chapter hits
  document.querySelectorAll('.navtoc').forEach((t,i)=>{
    const any = [...t.querySelectorAll('a')].some(a=>a.style.display==='block');
    const chapHit = DOCS[i].md.toLowerCase().includes(q);
    t.style.display = (any||chapHit) ? 'block':'none';
    document.querySelectorAll('.navchap')[i].style.display = (any||chapHit) ? 'flex':'none';
  });
}

/* ---- scroll spy + progress ---- */
window.addEventListener('scroll', ()=>{
  const h = document.documentElement;
  const pct = h.scrollTop / Math.max(1,(h.scrollHeight - h.clientHeight));
  document.getElementById('progress').style.width = (pct*100)+'%';
  document.getElementById('totop').classList.toggle('show', h.scrollTop>600);
  const sec = document.getElementById(DOCS[cur].id);
  let active = null;
  sec.querySelectorAll('h2').forEach(h=>{ if(h.getBoundingClientRect().top < 140) active = h.id; });
  document.querySelectorAll('.navtoc a').forEach(a=>{
    a.classList.toggle('on', a.getAttribute('href') === '#'+active);
  });
}, {passive:true});

/* ---- keyboard ---- */
document.addEventListener('keydown', e=>{
  if(e.target === search) return;
  if(e.key==='ArrowLeft') go(cur-1);
  if(e.key==='ArrowRight') go(cur+1);
  if(e.key==='/'){ e.preventDefault(); search.focus(); }
});

/* ---- boot ---- */
(async()=>{
  document.getElementById('loading').style.display='none';
  const hash = location.hash.replace('#','');
  const idx = DOCS.findIndex(d=>d.id===hash || hash.startsWith(d.id+'-'));
  go(idx>=0?idx:0, ()=>{
    if(hash && hash.includes('-')){
      const el=document.getElementById(hash);
      if(el) el.scrollIntoView({block:'start'});
    }
  });
})();
</script>
</body>
</html>
"""

if __name__ == "__main__":
    build()