package com.farrow.app.data.social

import kotlinx.serialization.json.JsonPrimitive

/**
 * JavaScript helpers evaluated in the page (via the bridge's `eval`) to locate elements the way a person would:
 * by visible text or label, across open shadow roots and same-origin iframes. A found element is tagged with
 * `data-farrow-target="1"`. The result is "top" when it lives in the main document (so the bridge can click or type into
 * [TARGET_CSS] with human input), "deep" when it is inside a shadow root or iframe (handled in JS), or "no".
 */
object DomFinder {
    const val TARGET_CSS = "[data-farrow-target=\"1\"]"
    const val DEFAULT_CLICKABLE = "button,[role=\"button\"],a,input[type=\"submit\"],input[type=\"button\"]"

    private fun js(s: String?): String = if (s == null) "null" else JsonPrimitive(s).toString()

    /** Shared library; defines `B`. Kept dependency-free and ES2017-compatible. */
    val LIB = """
const B={};
B.roots=()=>{const out=[];const walk=(r,top)=>{out.push([r,top]);let els=[];try{els=r.querySelectorAll('*')}catch(e){}
 for(const e of els){if(e.shadowRoot)walk(e.shadowRoot,false);if(e.tagName==='IFRAME'){try{const d=e.contentDocument;if(d)walk(d,false)}catch(x){}}}};
 walk(document,true);return out};
B.all=(sel)=>{const out=[];for(const [r,top] of B.roots()){let l=[];try{l=r.querySelectorAll(sel)}catch(e){}for(const e of l)out.push([e,top])}return out};
B.vis=e=>{try{const w=(e.ownerDocument&&e.ownerDocument.defaultView)||window;const s=w.getComputedStyle(e);
 if(s.display==='none'||s.visibility==='hidden'||e.hidden)return false;if(window.__farrowAssumeVisible)return true;
 const r=e.getBoundingClientRect();return r.width>0&&r.height>0}catch(x){return false}};
B.txt=e=>String(e.innerText||e.textContent||'').replace(/\s+/g,' ').trim();
B.labelOf=e=>{const d=e.ownerDocument;const parts=[e.getAttribute('placeholder'),e.getAttribute('aria-label')];
 if(e.id){try{const l=d.querySelector('label[for="'+e.id.replace(/"/g,'\\"')+'"]');if(l)parts.push(B.txt(l))}catch(x){}}
 const lb=e.getAttribute('aria-labelledby');if(lb)lb.split(/\s+/).forEach(i=>{const n=d.getElementById(i);if(n)parts.push(B.txt(n))});
 const pl=e.closest('label');if(pl)parts.push(B.txt(pl));
 let p=e.parentElement;for(let k=0;k<4&&p;k++,p=p.parentElement){const t=B.txt(p);if(t&&t.length<80)parts.push(t)}
 return parts.filter(Boolean).map(s=>String(s).trim())};
B.clear=()=>{for(const [e] of B.all('[data-farrow-target]'))e.removeAttribute('data-farrow-target')};
B.mark=(e,top)=>{B.clear();e.setAttribute('data-farrow-target','1');return top?'top':'deep'};
B.exists=sel=>B.all(sel).length>0;
B.first=sels=>{for(let i=0;i<sels.length;i++){if(B.exists(sels[i]))return String(i)}return '-1'};
B.findText=(re,ex,scope)=>{const R=new RegExp(re,'i'),X=ex?new RegExp(ex,'i'):null;
 for(const [e,top] of B.all(scope||'${DEFAULT_CLICKABLE.replace("\"", "\\\"")}')){if(!B.vis(e))continue;
  if(e.getAttribute('aria-disabled')==='true'||e.disabled)continue;
  const t=e.tagName==='INPUT'?String(e.value||''):B.txt(e);const a=String(e.getAttribute('aria-label')||'');
  const hit=v=>v&&R.test(v)&&!(X&&X.test(v));if(hit(t)||hit(a))return B.mark(e,top)}return 'no'};
B.posted=(box,item,text)=>{const n=t=>String(t||'').replace(/\u200b/g,'').replace(/\s+/g,' ').trim();const T=n(text);
 for(const [e] of B.all(box)){if(B.vis(e))return 'open'}
 if(T){for(const [e] of B.all(item)){if(!e.closest('[role="dialog"]')&&n(B.txt(e)).includes(T))return 'feed'}}
 return 'closed'};
B.okInput=e=>B.vis(e)&&!e.disabled&&!e.readOnly&&e.type!=='hidden';
B.findInput=(re,sel,fallback)=>{if(re){const R=new RegExp(re,'i');
  for(const [e,top] of B.all('input,textarea')){if(B.okInput(e)&&B.labelOf(e).some(t=>R.test(t)))return B.mark(e,top)}}
 if(sel){for(const [e,top] of B.all(sel)){if(B.okInput(e))return B.mark(e,top)}}
 if(fallback){for(const [e,top] of B.all('[role="dialog"] input,[aria-modal="true"] input')){
  const ty=(e.getAttribute('type')||'text').toLowerCase();if(B.okInput(e)&&['text','email','tel'].includes(ty))return B.mark(e,top)}}
 return 'no'};
B.marked=()=>{const h=B.all('[data-farrow-target="1"]')[0];return h?h[0]:null};
B.clickMarked=()=>{const e=B.marked();if(!e)return 'no';if(e.scrollIntoView)e.scrollIntoView({block:'center'});if(e.focus)e.focus();e.click();return 'ok'};
B.setMarked=v=>{const e=B.marked();if(!e)return 'no';const w=e.ownerDocument.defaultView||window;e.focus();
 const P=(e.tagName==='TEXTAREA'?w.HTMLTextAreaElement:w.HTMLInputElement).prototype;
 Object.getOwnPropertyDescriptor(P,'value').set.call(e,v);
 e.dispatchEvent(new w.Event('input',{bubbles:true}));e.dispatchEvent(new w.Event('change',{bubbles:true}));return 'ok'};
""".trimIndent()

