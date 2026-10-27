# AudioHub 网络图谱（GRAPH）协议 v1

> 目标：全网维护**同一张图谱**——包含所有已入网设备，以及"谁是汇聚端"。
> 切换汇聚端 = 改图谱 + 同步给所有设备；每台设备按图谱自己决定职责（收流播放 / 采集发送）。
> 本文是 PC（C++ + Electron）与 Android 两端实现的唯一契约。改协议先改本文。

---

## 0. 一句话模型

图谱 = **节点集合（取并集，CRDT）+ 汇聚端指针（LWW 寄存器）**。
- 节点集合：单调增长。谁发现了新设备就加进去并广播，各端并集收敛。
- 汇聚端：带 Lamport 版本号的 last-writer-wins。任何设备都能改，改完广播，全网收敛到同一值。

---

## 1. 数据模型

```
Node {
  id        : "ip:audioPort"   // 设备唯一标识 = 可达地址，如 "192.168.1.20:47000"
  name      : string           // 显示名，UTF-8，≤ 40 字节，清洗过
  kind      : "pc" | "android" | "other"
  audioPort : uint16           // 该设备的音频口 / 握手口 = audioPort+2
  httpPort  : uint16           // PC 为 HTTP 控制口；Android 为 0
}

Graph {
  version : uint64             // Lamport 版本号，只增
  origin  : string             // 最近一次 hub 改动的发起者 node id（同版本时按字典序裁决）
  hubId   : string             // 当前汇聚端 node id；空 = 尚未确定
  nodes   : Node[]             // 按 id 升序（规范序，便于比较/去重）
}
```

**节点身份**：`id = ip:audioPort`，ip 是该设备在局域网内被对端看到的地址。
角色切换不改 id：PC 的 hub 进程常驻 47000；Android 的 InviteListener 常驻 `audioPort+2`。
设备名：PC = hostname；Android = `Build.MODEL`（拿不到时用"Android 设备"）。

**默认值**
- PC 启动：`{version:1, origin:selfId, hubId:selfId, nodes:[self]}`（PC 默认汇聚端）。
- Android 启动：拿到本机 WiFi IP 后 `{version:0, origin:"", hubId:"", nodes:[self]}`，等 PC 的 GRAPH 带过来。

---

## 2. 线格式（UDP，握手口 = audioPort + 2，UTF-8，`\n` 结尾）

与现有 `CONN-*` / `ROLE` 同一通道、同一风格。帧总长必须 < 1000 字节（接收缓冲 1024）。

### 2.1 图谱帧

```
GRAPH|<version>|<origin>|<hubId>|<node>;<node>;...
node = <id>,<name>,<kind>,<audioPort>,<httpPort>
```

- `nodes` 为空时也要保留末尾 `|` 之后为空串（即末尾一个 `|` 后什么都没有）——实现上直接按 `|` split 后第 5 段为空。
- 名字清洗：把 `|` `,` `;` `\n` `\r` 以及其它小于 0x20 的控制字符替换为 `_`；UTF-8 截断到 40 字节（按 UTF-8 字符边界截，不能截半个汉字）。
- `hubId` 允许为空（Android 还没加入网络时）。
- 解析必须宽松：字段缺失/数字非法/节点 id 不含 `:`/节点数 > 32 → 整帧丢弃。
- 编码前 nodes 按 id 升序排序。
- **规模上限（两端一致）**：节点最多 32 个；编码后若超过 1000 字节，按 id 升序从**尾部**裁掉节点直到不超过 1000 字节（确定性规则，避免两端算出不同帧）。解码时节点数 > 32 或帧长 > 1000 → 整帧丢弃。实际局域网远达不到上限，这是防御性上限。
- **活标志**：收到某条数据报时，用**数据报来源 IP** 匹配 nodes 里 `ip` 相同的节点并更新 `lastSeenMs`；GRAPH 帧和音频包都算（Android 收不到音频包，只能靠 GRAPH 保活，3 秒广播已足够）。

示例：
```
GRAPH|3|192.168.1.20:47000|192.168.1.20:47000|192.168.1.20:47000,客厅PC,pc,47000,4780;192.168.1.31:47000,Redmi K60,android,47000,0
```

### 2.2 同步请求帧

```
GRAPH-REQ
```

