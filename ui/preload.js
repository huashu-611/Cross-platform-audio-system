// preload：渲染进程与主进程之间唯一被允许的桥。
//
// 铁律：只暴露"收窄的、带校验的具体动作"，绝不暴露 ipcRenderer / require / electron 本身。
// 一旦页面被 XSS 注入，暴露越窄，攻击面越小。
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('hub', {
  // 主动拉一次状态（含 graph 字段）
  getState: () => ipcRenderer.invoke('hub:state'),

  // 订阅主进程推送（200 ms 一次的状态快照；state.graph 是全网图谱快照）
  onState: (callback) => {
    const handler = (_event, state) => callback(state)
    ipcRenderer.on('hub:push', handler)
    // 返回取消订阅函数，避免重复注册叠加监听器
    return () => ipcRenderer.removeListener('hub:push', handler)
  },

  // 切换汇聚端（图谱上的节点 action）。nodeId = "ip:audioPort"
  // 主进程 POST /api/graph/switch，返回 { ok, graph }（失败时带 error）
  switchHub: (nodeId) => ipcRenderer.invoke('hub:switch', { nodeId }),

  // 调某一路的音量 / 静音
  setSource: (id, patch) => ipcRenderer.invoke('hub:setSource', { id, patch }),

  // 总音量
  setMaster: (gain) => ipcRenderer.invoke('hub:setMaster', { gain }),

  // 目标延迟
  setLatency: (ms) => ipcRenderer.invoke('hub:setLatency', { ms }),

  // 主动扫描一轮，返回本次扫到的设备
  scan: () => ipcRenderer.invoke('hub:scan'),

  // 旧接口：设定汇聚端（self=true 本机；false 时给 ip/audioPort）。
  // 新界面不再调用；汇聚端改由 switchHub 按图谱切换。保留给旧脚本/旧页面。
  setRole: (arg) => ipcRenderer.invoke('hub:setRole', arg),

  // 清空会话关系表（用户主动断开时）
  clearSession: () => ipcRenderer.invoke('hub:clearSession'),

  // 对端发来的邀请
  acceptInvite: (ip) => ipcRenderer.invoke('hub:accept-invite', { ip }),
  rejectInvite: (ip) => ipcRenderer.invoke('hub:reject-invite', { ip }),

  // 连接握手
  invitePeer: (peer) => ipcRenderer.invoke('hub:invite', peer),
  accept: (streamId) => ipcRenderer.invoke('hub:accept', { streamId }),
  reject: (streamId) => ipcRenderer.invoke('hub:reject', { streamId }),
  disconnect: (streamId) => ipcRenderer.invoke('hub:disconnect', { streamId }),
  setAutoAccept: (on) => ipcRenderer.invoke('hub:setAutoAccept', { on }),

  // 3D 场景 → 空间音频：听者位姿（位置恒为原点，yaw/pitch 由相机推导）。
  // 渲染进程按 20 Hz 上限节流；旧内核没有 /api/listener 时主进程回 { ok:false, unsupported:true }，
  // 渲染进程据此静默停发，不影响其他交互。
  setListener: (pose) => ipcRenderer.invoke('hub:setListener', pose),

  // 单个节点的世界位置（nodeId = 图谱节点 id "ip:audioPort"；enabled=false = 旁路空间化）。
  // y 固定为 0：所有节点都在听者所在的水平面上，与 2D 图谱"同一平面"语义对应。
  setSourceSpatial: (nodeId, x, z, enabled) =>
    ipcRenderer.invoke('hub:setSourceSpatial', { nodeId, x, y: 0, z, enabled })
})
