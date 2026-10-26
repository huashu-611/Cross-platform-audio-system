// End-to-end smoke for the Electron UI against a real hub process.
//
// What it proves (the user's core scenario):
//   1. the app boots and renders the network graph from /api/state.graph;
//   2. a second device appearing in the graph shows up as a clickable node
//      (and the old role <select id="hubSelect"> is gone);
//   3. clicking that node opens the switch action bar, pressing switch moves the
//      hub (graph.hubId changes) and the UI reports sync;
//   4. switching back to this PC restores hub role.
//
// Transport: Chrome DevTools Protocol (electron --remote-debugging-port). The
// second device is injected as a raw GRAPH frame over UDP, no phone required.
//
// Usage:
//   node tools/ui-e2e.js                              (开发态：electron.exe + ui/)
//   node tools/ui-e2e.js 打包后的 exe                    (环境变量 AUDIOHUB_UI_EXE 指到 exe)
//         例: $env:AUDIOHUB_UI_EXE="ui\dist\AudioHub-便携版.exe"; node tools/ui-e2e.js
//
// 打包态验证的是"果壳里的 exe 真的能跑"：界面从 asar 里加载、音频核心从
// resources/app.asar.unpacked/bin 里启动、CDP 仍能连上。退出码 0 = 全部通过。

'use strict';

const { spawn, execFileSync } = require('child_process');
const http = require('http');
const dgram = require('dgram');
const path = require('path');
const fs = require('fs');
const os = require('os');

const ROOT = path.resolve(__dirname, '..');
const UI_DIR = path.join(ROOT, 'ui');
const ELECTRON = path.join(UI_DIR, 'node_modules', 'electron', 'dist', 'electron.exe');
const UI_EXE = process.env.AUDIOHUB_UI_EXE ? path.resolve(process.env.AUDIOHUB_UI_EXE) : null;
const PACKAGED = !!UI_EXE;
const HUB_AUDIO_PORT = 47000;
const HUB_HTTP_PORT = 4780;
const DEBUG_PORT = 9333;
const FAKE_ID = '127.0.0.1:47100';
const FAKE_NAME = 'E2E-B';

const results = [];
function check(cond, what) {
  results.push({ ok: !!cond, what });
  console.log((cond ? '  [PASS] ' : '  [FAIL] ') + what);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function getJson(urlPath) {
  return new Promise((resolve, reject) => {
    const req = http.get({ host: '127.0.0.1', port: DEBUG_PORT, path: urlPath, timeout: 1000 }, (res) => {
      let body = '';
      res.on('data', (c) => (body += c));
      res.on('end', () => {
        try { resolve(JSON.parse(body)); } catch (e) { reject(e); }
      });
    });
    req.on('error', reject);
    req.on('timeout', () => req.destroy(new Error('timeout')));
  });
}

async function waitPageTarget(ms) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline) {
    try {
      const list = await getJson('/json/list');
      const page = list.find((t) => t.type === 'page' && /index\.html/.test(t.url));
      if (page && page.webSocketDebuggerUrl) return page;
    } catch (e) { /* electron not up yet */ }
    await sleep(200);
  }
  throw new Error('electron page target not found on port ' + DEBUG_PORT);
}

class CDP {
  constructor(ws) {
    this.ws = ws;
    this.nextId = 0;
    this.pending = new Map();
    ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      if (msg.id && this.pending.has(msg.id)) {
        const p = this.pending.get(msg.id);
        this.pending.delete(msg.id);
        if (msg.error) p.reject(new Error(JSON.stringify(msg.error)));
        else p.resolve(msg.result);
      }
    });
  }
  send(method, params) {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params: params || {} }));
    });
  }
  async eval(expression) {
    const res = await this.send('Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true, userGesture: true
    });
    if (res.exceptionDetails) {
      throw new Error('eval failed: ' + JSON.stringify(res.exceptionDetails.exception || res.exceptionDetails));
    }
    return res.result.value;
  }
}

function connect(wsUrl) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(wsUrl);
    ws.addEventListener('open', () => resolve(new CDP(ws)));
    ws.addEventListener('error', (e) => reject(new Error('ws error')));
  });
}