收到后，立刻把**当前图谱**单播回来源地址:端口（即数据报的 src），同一来源 200ms 内不重复回。

---

## 3. 合并算法（两端必须逐字一致）

```
merge(local, remote) -> changed
  # 1) 节点：按 id 取并集；id 冲突保留 local（节点字段在会话内不变）
  #    合并后按 id 升序排序
  # 2) 汇聚端：LWW
  if remote.version > local.version:
      local.hubId = remote.hubId; local.version = remote.version; local.origin = remote.origin
  else if remote.version == local.version and remote.origin != local.origin:
      if remote.origin > local.origin:          # 逐字节字典序，ASCII 比较即可
          local.hubId = remote.hubId; local.origin = remote.origin
  # 3) 其它情况保持 local
  changed = (节点集合有变化 or hubId/version/origin 有变化)
```

补充规则：
- **hubId 指向不存在的节点**：保留该值（成员可能稍后到达），UI 显示"汇聚端未知"。
- 节点一旦存在就不改字段（名字/kind/端口会话内视作不变）；`upsertNode` 只在 id 不存在时追加。
- 本地产生改动时：`version = max(local.version, 本地见过的最大 version) + 1`，`origin = selfId`。
  （单调时钟：收到任何 remote.version 都更新"见过的最大 version"，即使没采纳。）

---

## 4. 本地动作与触发条件（两张都做）

### 4.1 节点注册（入网）

以下事件把对端加入 nodes（不算 hub 改动，**不 bump version**）：
1. `CONN-REQ` 被接受 / `CONN-ACK` 发出或收到（握手成功，双方都加对方）。
2. 手动邀请了一个 IP（PC `InvitePeer` / UI 手填地址）→ 先加一个占位节点（name=ip 或对端名，kind=other），握手成功后再用握手帧里的名字补齐。
   注意：§3 规定同 id 节点"保留 local、字段会话内不变"，所以占位名不会因为收到对端 GRAPH 而被"校正"——手动邀请的节点可能一直显示成 IP，
   这是可接受的（功能不受影响）；不要为了改名去破坏并集收敛的确定性。
3. 从别人的 GRAPH 里学到新节点 → 并集加入。

节点加入后若有变化：立即广播当前 GRAPH（见 §4.4）。**不删除节点**（本轮不做离开/移除；会话内节点集合只增）。

### 4.2 收到 GRAPH

1. 解析 → merge（§3）→ 记录该数据报 source IP 对应节点的 `lastSeenMs = now`（活标志，见 §8）。
2. 若 `changed`：应用（见 §6 职责协调）+ 广播当前图谱（去重/限速 §4.4）。
3. 若未变化：不回发（防止广播风暴）。

### 4.3 收到 GRAPH-REQ

回复当前图谱（200ms 内同源不重复回）。

### 4.4 广播

- 目标：nodes 里除 self 外的每个节点，取 `id` 的 ip + `audioPort + 2`。
- 发送后失败不影响本地状态（UDP 无连接，忽略错误）。
- **触发**：
  - 本地变化（新增节点、切换 hub）：立即发 2 次，间隔 60ms；
  - 合并导致变化：立即发 1 次；
  - 启动后 / 学会新节点：发 1 次 + 向该节点发一次 GRAPH-REQ；
  - 周期保活：每 3 秒向所有节点广播一次当前 GRAPH（补偿丢包）。
- **限速**：同一设备 1 秒内最多广播 5 次；超出则合并为一次延迟发送。
- 回环终止：只有 `changed` 或定时器才广播；收到相同状态不广播。

### 4.5 本地切换汇聚端（用户右键点节点 N → 菜单「切换汇聚端到此设备」）

1. N 必须存在且在 nodes 中，否则拒绝（HTTP 接口返回 error / Android toast）。
2. `version = max(version, seen) + 1; origin = selfId; hubId = N.id`。
3. 应用职责（§6）+ 广播 2 次（60ms 间隔）。

### 4.6 会话清空（"全部断开"）

本地把 graph 重置为 `{version:version+1, origin:selfId, hubId:selfId, nodes:[self]}`，**不广播**（对端已断开/不可达）。Android 断开全部同理（hubId 清空）。

---

## 5. 自动放行连接（入网信任规则）

