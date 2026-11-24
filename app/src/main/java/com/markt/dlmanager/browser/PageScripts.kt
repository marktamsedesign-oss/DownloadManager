package com.markt.dlmanager.browser

/** JavaScript the browser runs inside pages. */
object PageScripts {

    /**
     * Ad shield, run on every page where ads are blocked:
     * - hides common ad boxes (AdSense, Google ad slots, Taboola/Outbrain widgets);
     * - removes layers that exist only to steal taps: big, invisible (or empty, transparent) elements
     *   stacked on top of the page, and full-screen ad frames. It checks right under your finger
     *   when you touch the screen, so the tap lands on the real button instead of an ad.
     */
    val shield: String by lazy {
        """
        (function () {
          if (window.__dlmShield) return;
          window.__dlmShield = true;
          var AD = ${Shields.adHostsJs};
          function adHost(u) {
            var h;
            try { h = new URL(u, location.href).hostname.toLowerCase(); } catch (e) { return false; }
            while (h) {
              if (AD.indexOf(h) >= 0) return true;
              var i = h.indexOf('.');
              if (i < 0) return false;
              h = h.substring(i + 1);
            }
            return false;
          }
          var css = 'ins.adsbygoogle,.adsbygoogle,[id^="google_ads_iframe"],[id^="div-gpt-ad"],[id^="aswift_"],' +
                    'div[id^="taboola-"],.trc_related_container,.OUTBRAIN,.ob-widget{display:none!important}';
          function addCss() {
            if (document.getElementById('__dlm_css')) return;
            var s = document.createElement('style');
            s.id = '__dlm_css';
            s.textContent = css;
            (document.head || document.documentElement).appendChild(s);
          }
          function stealer(el) {
            if (!el || el === document.body || el === document.documentElement) return false;
            var cs = getComputedStyle(el);
            if (cs.pointerEvents === 'none' || cs.visibility === 'hidden') return false;
            if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
            if (!(parseInt(cs.zIndex, 10) >= 100)) return false;
            var r = el.getBoundingClientRect();
            if (r.width * r.height < 0.4 * innerWidth * innerHeight) return false;
            if (el.tagName === 'IFRAME') {
              var src = el.getAttribute('src') || '';
              return !src || src === 'about:blank' || src.indexOf('javascript:') === 0 || adHost(src);
            }
            if (parseFloat(cs.opacity) < 0.1) return true;
            var clear = (cs.backgroundColor === 'transparent' || cs.backgroundColor === 'rgba(0, 0, 0, 0)') &&
                        cs.backgroundImage === 'none';
            return clear && !(el.innerText || '').trim();
          }
          function stealerOf(el) {
            for (var e = el; e && e !== document.body && e !== document.documentElement; e = e.parentElement) {
              if (stealer(e)) return e;
            }
            return null;
          }
          function clearAt(x, y) {
            for (var k = 0; k < 5; k++) {
              var s = stealerOf(document.elementFromPoint(x, y));
              if (!s) return;
              s.remove();
            }
          }
          function sweep() {
            var w = innerWidth, h = innerHeight;
            [[.5, .5], [.5, .25], [.5, .75], [.25, .5], [.75, .5]].forEach(function (p) { clearAt(w * p[0], h * p[1]); });
          }
          addCss();
          sweep();
          addEventListener('touchstart', function (e) {
            var t = e.touches && e.touches[0];
            if (t) clearAt(t.clientX, t.clientY);
          }, { capture: true, passive: true });
          addEventListener('pointerdown', function (e) { clearAt(e.clientX, e.clientY); }, true);
          var pending = 0;
          new MutationObserver(function () {
            if (pending) return;
            pending = setTimeout(function () { pending = 0; addCss(); sweep(); }, 400);
          }).observe(document.documentElement, { childList: true, subtree: true });
        })();
        """.trimIndent()
    }

    /** Lists every link and media source on the page as [{u: url, t: text}]. */
    val findLinks: String = """
        (function () {
          var out = [], seen = {};
          function add(u, t) {
            if (!u || seen[u]) return;
            seen[u] = 1;
            out.push({ u: u, t: String(t || '').replace(/\s+/g, ' ').trim().slice(0, 120) });
          }
          document.querySelectorAll('a[href]').forEach(function (a) {
            add(a.href, a.innerText || a.title || a.getAttribute('aria-label') || a.getAttribute('download'));
          });
          document.querySelectorAll('video[src],audio[src],source[src]').forEach(function (m) { add(m.src, 'Media file'); });
          document.querySelectorAll('[data-href],[data-url],[data-link],[data-download]').forEach(function (e) {
            var v = e.getAttribute('data-href') || e.getAttribute('data-url') || e.getAttribute('data-link') || e.getAttribute('data-download');
            try { add(new URL(v, location.href).href, e.innerText); } catch (x) {}
          });
          return out.slice(0, 800);
        })();
    """.trimIndent()
}
