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
// 3D 迁移（2D SVG → Canvas 2D 透视）：#graph 已经是 <canvas>，没有 g[data-id]
// 可以 querySelector 了。命中目标的屏幕坐标从 __hubDebug().scene.nodeScreen 拿，
// 再用 CDP Input.dispatchMouseEvent 发真实鼠标事件 —— 这样点击仍然走渲染进程的
// canvas 拾取（project/hitTest），而不是绕过它直接调 onSelect。断言语义不变。
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
// 每次跑都用全新的 Electron profile：localStorage（节点 pin / 相机）不跨轮残留，
// 否则上一轮拖动留下的 pin 会让"新设备落到房间槽位"之类的断言假红。
const PROFILE_DIR = fs.mkdtempSync(path.join(os.tmpdir(), 'audiohub-e2e-'));

const results = [];
function check(cond, what) {
  results.push({ ok: !!cond, what });
  console.log((cond ? '  [PASS] ' : '  [FAIL] ') + what);
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// AUDIOHUB_E2E_DEBUG=1 时在关键步骤打印相机/节点快照，排查 rAF/拾取相关偶发问题。
const DBG = process.env.AUDIOHUB_E2E_DEBUG === '1';
async function note (cdp, tag) {
  if (!DBG) return;
  try {
    const s = JSON.parse(await cdp.eval('JSON.stringify(window.__hubDebug ? window.__hubDebug() : null)'));
    const nodes = (s && s.scene ? s.scene.nodes : []).map((n) => n.id + ':dist' + n.dist + ',sx' + Math.round(n.sx) + ',sy' + Math.round(n.sy) + ',r' + Math.round(n.rPx));
    console.log('[dbg] ' + tag + ' view=' + JSON.stringify(s.scene.view) + ' ' + nodes.join(' | '));
  } catch (e) { /* debug only */ }
}

// 页面适配断言：整页不出现滚动条（v1.4 起界面按视口自适应，内容不滚动即完整展示）。
async function pageFit(cdp, what) {
  const s = await cdp.eval(
    "JSON.stringify({w:document.documentElement.scrollWidth,h:document.documentElement.scrollHeight,iw:innerWidth,ih:innerHeight})");
  const m = JSON.parse(s);
  check(m.w <= m.iw + 1 && m.h <= m.ih + 1,
    what + ' fits the window with no page scrollbars (' + m.w + 'x' + m.h + ' ≤ ' + m.iw + 'x' + m.ih + ')');
  return m;
}

// 直接读本机 hub 的 /api/state（绕过渲染层，用来验证内核里真实存了什么）。
function hubState() {
  return new Promise((resolve) => {
    http.get({ host: '127.0.0.1', port: HUB_HTTP_PORT, path: '/api/state', timeout: 2000 }, (res) => {
      let b = '';
      res.on('data', (c) => (b += c));
      res.on('end', () => { try { resolve(JSON.parse(b)); } catch (e) { resolve(null); } });
    }).on('error', () => resolve(null));
  });
}

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

// ---------------------------------------------------------------- canvas 点击
// canvas 场景的节点屏幕坐标（视口 CSS px，与 CDP Input 坐标同一坐标系）。
async function waitNodeScreen(cdp, id, ms) {
  const deadline = Date.now() + ms;
  let last = null;
  while (Date.now() < deadline) {
    last = await cdp.eval('JSON.stringify(window.__hubDebug ? (window.__hubDebug().scene || null) : null)');
    const scene = last ? JSON.parse(last) : null;
    const ns = scene && scene.nodeScreen && scene.nodeScreen[id];
    if (ns && Number.isFinite(ns.clientX) && Number.isFinite(ns.clientY)) return ns;
    await sleep(120);
  }
  throw new Error('timeout waiting screen coords for ' + id + ' (last=' + last + ')');
}

// 真实鼠标事件：mouseMoved → mousePressed → mouseReleased（走 canvas 的 mousedown/up）。
async function clickAt(cdp, x, y) {
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, buttons: 0 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'left', buttons: 1, clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'left', buttons: 0, clickCount: 1 });
}