- **图谱成员 IP** 的连接请求：任何设备（hub 或非 hub）都**自动 ACK**，不弹窗。
- **非成员** 的连接请求：沿用现有 `auto_accept` 逻辑（默认自动 ACK；关掉时走待确认弹窗）。
  非 hub 设备也要能接受新设备入网——否则新设备只能靠发现 hub 才能入网，而 hub 会切到手机，靠发现不总是可行。
- 握手成功后（ACK 发出 / ACK 收到 / REQ 被接受）双方立刻 `UpsertNode` 对端并**互发一次当前 GRAPH**，
  让新设备瞬间学到"真正的 hub 是谁"；随后它的音源由 reconcile 自动改投 hub。
- 非 hub 接受了新设备的流：允许短暂发生（对端还没收到 GRAPH 之前），本机不混音（静音），由对端下一次 reconcile 纠正。
- 安全模型不变：局域网明文信任，不做配对鉴权（见 §12）。

---

## 6. 职责协调（每端都实现为"按图谱 reconcile"）

派生状态：
```
selfId  = 本机节点 id
isHub   = (graph.hubId == selfId)
hubNode = nodes 里 id == graph.hubId 的节点（可能不存在/离线）
isSource = !isHub && hubNode 存在
streamId = 1 + （self 在 nodes 按 id 升序的序号，从 0 计）   // 供音源使用；hub 不用
```

### 6.1 PC（Electron 主进程，每 200ms 读 `/api/state` 后 reconcile）

- `isHub` → 停止 `pcSource` 子进程（PC 自己收流/混音，靠常驻 hub 进程）。
- `isSource` → 确保 `pcSource` 在跑且目标是 `hubNode.ip:hubNode.audioPort`、且 `--stream-id == streamId`；
  目标或 streamId 与当前进程参数不同 → 停旧起新（同一目标不重启）。
  启动参数沿用现有：`audiohub-source --hub <ip>:<port> --handshake --device-name <hostname> --stream-id <n>`。
- hub 进程永远常驻（它是本机的"网络节点代理"：图谱同步、握手口、发现）。
- **PC 不是 hub 时，混音线程输出静音**（不混任何流、不发声），避免和远端 hub 抢着放音（在 C++ 里做：`is_hub` 由 HubCore 图谱状态决定）。

### 6.2 Android（MainActivity 400ms poller 后 reconcile）

- `isHub` → 启动并保持 `ReceiverService`（ACTION_START，端口 47000）；若 `CaptureService` 在跑则先停（同一设备不能既发又收）。
- `isSource` → 若 `CaptureService` 在跑且目标/streamId 与图谱不一致 → 用新目标重启（复用已保存的 MediaProjection 授权）；若没授权运行 → 仅提示"本机已设为音源，开始采集后生效"。
- 两者都不是（hub 未知）→ 停掉正在跑的服务，保持空闲。
- `ReceiverService` 停止时若本机不再是 hub：保留图谱（切回 source 后还能切回来）。

### 6.3 关键不变式

- 全网**最多一个** hub：由 LWW 收敛保证（短暂双 hub 允许，几秒内收敛）。
- 音源永远发给"图谱说的 hub"，不发给"上次连过的 hub"；切换后由 reconcile 重启发送目标。
- streamId 冲突由 rank 规则避免（成员变化时 rank 可能变 → 音源端发现 streamId 变了就重启发送，hub 端旧流 5s 超时消失）。

---

## 7. PC HTTP 接口（127.0.0.1，在现有 `/api/state` 基础上扩展）

> 请求体读取约定：HTTP 服务端收到请求头块后，按 `Content-Length` 继续收满声明的字节数再交给处理器；
> 没有 `Content-Length` 视为无请求体。客户端应保证请求头与请求体在同一条连接内发完
> （服务端响应带 `Connection: close`）。

### 7.1 GET /api/state —— 新增 `graph` 字段

```jsonc
"graph": {
  "version": 7,
  "origin": "192.168.1.20:47000",
  "hubId": "192.168.1.20:47000",
  "selfId": "192.168.1.20:47000",
  "isHub": true,
  "nodes": [
    {
      "id": "192.168.1.20:47000",
      "name": "客厅PC",
      "kind": "pc",
      "ip": "192.168.1.20",
      "audioPort": 47000,
      "httpPort": 4780,
      "online": true,
      "isSelf": true,
      "isHub": true,
      "streamId": 0,          // 该节点当前承载的音频流 id；0 = 无
      "level": -120.0,        // dBFS
      "occupancy": 0,         // ms
      "gain": 1.0,
      "muted": false
    }
  ]
}
```

