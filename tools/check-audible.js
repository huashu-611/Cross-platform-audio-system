// 汇聚端「到底有没有真的出声」——独立判定工具。
//
// 为什么需要它：界面上的「总线电平」来自 Meter::Take()，那是**取走即清零**的采样器，
// 界面每 200ms 轮询一次就把它搬空了。你在外面用 curl 再读一次，永远只会看到 -120，
// 于是「没声音」和「我刚把数读空了」看起来一模一样 —— 这个假象已经误导过好几轮排查。
//
// 本工具改读 v0.27 加的**渲染探针**：它夹在"真正送进声卡的那一帧"上，从启动起只增不减，
// 再多读者反复读都读到同一份值，谁也不会把它偷走。三个量：
//   renderFrames         已渲染帧数（音频设备真的在跑）
//   renderAudibleFrames  其中至少一个声道非零的帧数（真的在出声）
//   renderPeakDbfs       见过的最大电平
//
// 用法：
//   node tools/check-audible.js                    # 观察 5 秒，判定默认 hub（127.0.0.1:47900）
//   node tools/check-audible.js --port 47390 --seconds 10
//   node tools/check-audible.js --dump             # 顺便把实际输出录成 WAV，可直接播放核对
//
// 退出码：0 = 确实在出声；1 = 没出声（四种原因之一，见结论）；2 = 连不上 hub
'use strict';

const http = require('http');
const fs = require('fs');
const os = require('os');
const path = require('path');

// ---------------------------------------------------------------- 参数

let PORT = 47900;
let HOST = '127.0.0.1';
let SECONDS = 5;
let WANT_DUMP = false;
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--port' && argv[i + 1]) { PORT = Number.parseInt(argv[i + 1], 10); i++; }
  else if (argv[i] === '--host' && argv[i + 1]) { HOST = argv[i + 1]; i++; }
  else if (argv[i] === '--seconds' && argv[i + 1]) { SECONDS = Number.parseFloat(argv[i + 1]); i++; }
  else if (argv[i] === '--dump') { WANT_DUMP = true; }
  else if (argv[i] === '-h' || argv[i] === '--help') {
    console.log('用法: node tools/check-audible.js [--host 127.0.0.1] [--port 47900] [--seconds 5] [--dump]');
    process.exit(0);
  }
}
if (!Number.isFinite(PORT) || !Number.isFinite(SECONDS) || SECONDS <= 0) {
  console.error('[check] 参数无效');
  process.exit(2);
}

// ---------------------------------------------------------------- HTTP

function request (method, urlPath, body) {
  return new Promise((resolve) => {
    const data = body === undefined ? null : Buffer.from(body, 'utf8');
    const req = http.request({
      host: HOST,
      port: PORT,
      method,
      path: urlPath,
      timeout: 4000,
      headers: data ? { 'Content-Type': 'application/json', 'Content-Length': data.length } : {}
    }, (res) => {
      const chunks = [];
      res.on('data', (c) => chunks.push(c));
      res.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        let json = null;
        try { json = JSON.parse(text); } catch (_) { /* 保留 null */ }
        resolve({ status: res.statusCode, text, json });
      });
    });
    req.on('timeout', () => { req.destroy(new Error('timeout')); });
    req.on('error', (err) => resolve({ status: 0, text: '', json: null, error: err.message }));
    if (data) req.write(data);
    req.end();
  });
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------------------------------------------------------------- 判定

const num = (v, d) => (typeof v === 'number' && Number.isFinite(v) ? v : d);
const dbfs = (v) => (v <= -119 ? '静音' : v.toFixed(1) + ' dBFS');

function fmtDuration (frames) {
  if (!Number.isFinite(frames) || frames <= 0) return '0.00s';
  return (frames / 48000).toFixed(2) + 's';
}

