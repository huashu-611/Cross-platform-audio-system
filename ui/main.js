// 主进程：负责起 C++ 音频核心、轮询它的 HTTP 接口、把状态推给渲染进程。
//
// 进程边界（electron-assistant 的安全底线）：
//   主进程（本文件，Node）  ──IPC──▶  preload（窄接口）  ──▶  渲染进程（纯网页）
//   渲染进程不碰 Node，只通过 window.hub.* 这几个函数通信。
const electronModule = require('electron')

// 这个坑很隐蔽：某些 Electron 宿主环境（包括本机的 WorkBuddy）会给子进程设
// ELECTRON_RUN_AS_NODE=1，Electron 就退化成纯 Node，内置 API 不再注入，
// require('electron') 只能落到 npm 包那个"返回二进制路径"的分支。
// 不检查的话，报错是 "Cannot read properties of undefined (reading 'whenReady')"，
// 完全指不到真正原因。
if (typeof electronModule === 'string') {
  console.error('[AudioHub] require("electron") 返回了路径而不是 API 对象。')
  console.error('[AudioHub] 原因基本可以确定：环境里设置了 ELECTRON_RUN_AS_NODE=1。')
  console.error('[AudioHub] 请先执行  unset ELECTRON_RUN_AS_NODE  再 npm start')
  process.exit(1)
}

const { app, BrowserWindow, ipcMain } = electronModule
const path = require('path')
const { spawn } = require('child_process')
const http = require('http')

const DATA_PORT = 47000   // 音频 UDP 口，手机上填的就是它
const HTTP_PORT = 4780    // 本地控制口，只绑 127.0.0.1
const LATENCY_MS = 60

let hubProcess = null
let mainWindow = null
let pollTimer = null

// ------------------------------------------------------------------ 启动 C++ 核心

function hubExePath () {
  if (app.isPackaged) {
    // asarUnpack 过的外部可执行文件：asar 是只读归档，
    // 要 spawn 的二进制必须解包到真实路径，否则加载失败。
    return path.join(process.resourcesPath, 'app.asar.unpacked', 'bin', 'audiohub-hub.exe')
  }
  // 开发态：直接用仓库 build 目录里的产物
  return path.join(__dirname, '..', 'build', 'audiohub-hub.exe')
}

function startHub () {
  const exe = hubExePath()
  hubProcess = spawn(exe, [
    '--port', String(DATA_PORT),
    '--http-port', String(HTTP_PORT),
    '--latency', String(LATENCY_MS)
  ], { stdio: 'ignore' })

  hubProcess.on('exit', (code) => {
    hubProcess = null
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hub:push', { error: '音频核心已退出（code=' + code + '）' })
    }
  })
  hubProcess.on('error', (err) => {
    if (mainWindow && !mainWindow.isDestroyed()) {
      mainWindow.webContents.send('hub:push', { error: '无法启动音频核心: ' + err.message })
    }
  })
}

function stopHub () {
  if (hubProcess) {
    hubProcess.kill()
    hubProcess = null
  }
}

// ------------------------------------------------------------------ 本地 HTTP 客户端

function httpRequest (method, urlPath, body) {
  return new Promise((resolve) => {
    const data = body ? JSON.stringify(body) : null
    const req = http.request({
      host: '127.0.0.1',
      port: HTTP_PORT,
      path: urlPath,
      method: method,
      headers: data
        ? { 'Content-Type': 'application/json', 'Content-Length': Buffer.byteLength(data) }
        : {}
    }, (res) => {
      let buf = ''
      res.on('data', (c) => { buf += c })
      res.on('end', () => {
        try { resolve(JSON.parse(buf)) } catch (e) { resolve(null) }
      })
    })
    // 核心还没起来时连接会被拒，这里静默失败，等下一次轮询
    req.on('error', () => resolve(null))
    if (data) req.write(data)
    req.end()
  })
}

async function fetchState () {
  const state = await httpRequest('GET', '/api/state')
  if (state && mainWindow && !mainWindow.isDestroyed()) {
    mainWindow.webContents.send('hub:push', state)
  }
}

// ------------------------------------------------------------------ 窗口

function createWindow () {
  mainWindow = new BrowserWindow({
    width: 1080,
    height: 760,
    minWidth: 720,
    minHeight: 560,
    backgroundColor: '#0b1015',
    title: 'AudioHub 汇聚端',
    // 安全底线：渲染进程是"没牙的老虎"，所有能力都从 preload 窄接口出
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      preload: path.join(__dirname, 'preload.js')
    }
  })

  mainWindow.loadFile('index.html')

  // 开发态自动开 DevTools，排障省一步
  if (!app.isPackaged) {
    mainWindow.webContents.openDevTools({ mode: 'detach' })
  }
}

// 界面是纯 CSS，用不到 GPU 合成；关掉硬件加速还能避免
// 虚拟化/无 GPU 环境里 GPU 进程崩溃导致整个应用起不来。
app.disableHardwareAcceleration()

app.whenReady().then(() => {
  createWindow()
  startHub()
  // 200 ms 轮询：电平条要顺滑，但也没必要更频
  pollTimer = setInterval(fetchState, 200)
  fetchState()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})

app.on('before-quit', () => {
  if (pollTimer) clearInterval(pollTimer)
  stopHub()
})

// ------------------------------------------------------------------ IPC

// 只注册这几个具体动作，不暴露 ipcRenderer 本身
ipcMain.handle('hub:state', async () => httpRequest('GET', '/api/state'))

ipcMain.handle('hub:setSource', async (_e, arg) => {
  const { id, patch } = arg || {}
  if (!Number.isInteger(id)) return { ok: false }
  // 参数收窄：只认这两个字段，且取值范围固定，防止页面塞奇怪值进来
  const body = { id }
  if (patch && typeof patch.gain === 'number') {
    body.gain = Math.min(Math.max(patch.gain, 0), 4)
  }
  if (patch && typeof patch.muted === 'boolean') {
    body.muted = patch.muted
  }
  return httpRequest('POST', '/api/source', body)
})

ipcMain.handle('hub:setMaster', async (_e, arg) => {
  const gain = arg && typeof arg.gain === 'number' ? Math.min(Math.max(arg.gain, 0), 4) : null
  if (gain === null) return { ok: false }
  return httpRequest('POST', '/api/master', { gain })
})

ipcMain.handle('hub:setLatency', async (_e, arg) => {
  const ms = arg && typeof arg.ms === 'number' ? Math.round(arg.ms) : null
  if (ms === null || ms < 20 || ms > 250) return { ok: false }
  return httpRequest('POST', '/api/latency', { ms })
})