- `online` 规则：isSelf 恒 true；否则 `5s 内有该 ip 的音频包` 或 `会话关系表里有该 ip` 或 `发现列表 last_seen < 10s` 或 `10s 内收到过该 ip 的 GRAPH`。
- 流字段按 `source.peer_ip == node.ip` 匹配；无流时取默认值（0 / -120 / 0 / 1.0 / false）。
- 原有字段（`sources`/`peers`/`pending`/`ip`/`latency`/`autoAccept`/...）保持兼容，不删。

### 7.2 POST /api/graph/switch

请求：`{"nodeId":"192.168.1.31:47000"}`
响应：`{"ok":true,"graph":{...同 7.1...}}`；节点不存在：`{"ok":false,"error":"unknown node"}`。
语义 = §4.5：本地改图谱并广播，立即生效。

### 7.3 POST /api/graph/sync

请求体可为空或 `{}`。立即向所有节点广播一次 GRAPH，并给每个节点发一次 GRAPH-REQ。
响应：`{"ok":true,"sent":<节点数>}`。

### 7.4 兼容

- `/api/role` 保留但 UI 不再调用（旧界面/脚本仍可用）。
- 其它接口（source/gain、invite、accept、reject、disconnect、auto_accept、session/clear）语义不变。
  `POST /api/session/clear` 额外执行 §4.6 的图谱重置。

---

## 8. 活标志与离线

- 每端记录 `node.lastSeenMs`：收到该 ip 的数据报（GRAPH 或音频包）时更新。
- 判定 online：`isSelf` 或 `now - lastSeen < 10s`；PC 还可参考发现列表。
- 离线节点仍留在图谱里（用户可能想切过去等它回来）。UI 半透明显示，切换前提示"设备当前离线"。

---

## 9. UI 要求（两端信息架构一致）

### 9.1 图谱（核心交互面）

- 渲染 **nodes 全集**：hub 节点 = 大球（约 1.5 倍半径 + 光晕 + "汇聚端"角标）；其余小球环绕；
  本机节点有"本机"角标；离线节点半透明 + 虚线描边；选中节点描边加粗。
- 连线：hub → 其余每个节点一条线（有音频在流的线加粗/随电平变化）。
- **交互分工（v1.5，用户定案）**：左键只负责拖动（拖节点=摆位，拖空白=轨道旋转），
  单击不选中、不弹条；**右键点节点**弹菜单（v1.6 起不再有音量调节，距离就是音量）：
  - 「切换汇聚端到此设备」：非 hub 节点 → 调切换接口/IPC，立即显示"已切换，正在同步…"，
    收到新图谱后显示"已同步（v<version>）"；hub 节点该行置灰（"它已经是汇聚端"）。
  - 「位置复位」：仅该节点被显式摆位过（POS 表里有它）时出现 → 清摆位、回到自动槽位。
  - 「所有设备位置复位」：只在汇聚端节点上出现 → 一次清完整张图的显式摆位。
  - Esc / 点菜单外 / 节点从图谱消失 → 收起菜单。
- 双击节点不再有任何动作（旧"双击切汇聚端"随操作条一起废除）；操作条 `#graphAction` 移除；
  音量面板（滑杆/静音）随 v1.6 "距离即音量"一起移除，后端 `/api/source`、`/api/master`
  接口保留（供脚本/旧客户端使用）。
- 旧的下拉框 `hubSelect` 移除；`autoAccept` 勾选框保留（只影响非成员连接请求）。

### 9.2 PC（Electron）

- 主进程新增 IPC：`hub:switch`（参数 nodeId）→ POST /api/graph/switch；`hub:state` 返回值里带 graph。
- 主进程负责 §6.1 的 pcSource 拉起/停止。
- 渲染层 `renderGraph` 改为按 `state.graph.nodes` 画全网；控制屏显示条件：
  `graph.nodes.length >= 2`（网络已形成）或本机有音频流。
- **删除旧逻辑**：`renderer.js` 里"角色跟随事实"（确认流数>0 且 !hubSelf 就自动 setRole(self)）必须删掉，
  它会和新模型的收敛打架。`main.js` 的 `hub:setRole` 保留但不再被 UI 调用。