// 「一路都没混进来」只是现象。同一个 0 至少有九种成因，从"对端根本没连上"
// 到"自己把它静音了"是完全不同的故障，笼统报一句等于没说。
// 判据全部来自已有的 /api/state（sources / pending / peers / graph.nodes），
// 不额外依赖任何后端字段。
function whyNoSource (st) {
  const list = Array.isArray(st.sources) ? st.sources : [];
  if (list.length === 0) {
    const pend = Array.isArray(st.pending) ? st.pending.length : 0;
    if (pend > 0) return '对端已请求连接，等你点「同意」';
    const peers = Array.isArray(st.peers) ? st.peers.length : 0;
    if (peers > 0) return '已经发现对端，但它还没开始送音';
    const nodes = Array.isArray(st.nodes) ? st.nodes : [];
    const others = nodes.filter(function (n) { return n && !n.isSelf; });
    if (nodes.length > 0 && others.length === 0) {
      // v0.31：把「没连上」再分成两件事 —— 这个进程收到过包没有。
      // rxPackets 在接收循环里只增不减，任何轮询都偷不走，是唯一可信的
      // 「网络层通没通」证据。==0 就别去查混音了。
      const rx = Number.isFinite(st.rxPackets) ? st.rxPackets : 0;
      if (rx === 0) return '星空里只有你自己，对端还没连上（本机一个包都没收到）';
      return '星空里只有你自己（收到 ' + rx + ' 个包，但没建成流）';
    }
    // v0.32：图谱里看得见、对方却连不上 —— 分界线是握手口（audioPort+2，
    // 承载 CONN-REQ / CONN-ACK / GRAPH / POS）有没有收到过包。
    //   hsPackets===0 且 discoveryPeers>0 → 广播(47300)通了但握手口被挡，
    //   两端互报「没回应」、音频口也必然是 0 包。这是"个别端口被挡"，不是"网络全断"。
    const hs = Number.isFinite(st.hsPackets) ? st.hsPackets : 0;
    const disco = Number.isFinite(st.discoveryPeers) ? st.discoveryPeers : 0;
    const hsPort = Number.isFinite(st.hsPort) ? st.hsPort : 0;
    // v0.36：最强的一条证据，排在最前。egressHijacked 来自后端 ProbeEgress
    // （connect()+getsockname()，不发任何包）：本机若发往这个节点，源地址会被
    // 系统选成代理 TUN 的地址（例如 198.18.0.1）。这种包的归宿是代理的虚拟网卡，
    // 永远到不了对面的手机/电脑；而且对端回包也按同一条错误路由走，所以两个
    // 方向会同时断——现场表现就是"两端互报没回应、音频口 0 包"。
    // 这与防火墙、AP 隔离、协议版本全都无关，第一步应当去关代理的 TUN 模式。
    const hijacked = others.filter(function (n) { return n && n.egressHijacked; });
    if (hijacked.length > 0) {
      return '本机发往 ' + hijacked[0].id + ' 的包被代理 TUN 接走了'
        + '（源地址会变成 ' + (hijacked[0].egressSrc || '代理地址') + '，对方的包也回不来）'
        + ' —— 请先关掉 Clash / 代理软件的 TUN（增强）模式';
    }
    if (others.length > 0 && others.every(function (n) { return !n.online; })) {
      if (hs === 0 && disco > 0) {
        return '对端在图谱里，但握手口 ' + (hsPort || 'audioPort+2')
          + ' 一个包都没收到（广播能看见它、这个端口被挡了，所以两端互报「没回应」）';
      }
      if (hs === 0) return '对端在图谱里但已离线（握手口也没收到过包）';
      return '对端在图谱里，握手口收到过 ' + hs + ' 个包，但没能建成流'
        + (st.hsLastCmd ? '（最近命令：' + st.hsLastCmd + '）' : '');
    }
    if (others.length > 0) {
      if (hs === 0 && disco > 0) {
        return '对端就在星空里，但它没开始送音（握手口 ' + (hsPort || 'audioPort+2') + ' 一个包都没收到）';
      }
      return '对端就在星空里，但它还没开始送音';
    }
    return '还没有任何对端连上来';
  }
  const muted = list.filter(function (x) { return x.muted; });
  if (muted.length === list.length) return '收到的 ' + list.length + ' 路音源全被你静音了';
  const unconfirmed = list.filter(function (x) { return !x.confirmed; });
  if (unconfirmed.length === list.length) return '流已收到，但都还没被确认（看「自动同意」）';
  const silent = list.filter(function (x) { return !x.packets || x.packets < 10; });
  if (silent.length === list.length) return '流刚建立，还没收到足够的包';
  return '收到了 ' + list.length + ' 路流，但没有一路通过混音条件';
}

