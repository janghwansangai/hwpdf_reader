package com.fastreader.app.core.render

/**
 * 쉘 HTML 에 넣는 기본 CSS 와 자바스크립트.
 * (Kotlin raw string 안이므로 JS 에서 달러 기호를 쓰지 않는다.)
 */
internal object RuntimeAssets {

    /** 원본 보기 기본 CSS. 문서별 .c/.k/.g 규칙이 뒤에 붙는다 */
    val PAGE_CSS = """
html,body{margin:0;padding:0}
html{-webkit-text-size-adjust:none;text-size-adjust:none}
body{background:#e8eaed;-webkit-tap-highlight-color:transparent}
body.dk{background:#2b2c2f}
#doc{padding:8px 0 28px}
.pg{position:relative;margin:0 auto 10px;background:#fff;color:#000;box-shadow:0 1px 4px rgba(0,0,0,.28);overflow:hidden}
.bd,.l,.ob,.td,.fb,.hf,.pn,.cp,.oi,.pb,.nr,.fn,.pbg{position:absolute}
.l{white-space:pre;line-height:0;transform-origin:0 0;overflow:visible}
.l.ac{text-align:center}.l.ar{text-align:right}
.s{display:inline-block;width:0;vertical-align:baseline}
.w{display:inline-block;width:0;overflow:hidden;vertical-align:baseline}
.n{display:none}
.rq{display:inline-block;transform-origin:0 0}
.t{display:inline-block;overflow:hidden;vertical-align:baseline;height:0}
.ld{border-bottom:1px solid currentColor}.ldd{border-bottom-style:dotted}.lda{border-bottom-style:dashed}
.hg{display:inline-block}
.ob.ib{position:relative;display:inline-block}
.tbg,.pic,.sh,.ph,.bx{position:absolute;left:0;top:0;right:0;bottom:0}
.bx{pointer-events:none}
.gl{position:absolute;width:0;height:0;pointer-events:none;z-index:1}
.pic{overflow:hidden}.pic img{display:block;width:100%;height:100%}
.ph{border:1px dashed #b4b4b4;display:flex;align-items:center;justify-content:center;color:#9a9a9a;font:12px sans-serif;box-sizing:border-box;background:#fafafa}
.sh{box-sizing:border-box}
.sv{position:absolute;left:0;top:0;overflow:visible}
.dg{position:absolute;left:0;top:0;width:100%;height:100%;overflow:visible}
.eq{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;white-space:nowrap;font-family:"Noto Serif KR","Noto Serif CJK KR",serif;line-height:1.15}
.ta{position:absolute;left:0;top:0;right:0;bottom:0;display:flex;align-items:center;justify-content:center;white-space:nowrap}
.fb{white-space:pre-wrap;word-break:keep-all;overflow-wrap:anywhere}
.fb .p{margin:0}
.fb .p.e::after{content:"\a0"}
.fb .tb{border-collapse:collapse;table-layout:fixed}
.fb .imw{display:inline-block;position:relative;overflow:hidden;vertical-align:middle}
.fb .imw img{display:block;width:100%;height:100%}
.nr,.fn{height:0;border-top:1px solid #000}
.hd{white-space:pre}
.rb{position:relative;display:inline-block}
.rt{position:absolute;left:-50%;right:-50%;bottom:100%;font-size:50%;text-align:center;line-height:1;white-space:nowrap}
.rbl{bottom:auto;top:100%}
sup.fr{font-size:.6em;vertical-align:super;line-height:0}
a.lk{color:inherit;text-decoration:inherit}
.err{position:absolute;left:0;right:0;top:40%;text-align:center;color:#999;font:14px sans-serif}
""" + COMMON_CSS