function injectGraphFrame(selfId, version) {
  const frame = 'GRAPH|' + version + '|' + selfId + '|' + selfId + '|' +
    FAKE_ID + ',' + FAKE_NAME + ',pc,' + 47100 + ',0';
  const sock = dgram.createSocket('udp4');
  return new Promise((resolve, reject) => {
    sock.send(Buffer.from(frame, 'utf8'), HUB_AUDIO_PORT + 2, '127.0.0.1', (err) => {
      sock.close();
      if (err) reject(err); else resolve(frame);
    });
  });
}

async function waitDebug(cdp, predicate, ms, what) {
  const deadline = Date.now() + ms;
  let last = null;
  while (Date.now() < deadline) {
    last = await cdp.eval('JSON.stringify(window.__hubDebug ? window.__hubDebug() : null)');
    const snap = last ? JSON.parse(last) : null;
    if (snap && predicate(snap)) return snap;
    await sleep(150);
  }
  throw new Error('timeout waiting for ' + what + ' (last=' + last + ')');
}

async function main() {
  if (PACKAGED) {
    if (!fs.existsSync(UI_EXE)) throw new Error('packaged exe missing: ' + UI_EXE);
    console.log('==> mode: packaged exe ' + UI_EXE);
  } else if (!fs.existsSync(ELECTRON)) {
    throw new Error('electron.exe missing: ' + ELECTRON);
  }

  // Kill leftovers from previous runs so port 47000 is free.
  for (const name of ['audiohub-hub.exe', 'audiohub-source.exe', 'AudioHub.exe']) {
    try {
      execFileSync('taskkill', ['/IM', name, '/F'], { stdio: 'ignore' });
    } catch (e) { /* none running */ }
  }

  const logPath = path.join(os.tmpdir(), 'audiohub-ui-e2e.log');
  const logStream = fs.createWriteStream(logPath);
  // DSH 的工具环境里带着 ELECTRON_RUN_AS_NODE=1，会让 electron.exe 退化成 node
  // （main.js 的 require('electron') 就只能拿到路径），所以启动前必须删掉。
  const childEnv = Object.assign({}, process.env);
  delete childEnv.ELECTRON_RUN_AS_NODE;
  const exe = PACKAGED ? UI_EXE : ELECTRON;
  const args = PACKAGED
    ? ['--no-sandbox', '--remote-debugging-port=' + DEBUG_PORT, '--disable-gpu']
    : ['.', '--no-sandbox', '--remote-debugging-port=' + DEBUG_PORT, '--disable-gpu'];
  const electron = spawn(exe, args,
    { cwd: PACKAGED ? path.dirname(exe) : UI_DIR, env: childEnv, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: false });
  electron.stdout.pipe(logStream, { end: false });
  electron.stderr.pipe(logStream, { end: false });
  console.log('==> electron pid ' + electron.pid + ' (log: ' + logPath + ')');

  let cdp = null;
  try {
    const target = await waitPageTarget(PACKAGED ? 90000 : 30000);
    cdp = await connect(target.webSocketDebuggerUrl);
    await cdp.send('Runtime.enable', {});

    await waitDebug(cdp, (s) => typeof s.screen === 'string', 15000, 'renderer boot');
    let snap = await waitDebug(cdp, (s) => s.selfId, 15000, 'hub graph state');
    console.log('==> app up: selfId=' + snap.selfId + ' screen=' + snap.screen + ' nodes=' + snap.nodes);
    check(snap.hasGraph === true, 'renderer reads graph from /api/state.graph');
    check(snap.nodes === 1, 'graph starts with only this PC');

    const noSelect = await cdp.eval("document.querySelector('#hubSelect') === null");
    check(noSelect === true, 'legacy role <select id="hubSelect"> is gone');

    // A second device joins the network (raw GRAPH frame, no phone needed).
    await injectGraphFrame(snap.selfId, 5);
    snap = await waitDebug(cdp, (s) => s.nodes >= 2, 5000, 'second node in graph');
    check(snap.nodes >= 2, 'second device appears in the shared graph');
    check(snap.controlShown === true, 'control screen with graph is shown');

    const svgNodes = await cdp.eval("document.querySelectorAll('#graph g[data-id]').length");
    check(svgNodes >= 2, 'graph renders both nodes as clickable groups');

    // Click the second node -> action bar appears.
    const clickFake = "(() => { const g = document.querySelector('#graph g[data-id=\"" + FAKE_ID + "\"]');" +
      " if (!g) return 'no-node'; g.dispatchEvent(new MouseEvent('click', {bubbles:true})); return 'ok'; })()";
    check((await cdp.eval(clickFake)) === 'ok', 'clicking the second node works');
    snap = await waitDebug(cdp, (s) => s.selected === FAKE_ID, 3000, 'node selection');
    const actionVisible = await cdp.eval(
      "document.querySelector('#graphAction') && document.querySelector('#graphAction').style.display !== 'none'");
    check(actionVisible === true, 'switch action bar appears after node click');
    const actionText = await cdp.eval("document.querySelector('#actionText').textContent");
    check(typeof actionText === 'string' && actionText.indexOf(FAKE_NAME) >= 0,
      'action text names the target device');

    // Press "switch": hub must move to the fake node and the UI must confirm sync.
    check((await cdp.eval("document.querySelector('#actionSwitch').click(); 'clicked'")) === 'clicked',
      'switch button is clickable');
    snap = await waitDebug(cdp, (s) => s.hubId === FAKE_ID, 6000, 'hub switch applied in UI state');
    check(snap.hubId === FAKE_ID, 'UI state hubId moves to the clicked device');
    const hubApi = await new Promise((resolve) => {
      http.get({ host: '127.0.0.1', port: HUB_HTTP_PORT, path: '/api/state', timeout: 2000 }, (res) => {
        let b = ''; res.on('data', (c) => (b += c)); res.on('end', () => resolve(JSON.parse(b)));
      }).on('error', () => resolve(null));
    });
    check(hubApi && hubApi.graph && hubApi.graph.hubId === FAKE_ID,
      'hub process really switched (HTTP /api/state agrees)');
    const syncText = await cdp.eval("document.querySelector('#graphStatus') ? document.querySelector('#graphStatus').textContent : ''");
    check(typeof syncText === 'string' && syncText.indexOf('同步') >= 0,
      'UI shows sync feedback after switch (' + JSON.stringify(syncText) + ')');

    // Switch back to this PC (restores hub role; also tests the reverse direction).
    snap = await cdp.eval("JSON.stringify(window.__hubDebug())").then(JSON.parse);
    const selfClick = "(() => { const g = document.querySelector('#graph g[data-id=\"" + snap.selfId + "\"]');" +
      " if (!g) return 'no-node'; g.dispatchEvent(new MouseEvent('click', {bubbles:true})); return 'ok'; })()";
    await cdp.eval(selfClick);
    await waitDebug(cdp, (s) => s.selected === snap.selfId, 3000, 'self node selected');
    await cdp.eval("document.querySelector('#actionSwitch').click(); 'clicked'");
    snap = await waitDebug(cdp, (s) => s.hubId === s.selfId, 6000, 'hub restored to this PC');
    check(snap.hubId === snap.selfId, 'switching back to this PC restores hub role');
    const barHidden = await cdp.eval(
      "document.querySelector('#graphAction') && document.querySelector('#graphAction').style.display === 'none'");
    check(barHidden === true, 'action bar hides after switching');

    // Cleanup: "断开全部" clears the session graph back to this node only.
    console.log('==> done');
  } catch (err) {
    check(false, 'e2e failed: ' + err.message);
    console.log('--- electron log tail ---');
    try { console.log(fs.readFileSync(logPath, 'utf8').split('\n').slice(-40).join('\n')); } catch (e) { }
  } finally {
    if (cdp && cdp.ws) { try { cdp.ws.close(); } catch (e) { } }
    try { execFileSync('taskkill', ['/PID', String(electron.pid), '/T', '/F'], { stdio: 'ignore' }); } catch (e) { }
    await sleep(500);
    for (const name of ['AudioHub.exe', 'audiohub-hub.exe', 'audiohub-source.exe']) {
      try { execFileSync('taskkill', ['/IM', name, '/F'], { stdio: 'ignore' }); } catch (e) { }
    }
    logStream.end();
  }

  const failed = results.filter((r) => !r.ok);
  console.log('');
  if (failed.length === 0) console.log('==> UI E2E: ALL CHECKS PASSED');
  else {
    console.log('==> UI E2E: ' + failed.length + ' FAILURE(S)');
    failed.forEach((f) => console.log('   - ' + f.what));
  }
  process.exit(failed.length === 0 ? 0 : 1);
}

main().catch((e) => { console.error('FATAL: ' + e.stack); process.exit(1); });