- PC 作为音源时，自节点电平取 `state.pcSource*` 字段显示。

### 9.3 Android

- `HubGraphView` 改为按 GraphStore 快照画全网（hub 大球/本机角标/离线虚化/选中高亮/点击回调带 node id）。
- MainActivity：点节点 → 非 hub 弹确认框 → `GraphStore.switchHub(nodeId)` + `InviteListener.broadcastGraph()` + toast。
- 屏幕切换条件沿用现有状态机：有图谱网络或本机在采集/接收时显示控制屏。
- 现有"汇聚端"下拉/切换按钮删除，由点图谱节点替代。

---

## 10. 实现分工与接口（本仓库）

### 10.1 C++（平台无关，可单测）

新增 `include/audiohub/graph.hpp` + `src/graph.cpp`（**不得依赖 socket / WASAPI**）：

```cpp
namespace audiohub {
struct GraphNode { std::string id, name, kind; uint16_t audio_port = 0, http_port = 0; };
struct GraphState {
  uint64_t version = 0; std::string origin, hub_id; std::vector<GraphNode> nodes;
};
struct GraphMergeResult { bool changed = false; bool nodes_changed = false; bool hub_changed = false; };

std::string SanitizeName(const std::string& in);        // 清洗 + 40 字节截断
std::string EncodeGraphFrame(const GraphState& g);      // 含结尾 '\n'
bool DecodeGraphFrame(const std::string& frame, GraphState* out);  // false = 非法
bool IsGraphRequestFrame(const std::string& frame);     // "GRAPH-REQ"（允许结尾空白）
GraphMergeResult MergeGraph(GraphState* local, const GraphState& remote);
bool UpsertNode(GraphState* g, const GraphNode& n);     // 已存在返回 false
bool SwitchHub(GraphState* g, const std::string& self_id, const std::string& hub_id);
bool IsHub(const GraphState& g, const std::string& self_id);
const GraphNode* FindNode(const GraphState& g, const std::string& id);
const GraphNode* FindNodeByIp(const GraphState& g, const std::string& ip);
uint16_t StreamIdFor(const GraphState& g, const std::string& self_id); // rank+1
void SortNodes(GraphState* g);                          // 按 id 升序
}
```

HubCore 扩展（`include/audiohub/hub_core.hpp` + `src/hub_core.cpp`）：
- `GraphState Graph() const;`、`std::string graph_self_id() const;`、`bool graph_is_hub() const;`
- `bool SwitchGraphHub(const std::string& node_id);`、`void SyncGraphNow();`
- 握手线程里处理 `GRAPH`/`GRAPH-REQ`；握手成功（ACK 发出/收到、REQ 接受）时 `UpsertNode` + 广播；
  3 秒定时广播；收到 GRAPH 更新 lastSeen。
- 混音线程：`graph_is_hub()` 为 false 时**输出静音**（不混流）。
- 自节点：`StartOptions` 增加 `uint16_t http_port = 0;`；kind 固定 `"pc"`；name = display_name；id 用 `local_ip()` + 音频口。
- **测试钩子**：`local_ip()` 优先返回环境变量 `AUDIOHUB_LOCAL_IP`（非空时），仅用于本机双进程冒烟测试
  （`tools/graph-smoke.ps1` 用它让两个 hub 都宣告 127.0.0.1）；生产环境不设置，行为不变。
- 新节点/图谱变化通过现有 event callback 打日志即可（`GRAPH ...` 前缀便于调试）。

HTTP（`apps/hub_main.cpp` 的路由 + `src/http_api.cpp` 的 JSON 助手）：
- `/api/state` 加 graph（§7.1）；`/api/graph/switch`；`/api/graph/sync`；`/api/session/clear` 重置图谱。
- 只允许用现有朴素 JSON 解析助手（`JsonGetString` / `JsonFindNumber` / `JsonFindBool`），需要数组解析时自己写简单循环，别引入依赖。

单元测试：`tests/test_graph.cpp`（注册进 `build.sh` 与 `CMakeLists.txt`）：
编码/解码往返、非法帧、节点并集、同 id 冲突、LWW（高版本胜 / 同版本按 origin 字典序 / 同版本同 origin 不变）、
`SwitchHub` 版本递增与未知节点拒绝、`StreamIdFor` rank、名字清洗与 UTF-8 截断（含中文）。