    /** 읽기 모드 기본 CSS */
    val FLOW_CSS = """
html{font-size:16px;-webkit-text-size-adjust:none;text-size-adjust:none}
body{margin:0;background:#fff;color:#1b1b1b;word-break:keep-all;overflow-wrap:break-word;-webkit-tap-highlight-color:transparent}
body.dk{background:#141414;color:#e4e4e4}
#flow{padding:14px 16px 48px;max-width:48rem;margin:0 auto}
.p{white-space:pre-wrap;margin:0;tab-size:4}
.p.e::after{content:"\a0"}
.tt{display:inline-block;min-width:1.5em}
.obk{margin:.45em 0;max-width:100%}
.tw{overflow-x:auto;max-width:100%;margin:.4em 0;-webkit-overflow-scrolling:touch}
.tb{border-collapse:collapse;table-layout:auto;font-size:.88em}
.tb .p{line-height:1.45}
.imw{display:inline-block;position:relative;overflow:hidden;max-width:100%;vertical-align:middle}
.imw img{display:block;width:100%;height:100%}
.phf{display:inline-block;border:1px dashed #999;color:#888;padding:.15em .6em;font-size:.8rem}
.tbx{padding:.5em .7em;margin:.4em 0;max-width:100%;box-sizing:border-box}
.cap{font-size:.92em;margin:.25em 0}
.pbx{padding:.3em .5em}
.notes{margin-top:1.1em;border-top:1px solid rgba(128,128,128,.45);padding-top:.5em;font-size:.88em}
.note{display:flex;gap:.4em;margin:.2em 0}.nn{flex:none}.nb{flex:1;min-width:0}
hr.sec{border:none;border-top:1px dashed rgba(128,128,128,.6);margin:1.6em 0}
.eqf{font-family:"Noto Serif KR","Noto Serif CJK KR",serif;white-space:nowrap}
.ta2{font-weight:700}
.hd{white-space:pre}
.rb{display:inline-flex;flex-direction:column-reverse;align-items:center;vertical-align:bottom;line-height:1.1}
.rt{font-size:55%}
.rbl{order:-1}
sup.fr{font-size:.65em;vertical-align:super;line-height:0}
a.lk{color:#1565c0;text-decoration:underline}
body.dk a.lk{color:#8ab4f8}
#sent{height:1px}
""" + COMMON_CSS

    private const val COMMON_CSS = """
.ef{display:inline-flex;flex-direction:column;vertical-align:middle;text-align:center;font-size:.9em;margin:0 .1em;line-height:1.15}
.en{border-bottom:1px solid currentColor;padding:0 .15em}.ed{padding:0 .15em}
.eo{border-top:1px solid currentColor;padding-top:.05em}.ei{font-size:.6em;margin-right:-.3em}
.eq sup:not(.ei),.eqf sup:not(.ei){font-size:.7em;vertical-align:.5em;line-height:0}.eq sub,.eqf sub{font-size:.7em;vertical-align:-.3em;line-height:0}
mark.hit{background:rgba(255,213,79,.8);color:inherit;border-radius:2px}
mark.hit.cur{background:rgba(255,152,0,.95)}
"""

