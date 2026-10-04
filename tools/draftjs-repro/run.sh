#!/bin/bash
# Reproduces the v1.0.9 x_reply typing bug on a desktop Linux box with the phone's stack: Firefox + TBP (xdotool,
# DevTools-console evals) + Farrow's tbp_bridge.py. Needs: firefox(-esr), xdotool, xclip, openbox, imagemagick, Xvfb,
# node/npm, and `pip install -e` of https://github.com/salviz/termux-browser-pilot.
# Fixture query: ?trap=0 (no focus trap), ?eat=1 (modal swallows input), ?modal=0 (home only).
set -e
cd "$(dirname "$0")"
npm i --silent
for f in react/umd/react.production.min.js react-dom/umd/react-dom.production.min.js immutable/dist/immutable.min.js draft-js/dist/Draft.min.js draft-js/dist/Draft.css; do cp "node_modules/$f" .; done
python3 -m http.server 8000 --bind 127.0.0.1 >/dev/null 2>&1 & HTTP=$!
python3 ../../app/src/main/assets/tbp_bridge.py --port 8765 --token t >/tmp/repro-bridge.log 2>&1 & BR=$!
trap 'kill $HTTP $BR' EXIT
sleep 2
b() { curl -s -m 200 -X POST -H 'X-Bridge-Token: t' -H 'Content-Type: application/json' localhost:8765/cmd -d "$1"; echo; }
curl -s -m 120 -X POST -H 'X-Bridge-Token: t' localhost:8765/daemon/start; echo
b '{"cmd":"goto","args":{"url":"http://127.0.0.1:8000/index.html"}}' >/dev/null; sleep 2
echo "--- v1.0.9 call: generic testid (first match = home box behind the modal)"
b '{"cmd":"editor_type","args":{"selector":"[data-testid=\"tweetTextarea_0\"]","text":"Great point"}}'
b '{"cmd":"eval","args":{"expression":"texts()"}}'
b '{"cmd":"goto","args":{"url":"http://127.0.0.1:8000/index.html?2"}}' >/dev/null; sleep 2
echo "--- v1.0.10 call: the dialog's editor marked, unique selector"
b '{"cmd":"eval","args":{"expression":"document.querySelector(\"[role=dialog] [data-testid=tweetTextarea_0]\").setAttribute(\"data-farrow-compose\",\"1\")"}}' >/dev/null
b '{"cmd":"editor_type","args":{"selector":"[data-farrow-compose=\"1\"]","text":"Great point"}}'
b '{"cmd":"eval","args":{"expression":"texts()"}}'