// 右键：Chromium 会把 button:'right' 的按下/抬起派发成 contextmenu（scene3d 的 onContextMenu）。
async function rightClickAt(cdp, x, y) {
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x, y, buttons: 0 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x, y, button: 'right', buttons: 2, clickCount: 1 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x, y, button: 'right', buttons: 0, clickCount: 1 });
}

// 节点右键菜单（#ctxMenu）当前是否可见
async function menuShown(cdp) {
  return (await cdp.eval(
    "document.querySelector('#ctxMenu') && document.querySelector('#ctxMenu').style.display !== 'none'")) === true;
}

// 点菜单里文字包含 text 的按钮（返回 'clicked' | 'disabled' | 'missing' | 'no-menu'）
async function clickMenuText(cdp, text) {
  return cdp.eval(
    "(function(){var el=document.querySelector('#ctxMenu');if(!el)return 'no-menu';" +
    "var bs=[].slice.call(el.querySelectorAll('button'));" +
    "for(var i=0;i<bs.length;i++){if(bs[i].textContent.indexOf(" + JSON.stringify(text) + ")>=0){" +
    "if(bs[i].disabled)return 'disabled';bs[i].click();return 'clicked';}}return 'missing';})()");
}

// 拖动（轨道相机 / 拖节点）：中间补几帧 mouseMoved，canvas 的 drag.moved 才会置真。
async function dragAt(cdp, x1, y1, x2, y2) {
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseMoved', x: x1, y: y1, buttons: 0 });
  await cdp.send('Input.dispatchMouseEvent', { type: 'mousePressed', x: x1, y: y1, button: 'left', buttons: 1, clickCount: 1 });
  const steps = 6;
  for (let i = 1; i <= steps; i++) {
    await cdp.send('Input.dispatchMouseEvent', {
      type: 'mouseMoved',
      x: x1 + (x2 - x1) * i / steps,
      y: y1 + (y2 - y1) * i / steps,
      button: 'left',
      buttons: 1
    });
  }
  await cdp.send('Input.dispatchMouseEvent', { type: 'mouseReleased', x: x2, y: y2, button: 'left', buttons: 0, clickCount: 1 });
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
    ? ['--no-sandbox', '--remote-debugging-port=' + DEBUG_PORT, '--disable-gpu',
       '--user-data-dir=' + PROFILE_DIR]
    : ['.', '--no-sandbox', '--remote-debugging-port=' + DEBUG_PORT, '--disable-gpu',
       '--user-data-dir=' + PROFILE_DIR];
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
    // 窗口被遮挡时 Chromium 会暂停 requestAnimationFrame，250ms 布局插值就永远走不完；
    // 把页面提到最前，保证动画/重绘在无人值守的 CI 里也能跑。
    try { await cdp.send('Page.bringToFront', {}); } catch (e) { /* 旧 CDP 无此命令时忽略 */ }

    await waitDebug(cdp, (s) => typeof s.screen === 'string', 15000, 'renderer boot');
    let snap = await waitDebug(cdp, (s) => s.selfId, 15000, 'hub graph state');
    console.log('==> app up: selfId=' + snap.selfId + ' screen=' + snap.screen + ' nodes=' + snap.nodes);
    check(snap.hasGraph === true, 'renderer reads graph from /api/state.graph');
    check(snap.nodes === 1, 'graph starts with only this PC');
    await pageFit(cdp, 'connect screen');
    // 「全部断开」只在控制界面出现：连接界面没有会话可断，而且它已经从底部"会话"卡片
    // 挪到了头部右侧的小按钮（用户要求：卡片去掉，按钮单独放）。
    const leaveHiddenOnConnect = await cdp.eval(
      "document.querySelector('#leaveBtn') && document.querySelector('#leaveBtn').style.display === 'none'");
    check(leaveHiddenOnConnect === true, '「全部断开」在连接界面隐藏');
    const noSessionCard = await cdp.eval("document.body.textContent.indexOf('断开所有连接') < 0");
    check(noSessionCard === true, '旧「会话」卡片已移除（只剩头部的小按钮）');

    const noSelect = await cdp.eval("document.querySelector('#hubSelect') === null");
    check(noSelect === true, 'legacy role <select id="hubSelect"> is gone');

    // A second device joins the network (raw GRAPH frame, no phone needed).
    await injectGraphFrame(snap.selfId, 5);
    snap = await waitDebug(cdp, (s) => s.nodes >= 2, 5000, 'second node in graph');
    check(snap.nodes >= 2, 'second device appears in the shared graph');
    check(snap.controlShown === true, 'control screen with graph is shown');
    const leaveShownInControl = await cdp.eval(
      "document.querySelector('#leaveBtn') && document.querySelector('#leaveBtn').style.display !== 'none'");
    check(leaveShownInControl === true, '「全部断开」在控制界面出现在头部右侧');
    const leaveGeom = JSON.parse(await cdp.eval(`JSON.stringify((function () {
      var b = document.querySelector('#leaveBtn');
      var r = b.getBoundingClientRect();
      var a = document.querySelector('.addr').getBoundingClientRect();
      return { text: b.textContent.trim(), x: r.x, y: r.y, right: r.right, bottom: r.bottom,
               addrRight: a.right, w: innerWidth, h: innerHeight,
               hit: document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2) === b };
    })())`));
    check(leaveGeom.text === '全部断开' && leaveGeom.hit === true &&
      leaveGeom.right <= leaveGeom.w && leaveGeom.bottom <= leaveGeom.h,
      '「全部断开」按钮在视口内且可点（' + Math.round(leaveGeom.right) + '≤' + leaveGeom.w + '）');
    check(leaveGeom.x > leaveGeom.addrRight, '「全部断开」排在地址框右侧，不挤占正文');
    await sleep(350);   // 等一帧布局与画布 resize
    await pageFit(cdp, 'control screen');

    // 3D：图谱是 <canvas>，用场景快照断言两个节点都有投影坐标（不再有 DOM 节点）。
    const graphTag = await cdp.eval("document.querySelector('#graph') ? document.querySelector('#graph').tagName : ''");
    const legacyGroups = await cdp.eval("document.querySelectorAll('#graph g[data-id]').length");
    check(graphTag === 'CANVAS' && legacyGroups === 0, 'graph is a <canvas> 3D scene (no SVG node groups left)');
    snap = await waitDebug(cdp, (s) => s.scene && s.scene.nodes.length >= 2 && s.scene.hubScreen, 5000, '3D scene projection');
    check(snap.scene.nodes.length >= 2, '3D scene projects both nodes (' + snap.scene.nodes.map((n) => n.id).join(',') + ')');
    check(Math.abs(snap.scene.hubScreen.sx - 460) <= 2 && Math.abs(snap.scene.hubScreen.sy - 208) <= 2,
      'hub stays at screen (460,208) ±2px (' + snap.scene.hubScreen.sx + ',' + snap.scene.hubScreen.sy + ')');
    check(Math.abs(snap.scene.view.focal - 522.71) <= 1, 'camera focal matches 522.71px (' + snap.scene.view.focal + ')');
    await note(cdp, 'after projection');

    // 滚轮方向（用户反馈"方向反了"的回归）：滚轮向下 = 拉远看全景（deltaY>0 → dist 增大），
    // 滚轮向上 = 凑近放大。用 CDP 真滚轮事件，走 canvas 自己的 wheel 监听。
    const wheelRect = JSON.parse(await cdp.eval(
      "JSON.stringify(document.querySelector('#graph').getBoundingClientRect())"));
    const wheelX = wheelRect.left + wheelRect.width * 0.5;
    const wheelY = wheelRect.top + wheelRect.height * 0.5;
    async function readDist() {
      const v = JSON.parse(await cdp.eval('JSON.stringify(window.__hubDebug().scene.view)'));
      return v.dist;
    }
    async function wheel(deltaY) {
      await cdp.send('Input.dispatchMouseEvent', {
        type: 'mouseWheel', x: wheelX, y: wheelY, deltaX: 0, deltaY: deltaY, modifiers: 0
      });
      await sleep(250);
    }
    const dist0 = await readDist();
    await wheel(120);
    const distOut = await readDist();
    await wheel(-120);
    const distBack = await readDist();
    check(distOut > dist0 + 0.05, 'wheel down zooms out (dist ' + dist0.toFixed(2) + ' → ' + distOut.toFixed(2) + ' m)');
    check(distBack < distOut - 0.05 && Math.abs(distBack - dist0) < 0.15,
      'wheel up zooms back in (dist ' + distOut.toFixed(2) + ' → ' + distBack.toFixed(2) + ' m)');

    // v1.3 物理房间模型：听者固定在房间中心、朝 -Z；节点按"真实距离"落位（0.85–2.2 m），
    // 球大小随距离缩小（用户的"用大小模拟远近"），并且相机 orbit 不改变声场。
    const lis = snap.scene.listener;
    check(!!lis && lis.x === 0 && lis.y === 0 && lis.z === 0 && lis.yaw === 0 && lis.pitch === 0,
      'listener is fixed at the room centre facing -Z (yaw=0, pitch=0)');
    const fakeNode = snap.scene.nodes.find((n) => n.id === FAKE_ID);
    const fakePos = snap.scene.positions[FAKE_ID];
    const fakeDist = fakePos ? Math.hypot(fakePos[0], fakePos[2]) : NaN;
    check(!!fakeNode && fakeNode.slot >= 0 && fakeDist >= 0.8 && fakeDist <= 2.3,
      'new device lands at a physical spot (' + fakeDist.toFixed(2) + ' m, slot ' + (fakeNode || {}).slot + ')');
    const hubNode = snap.scene.nodes.find((n) => n.isHub);
    const sizeExpected = Math.max(0.55, Math.min(1.5, Math.pow(1 / Math.max(fakeDist, 0.2), 0.55)));
    check(!!fakeNode && Math.abs(fakeNode.sizeScale - sizeExpected) < 0.02 && fakeNode.rPx < hubNode.rPx,
      'ball size shrinks with distance (sizeScale=' + (fakeNode || {}).sizeScale + ', rPx=' + (fakeNode || {}).rPx +
      ' < hub ' + hubNode.rPx + ')');

    // 遮挡回归（Lead 复核 D1 修订）：默认相机（pitch 40°）下，任何非 hub 节点球都不得压住
    // hub 球或它的光晕 —— 球心距 > hub.rPx + node.rPx + 6。若以后有人把默认 pitch 调回去，
    // 这条会先挂，而不是等肉眼发现"第一个节点被汇聚端吞了"。
    function checkNoHubOverlap(scene, tag) {
      const hub = scene.nodes.find((n) => n.isHub);
      const others = scene.nodes.filter((n) => !n.isHub && n.visible);
      const gaps = others.map((n) => Math.hypot(n.sx - hub.sx, n.sy - hub.sy) - hub.rPx - n.rPx);
      const minGap = gaps.length ? Math.min.apply(null, gaps) : Infinity;
      check(!!hub && others.length > 0 && minGap > 6,
        'no ball overlaps the hub ball [' + tag + '] minGap=' + (Number.isFinite(minGap) ? minGap.toFixed(1) : '-') + 'px (>6)');
    }
    checkNoHubOverlap(snap.scene, 'initial');

    // 相机 orbit 只改"从哪看"：φ 变 >15°，但听者 yaw 与节点相对听者的声像方位（azDeg）不变。
    // 拖动点选画布底部空白处（该区域没有节点），拖 90px 再拖回，保持后续点击坐标可复现。
    const graphRect = JSON.parse(await cdp.eval(
      "JSON.stringify(document.querySelector('#graph').getBoundingClientRect())"));
    const orbitX = graphRect.left + graphRect.width * 0.5;
    const orbitY = graphRect.top + graphRect.height * 0.94;
    const phiBefore = snap.scene.view.phiDeg;
    const azBefore = fakeNode.azDeg;

    // 相机归位：e2e 里只能用"拖空白"改相机，偶发的指针/布局时序可能把 φ/pitch 带偏；
    // 这里读回 view 计算还差多少，再拖对应的空白区，最多试 6 次，保证后续点击坐标可复现。
    const PITCH_DEFAULT = 40;
    async function readView () {
      const s = JSON.parse(await cdp.eval('JSON.stringify(window.__hubDebug ? window.__hubDebug() : null)'));
      return s && s.scene ? s.scene.view : null;
    }
    async function ensureDefaultView (tag) {
      for (let attempt = 0; attempt < 6; attempt++) {
        const v = await readView();
        if (!v) return false;
        const phi = ((v.phiDeg % 360) + 360) % 360;
        const phiErr = phi > 180 ? phi - 360 : phi;
        const pitchErr = v.pitchDeg - PITCH_DEFAULT;
        if (Math.abs(phiErr) < 0.7 && Math.abs(pitchErr) < 0.9) return true;
        const rect = JSON.parse(await cdp.eval(
          "JSON.stringify(document.querySelector('#graph').getBoundingClientRect())"));
        const fx = [0.5, 0.3, 0.7][attempt % 3];       // 底部中间/左下/右下，避开投影出来的节点
        const sx = rect.left + rect.width * fx;
        const sy = rect.top + rect.height * 0.94;
        const dx = Math.max(-180, Math.min(180, phiErr / 0.35));    // φ = φ − dx·0.35
        const dy = Math.max(-120, Math.min(120, -pitchErr / 0.25)); // pitch = pitch + dy·0.25
        await dragAt(cdp, sx, sy, sx + dx, sy + dy);
        await sleep(140);
        if (DBG) console.log('[dbg] ensureDefaultView[' + tag + '] attempt ' + attempt + ' v=' + JSON.stringify(v));
      }
      return false;
    }

    await dragAt(cdp, orbitX, orbitY, orbitX + 90, orbitY);
    let orbitSnap = await waitDebug(cdp, (s) => s.scene && Math.abs(s.scene.view.phiDeg - phiBefore) > 15,
      3000, 'camera orbit');
    check(Math.abs(orbitSnap.scene.view.phiDeg - phiBefore) > 15,
      'dragging empty space orbits the camera (φ ' + phiBefore + '° → ' + orbitSnap.scene.view.phiDeg + '°)');
    const azDuring = orbitSnap.scene.nodes.find((n) => n.id === FAKE_ID).azDeg;
    check(Math.abs(azDuring - azBefore) < 0.5 && orbitSnap.scene.listener.yaw === 0,
      'camera orbit does not rotate the soundstage (node az ' + azBefore + '° → ' + azDuring + '°, listener yaw stays 0)');
    await ensureDefaultView('after orbit');
    let restored = await waitDebug(cdp, (s) => s.scene && Math.abs(s.scene.view.phiDeg - phiBefore) < 0.6,
      2500, 'camera restored');
    await note(cdp, 'after camera restored');

    // 拖动节点 = 在地面自由摆位：朝相机方向拖近 → 真实距离变小、球变大（大小是远近的视觉编码）。
    // 若相机/窗口时序让首次拖动落空，归位相机后重试（最多 3 次），避免偶发假红。
    let fakeBefore = restored.scene.nodes.find((n) => n.id === FAKE_ID);
    let fakeAfter = null;
    for (let attempt = 0; attempt < 3 && !fakeAfter; attempt++) {
      if (attempt > 0) await ensureDefaultView('drag retry ' + attempt);
      const rect = JSON.parse(await cdp.eval(
        "JSON.stringify(document.querySelector('#graph').getBoundingClientRect())"));
      const ns = await waitNodeScreen(cdp, FAKE_ID, 3000);
      const x = Math.min(Math.max(ns.clientX, rect.left + 8), rect.right - 8);
      const y = Math.min(Math.max(ns.clientY, rect.top + 8), rect.bottom - 8);
      const cur = await waitDebug(cdp, (s) => !!(s.scene && s.scene.nodes.find((n) => n.id === FAKE_ID)),
        2000, 'node before drag');
      const b = cur.scene.nodes.find((n) => n.id === FAKE_ID);
      await dragAt(cdp, x, y, x, y + 70);
      try {
        const d = await waitDebug(cdp, (s) => {
          const n = s.scene.nodes.find((q) => q.id === FAKE_ID);
          return n && n.dist < b.dist - 0.2;
        }, 1500, 'node dragged closer');
        fakeBefore = b;
        fakeAfter = d.scene.nodes.find((n) => n.id === FAKE_ID);
      } catch (e) {
        if (DBG) console.log('[dbg] drag attempt ' + attempt + ' failed: ' + e.message);
      }
    }
    await note(cdp, 'after node drag');
    check(!!fakeAfter && fakeAfter.dist < fakeBefore.dist - 0.2 && fakeAfter.rPx > fakeBefore.rPx,
      'dragging a node changes its distance and ball size (dist ' + fakeBefore.dist + '→' +
      (fakeAfter || {}).dist + ' m, rPx ' + fakeBefore.rPx + '→' + (fakeAfter || {}).rPx + ')');

    // v1.4 摆位同步：拖完的落点要写进本机内核图谱（POS 帧），并被 /api/state 回吐给
    // 所有电脑/手机（这里用 HTTP 直读内核验证，不依赖渲染层自说自话）。
    let placed = null;
    for (let i = 0; i < 25 && !placed; i++) {
      const st = await hubState();
      const node = st && st.graph && (st.graph.nodes || []).find((n) => n.id === FAKE_ID);
      if (node && node.posSet === true && st.graph.placementVersion >= 1) placed = { node: node, version: st.graph.placementVersion };
      else await sleep(160);
    }
    check(!!placed, 'dragged layout is stored in the hub graph as a POS placement (placementVersion=' +
      (placed ? placed.version : '-') + ')');
    if (placed) {
      const scenePos = JSON.parse(await cdp.eval('JSON.stringify(window.__hubDebug().scene.positions["' + FAKE_ID + '"])'));
      check(Array.isArray(scenePos) && Math.abs(placed.node.x - scenePos[0]) < 0.15 &&
        Math.abs(placed.node.z - scenePos[2]) < 0.15,
        'graph position (x=' + placed.node.x + ', z=' + placed.node.z + ') matches the dragged scene spot (' +
        (scenePos ? scenePos[0] + ',' + scenePos[2] : '-') + ')');
      // 内核回声后场景仍停在该落点（没有被 200ms 轮询弹回自动槽位）
      const echo = await waitDebug(cdp, (s) => {
        const p = s.scene && s.scene.positions && s.scene.positions[FAKE_ID];
        return p && Math.abs(p[0] - placed.node.x) < 0.2 && Math.abs(p[2] - placed.node.z) < 0.2;
      }, 3000, 'placement echo in scene');
      check(!!echo, 'placement survives the /api/state poll (node stays where it was dropped)');
    }

    // v1.5 交互（用户定案）：左键只负责拖动；切换汇聚端 / 位置复位都走右键菜单。
    // 坐标来自 3D 投影，点击走 canvas 拾取。
    const fakeNs = await waitNodeScreen(cdp, FAKE_ID, 5000);
    check(Number.isFinite(fakeNs.clientX) && Number.isFinite(fakeNs.clientY), 'second node has 3D-projected screen coords');

    // 左键单击：不选中、不弹菜单（单击不再有任何动作）
    await clickAt(cdp, fakeNs.clientX, fakeNs.clientY);
    await sleep(300);
    snap = JSON.parse(await cdp.eval('JSON.stringify(window.__hubDebug())'));
    check(snap.selected === null && (await menuShown(cdp)) === false,
      'left click no longer selects a node or opens a bar/menu');

    // 右键 → 菜单（标题 + 切换 + 复位；该节点刚被拖过，所以有"位置复位"）
    await rightClickAt(cdp, fakeNs.clientX, fakeNs.clientY);
    let menuOpen = false;
    for (let i = 0; i < 20 && !menuOpen; i++) { menuOpen = await menuShown(cdp); if (!menuOpen) await sleep(60); }
    check(menuOpen === true, 'right click opens the node context menu');
    const menuTitle = await cdp.eval("(document.querySelector('#ctxMenu .ctxtitle')||{}).textContent || ''");
    check(typeof menuTitle === 'string' && menuTitle.indexOf(FAKE_NAME) >= 0,
      'context menu names the target device (' + JSON.stringify(menuTitle) + ')');
    check((await clickMenuText(cdp, '位置复位')) === 'clicked', 'context menu offers 位置复位 for a dragged node');
    let unplaced = null;
    for (let i = 0; i < 25 && !unplaced; i++) {
      const st = await hubState();
      const node = st && st.graph && (st.graph.nodes || []).find((n) => n.id === FAKE_ID);
      if (node && node.posSet === false) unplaced = node;
      else await sleep(160);
    }
    check(!!unplaced, '菜单"位置复位" clears the POS placement in the hub graph');
    check((await menuShown(cdp)) === false, 'context menu closes after choosing an item');

    // 右键 → "切换汇聚端到此设备"
    const fakeNs2 = await waitNodeScreen(cdp, FAKE_ID, 3000);
    await rightClickAt(cdp, fakeNs2.clientX, fakeNs2.clientY);
    let menuOpen2 = false;
    for (let i = 0; i < 20 && !menuOpen2; i++) { menuOpen2 = await menuShown(cdp); if (!menuOpen2) await sleep(60); }
    check(menuOpen2 === true, 'context menu reopens for the hub switch');
    check((await clickMenuText(cdp, '切换汇聚端')) === 'clicked',
      'context menu offers switching the hub to the target');
    snap = await waitDebug(cdp, (s) => s.hubId === FAKE_ID, 6000, 'hub switch applied in UI state');
    check(snap.hubId === FAKE_ID, 'UI state hubId moves to the right-clicked device');
    check((await menuShown(cdp)) === false, 'context menu hides after switching');
    // 切 hub 后布局动画 250ms：新 hub 大球回到屏幕中心，旧 hub 回到环上（3D 坐标）。
    snap = await waitDebug(cdp, (s) => s.scene && s.scene.hubScreen &&
      Math.abs(s.scene.hubScreen.sx - 460) <= 2 && Math.abs(s.scene.hubScreen.sy - 208) <= 2 &&
      s.scene.nodes.some((n) => n.id === FAKE_ID && n.isHub), 4000, 'hub ball re-centred after switch');
    check(true, 'new hub ball returns to screen centre (460,208) after the 250ms animation');
    const postScene = snap.scene;
    checkNoHubOverlap(postScene, 'after hub switch');
    const selfNodePost = postScene.nodes.find((n) => n.id === snap.selfId);
    check(!!selfNodePost, 'this PC is still projected on the ring after losing hub role');
    // D3：本机挂着 pcSource 时该源 enabled=false（旁路空间化）；否则必须为 true。
    check(!!selfNodePost && selfNodePost.enabled === !postScene.pcSource,
      'source enabled flag follows D3 bypass (pcSource=' + postScene.pcSource + ' → enabled=' + (selfNodePost || {}).enabled + ')');
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

    // 右键本机节点 → 切回来（反向验证）
    snap = JSON.parse(await cdp.eval('JSON.stringify(window.__hubDebug())'));
    await sleep(400);   // 等上一次切 hub 的 250ms 布局插值落位，点到的才是最终位置
    const selfNs = await waitNodeScreen(cdp, snap.selfId, 3000);
    await rightClickAt(cdp, selfNs.clientX, selfNs.clientY);
    let menuOpen3 = false;
    for (let i = 0; i < 20 && !menuOpen3; i++) { menuOpen3 = await menuShown(cdp); if (!menuOpen3) await sleep(60); }
    check(menuOpen3 === true, 'context menu opens on this PC node');
    check((await clickMenuText(cdp, '切换汇聚端')) === 'clicked',
      'context menu can switch the hub back to this PC');
    snap = await waitDebug(cdp, (s) => s.hubId === s.selfId, 6000, 'hub restored to this PC');
    check(snap.hubId === snap.selfId, 'switching back to this PC restores hub role');
    check((await menuShown(cdp)) === false, 'context menu hides after switching back');

    // Cleanup: "断开全部" clears the session graph back to this node only.
    // 「全部断开」从控制屏底部的"会话"卡片挪到了头部右侧：它必须只在控制界面出现。
    await cdp.eval("document.querySelector('#leaveBtn').click(); 'clicked'");
    snap = await waitDebug(cdp, (s) => s.screen === 'connect', 6000, 'back to connect screen after 全部断开');
    check(snap.screen === 'connect' && snap.nodes === 1, '「全部断开」清除会话并回到连接界面');
    const leaveHiddenAgain = await cdp.eval(
      "document.querySelector('#leaveBtn') && document.querySelector('#leaveBtn').style.display === 'none'");
    check(leaveHiddenAgain === true, '「全部断开」回到连接界面后重新隐藏');
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
    try { fs.rmSync(PROFILE_DIR, { recursive: true, force: true }); } catch (e) { /* profile 清理失败不影响结果 */ }
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