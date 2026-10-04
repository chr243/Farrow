# Draft.js composer repro (v1.0.10)

`index.html` mimics X's layout with the real draft-js 0.11.7: a home inline composer in `#react-root` and a reply
modal (`role=dialog`, "Replying to @MetamateDaz", autofocused, focus trap) in `#layers`. Both editors carry
`data-testid="tweetTextarea_0"`, like X. `run.sh` drives it via Firefox, TBP and `tbp_bridge.py`, just like the phone.

Results on Firefox ESR 153 + TBP (box run, 2026-10-04):
- Bridge 1.11.0, `editor_type` with the generic testid: `document.querySelector` returns the home box, so the bridge
  focuses it. With the modal's focus trap, the keys land in the modal, but the bridge reads the home box, sees 0 chars
  and then runs console `execCommand('insertText')`, which knocked the Draft.js modal out of the page.
  Without a trap, the text goes into the hidden home box and the bridge reports success.
- `ClipboardEvent('paste')` dispatched from the console: Draft.js calls preventDefault but inserts nothing.
  A real paste (xclip + ctrl+v) works, including é ✓ 🚀 and line breaks.
- xdotool `type` drops non-ASCII characters and loses characters after shift+Return.
  Single-line ASCII typed key by key works.
- Bridge 1.12.0 with the marker selector types into the modal. The modal ends up exact both with and without the
  trap, unicode and multi-line text go through paste, and a modal that swallows input fails in about 9 s with
  diagnostics.