async function main () {
  // 先确认连得上
  const probe = await request('GET', '/api/state');
  if (!probe.json) {
    console.error('[check] 连不上 http://' + HOST + ':' + PORT + '/api/state —— 汇聚端没在跑？'
      + (probe.error ? '（' + probe.error + '）' : ''));
    process.exit(2);
  }

  const first = probe.json;
  if (!('renderFrames' in first)) {
    console.error('[check] 这个汇聚端没有渲染探针（旧版本）。请用 v0.27 或更新的版本。');
    process.exit(2);
  }

  const base = {
    frames: num(first.renderFrames, 0),
    audible: num(first.renderAudibleFrames, 0),
    peak: num(first.renderPeakDbfs, -240),
    mixedFrames: num(first.mixedFrames, 0),
    mixedAudible: num(first.mixedAudibleFrames, 0),
    rxPackets: num(first.rxPackets, 0)
  };

  // 可选：录一段真实输出，用户可以直接双击播放来核对"我听到的"和"它记到的"是不是一回事
  let dumpPath = null;
  if (WANT_DUMP) {
    const target = path.join(os.tmpdir(), 'audiohub-audible-' + Date.now() + '.wav');
    const r = await request('POST', '/api/render-dump/start', JSON.stringify({ path: target }));
    if (r.json && r.json.ok) {
      dumpPath = r.json.path || target;
      console.log('[check] 开始录音 → ' + dumpPath);
    } else {
      console.log('[check] 录音启动失败：' + (r.text || '未知原因') + '（继续做电平判定）');
    }
  }

  // 观察窗口：每 250ms 采样一次，看两个量各自涨不涨
  const samples = [];
  const deadline = Date.now() + SECONDS * 1000;
  while (Date.now() < deadline) {
    await sleep(250);
    const r = await request('GET', '/api/state');
    if (r.json) {
      samples.push({
        at: Date.now(),
        frames: num(r.json.renderFrames, 0),
        audible: num(r.json.renderAudibleFrames, 0),
        peak: num(r.json.renderPeakDbfs, -240),
        ringMissed: num(r.json.renderRingMissed, 0),
        asHub: r.json.renderingAsHub !== false,
        mixedSources: num(r.json.mixedSources, 0),
        mixedFrames: num(r.json.mixedFrames, 0),
        mixedAudible: num(r.json.mixedAudibleFrames, 0),
        sources: Array.isArray(r.json.sources) ? r.json.sources : [],
        pending: Array.isArray(r.json.pending) ? r.json.pending : [],
        peers: Array.isArray(r.json.peers) ? r.json.peers : [],
        nodes: (r.json.graph && Array.isArray(r.json.graph.nodes)) ? r.json.graph.nodes : [],
        // v0.36：本机真实网卡清单（不含 127/169.254/198.18 等）与每个对端的出站源地址。
        localNics: Array.isArray(r.json.localNics) ? r.json.localNics : [],
        bus: num(r.json.busLevel, -120),
        // v0.31：接收侧证据。rxPackets 是接收循环里只增不减的计数，
        // 与混音/渲染完全无关，用来把"没连上"分成"包没到"和"包到了没建成流"。
        rxPackets: num(r.json.rxPackets, 0),
        rxBadPackets: num(r.json.rxBadPackets, 0),
        rxDistinctSenders: num(r.json.rxDistinctSenders, 0),
        rxLastIp: r.json.rxLastIp || '',
        rxLastPort: num(r.json.rxLastPort, 0),
        rxLastReject: r.json.rxLastReject || '',
        listenPort: num(r.json.listenPort, 0),
        // v0.32：握手口证据。rx_* 只覆盖音频口，而握手/图谱/摆位全走
        // audioPort+2 —— 完全独立的端口。只看音频口会把"只有 47002 被挡"
        // 误判成"网络全断"。
        hsPackets: num(r.json.hsPackets, 0),
        hsRecent: num(r.json.hsRecent, 0),
        hsLastIp: r.json.hsLastIp || '',
        hsLastPort: num(r.json.hsLastPort, 0),
        hsLastCmd: r.json.hsLastCmd || '',
        hsLastLine: r.json.hsLastLine || '',
        hsPort: num(r.json.hsPort, 0),
        discoveryPeers: num(r.json.discoveryPeers, 0)
      });
    }
  }

  let finalPath = null;
  if (dumpPath) {
    const r = await request('POST', '/api/render-dump/stop');
    finalPath = (r.json && r.json.path) || dumpPath;
    const frames = (r.json && num(r.json.frames, 0)) || 0;
    console.log('[check] 录音结束 → ' + finalPath + '（' + fmtDuration(frames) + '，'
      + (frames > 0 ? '可直接双击播放' : '⚠ 一帧都没录到，说明渲染设备没跑') + '）');
  }

  if (samples.length < 2) {
    console.error('[check] 采样太少，汇聚端可能中途退出了');
    process.exit(2);
  }

  const last = samples[samples.length - 1];
  const dFrames = last.frames - base.frames;
  const dAudible = last.audible - base.audible;

  // 本次窗口内的输出峰值（不是历史峰值）：只看这段时间有没有真的响过
  const peakInWindow = last.peak;

  const confirmedSources = last.sources.filter((s) => s && s.confirmed !== false);
  const anySources = last.sources.length;

  console.log('');
  console.log('===== 汇聚端出声判定（' + HOST + ':' + PORT + '，观察 ' + SECONDS + 's）=====');
  console.log('  音源路数        : ' + anySources + '（其中已确认 ' + confirmedSources.length + '）');
  if (anySources > 0) {
    for (const s of last.sources) {
      console.log('    · stream ' + s.id + '  电平 ' + dbfs(num(s.level, -120))
        + '  包数 ' + num(s.packets, 0) + '  ' + (s.confirmed === false ? '未确认（不参与混音）' : '已确认'));
    }
  }
  console.log('  渲染帧数         : ' + last.frames + '（本窗口 +' + dFrames + '，约 '
    + fmtDuration(dFrames) + ' 音频）');
  console.log('  有声帧数         : ' + last.audible + '（本窗口 +' + dAudible + '）');
  console.log('  峰值             : ' + dbfs(peakInWindow));
  console.log('  环形缓冲欠载     : ' + last.ringMissed + ' 帧');
  console.log('  接收包数         : ' + last.rxPackets + '（本窗口 +' + (last.rxPackets - base.rxPackets)
    + '，坏包 ' + last.rxBadPackets + '，来源 ' + last.rxDistinctSenders + ' 个）');
  if (last.rxPackets > 0) {
    console.log('    最近来源      : ' + (last.rxLastIp || '-') + ':' + last.rxLastPort
      + (last.rxLastReject ? '，最近一次拒收：' + last.rxLastReject : ''));
  }
  console.log('  监听端口         : ' + (last.listenPort || '(未知)'));
  // v0.36：出站网卡。收包绑的是 0.0.0.0，发出去走哪张卡却由系统路由表决定 ——
  // 装了代理 TUN 之后，发往跨网段对端的包会被接走，而这一层在收包侧毫无痕迹。
  if (Array.isArray(last.localNics) && last.localNics.length > 0) {
    console.log('  本机网卡         : ' + last.localNics.join('；'));
    const bad = (Array.isArray(last.nodes) ? last.nodes : []).filter(function (n) {
      return n && !n.isSelf && n.egressHijacked;
    });
    if (bad.length > 0) {
      console.log('  ⚠ 出站被接管    : ' + bad.map(function (n) { return n.id; }).join('、')
        + ' 的包会被代理 TUN 接走（源地址 '
        + (bad[0].egressSrc || '?') + '），永远到不了对端。');
      console.log('    处理办法      : 关掉 Clash / 代理的 TUN（增强）模式，或让两端处于同一网段。');
    }
  }
  console.log('  握手口包数       : ' + (last.hsPackets === 0 ? '一个都没收到' : last.hsPackets
    + '（最近 10 秒 ' + last.hsRecent + ' 个）') + '，端口 ' + (last.hsPort || '(未知)'));
  if (last.hsPackets > 0) {
    console.log('  最近握手来源     : ' + (last.hsLastIp || '-') + ':' + last.hsLastPort
      + (last.hsLastCmd ? '，命令 ' + last.hsLastCmd : '')
      + (last.hsLastLine ? '（' + last.hsLastLine + '）' : ''));
  }
  console.log('  发现到的设备     : ' + last.discoveryPeers + ' 台（广播 47300）');
  console.log('  本机是汇聚端     : ' + (last.asHub ? '是' : '否（按协议混音输出恒为静音）'));
  console.log('  实际混入路数     : ' + last.mixedSources);
  console.log('  混入帧/有声帧    : ' + last.mixedFrames + ' / ' + last.mixedAudible
    + '（本窗口 +' + (last.mixedFrames - base.mixedFrames) + ' / +'
    + (last.mixedAudible - base.mixedAudible) + '）');
  console.log('');

  // 判定树：四种情形，各自对应完全不同的下一步
  let verdict;
  let code;
  if (dFrames === 0) {
    verdict = ['✗ 渲染设备根本没在跑（本窗口 0 帧）。',
      '  汇聚端可能没打开输出设备、或设备被独占。这不是"没声音"，是"没有输出通道"。'];
    code = 1;
  } else if (anySources === 0) {
    // 注意这一支**必须**也用 whyNoSource：用户最常遇到的就是"一个音源都没有"，
    // 如果这里只报一句"没收到包"，那九支细分判定在最常见的场景下永远用不上。
    const why = whyNoSource(last);
    // 第二句必须按 rxPackets 分开写：包到了却仍说"一包都没收到"是自相矛盾，
    // 会把用户引到完全错误的方向（去查防火墙，其实防火墙没问题）。
    const nothingArrived = (last.rxPackets || 0) === 0;
    verdict = ['✗ 一个音源都没接入：' + why,
      nothingArrived
        ? '  汇聚端一包音频都没收到，问题在网络/发送端，不在混音，也不在音量。'
        : '  包已经到了（' + last.rxPackets + ' 个），但没能建成音频流，问题在协议/格式，不在网络。'];
    if (why.indexOf('一个包都没收到') >= 0) {
      // 决定性证据：这个进程一个字节都没收到。别再去调音量/混音，那是白费。
      verdict.push('  本机一个 UDP 包都没收到 —— 问题在网络层，不在音频层。按顺序查：');
      verdict.push('    1) 对端 App 里点了「送入」吗？（只设成"音源"、不点"送入"不会发）');
      verdict.push('    2) 对端填的 IP 是不是这台电脑当前的地址？'
        + '（手机端会把上次的 hubIp 存进 SharedPreferences，换了网段也照样往旧 IP 发）');
      verdict.push('    3) Windows 防火墙：便携版每次启动都解压到 %TEMP%\\<随机名>\\，'
        + '新目录没有放行规则，入站 UDP 会被静默丢弃。');
      verdict.push('       放行命令（管理员 PowerShell）：');
      verdict.push('       New-NetFirewallRule -DisplayName "AudioHub 入站" -Direction Inbound '
        + '-Program "<本程序路径>" -Action Allow -Profile Public');
    } else if (why.indexOf('没建成流') >= 0) {
      // 上面第二句已经说明了「包到了、没建成流」，这里只补原因，别重复。
      if (last.rxLastReject) verdict.push('  最近一次拒收原因：' + last.rxLastReject);
    } else if (why.indexOf('星空里只有你自己') >= 0 || why.indexOf('还没有任何对端') >= 0) {
      verdict.push('  先解决连通性：对端 App 里点「送入」，两端防火墙放行 UDP，'
        + '图谱里应该能看到对方那颗星球。');
    } else if (why.indexOf('已离线') >= 0) {
      verdict.push('  对端在图谱里但已离线：它的 App 可能被系统杀掉了，或换了 IP。');
    } else if (why.indexOf('请求连接') >= 0) {
      verdict.push('  对端在等你确认，去界面上点「同意」。');
    } else {
      verdict.push('  对端已经在线却没送音：查它是否点了「送入」并授权了录屏/采集。');
    }
    code = 1;
  } else if (confirmedSources.length === 0) {
    verdict = ['✗ 有 ' + anySources + ' 路音源，但没有一路是"已确认"状态 —— 它们全被排除在混音之外。',
      '  这是 v0.26 之前那种"包在涨、耳机全静音"的经典配方。',
      '  查：汇聚端的"自动同意"是否关闭了？发送端有没有走 CONN-REQ 握手？'];
    code = 1;
  } else if (!last.asHub) {
    // 这一条必须排在 audible===0 之前：本机不是汇聚端时，0 是**正确行为**。
    // 以前没有这个判据，把"协议规定的静音"误报成缺陷，白白排查好几轮。
    verdict = ['△ 本机不是汇聚端 —— 混音输出按协议 §6.1 恒为静音，' + last.frames + ' 帧全是零是正确的。',
      '  声音应该在汇聚端那台设备上；要检查"到底有没有出声"，请到那台设备上再跑一次本工具。',
      '  如果两台设备都听不到，问题在图谱/角色分配（谁是汇聚端），不在音频输出。'];
    code = 1;
  } else if (last.audible === 0 && last.mixedSources === 0) {
    // 「一路都没混进来」只是现象。同一个 0 至少有九种成因，从"对端根本没连上"
    // 到"自己把它静音了"是完全不同的故障，笼统报一句等于没说。
    const why = whyNoSource(last);
    verdict = ['✗ 本机是汇聚端，但一路音源都没混进来：' + why,
      '  渲染了 ' + fmtDuration(last.frames) + ' 音频，全是零 —— 这是"没混进去"，'
      + '不是音量或输出设备的问题，改那两样没用。'];
    if (why.indexOf('星空里只有你自己') >= 0 || why.indexOf('还没有任何对端') >= 0) {
      verdict.push('  先解决连通性：对端要点「送入」，两端防火墙别拦 UDP，图谱里要能看见对方。');
    } else if (why.indexOf('静音') >= 0) {
      verdict.push('  去界面上把这路音源的静音取消。');
    } else if (why.indexOf('未确认') >= 0) {
      verdict.push('  打开汇聚端的「自动同意」，或让发送端走 CONN-REQ 握手。');
    } else {
      verdict.push('  对端在线时，查它是否真的在播放媒体声音、采集授权是否通过。');
    }
    code = 1;
  } else if (last.audible === 0) {
    verdict = ['✗ 本机是汇聚端，确实混入了 ' + last.mixedSources + ' 路（共 ' + last.mixedFrames
      + ' 帧参与混音），但混出来全是零。',
      '  数据进来了、内容是静音：说明采集端抓到的就是无声。',
      '  本功能送的是发送端「正在播放的声音」（音乐/视频/游戏），不是麦克风 ——',
      '  请确认发送端真的在放媒体声音，且它的采集授权已通过（不是"点了开始但没授权"）。'];
    code = 1;
  } else if (dAudible === 0) {
    verdict = ['△ 之前在出声（历史有声帧 ' + last.audible + '），但这 ' + SECONDS + ' 秒里没有新的声音。',
      '  汇聚端本身是通的 —— 现在只是发送端没在播放媒体声音。',
      '  本功能送的是发送端「正在播放的声音」，不是麦克风。'];
    code = 1;
  } else {
    verdict = ['✓ 确实在出声：本窗口有 ' + dAudible + ' 帧真的带着声音送进了声卡（峰值 ' + dbfs(peakInWindow) + '）。',
      '  如果你耳机里听不到，问题在汇聚端之后：系统音量 / 静音开关 / 默认输出设备选错了。'];
    code = 0;
  }
  console.log('===== 结论 =====');
  for (const line of verdict) console.log(line);
  if (finalPath) console.log('\n录音文件（双击可播放核对）：' + finalPath);
  console.log('');
  process.exit(code);
}

main().catch((err) => {
  console.error('[check] 异常: ' + (err && err.stack ? err.stack : err));
  process.exit(2);
});
