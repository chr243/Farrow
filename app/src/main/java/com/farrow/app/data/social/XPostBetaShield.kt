package com.farrow.app.data.social

/**
 * x_post_beta's own copies (Farrow v1.0.14+) of the page shield JS that x_post installs: [GROK_ON]/[GROK_OFF] are
 * verbatim copies of [GrokShield.ON]/[GrokShield.OFF], [mediaOn] of [FastScrape.mediaOnJs]. Copied, not shared, so
 * beta changes can never touch x_post (XPostBetaTest checks they still match the originals).
 */
object XPostBetaShield {
    const val GROK_ATTR = "data-farrow-grok"
    const val GROK_TTL_MS = 90_000L
    const val GROK_PROTECT = "[data-testid^=\"tweetTextarea_\"],[data-testid^=\"tweetButton\"],[data-testid=\"primaryColumn\"],article,[data-testid=\"toast\"]"
    const val PAGE_MEDIA_TTL_MS = 10 * 60_000L

    val GROK_ON = """(function(){var w=window;var h=location.hostname.replace(/^(www|mobile)\./,'');
      if(!/^(x|twitter)\.com$/.test(h))return 'off';
      if(w.__fwGrok){w.__fwGrok.until=Date.now()+$GROK_TTL_MS;w.__fwGrok.scan();return 'on'}
      var st={until:Date.now()+$GROK_TTL_MS,hidden:0};var A='$GROK_ATTR';var P=${kotlinx.serialization.json.JsonPrimitive(GROK_PROTECT)};
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

    const val GROK_OFF = "(()=>String(window.__fwGrokOff?window.__fwGrokOff():0))()"

    fun mediaOn(ttlMs: Long): String = """var w=window;if(w.__fwMB){w.__fwMB.until=Math.max(w.__fwMB.until,Date.now()+$ttlMs);}else{(function(){
      var st={until:Date.now()+$ttlMs,saved:[],held:[]};var on=function(){return Date.now()<st.until};
      var E=Element.prototype,M=HTMLMediaElement.prototype;
      var media=function(el,k){k=String(k).toLowerCase();
        if(el instanceof HTMLImageElement||el instanceof HTMLSourceElement)return k==='src'||k==='srcset'?k:null;
        if(el instanceof HTMLMediaElement)return k==='src'||k==='poster'?k:null;return null};
      var hold=function(el,k,v){if(!el.__fwH){el.__fwH={};st.held.push(el)}el.__fwH[k]=String(v)};
      [[HTMLImageElement.prototype,'src'],[HTMLImageElement.prototype,'srcset'],[HTMLSourceElement.prototype,'src'],
       [HTMLSourceElement.prototype,'srcset'],[M,'src'],[HTMLVideoElement.prototype,'poster']].forEach(function(p){
        var o=Object.getOwnPropertyDescriptor(p[0],p[1]);if(!o||!o.set)return;st.saved.push([p[0],p[1],o]);
        Object.defineProperty(p[0],p[1],{configurable:true,enumerable:o.enumerable,
          get:function(){return this.__fwH&&(p[1] in this.__fwH)?this.__fwH[p[1]]:o.get.call(this)},
          set:function(v){if(on())hold(this,p[1],v);else o.set.call(this,v)}})});
      var sa=E.setAttribute;st.sa=sa;E.setAttribute=function(k,v){var m=on()&&media(this,k);if(m){hold(this,m,v);return}
        return sa.apply(this,arguments)};
      var pl=M.play;st.pl=pl;M.play=function(){if(on())return Promise.reject(new DOMException('media blocked while scraping','NotAllowedError'));
        return pl.apply(this,arguments)};
      var css=document.createElement('style');css.textContent='*{background-image:none!important}';
      (document.head||document.documentElement).appendChild(css);st.css=css;
      document.querySelectorAll('video').forEach(function(v){try{pl&&v.pause()}catch(e){}});
      w.__fwMBoff=function(){if(w.__fwMB!==st)return 0;w.__fwMB=null;clearInterval(st.t);
        st.saved.forEach(function(s){Object.defineProperty(s[0],s[1],s[2])});E.setAttribute=st.sa;M.play=st.pl;
        try{st.css.remove()}catch(e){}var n=0;st.held.forEach(function(el){var h=el.__fwH;delete el.__fwH;
          for(var k in h){try{st.sa.call(el,k,h[k]);n++}catch(e){}}});return n};
      st.t=setInterval(function(){if(!on())w.__fwMBoff()},2000);w.__fwMB=st;})();}""".trimIndent()

    /** Statement: media filter + Grok shield (idempotent), as x_post installs them. */
    val ON_STMT: String get() = mediaOn(PAGE_MEDIA_TTL_MS) + "\ntry{" + GROK_ON + "}catch(e){};\n"
}
