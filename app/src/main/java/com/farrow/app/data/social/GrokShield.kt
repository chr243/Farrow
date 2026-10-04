package com.farrow.app.data.social

import org.jsoup.nodes.Element

/**
 * v1.0.7: X's Grok drawer / floating Grok button kept covering the page (phone report: a tool ran out of its
 * time budget on a page with a Grok drawer). While an X tool runs, a page-scope shield hides them:
 *  - a persistent style rule for the known roots (`[data-testid="GrokDrawer"]`…) and for every root it marked;
 *  - a MutationObserver that marks new Grok overlays: an element whose data-testid contains "grok" or whose
 *    aria-label contains "Grok", or a dialog/fixed panel whose header reads "Grok", climbed up to its outermost
 *    FIXED/ABSOLUTE ancestor (or #layers child) — never one that contains a composer, a post or the main column;
 *  - its close button is clicked once, then the root is hidden (display:none + pointer-events:none).
 * Lapses by itself after [TTL_MS] if the release eval never comes. Pure Kotlin twin [isGrokOverlay] for snapshots.
 */
object GrokShield {
    const val ATTR = "data-farrow-grok"
    const val TTL_MS = 90_000L

    /** Never hidden: anything that contains one of these. */
    const val PROTECT = "[data-testid^=\"tweetTextarea_\"],[data-testid^=\"tweetButton\"],[data-testid=\"primaryColumn\"],article,[data-testid=\"toast\"]"

    private val GROK_TESTID = Regex("grok", RegexOption.IGNORE_CASE)
    private val GROK_LABEL = Regex("\\bgrok\\b", RegexOption.IGNORE_CASE)
    private val GROK_HEADER = Regex("^\\s*grok\\b", RegexOption.IGNORE_CASE)
    /** Composer controls that merely mention Grok (image generation, "Explain with Grok") are not overlays. */
    private val NOT_OVERLAY = setOf("grokimggen")

    /** For snapshots (Jsoup): is this overlay root Grok's (marked by the shield, or Grok-looking without a composer)? */
    fun isGrokOverlay(root: Element): Boolean {
        if (root.hasAttr(ATTR)) return true
        if (root.selectFirst(StrayDialogs.jsoupCss(PROTECT)) != null) return false
        if (root.selectFirst("[$ATTR]") != null) return true
        val tid = root.select("[data-testid]").any { e -> e.attr("data-testid").let { GROK_TESTID.containsMatchIn(it) && it.lowercase() !in NOT_OVERLAY } }
        val label = root.select("[aria-label]").any { GROK_LABEL.containsMatchIn(it.attr("aria-label")) }
        val header = root.select("h1,h2,h3,[role=heading]").firstOrNull()?.text()?.let { GROK_HEADER.containsMatchIn(it) } == true
        return tid || label || header
    }

    /** Idempotent install (extends the TTL when already on). Returns 'on'. Safe on non-X pages (does nothing there). */
    val ON = """(function(){var w=window;var h=location.hostname.replace(/^(www|mobile)\./,'');
      if(!/^(x|twitter)\.com$/.test(h))return 'off';
      if(w.__fwGrok){w.__fwGrok.until=Date.now()+$TTL_MS;w.__fwGrok.scan();return 'on'}
      var st={until:Date.now()+$TTL_MS,hidden:0};var A='$ATTR';var P=${kotlinx.serialization.json.JsonPrimitive(PROTECT)};
      var css=document.createElement('style');css.setAttribute('data-farrow-grok-style','1');
      css.textContent='['+A+'],[data-testid="GrokDrawer"],[data-testid="GrokDrawerHeader"],[data-testid="grokFloatingButton"]'+
        '{display:none!important;pointer-events:none!important;visibility:hidden!important}';
      (document.head||document.documentElement).appendChild(css);st.css=css;
      var pos=function(e){try{var p=getComputedStyle(e).position;return p==='fixed'||p==='absolute'||p==='sticky'}catch(x){return false}};
      var layers=document.getElementById('layers');
      var rootOf=function(e){var r=null,c=e;while(c&&c!==document.body&&c!==document.documentElement){
          if(layers&&c.parentElement===layers){r=c;break}
          if(c.id==='react-root'||c.id==='layers')break;
          if(pos(c)||(c.getAttribute&&c.getAttribute('role')==='dialog'))r=c;c=c.parentElement}
        return r};
      var grokish=function(e){var t=(e.getAttribute('data-testid')||'');if(/grok/i.test(t)&&t.toLowerCase()!=='grokimggen')return true;
        if(/\bgrok\b/i.test(e.getAttribute('aria-label')||''))return true;return false};
      var hide=function(r,why){if(!r||r.hasAttribute(A))return;if(r.matches(P)||r.querySelector(P))return;
        var c=r.querySelector('[aria-label="Close"],[aria-label="Fermer"],[data-testid="app-bar-close"],[aria-label*="Close" i]');
        r.setAttribute(A,why);try{if(c)c.click()}catch(x){}st.hidden++};
      st.scan=function(){if(Date.now()>st.until){w.__fwGrokOff();return}
        document.querySelectorAll('[data-testid*="grok" i],[aria-label*="Grok" i]').forEach(function(e){if(!grokish(e))return;
          if(e.closest('a[href]')&&!pos(e)&&!rootOf(e))return; /* nav links like /i/grok stay */
          var r=rootOf(e);if(r)hide(r,'grok')});
        document.querySelectorAll('[role="dialog"],[aria-modal="true"]'+(layers?',#layers > *':'')).forEach(function(d){
          var hd=d.querySelector('h1,h2,h3,[role="heading"]');if(hd&&/^\s*grok\b/i.test(hd.textContent||''))hide(rootOf(d)||d,'grok header')})};
      var t=null;st.mo=new MutationObserver(function(){if(t)return;t=setTimeout(function(){t=null;st.scan()},40)});
      st.mo.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['data-testid','aria-label','role']});
      w.__fwGrokOff=function(){if(w.__fwGrok!==st)return 0;w.__fwGrok=null;try{st.mo.disconnect()}catch(x){}
        try{st.css.remove()}catch(x){}var n=0;document.querySelectorAll('['+A+']').forEach(function(e){e.removeAttribute(A);n++});return n};
      w.__fwGrok=st;st.scan();return 'on'})()"""

    /** Expression statement for embedding into other evals. */
    val ON_STMT get() = "try{$ON}catch(e){};"

    const val OFF = "(()=>String(window.__fwGrokOff?window.__fwGrokOff():0))()"

    /** How many overlays the shield hid so far (for the step log). */
    const val COUNT = "(()=>String(window.__fwGrok?window.__fwGrok.hidden:-1))()"
}