### 10.2 Electron

`ui/main.js`：`getState` 透传 graph；`reconcileGraph`（§6.1）；IPC `hub:switch`；去掉旧的 setRole 触发路径。
`ui/preload.js`：暴露 `window.hub.switchHub(nodeId)`、`onState` 里带 graph。
`ui/renderer.js`：§9.2（全网图谱、右键节点菜单、删除角色跟随事实、hubSelect 移除）。
`ui/index.html` / `ui/styles.css`：新增节点右键菜单 `#ctxMenu` DOM/样式，移除旧切换操作条。

### 10.3 Android

新增 `GraphStore.java`（静态单例，synchronized，等价于 10.1 的算法；编解码用 UTF-8）：
`setSelf(ip,name)` / `snapshot()` / `mergeFromFrame(String)` / `encodeFrame()` / `switchHub(id)` /
`isHub()` / `hubNode()` / `hasNodeIp(ip)` / `streamIdForSelf()` / `nodes()` / `markSeen(ip)`。
`InviteListener.java`：解析 `GRAPH`/`GRAPH-REQ`；成员自动 ACK（§5）；提供 `broadcastGraph()`、`sendGraphTo(ip,port)`、
`requestSync()`；3 秒定时广播（可放在 listener 线程里，用 deadline 计数，别开新线程）。
`MainActivity.java`：poller reconcile（§6.2）、确认框、toast、控制屏条件。
`HubGraphView.java`：全网渲染（§9.3）。
`CaptureService.java` / `ReceiverService.java`：只做最小改动（streamId 可配、目标可改写、停止/重启幂等）。

---

## 11. 构建与验证命令

```powershell
# PC C++（测试 + 两个 exe + 跑单测）
powershell -NoProfile -File tools/build.ps1

# 只要单测 / 只要 exe
powershell -NoProfile -File tools/build.ps1 -Target tests
powershell -NoProfile -File tools/build.ps1 -Target apps

# Android APK（脚本由 Lead 提供：tools/build-apk.ps1）
powershell -NoProfile -File tools/build-apk.ps1
```

验收（本轮必须满足）：
1. C++ 单测全过（含 test_graph）。
2. PC：`audiohub-hub.exe --port 47000 --http-port 4780 --name X` 起两个进程（47000 / 47100），
   往 A 的 47002 发一帧包含 B 节点的 GRAPH，A 的 `/api/state` 能看到 B；`/api/graph/switch` 设 hub=B 后，
   B 的 47002 能收到 A 广播的 GRAPH（version 递增、hubId=B）。
3. PC + Android：手机入网后两端图谱节点集合一致；在任一端点节点切换，另一端 3 秒内跟随，
   音源自动改投新 hub（本轮无真机则至少保证编译/打包通过，并在 README 写手测清单）。