    private fun call(expr: String) = "(()=>{$LIB\nreturn $expr})()"

    fun exists(css: String) = call("String(B.exists(${js(css)}))")
    fun firstPresent(css: List<String>) = call("B.first([${css.joinToString(",") { js(it) }}])")
    fun findText(regex: String, exclude: String? = null, scopeCss: String? = null) = call("B.findText(${js(regex)},${js(exclude)},${js(scopeCss)})")
    fun findInput(labelRegex: String?, css: String?, dialogFallback: Boolean) = call("B.findInput(${js(labelRegex)},${js(css)},$dialogFallback)")
    fun clickMarked() = call("B.clickMarked()")
    /** "open" (compose box still visible) | "feed" (an article outside the dialog shows the text) | "closed". */
    fun postedState(boxCss: String, itemCss: String, text: String) = call("B.posted(${js(boxCss)},${js(itemCss)},${js(text)})")
    fun setMarked(value: String) = call("B.setMarked(${js(value)})")

    // Editor fallbacks (Draft.js contenteditable such as X's tweetTextarea_0) — used when `tbp click` hangs/fails or
    // on a bridge without editor_type. One eval each, main document only.
    private const val EDITABLE = "[contenteditable=true],[contenteditable=\"\"],[role=textbox],input,textarea"
    private fun editorOf(css: String) = "(function(){var e=document.querySelector(${js(css)});if(!e)return null;" +
        "return e.matches(${js(EDITABLE)})?e:(e.querySelector('[contenteditable=true],[role=textbox]')||e)})()"

    /** "focused" (editable: focus + caret at the end) | "clicked" (anything else: element.click()) | "missing". */
    fun focusOrClick(css: String) = "(function(){var e=document.querySelector(${js(css)});if(!e)return 'missing';" +
        "var ed=e.matches(${js(EDITABLE)})?e:e.querySelector('[contenteditable=true],[role=textbox]');var t=ed||e;" +
        "t.scrollIntoView({block:'center'});if(ed){ed.focus();try{var r=document.createRange();r.selectNodeContents(ed);r.collapse(false);" +
        "var s=getSelection();s.removeAllRanges();s.addRange(r)}catch(x){}return 'focused'}t.click();return 'clicked'})()"

    /** Current text of the editor (innerText, or value for inputs). */
    fun editorText(css: String) = "(function(){var ed=${editorOf(css)};if(!ed)return '';" +
        "return String((ed.tagName==='INPUT'||ed.tagName==='TEXTAREA')?ed.value:ed.innerText||'')})()"

    /** focus + select all + document.execCommand('insertText') (Draft.js handles it), paste event as a last resort. */
    fun insertText(css: String, text: String) = "(function(){var ed=${editorOf(css)};if(!ed)return 'missing';ed.focus();" +
        "try{document.execCommand('selectAll',false,null)}catch(x){}var ok=false;try{ok=document.execCommand('insertText',false,${js(text)})}catch(x){}" +
        "if(!ok){try{var dt=new DataTransfer();dt.setData('text/plain',${js(text)});" +
        "ed.dispatchEvent(new ClipboardEvent('paste',{clipboardData:dt,bubbles:true,cancelable:true}));ok=true}catch(x){}}" +
        "return ok?'inserted':'failed'})()"

    /** Whitespace-insensitive "editor contains the text" check. */
    fun containsText(editor: String?, text: String): Boolean {
        fun n(t: String) = t.replace("\u200b", "").replace(Regex("\\s+"), " ").trim()
        return n(editor.orEmpty()).contains(n(text))
    }
}
