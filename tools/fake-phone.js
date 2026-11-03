// 假手机：扮演"手机当汇聚端"，验证桌面端到底有没有把音频发过来。
//
// 它做三件事：
//   1. 在 47000 收音频包并计数（这就是"已接收 N 路"的来源）；
//   2. 在 47002 收 CONN-REQ 并回 CONN-ACK（手机的 InviteListener 行为）；
//   3. 每 500ms 打一行统计，任何时刻都能看出"有没有在收"。
//
// 用法：node tools/fake-phone.js [--audio-port 47000]
'use strict';

const dgram = require('dgram');

// 端口可覆盖：本机可能正跑着真实的 App/hub，绝不能默认去抢 47000。
let AUDIO_PORT = 47000;
// 绑定地址也可覆盖：本机 hub 常在同一台机器上，它会把 "127.0.0.1" 当成自己，
// 用 127.0.0.2 冒充另一台设备才不会撞。
let BIND_IP = '0.0.0.0';
const argv = process.argv.slice(2);
for (let i = 0; i < argv.length; i++) {
  if (argv[i] === '--audio-port' && argv[i + 1]) {
    AUDIO_PORT = Number.parseInt(argv[i + 1], 10);
    i++;
  } else if (argv[i] === '--bind' && argv[i + 1]) {
    BIND_IP = argv[i + 1];
    i++;
  }
}
if (!Number.isInteger(AUDIO_PORT) || AUDIO_PORT <= 0 || AUDIO_PORT > 65000) {
  console.error('[phone] --audio-port 无效');
  process.exit(2);
}
const ACK = 'CONN-ACK|FakePhone|1\n';

const audio = dgram.createSocket({ type: 'udp4', reuseAddr: true });
const hs = dgram.createSocket({ type: 'udp4', reuseAddr: true });

let packets = 0;
let bytes = 0;
let firstAt = null;
let acks = 0;
let requests = 0;
const streamIds = new Set();
const peerIps = new Set();

audio.on('message', (msg, rinfo) => {
  if (msg.length < 16) return;
  const version = (msg[0] & 0xf0) >> 4;
  if (version !== 1) return;
  const streamId = msg.readUInt16LE(2);
  streamIds.add(streamId);
  peerIps.add(rinfo.address);
  packets++;
  bytes += msg.length;
  if (firstAt === null) {
    firstAt = Date.now();
    console.log(`[phone] 第一个音频包 ${msg.length}B stream=${streamId} from ${rinfo.address}:${rinfo.port}`);
  }
});

hs.on('message', (msg, rinfo) => {
  const text = msg.toString('utf8').trim();
  if (text.startsWith('CONN-REQ')) {
    requests++;
    hs.send(Buffer.from(ACK, 'utf8'), rinfo.port, rinfo.address);
    acks++;
    if (requests <= 3) {
      console.log(`[phone] CONN-REQ <- ${rinfo.address}:${rinfo.port} "${text}" → 已回 CONN-ACK`);
    }
  } else if (requests <= 3) {
    console.log(`[phone] 握手口收到 "${text}"`);
  }
});

audio.bind(AUDIO_PORT, BIND_IP, () => {
  console.log(`[phone] 音频口 ${BIND_IP}:${AUDIO_PORT} 已监听（这是"已接收 N 路"的来源）`);
});
hs.bind(AUDIO_PORT + 2, BIND_IP, () => {
  console.log(`[phone] 握手口 ${AUDIO_PORT + 2} 已监听`);
});

let last = 0;
setInterval(() => {
  const rate = packets - last;
  last = packets;
  const kaudio = (bytes / 1024).toFixed(0);
  console.log(
    `[phone] 收包 ${packets}（近1s +${rate}） ${kaudio}KB 路数=${streamIds.size} ` +
    `握手请求=${requests} 已回ACK=${acks} 来源=${[...peerIps].join(',') || '-'}`
  );
}, 1000);