4. Electron：`node --check ui/main.js ui/renderer.js ui/preload.js` 通过；启动不报 IPC 错。
```

---

## 12. 明确不做（本轮）

- 节点移除 / 离开网络（只增不删）。
- 跨重启持久化（图谱存内存，进程退出即消失）。
- 鉴权/配对（沿用局域网明文信任模型，文档注明）。
- 多 hub 分区/脑裂恢复策略（LWW 收敛即可）。
- Android 端 HTTP 控制口（`httpPort=0`，PC↔Android 全走 UDP 握手口）。

---

## 13. 房间摆位同步（POS 帧）v1.4

> 目标：桌面端图谱里拖动的落点要同步给全网（多台电脑 + 手机），手机端用同一份坐标画俯视平面图。
> 摆位是 **UI 布局数据**，不参与音频路由；混音用的空间坐标仍由"汇聚端本机的 UI"另行推送
> （`/api/source/spatial`），两者一份来源、各走各的通道。

### 13.1 线格式

文本 UDP 帧，与 GRAPH 同一个端口（握手口 `audioPort+2`）、同一条接收分支：

```
POS|<version>|<origin>|<items>\n
```

- `version`：Lamport 版本号（uint64，只增）。
- `origin`：最近一次改动的发起者 node id（同版本时按字典序裁决）。
- `items`：`;` 分隔的摆位项，每项 `nodeId,xCm,zCm`；`nodeId` 是图谱里的 `"ip:audioPort"`；
  坐标是**厘米**的十进制整数，范围 `[-32000, 32000]`（±320 m，与内核 `kSpatialCoordLimit` 一致；
  v1.6 起"距离就是音量"，拖多远就衰减多少，不再有 20 m 上限）。
- 空 `items`（帧形如 `POS|3|ip:47000|`）表示**整表清空**；`POS|0||` 是合法的空表。
- 帧长上限 1000 字节（与 GRAPH 一致）；编码时按 nodeId 升序、超长从尾部裁项。
- **清除单个设备 = 下一帧不带它**（没有 -32768 哨兵；坐标严格校验 ±32000，越界/加号/坏 u64 整帧丢弃）。
- `POS-REQ\n`：请求对方立即回一帧 POS。

### 13.2 合并语义

- 摆位是**整表 LWW**（不是逐节点 CRDT）：`version` 大者胜；同版本按 `origin` 字典序大者胜；完全相等则不变。
- 收到比自己新的 POS 帧 → 整表替换本机摆位、版本跟上、并把新帧广播一次（中继，让全网收敛）。
- 本机整表为空（version 0 且 items 空）时**不主动广播空帧**，避免覆盖别人的摆位；
  只在对端发 POS-REQ 时回"当前表"（空表也回，表示我这边没摆过）。
- 本地改动（桌面拖一次 / 右键复位）= version = max(本机 seen, 表版本) + 1、origin = 本机 id，
  然后连发 2 次（60 ms 间隔）+ 3 秒周期广播，单节点 1 秒限 5 帧、超限进补发队列。

### 13.3 与 GRAPH 的关系

- **完全不动 GRAPH 帧**：GRAPH 解码器严格 5 字段/节点（旧版本一改就整帧丢弃），所以摆位单独成帧。
- 新帧类型对旧端天然兼容：旧端的握手线程只认 `GRAPH|` / `GRAPH-REQ`，`POS|` 因不匹配被静默忽略
  （PC 侧也一样），不会影响图谱同步与音频。
- 新节点入网（握手成功 / GRAPH-REQ / 首次收到其 GRAPH）时：单播一次当前 POS，并排队一次 POS-REQ。
- 全部走现有的握手线程与事件循环，**不新增线程、不新增端口**。

### 13.4 PC HTTP

`GET /api/state` 的 `graph` 增加：

```jsonc
"graph": {
  "version": 7,
  "placementVersion": 3,       // 摆位表版本；0 = 全网还没人摆过
  "nodes": [
    { "id": "192.168.1.31:47000", "posSet": true, "x": 1.2, "z": -0.8, "...": "其余字段不变" }
  ]
}
```

- `posSet=false` 时不出 `x`/`z`（各端用自己的自动槽位）。
- `POST /api/graph/placement` 请求 `{"nodeId":"...","x":1.2,"z":-0.8}`（米，±320，四舍五入到厘米）：
  写本机摆位表、版本 +1、广播；返回 `{"ok":true,"changed":true,"placementVersion":N}`。
- 复位清项：`{"nodeId":"...","clear":true}`；节点不在图谱里返回 `{"ok":false,"error":"unknown node"}`。
- 旧内核没有该端点时，PC UI 的 `window.hub.setNodePlacement` 静默失败（不打断拖动交互）。

### 13.5 两端 UI 约定

- **PC（Electron）**：节点没摆过 → 用 32 个预计算散点槽位（按 id 定死，各电脑一致）；
  左键拖完把落点 POST 进内核（位置随即由图谱驱动，不再只存本机 localStorage）；右键菜单
  「位置复位」= 清项、回自动槽位。别的电脑拖动 → 本机 200 ms 轮询到新坐标后 250 ms 平滑跟过去。
- **Android**：俯视 2D 正交平面图（只读）：听者在原点、屏幕上方 = 正前方（-Z），1 m = 同一缩放；
  读 POS 表画点：hub 大圆、本机角标、离线虚化；**不提供拖动**（摆位只在桌面端拖，通过 POS 帧同步过来）。
- 坐标系：1 unit = 1 m，听者固定在原点、yaw=0 朝 -Z，+X = 右耳——手机俯视图与 PC 3D 视图同源。