    /**
     * 실행 코드. html[data-mode] 가 "page" 면 원본 보기, "flow" 면 읽기 모드.
     * Android 브리지: window.FRBridge (pageHtml, flowHtml, onReady, onPosition, onLink). 없으면 window.FR_FRAGMENTS 사용.
     */
    val JS = """
(function () {
  'use strict';
  var root = document.documentElement;
  var MODE = root.getAttribute('data-mode');
  var META = window.FR_META || {};
  var B = window.FRBridge || null;
  var F = window.FR_FRAGMENTS || null;
  var hits = [];

  function frag(kind, i) {
    try {
      if (B) { var s = kind === 'page' ? B.pageHtml(i) : B.flowHtml(i); return s == null ? '' : String(s); }
      if (F && F[kind]) return F[kind][i] || '';
    } catch (e) {}
    return '';
  }
  function call(name, arg) { try { if (B && B[name]) B[name](arg); } catch (e) {} }
  function parse(s) { if (typeof s !== 'string') return s; try { return JSON.parse(s); } catch (e) { return null; } }
  function keyNum(a) { var n = parseInt(String(a || '').replace(/^[ph]/, ''), 10); return isNaN(n) ? 0 : n; }
  function lastLE(arr, v) {
    var lo = 0, hi = arr.length - 1, r = 0;
    while (lo <= hi) { var m = (lo + hi) >> 1; if (arr[m] <= v) { r = m; lo = m + 1; } else hi = m - 1; }
    return r;
  }
  function progress() {
    var h = root.scrollHeight - window.innerHeight;
    return h > 0 ? Math.min(1, Math.max(0, window.scrollY / h)) : 0;
  }

  // ---- 검색 위치 계산: 생성 글자(.gen), 개체(.ob/.obk), 안쪽 문단은 건너뛴다
  function skipped(node, container) {
    for (var n = node.parentNode; n && n !== container; n = n.parentNode) {
      if (n.nodeType !== 1) continue;
      var c = n.classList;
      if (c.contains('gen') || c.contains('ob') || c.contains('obk') || n.hasAttribute('data-p')) return true;
    }
    return false;
  }
  function textNodes(container) {
    var out = [], w = document.createTreeWalker(container, NodeFilter.SHOW_TEXT, null), n;
    while ((n = w.nextNode())) if (!skipped(n, container)) out.push(n);
    return out;
  }
  function wrap(nodes, start, len) {
    var marks = [], pos = 0, end = start + len;
    for (var k = 0; k < nodes.length && pos < end; k++) {
      var t = nodes[k], L = t.nodeValue.length, a = Math.max(start, pos), b = Math.min(end, pos + L);
      if (a < b) {
        var r = document.createRange();
        r.setStart(t, a - pos); r.setEnd(t, b - pos);
        var m = document.createElement('mark'); m.className = 'hit';
        try { r.surroundContents(m); marks.push(m); } catch (e) {}
      }
      pos += L;
    }
    return marks;
  }
  function clearHits() {
    for (var i = 0; i < hits.length; i++) {
      var m = hits[i], p = m.parentNode;
      if (!p) continue;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
      p.normalize();
    }
    hits = [];
  }
  function reveal() {
    if (!hits.length) return;
    hits.forEach(function (m) { m.classList.add('cur'); });
    var r = hits[0].getBoundingClientRect();
    window.scrollTo(0, window.scrollY + r.top - window.innerHeight * 0.35);
  }

  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a.lk') : null;
    if (a) { e.preventDefault(); call('onLink', a.getAttribute('href')); }
  }, true);

  var tmr = 0;
  function onScroll() { if (tmr) return; tmr = setTimeout(function () { tmr = 0; call('onPosition', JSON.stringify(api.stateObj())); }, 200); }

  var api;

  // ================================================================== 원본 보기
  function initPage() {
    var docEl = document.getElementById('doc');
    var pages = [];
    for (var c = docEl.firstElementChild; c; c = c.nextElementSibling) if (c.classList.contains('pg')) pages.push(c);
    var maxW = parseFloat(docEl.getAttribute('data-maxw')) || 794;
    var keys = META.keys || [];
    var loaded = {}, count = 0;

    function fit() {
      var avail = Math.max(160, root.clientWidth - 12);
      docEl.style.zoom = Math.min(avail / maxW, 2.5);
    }

    function justify(l, extra) {
      var spaces = 0, chars = 0, nodes = textNodes(l);
      for (var k = 0; k < nodes.length; k++) {
        var p = nodes[k].parentNode;
        if (p && p.classList && p.classList.contains('w')) continue;
        var s = nodes[k].nodeValue;
        for (var j = 0; j < s.length; j++) {
          var ch = s.charCodeAt(j);
          if (ch === 32) spaces++; else if (ch !== 10 && ch !== 9) chars++;
        }
      }
      if (spaces > 0) { if (extra / spaces < 60) l.style.wordSpacing = (extra / spaces) + 'px'; }
      else if (chars > 1 && extra / (chars - 1) < 20) l.style.letterSpacing = (extra / (chars - 1)) + 'px';
    }

    // 글꼴이 한글 프로그램과 달라 넘치는 줄은 가로로 줄이고, 양쪽 정렬 줄은 남는 폭을 띄어쓰기에 나눈다
    function fitLines(el) {
      var ls = el.querySelectorAll('.l'), n = ls.length, sw = new Array(n), cw = new Array(n), k;
      for (k = 0; k < n; k++) { sw[k] = ls[k].scrollWidth; cw[k] = ls[k].clientWidth; }
      for (k = 0; k < n; k++) {
        var l = ls[k];
        if (cw[k] <= 0) continue;
        if (sw[k] > cw[k] + 0.5) {
          var r = parseFloat(l.getAttribute('data-r') || '1') || 1;
          l.style.width = sw[k] + 'px';
          l.style.transform = 'scaleX(' + (r * cw[k] / sw[k]) + ')';
        } else if (sw[k] < cw[k] - 1 && (l.classList.contains('aj') || l.classList.contains('ad'))) {
          justify(l, cw[k] - sw[k]);
        }
      }
    }

    function load(i) {
      if (i < 0 || i >= pages.length || loaded[i]) return;
      var el = pages[i];
      if (!el.firstChild) el.innerHTML = frag('page', i);
      loaded[i] = true; count++;
      fitLines(el);
    }
    function unload(i) {
      var el = pages[i];
      if (!loaded[i] || el.querySelector('mark.hit')) return;
      el.innerHTML = '';
      delete loaded[i]; count--;
    }
    function current() {
      var mid = window.innerHeight * 0.3, lo = 0, hi = pages.length - 1;
      while (lo < hi) { var m = (lo + hi + 1) >> 1; if (pages[m].getBoundingClientRect().top <= mid) lo = m; else hi = m - 1; }
      return lo;
    }
    function prune() {
      if (count <= 14) return;
      var cur = current();
      for (var k in loaded) if (Math.abs(k - cur) > 5) unload(+k);
    }
    function goToPage(i) {
      i = Math.max(0, Math.min(pages.length - 1, i | 0));
      load(i); load(i + 1);
      window.scrollTo(0, window.scrollY + pages[i].getBoundingClientRect().top - 6);
    }
    function goToProgress(f) {
      window.scrollTo(0, Math.max(0, Math.min(1, +f || 0)) * (root.scrollHeight - window.innerHeight));
    }
    function stateObj() {
      var c = current(), r = pages[c].getBoundingClientRect();
      var off = r.height > 0 ? Math.min(1, Math.max(0, -r.top / r.height)) : 0;
      return { mode: 'page', page: c, total: pages.length, progress: progress(), offset: off, anchor: 'p' + (keys[c] || 0) };
    }
    function restore(s) {
      s = parse(s);
      if (!s) return;
      var i = -1;
      if (s.mode === 'page' && typeof s.page === 'number') i = s.page;
      else if (s.anchor && keys.length) i = lastLE(keys, keyNum(s.anchor));
      else if (typeof s.progress === 'number') { goToProgress(s.progress); return; }
      if (i < 0) return;
      i = Math.min(i, pages.length - 1);
      goToPage(i);
      if (s.mode === 'page' && s.offset > 0) window.scrollBy(0, pages[i].getBoundingClientRect().height * s.offset);
    }
    function showHit(h) {
      h = parse(h);
      if (!h) return;
      clearHits();
      var i = Math.max(0, Math.min(pages.length - 1, h.page | 0));
      load(i - 1); load(i); load(i + 1);
      var els = docEl.querySelectorAll('.l[data-p="' + String(h.paraKey).replace(/[^a-z0-9]/gi, '') + '"]');
      var lines = [];
      for (var k = 0; k < els.length; k++) lines.push(els[k]);
      lines.sort(function (a, b) { return (+(a.getAttribute('data-o') || 0)) - (+(b.getAttribute('data-o') || 0)); });
      var start = h.offset | 0, end = start + (h.length | 0);
      for (k = 0; k < lines.length; k++) {
        var o = +(lines[k].getAttribute('data-o') || 0);
        var nodes = textNodes(lines[k]), len = 0;
        for (var j = 0; j < nodes.length; j++) len += nodes[j].nodeValue.length;
        if (o + len <= start || o >= end) continue;
        var a = Math.max(start, o) - o, b = Math.min(end, o + len) - o;
        hits = hits.concat(wrap(nodes, a, b - a));
      }
      if (hits.length) reveal(); else goToPage(i);
    }

    fit();
    // 화면 회전 등으로 폭이 바뀌면 배율이 달라지므로 보던 쪽과 쪽 안 위치를 유지한다
    window.addEventListener('resize', function () {
      var c = current(), r = pages[c].getBoundingClientRect();
      var off = r.height > 0 ? -r.top / r.height : 0;
      fit();
      var r2 = pages[c].getBoundingClientRect();
      window.scrollTo(0, window.scrollY + r2.top + off * r2.height);
      onScroll();
    });
    for (var k = 0; k < pages.length; k++) if (pages[k].firstChild) load(k);
    var io = new IntersectionObserver(function (es) {
      for (var q = 0; q < es.length; q++) if (es[q].isIntersecting) load(+es[q].target.getAttribute('data-i'));
      prune();
    }, { rootMargin: '150% 0px 150% 0px' });
    pages.forEach(function (p) { io.observe(p); });
    window.addEventListener('scroll', onScroll, { passive: true });

    return { goToPage: goToPage, goToProgress: goToProgress, stateObj: stateObj, restore: restore, showHit: showHit, setTextScale: function () {} };
  }

  // ================================================================== 읽기 모드
  function initFlow() {
    var flow = document.getElementById('flow');
    var sent = document.getElementById('sent');
    var total = +flow.getAttribute('data-total') || 1;
    var ckeys = META.keys || [0];
    var keyCount = META.keyCount || 1;
    var next = flow.querySelectorAll('.ck').length;

    function loadChunk() {
      if (next >= total) { sent.style.display = 'none'; return false; }
      var d = document.createElement('div');
      d.className = 'ck';
      d.setAttribute('data-c', String(next));
      d.innerHTML = frag('flow', next);
      flow.insertBefore(d, sent);
      next++;
      return true;
    }
    function ensure(c) { while (next <= c && loadChunk()) {} }
    var pumping = false;
    function pump() {
      if (pumping) return;
      pumping = true;
      requestAnimationFrame(function () {
        pumping = false;
        if (next < total && sent.getBoundingClientRect().top < window.innerHeight * 4 && loadChunk()) pump();
      });
    }
    function tops() { return flow.querySelectorAll('.ck > .p'); }
    function scrollToKey(k) {
      ensure(lastLE(ckeys, k));
      var ps = tops(), best = null;
      for (var i = 0; i < ps.length; i++) { var v = keyNum(ps[i].getAttribute('data-p')); if (v <= k) best = ps[i]; else break; }
      if (best) window.scrollTo(0, window.scrollY + best.getBoundingClientRect().top - 8);
      pump();
    }
    function anchor() {
      var ps = tops(), lo = 0, hi = ps.length - 1;
      if (hi < 0) return null;
      while (lo < hi) { var m = (lo + hi + 1) >> 1; if (ps[m].getBoundingClientRect().top <= 4) lo = m; else hi = m - 1; }
      return ps[lo].getAttribute('data-p');
    }
    function stateObj() {
      var a = anchor();
      return { mode: 'flow', progress: Math.min(1, keyNum(a) / keyCount), anchor: a || 'p0' };
    }
    function goToProgress(f) { scrollToKey(Math.floor(Math.max(0, Math.min(1, +f || 0)) * keyCount)); }
    function restore(s) {
      s = parse(s);
      if (!s) return;
      if (s.anchor) scrollToKey(keyNum(s.anchor));
      else if (typeof s.progress === 'number') goToProgress(s.progress);
    }
    function showHit(h) {
      h = parse(h);
      if (!h) return;
      clearHits();
      ensure(h.chunk | 0);
      var el = flow.querySelector('.p[data-p="' + String(h.paraKey).replace(/[^a-z0-9]/gi, '') + '"]');
      if (!el) return;
      hits = wrap(textNodes(el), h.offset | 0, h.length | 0);
      if (hits.length) reveal(); else window.scrollTo(0, window.scrollY + el.getBoundingClientRect().top - window.innerHeight * 0.35);
      pump();
    }
    function setTextScale(s) {
      var v = Math.max(0.6, Math.min(3, +s || 1));
      var a = anchor();
      root.style.fontSize = (16 * v) + 'px';
      if (a) scrollToKey(keyNum(a));
    }

    var io = new IntersectionObserver(function (es) { if (es[0] && es[0].isIntersecting) pump(); }, { rootMargin: '0px 0px 300% 0px' });
    io.observe(sent);
    window.addEventListener('scroll', function () { onScroll(); pump(); }, { passive: true });
    window.addEventListener('resize', onScroll);
    pump();
    return { goToPage: function () {}, goToProgress: goToProgress, stateObj: stateObj, restore: restore, showHit: showHit, setTextScale: setTextScale };
  }

  api = MODE === 'page' ? initPage() : initFlow();
  window.FR = {
    goToPage: function (i) { api.goToPage(i); },
    goToProgress: function (f) { api.goToProgress(f); },
    state: function () { return JSON.stringify(api.stateObj()); },
    restore: function (s) { api.restore(s); },
    showHit: function (h) { api.showHit(h); },
    clearHits: clearHits,
    setTextScale: function (s) { api.setTextScale(s); }
  };
  requestAnimationFrame(function () {
    window.FR_READY = true;
    call('onReady', MODE);
    call('onPosition', JSON.stringify(api.stateObj()));
  });
})();
"""
}
