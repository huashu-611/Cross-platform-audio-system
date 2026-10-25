// preload：渲染进程与主进程之间唯一被允许的桥。
//
// 铁律：只暴露"收窄的、带校验的具体动作"，绝不暴露 ipcRenderer / require / electron 本身。
// 一旦页面被 XSS 注入，暴露越窄，攻击面越小。
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('hub', {
  // 主动拉一次状态
  getState: () => ipcRenderer.invoke('hub:state'),

  // 订阅主进程推送（200 ms 一次的状态快照）
  onState: (callback) => {
    const handler = (_event, state) => callback(state)
    ipcRenderer.on('hub:push', handler)
    // 返回取消订阅函数，避免重复注册叠加监听器
    return () => ipcRenderer.removeListener('hub:push', handler)
  },

  // 调某一路的音量 / 静音
  setSource: (id, patch) => ipcRenderer.invoke('hub:setSource', { id, patch }),

  // 总音量
  setMaster: (gain) => ipcRenderer.invoke('hub:setMaster', { gain }),

  // 目标延迟
  setLatency: (ms) => ipcRenderer.invoke('hub:setLatency', { ms }),

  // 连接握手
  accept: (streamId) => ipcRenderer.invoke('hub:accept', { streamId }),
  reject: (streamId) => ipcRenderer.invoke('hub:reject', { streamId }),
  disconnect: (streamId) => ipcRenderer.invoke('hub:disconnect', { streamId }),
  setAutoAccept: (on) => ipcRenderer.invoke('hub:setAutoAccept', { on })
})
