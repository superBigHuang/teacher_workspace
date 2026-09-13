<template>
  <div class="radish-stage" @mousemove="onMouseMove">
    <!-- 音量折线：透明底单条折线，仅 PULLING 显示，pointer-events:none 不遮挡背景视频 -->
    <canvas v-if="state === 'PULLING'" ref="lineEl" class="volume-line"></canvas>

    <!-- 主舞台：循环段单机 ping-pong（正放⇄倒放）往返，由朗读声音门控；intro/pullout/success/ending 用 solo 单片 -->
    <div class="video-wrap">
      <!-- 循环用力拔：正放到尾→倒放回头→再正放，首尾同帧无接缝；恒速 1x。muted（倒放音轨无法正常出声，
           且不盖朗读声）；loop=false，往返由 JS 管理；仅 PULLING 显示，不读时门控暂停定格 -->
      <video
        ref="loopEl"
        class="stage-video"
        playsinline
        muted
        preload="auto"
        :loop="false"
        :src="SRC.loop"
        :style="{ opacity: state === 'PULLING' ? 1 : 0 }"
      ></video>
      <!-- 单片：开头/拔出/感谢成片；仅 INTRO/SUCCESS/ENDING 可见（盖住循环机） -->
      <video
        ref="soloEl"
        class="stage-video solo-video"
        playsinline
        preload="auto"
        @ended="onSoloEnded"
        :style="{ opacity: (state === 'INTRO' || state === 'SUCCESS' || state === 'ENDING') ? 1 : 0 }"
      ></video>

      <!-- 左下角萝卜计数 -->
      <div v-if="state !== 'IDLE'" class="carrot-counter">
        <transition-group name="pop">
          <span v-for="i in carrots" :key="i" class="carrot">🥕</span>
        </transition-group>
        <span v-if="carrots > 0" class="cc-num">×{{ carrots }}</span>
      </div>

      <!-- 顶部右侧：倒计时 + 结束按钮 -->
      <div v-if="state === 'PULLING' || state === 'SUCCESS'" class="hud-top">
        <span v-if="countdownLeft > 0" class="hud-timer">⏱ {{ formatTime(countdownLeft) }}</span>
        <span v-if="combo > 0" class="hud-combo">🔥 连击 ×{{ comboSec }}s</span>
        <button class="hud-end" @click="endSession">结束早读</button>
      </div>

      <!-- 当前萝卜进度（只增不降；不设萝卜总数上限，一直拔到老师结束或倒计时到） -->
      <div v-if="state === 'PULLING'" class="progress-wrap">
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: progress.toFixed(1)+'%' }"></div>
        </div>
        <div class="progress-label">当前萝卜 {{ progress.toFixed(0) }}% · 已拔 {{ carrots }} 根</div>
      </div>

      <!-- 第N根萝卜大字特效 -->
      <transition name="bounce">
        <div v-if="showBanner" class="banner">
          <div class="banner-text">{{ bannerText }}</div>
        </div>
      </transition>

      <!-- 进度里程碑鼓励气泡：30/50/80% 各弹一次，轻量提示不抢拔出高潮 -->
      <transition name="tip">
        <div v-if="showTip && state === 'PULLING'" class="milestone-tip">{{ tipText }}</div>
      </transition>

      <!-- 结尾战报卡 -->
      <transition name="fade">
        <div v-if="state === 'ENDING' && showReport" class="report-card">
          <div class="rc-title">🌅 今日早读战报</div>
          <div class="rc-count">共拔出 🥕 × {{ carrots }} 根！</div>
          <div v-if="peakTime" class="rc-peak">最响时刻：{{ peakTime }}（{{ (peakVolume*100).toFixed(0) }}%）</div>
          <div class="rc-comment">{{ finalComment }}</div>
          <div v-if="isNewRecord" class="rc-record">🏆 新纪录！超越历史最佳 {{ record }} 根</div>
          <div class="rc-tomorrow">明天继续加油 💪</div>
          <button class="rc-btn" @click="reset">再来一次</button>
        </div>
      </transition>

      <!-- IDLE 封面 -->
      <transition name="fade">
        <div v-if="state === 'IDLE'" class="idle-cover">
          <div class="idle-emoji">🐰🥕</div>
          <div class="idle-title">拔萝卜早读</div>
          <div class="idle-sub">全班一起大声读，兔兔才能拔出萝卜！</div>
          <button class="idle-btn" @click="start">🎤 点击开始</button>
          <button class="idle-back" @click="goBack">← 返回工作台</button>
          <div v-if="record > 0" class="idle-record">📈 历史最佳：{{ record }} 根</div>
          <div v-if="micError" class="idle-err">{{ micError }}</div>
        </div>
      </transition>

      <!-- READY 测底噪 + 核心视频就绪门控 -->
      <transition name="fade">
        <div v-if="state === 'READY'" class="ready-cover">
          <div class="ready-emoji">🤫</div>
          <div class="ready-text">嘘…兔兔在听教室的安静声</div>
          <div class="ready-bar"><div class="ready-fill" :style="{ width: readyPct+'%' }"></div></div>
          <!-- 核心视频就绪状态：intro/loop/pullout 全部 canplaythrough 才进入 -->
          <div class="ready-load">
            <span class="rl-title">🐰 兔兔正在蹦过来</span>
            <span v-for="(s, i) in CORE_SRC" :key="s" class="rl-dot" :class="{ ok: mediaReady[s] }">
              {{ mediaReady[s] ? '✓' : '…' }} {{ CORE_LABEL[i] }}
            </span>
            <span class="rl-count">{{ coreReadyCount }}/3</span>
          </div>
          <div v-if="readySlow" class="ready-load-tip">第一次加载视频有点大，再等兔兔一下…</div>
        </div>
      </transition>
    </div>

    <!-- 设置齿轮（鼠标移入右上角显示） -->
    <div class="gear" :class="{ show: showGear }">
      <button class="gear-btn" @click="settingsOpen = !settingsOpen">⚙</button>
      <transition name="fade">
        <div v-if="settingsOpen" class="gear-panel">
          <div class="gp-row">
            <label>音量阈值 {{ cfg.threshold.toFixed(2) }}</label>
            <input type="range" min="0.05" max="0.4" step="0.01" v-model.number="cfg.threshold">
          </div>
          <div class="gp-row">
            <label>每根目标秒数 {{ cfg.targetSec }}s</label>
            <input type="range" min="5" max="60" step="1" v-model.number="cfg.targetSec">
          </div>
          <div class="gp-row">
            <label>倒计时分钟 {{ cfg.countdownMin }}</label>
            <input type="range" min="0" max="60" step="1" v-model.number="cfg.countdownMin">
          </div>
          <button class="gp-test" @click="testMic">🎤 测试麦克风</button>
          <div v-if="micTestResult" class="gp-result">{{ micTestResult }}</div>
        </div>
      </transition>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import confetti from 'canvas-confetti'

const router = useRouter()
/** 退出大屏回工作台（菜单进入的出口；未登录直访时路由守卫会导向登录页） */
function goBack() { router.push('/dashboard') }

// ============ 状态机：IDLE → READY → INTRO → PULLING ⇄ SUCCESS → … → ENDING → IDLE ============
const state = ref('IDLE')
// 循环段单机 loopEl（ping-pong 正倒放，无暂停）；soloEl 播 intro/pullout/success/ending
const loopEl = ref(null)
const soloEl = ref(null)
const lineEl = ref(null)     // 顶部音量折线 canvas

// 素材路径（public/radish/ 下）
const SRC = {
  intro: '/radish/intro.mp4',     // 开头（拔萝卜-1，1.19s，每次开始播一次）
  loop: '/radish/loop.mp4',       // 循环用力拔（拔萝卜-2，1.72s，原生 loop 正放重播）
  pullout: '/radish/pullout.mp4', // 拔出来动作（拔萝卜-3，3.41s）
  success: '/radish/success.mp4', // 拔出庆祝成片（8.83s）
  ending: '/radish/ending.mp4'    // 感谢成片（7.2s，结束早读）
}
// 拔出萝卜时连播：pullout（拔出来）→ success（庆祝成片），全播完回循环
// ping-pong 方向：+1 正放（原生 play）/ -1 倒放（rAF 逐帧回退 currentTime）；HTML5 video 不支持负 playbackRate
let pingDir = 1
// 音量折线 + 陡降检测
const LINE_WIN = 180        // 折线显示窗口（帧，约 3s @60fps）
const DROP_WIN = 18         // 陡降回看帧数（约 0.3s）
const DROP_DELTA = 0.12     // 0.3s 内 fastVol 下降超过此值 → 视为"快速降低"，正放立刻转倒放
const FLIP_CD = 400         // 一次倒放结束转正放后，多少 ms 内不再判陡降（等历史窗口刷新，防开头抖动）
let fastVol = 0             // 快速对称平滑音量（折线显示 + 陡降检测，升降都跟手）
const volHist = []          // 最近 LINE_WIN 个 fastVol
let lastFlip = 0            // 最近一次"倒放到头转正放"的时间戳（陡降冷却）

// ============ 音量采集（Web Audio API） ============
let audioCtx = null, analyser = null, micStream = null
let dataBuf = null
const volume = ref(0)          // 平滑后的实时音量 0~1
const micError = ref('')
const noiseFloor = ref(0.05)   // 环境底噪基线（READY 阶段测 2 秒）

// 配置（设置齿轮可调）
const cfg = reactive({
  threshold: 0.15,     // 低于此音量视为没读
  targetSec: 18,       // 每根萝卜目标秒数（全班正常朗读下）
  countdownMin: 40     // 倒计时分钟数（0=不限时），默认一节早读 40 分钟
})


// ============ 进度与计数 ============
const progress = ref(0)        // 当前萝卜进度 0~100
const carrots = ref(0)         // 已拔出根数
const combo = ref(0)           // 连击计数（持续超阈值的帧数）
const comboSec = computed(() => Math.floor(combo.value / 60))
const peakVolume = ref(0)
const peakTime = ref('')
const record = ref(0)          // localStorage 历史最佳
const isNewRecord = ref(false)

// 倒计时
const countdownLeft = ref(0)   // 剩余秒数
let countdownTimer = null

// 特效
const showBanner = ref(false)
const bannerText = ref('')
const showReport = ref(false)

// READY 测底噪
const readyPct = ref(0)

// 设置面板
const showGear = ref(false)
const settingsOpen = ref(false)
let gearTimer = null

// 每根难度递增系数（拔得越多越难）
const difficulty = computed(() => 1 + carrots.value * 0.1)

// ============ 计算属性 ============
const finalComment = computed(() => {
  const n = carrots.value
  if (n <= 2) return '兔兔还饿着呢，明天加油！'
  if (n <= 5) return '不错不错，兔兔吃饱啦！'
  if (n <= 8) return '声音洪亮的一天！'
  return '全校最响亮的班级！🏆'
})

// ============ 麦克风 ============
async function initMic() {
  micError.value = ''
  // 清理上一次采集（"测试麦克风"后再点开始会重复调用，避免麦克风/AudioContext 泄漏堆积）
  if (micStream) { micStream.getTracks().forEach(t => t.stop()); micStream = null }
  if (audioCtx) { try { audioCtx.close() } catch (e) { /* 已关闭忽略 */ } audioCtx = null }
  try {
    micStream = await navigator.mediaDevices.getUserMedia({ audio: true })
    audioCtx = new (window.AudioContext || window.webkitAudioContext)()
    const src = audioCtx.createMediaStreamSource(micStream)
    analyser = audioCtx.createAnalyser()
    analyser.fftSize = 2048
    src.connect(analyser)
    dataBuf = new Uint8Array(analyser.fftSize)
    return true
  } catch (e) {
    micError.value = '需要麦克风权限才能玩，请点地址栏左侧小锁允许'
    return false
  }
}

function readVolume() {
  if (!analyser) return 0
  analyser.getByteTimeDomainData(dataBuf)
  let sum = 0
  for (let i = 0; i < dataBuf.length; i++) {
    const v = (dataBuf[i] - 128) / 128
    sum += v * v
  }
  const rms = Math.sqrt(sum / dataBuf.length)
  return Math.min(1, rms * 3) // 增益放大到 0~1
}

// ============ READY 测底噪 + 核心视频就绪门控（并行） ============
const coreReadyCount = computed(() => CORE_SRC.reduce((n, src) => n + (mediaReady[src] ? 1 : 0), 0))
const readySlow = computed(() => noiseMeasured.value && coreReadyCount.value < CORE_SRC.length)

async function start() {
  const ok = await initMic()
  if (!ok) return
  state.value = 'READY'
  readyPct.value = 0
  noiseMeasured.value = false
  ensurePreload([SRC.intro, SRC.pullout])   // 幂等：封面 onMounted 已建，兜底

  const samples = []
  const t0 = performance.now()
  // 任务一：2s 环境底噪测量（90 分位，排除突发响动）
  const measureNoise = new Promise(resolve => {
    const tick = () => {
      const raw = readVolume()
      samples.push(raw)
      readyPct.value = Math.min(100, ((performance.now() - t0) / 2000) * 100)
      if (performance.now() - t0 < 2000) {
        requestAnimationFrame(tick)
      } else {
        samples.sort((a, b) => a - b)
        noiseFloor.value = samples[Math.floor(samples.length * 0.9)] || 0.05
        noiseMeasured.value = true
        resolve()
      }
    }
    tick()
  })

  // 任务二：3 个核心视频就绪（或 20s 超时兜底）；两者都完成才进 INTRO
  await Promise.all([measureNoise, waitCoreReady()])

  // 核心就绪后带宽全部让给后续视频（游戏过程时间充裕：success 首次拔出约 24s 后、ending 结束早读才用）
  ensurePreload(LATER_SRC)
  startIntro()
}

// ============ INTRO 开头片 → PULLING 叠化循环 ============
let rafId = null
let lastT = 0
let successStage = '' // SUCCESS 连播阶段：'pullout'（拔出来）→ 'celebrate'（庆祝成片）

// 进度里程碑鼓励：跨过 30/50/80% 各弹一次气泡（每根萝卜 startPulling 重置 step）
const MILESTONES = [
  { at: 30, text: '加把劲！兔兔正在用力！💪' },
  { at: 50, text: '萝卜松动啦！再大声点！📢' },
  { at: 80, text: '马上拔出来了！冲啊！🔥' }
]
let milestoneStep = 0
const showTip = ref(false)
const tipText = ref('')
let tipTimer = null

function showMilestone(text) {
  tipText.value = text
  showTip.value = true
  clearTimeout(tipTimer)
  tipTimer = setTimeout(() => { showTip.value = false }, 1300)
}

/** 测底噪后播开头片（一次），播完 onSoloEnded 进入叠化循环 */
function startIntro() {
  state.value = 'INTRO'
  playSolo(SRC.intro)
}

/** 播放单片（intro/success/ending）；播放前先停掉叠化循环双机 */
function playSolo(src) {
  stopLoop()
  const s = soloEl.value
  if (s) {
    s.src = src
    s.currentTime = 0
    s.playbackRate = 1.0
    s.play().catch(() => {})
  }
}

/** 启动循环：单机 ping-pong（正放⇄倒放）恒速 1x，持续播放不暂停；复位折线/方向/冷却 */
function startLoop() {
  pingDir = 1
  fastVol = 0
  volHist.length = 0
  lastFlip = 0
  const v = loopEl.value
  if (v) {
    v.loop = false
    v.muted = true
    v.playbackRate = 1
    v.currentTime = 0
    v.play().catch(() => {})
  }
}

/** 停止循环：暂停并回到开头（切 solo 单片或重置/结束时调用） */
function stopLoop() {
  pingDir = 1
  const v = loopEl.value
  if (v) {
    v.pause()
    v.currentTime = 0
  }
}

function startPulling() {
  state.value = 'PULLING'
  progress.value = 0
  combo.value = 0
  milestoneStep = 0
  if (soloEl.value) soloEl.value.pause()
  startLoop()
  if (countdownLeft.value <= 0 && cfg.countdownMin > 0) {
    countdownLeft.value = cfg.countdownMin * 60
    startCountdown()
  }
  lastT = performance.now()
  rafId = requestAnimationFrame(pullLoop)
}

function pullLoop(now) {
  if (state.value !== 'PULLING') return
  const dt = Math.min(0.1, (now - lastT) / 1000)
  lastT = now

  // 进度用音量：非对称平滑（上升快、下降缓），进度积分稳定
  const raw = readVolume()
  const effective = Math.max(0, raw - noiseFloor.value)
  if (effective >= volume.value) {
    volume.value = volume.value * 0.5 + effective * 0.5     // attack：约 2 帧即跟上
  } else {
    volume.value = volume.value * 0.94 + effective * 0.06   // decay：时间常数约 0.28s
  }
  // 折线/陡降用音量：快速对称平滑（升降都跟手），记录最近 LINE_WIN 帧
  fastVol = fastVol * 0.6 + effective * 0.4
  volHist.push(fastVol)
  if (volHist.length > LINE_WIN) volHist.shift()
  drawLine()

  // 连击：持续超阈值 5 秒 → 1.5 倍速
  if (volume.value >= cfg.threshold) {
    combo.value++
  } else {
    combo.value = 0
  }
  const speedMul = combo.value >= 300 ? 1.5 : 1.0

  // 进度积分：只增不降（静音时 gain=0 进度停住，不回退）
  const K = 100 / (0.25 * cfg.targetSec)
  const gain = Math.max(0, volume.value - cfg.threshold) * K * speedMul / difficulty.value * dt
  progress.value = Math.min(100, progress.value + gain)

  // 里程碑鼓励：首次跨过 30/50/80% 各弹一次（只在递增时触发，静音停涨不会重复）
  if (milestoneStep < MILESTONES.length && progress.value >= MILESTONES[milestoneStep].at) {
    showMilestone(MILESTONES[milestoneStep].text)
    milestoneStep++
  }

  // 峰值纪录
  if (volume.value > peakVolume.value) {
    peakVolume.value = volume.value
    const d = new Date()
    peakTime.value = `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
  }

  // 正放⇄倒放持续循环（不再因静音暂停）：+1 用原生 play 走到尾→切 -1；-1 rAF 逐帧倒放到头→切 +1。
  // 首尾同一帧，无接缝、无叠化重影；恒速 1x（HTML5 video 不支持负 playbackRate）。
  // 特殊：正放途中若音量"快速下降"（DROP_WIN≈0.3s 内降幅 > DROP_DELTA），立刻中断正放，
  // 从当前时间点直接倒放收回，一直倒到开头再正放。换向冷却 FLIP_CD 防止刚转正放时历史窗口未刷新导致抖动。
  const v = loopEl.value
  if (v && isFinite(v.duration)) {
    if (pingDir === 1 && now - lastFlip > FLIP_CD && volHist.length > DROP_WIN) {
      const drop = volHist[volHist.length - 1 - DROP_WIN] - fastVol
      if (drop > DROP_DELTA) pingDir = -1
    }
    if (pingDir === 1) {
      if (v.paused) v.play().catch(() => {})
      if (v.currentTime >= v.duration - 0.02) { v.pause(); pingDir = -1 }
    } else {
      if (!v.paused) v.pause()
      v.currentTime = Math.max(0, v.currentTime - dt)
      if (v.currentTime <= 0.02) { v.currentTime = 0; pingDir = 1; lastFlip = now }
    }
  }

  // 拔出判定
  if (progress.value >= 100) {
    onSuccess()
    return
  }

  rafId = requestAnimationFrame(pullLoop)
}

// ============ 音量折线（透明底单条折线，不挡背景） ============
/** 按 devicePixelRatio 同步 canvas 位图尺寸，返回 ctx 与 CSS 尺寸；每帧调用顺带处理窗口缩放 */
function lineCtx() {
  const cv = lineEl.value
  if (!cv) return null
  const dpr = window.devicePixelRatio || 1
  const w = cv.clientWidth, h = cv.clientHeight
  if (w === 0 || h === 0) return null
  if (cv.width !== Math.round(w * dpr) || cv.height !== Math.round(h * dpr)) {
    cv.width = Math.round(w * dpr)
    cv.height = Math.round(h * dpr)
  }
  const ctx = cv.getContext('2d')
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  return { ctx, w, h }
}

/** 画最近 LINE_WIN 帧的音量折线：最新点在最右、最亮，向左渐隐；透明底、无填充、无坐标（就一条线） */
function drawLine() {
  const lc = lineCtx()
  if (!lc) return
  const { ctx, w, h } = lc
  ctx.clearRect(0, 0, w, h)
  const n = volHist.length
  if (n < 2) return
  const pad = 12
  const usableH = h - pad * 2
  const stepX = w / (LINE_WIN - 1)
  const yFor = val => h - pad - Math.min(1, Math.max(0, val)) * usableH
  const grad = ctx.createLinearGradient(0, 0, w, 0)
  grad.addColorStop(0, 'rgba(255,225,77,0.15)')
  grad.addColorStop(0.7, 'rgba(255,225,77,0.75)')
  grad.addColorStop(1, '#ffe14d')
  ctx.beginPath()
  for (let i = 0; i < n; i++) {
    const x = w - (n - 1 - i) * stepX   // 最新点贴右
    const y = yFor(volHist[i])
    if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y)
  }
  ctx.strokeStyle = grad
  ctx.lineWidth = 3
  ctx.lineJoin = 'round'
  ctx.lineCap = 'round'
  ctx.shadowColor = 'rgba(255,225,77,.8)'
  ctx.shadowBlur = 8
  ctx.stroke()
}

// ============ SUCCESS 拔出萝卜 ============
function onSuccess() {
  cancelAnimationFrame(rafId)
  carrots.value++
  state.value = 'SUCCESS'

  // 大字播报
  bannerText.value = `第 ${carrots.value} 根萝卜！🥕`
  if (carrots.value % 5 === 0) bannerText.value = `🎉 ${carrots.value} 连拔！太厉害了！`
  showBanner.value = true
  setTimeout(() => { showBanner.value = false }, 2200)

  // 撒花（复用 EvolutionShow 三发模式）：每根普通，每满 5 根里程碑加大撒花（不设总数上限）
  fireConfetti(carrots.value % 5 === 0)

  // 拔出连播：先播"拔出来"动作片(pullout)，onSoloEnded 里自动接"庆祝成片"(success)，全播完回循环
  successStage = 'pullout'
  playSolo(SRC.pullout)
}

function fireConfetti(big = false) {
  const colors = ['#ffc107', '#ff9800', '#ff5722', '#fff']
  const z = 9999
  const count = big ? 200 : 60
  confetti({ particleCount: count, angle: 60, spread: 70, origin: { x: 0, y: 0.85 }, colors, zIndex: z })
  confetti({ particleCount: count, angle: 120, spread: 70, origin: { x: 1, y: 0.85 }, colors, zIndex: z })
  setTimeout(() => {
    confetti({ particleCount: big ? 180 : 90, spread: 110, origin: { y: 0.35 }, colors, zIndex: z })
  }, 260)
}

// ============ 单片视频事件 ============
// 循环段的叠化在 pullLoop(rAF) 里管理、无事件监听；solo 单片（intro/success/ending）播完在此分发
function onSoloEnded() {
  if (state.value === 'INTRO') {
    // 开头片播完 → 进入叠化循环
    startPulling()
  } else if (state.value === 'SUCCESS') {
    // 拔出连播：拔出来(pullout) → 庆祝成片(success) → 回循环拔下一根
    if (successStage === 'pullout') {
      successStage = 'celebrate'
      playSolo(SRC.success)
    } else {
      startPulling()
    }
  } else if (state.value === 'ENDING') {
    showReport.value = true
  }
}

// ============ ENDING ============
function endSession() {
  cancelAnimationFrame(rafId)
  clearInterval(countdownTimer)
  countdownTimer = null
  clearTimeout(tipTimer)
  showTip.value = false
  state.value = 'ENDING'
  showReport.value = false

  // 刷新纪录
  if (carrots.value > record.value) {
    isNewRecord.value = true
    record.value = carrots.value
    localStorage.setItem('radish_record', String(carrots.value))
  } else {
    isNewRecord.value = false
  }

  // 播感谢成片（停叠化循环，solo 播 ending）
  playSolo(SRC.ending)
  // 兜底：10 秒后强制出战报
  setTimeout(() => { if (state.value === 'ENDING') showReport.value = true }, 10000)
}

// ============ 倒计时 ============
function startCountdown() {
  clearInterval(countdownTimer)
  countdownTimer = setInterval(() => {
    if (countdownLeft.value > 0) {
      countdownLeft.value--
      if (countdownLeft.value === 0) endSession()
    }
  }, 1000)
}

function formatTime(sec) {
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

// ============ 重置 ============
function reset() {
  state.value = 'IDLE'
  progress.value = 0
  carrots.value = 0
  combo.value = 0
  countdownLeft.value = 0
  showReport.value = false
  showTip.value = false
  clearTimeout(tipTimer)
  peakVolume.value = 0
  peakTime.value = ''
  clearInterval(countdownTimer)
  countdownTimer = null
  stopLoop()
  if (soloEl.value) soloEl.value.pause()
}

// ============ 设置齿轮 ============
function onMouseMove(e) {
  const w = window.innerWidth
  if (e.clientX > w - 120 && e.clientY < 120) {
    showGear.value = true
    clearTimeout(gearTimer)
    gearTimer = setTimeout(() => { showGear.value = false; settingsOpen.value = false }, 3000)
  }
}

const micTestResult = ref('')
async function testMic() {
  micTestResult.value = '测试中…请大声说话 3 秒'
  const ok = await initMic()
  if (!ok) { micTestResult.value = '❌ 麦克风被禁用'; return }
  let maxV = 0
  const t0 = performance.now()
  const tick = () => {
    maxV = Math.max(maxV, readVolume())
    if (performance.now() - t0 < 3000) requestAnimationFrame(tick)
    else micTestResult.value = maxV > cfg.threshold
      ? `✅ 正常！最大音量 ${(maxV*100).toFixed(0)}%`
      : `⚠️ 声音偏小（最大 ${(maxV*100).toFixed(0)}%），可调低阈值`
  }
  tick()
}

// ============ 视频预加载（分级） ============
// 核心视频（READY 门控，全部 canplaythrough 才进入游戏）：
//   intro  READY 结束立刻播；loop intro 仅 1.18s 后就接；pullout 首次拔出时播，纳入门控零意外
// 后续视频（门控通过后立刻后台开下，游戏过程时间充裕）：success 首次拔出约 24s 后才需要、ending 结束早读才播
const CORE_SRC = [SRC.intro, SRC.loop, SRC.pullout]
const CORE_LABEL = ['开头片', '用力拔', '拔出来']
const LATER_SRC = [SRC.success, SRC.ending]
const READY_MEDIA_TIMEOUT = 20000   // 弱网兜底：最多等 20s 强进（faststart 后本就能边下边播）
const mediaReady = reactive({})     // src -> true（canplaythrough 触发），驱动 READY 加载提示
const noiseMeasured = ref(false)    // 2s 底噪已测完
let preloadEls = []

/** 取核心素材对应的 video 元素：loop 复用模板 loopEl，intro/pullout 用隐藏预取元素 */
function getCoreEl(src) {
  if (src === SRC.loop) return loopEl.value
  return preloadEls.find(el => el.dataset.src === src) || null
}

/** 幂等创建隐藏预取 video（1px+透明，非 display:none，浏览器正常调度下载） */
function ensurePreload(srcs) {
  for (const src of srcs) {
    if (preloadEls.some(el => el.dataset.src === src)) continue
    const v = document.createElement('video')
    v.dataset.src = src
    v.addEventListener('canplaythrough', () => { mediaReady[src] = true })
    v.preload = 'auto'
    v.src = src
    v.muted = true
    v.setAttribute('aria-hidden', 'true')
    v.style.cssText = 'position:absolute;width:1px;height:1px;opacity:0;pointer-events:none;'
    document.body.appendChild(v)
    preloadEls.push(v)
  }
}

/** READY 门控：等 3 个核心视频都 canplaythrough（已就绪立即返回），超时强进 resolve 不 reject */
function waitCoreReady() {
  return Promise.all(CORE_SRC.map(src => new Promise(resolve => {
    if (mediaReady[src]) { resolve(); return }
    const el = getCoreEl(src)
    if (!el) { resolve(); return }   // 元素异常不阻塞流程
    let timer = null
    const done = () => {
      clearTimeout(timer)
      el.removeEventListener('canplaythrough', done)
      mediaReady[src] = true         // 超时强进也置位，让 UI 计数收满
      resolve()
    }
    if (el.readyState >= 4) { done(); return }
    el.addEventListener('canplaythrough', done)
    timer = setTimeout(done, READY_MEDIA_TIMEOUT)
  })))
}

function destroyPreload() {
  for (const v of preloadEls) { v.pause(); v.removeAttribute('src'); v.load(); v.remove() }
  preloadEls = []
}

// ============ 生命周期 ============
onMounted(() => {
  record.value = parseInt(localStorage.getItem('radish_record') || '0', 10)
  // loop 由模板 loopEl 静态 src + preload=auto 自行下载，这里只补就绪标记（含已就绪的兜底判断）
  if (loopEl.value) {
    if (loopEl.value.readyState >= 4) mediaReady[SRC.loop] = true
    loopEl.value.addEventListener('canplaythrough', () => { mediaReady[SRC.loop] = true })
  }
  // 封面停留期间先下 intro/pullout（loop 已由 loopEl 下载），把等待藏在老师组织课堂的时间里
  ensurePreload([SRC.intro, SRC.pullout])
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  clearInterval(countdownTimer)
  clearTimeout(tipTimer)
  stopLoop()
  if (soloEl.value) soloEl.value.pause()
  destroyPreload()
  if (micStream) micStream.getTracks().forEach(t => t.stop())
  if (audioCtx) audioCtx.close()
})
</script>

<style scoped>
.radish-stage {
  position: fixed; inset: 0; background: #1a1a2e;
  overflow: hidden; user-select: none;
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
}

/* 顶部音量折线：透明底、不接事件，浮在视频上方不遮挡背景 */
.volume-line {
  position: absolute; top: 0; left: 0; width: 100%; height: 96px;
  z-index: 20; pointer-events: none; background: transparent;
}

/* 视频区：两个 video 绝对定位重叠铺满（循环机 loop + 单片机 solo）；显隐由各自 :style opacity 控制 */
.video-wrap { position: absolute; inset: 0; }
.stage-video {
  position: absolute; inset: 0;
  width: 100%; height: 100%; object-fit: contain;
  background: #1a1a2e;
}
/* solo 单片（开头/拔出/感谢）层级压过循环机；显隐由 :style opacity 瞬时切换 */
.solo-video { z-index: 2; }

/* 左下角萝卜计数 */
.carrot-counter {
  position: absolute; left: 24px; bottom: 24px; z-index: 15;
  display: flex; align-items: flex-end; gap: 4px; flex-wrap: wrap;
}
.carrot { font-size: 36px; display: inline-block; }
.cc-num { font-size: 36px; color: #ffc107; font-weight: 800; margin-left: 8px; }
.pop-enter-active { transition: all .4s cubic-bezier(.68,-0.55,.27,1.55); }
.pop-enter-from { transform: scale(0) translateY(-40px); opacity: 0; }

/* HUD 顶部右侧 */
.hud-top {
  position: absolute; top: 72px; right: 24px; z-index: 15;
  display: flex; align-items: center; gap: 16px;
}
.hud-timer {
  font-size: 32px; color: #fff; font-weight: 800;
  background: rgba(0,0,0,.5); padding: 4px 16px; border-radius: 12px;
}
.hud-combo {
  font-size: 24px; color: #ff9800; font-weight: 700;
  background: rgba(0,0,0,.5); padding: 4px 14px; border-radius: 12px;
  animation: pulse .6s ease infinite alternate;
}
@keyframes pulse { from { transform: scale(1) } to { transform: scale(1.08) } }
.hud-end {
  font-size: 18px; padding: 8px 20px; border-radius: 10px;
  background: rgba(255,255,255,.15); color: #fff; border: 1px solid rgba(255,255,255,.3);
  cursor: pointer; transition: background .2s;
}
.hud-end:hover { background: rgba(255,255,255,.3); }

/* 进度条 */
.progress-wrap {
  position: absolute; bottom: 80px; left: 50%; transform: translateX(-50%);
  width: 60%; z-index: 15;
}
.progress-track {
  height: 28px; background: rgba(255,255,255,.12);
  border-radius: 14px; overflow: visible; position: relative;
}
.progress-fill {
  height: 100%; border-radius: 14px;
  background: linear-gradient(90deg, #4caf50, #ffc107, #f44336);
  transition: width .1s linear;
}
.progress-label {
  text-align: center; color: rgba(255,255,255,.7); font-size: 16px; margin-top: 6px;
}

/* 第N根萝卜大字 */
.banner {
  position: absolute; inset: 0; z-index: 30;
  display: flex; align-items: center; justify-content: center;
  pointer-events: none;
}
.banner-text {
  font-size: 110px; font-weight: 900; color: #ffc107;
  text-shadow: 0 4px 24px rgba(0,0,0,.6), 0 0 60px rgba(255,193,7,.5);
  animation: bounceIn .5s cubic-bezier(.68,-0.55,.27,1.55);
}
.bounce-enter-active { transition: all .5s cubic-bezier(.68,-0.55,.27,1.55); }
.bounce-leave-active { transition: all .3s ease-in; }
.bounce-enter-from, .bounce-leave-to { transform: scale(3); opacity: 0; }
@keyframes bounceIn {
  0% { transform: scale(3); opacity: 0; }
  60% { transform: scale(.92); opacity: 1; }
  100% { transform: scale(1); }
}

/* 里程碑鼓励气泡（30/50/80%）：比拔出大字小一号，居中偏上，弹入后上浮淡出 */
.milestone-tip {
  position: absolute; top: 24%; left: 50%; z-index: 28;
  transform: translateX(-50%);
  font-size: 56px; font-weight: 900; color: #fff; white-space: nowrap;
  text-shadow: 0 3px 18px rgba(0,0,0,.65), 0 0 40px rgba(255,193,7,.35);
  pointer-events: none;
}
.tip-enter-active { animation: tipIn .35s cubic-bezier(.68,-0.55,.27,1.55); }
.tip-leave-active { transition: all .35s ease-in; }
.tip-enter-from { opacity: 0; }
.tip-leave-to { opacity: 0; transform: translateX(-50%) translateY(-18px) scale(.92); }
@keyframes tipIn {
  0% { opacity: 0; transform: translateX(-50%) scale(.4); }
  60% { transform: translateX(-50%) scale(1.12); }
  100% { opacity: 1; transform: translateX(-50%) scale(1); }
}

/* 结尾战报 */
.report-card {
  position: absolute; top: 50%; left: 50%; transform: translate(-50%,-50%);
  z-index: 40; background: rgba(0,0,0,.85); backdrop-filter: blur(12px);
  border-radius: 24px; padding: 48px 64px; text-align: center;
  border: 2px solid rgba(255,193,7,.3); min-width: 480px;
}
.rc-title { font-size: 32px; color: #ffc107; font-weight: 800; margin-bottom: 16px; }
.rc-count { font-size: 56px; color: #fff; font-weight: 900; margin-bottom: 12px; }
.rc-peak { font-size: 22px; color: rgba(255,255,255,.7); margin-bottom: 8px; }
.rc-comment { font-size: 26px; color: #4caf50; font-weight: 700; margin-bottom: 12px; }
.rc-record { font-size: 24px; color: #ffc107; font-weight: 700; margin-bottom: 8px; }
.rc-tomorrow { font-size: 20px; color: rgba(255,255,255,.6); margin-bottom: 24px; }
.rc-btn {
  font-size: 22px; padding: 12px 40px; border-radius: 14px;
  background: linear-gradient(135deg, #ffc107, #ff9800);
  color: #1a1a2e; font-weight: 800; border: none; cursor: pointer;
  transition: transform .2s;
}
.rc-btn:hover { transform: scale(1.05); }

/* IDLE 封面 */
.idle-cover {
  position: absolute; inset: 0; z-index: 50;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
}
.idle-emoji { font-size: 120px; margin-bottom: 24px; animation: float 3s ease-in-out infinite; }
@keyframes float { 0%,100% { transform: translateY(0) } 50% { transform: translateY(-20px) } }
.idle-title { font-size: 72px; font-weight: 900; color: #ffc107; margin-bottom: 16px; text-shadow: 0 4px 24px rgba(0,0,0,.5); }
.idle-sub { font-size: 28px; color: rgba(255,255,255,.7); margin-bottom: 48px; }
.idle-btn {
  font-size: 36px; padding: 20px 64px; border-radius: 20px;
  background: linear-gradient(135deg, #ffc107, #ff9800);
  color: #1a1a2e; font-weight: 900; border: none; cursor: pointer;
  box-shadow: 0 8px 32px rgba(255,193,7,.4);
  transition: transform .2s, box-shadow .2s;
}
.idle-btn:hover { transform: scale(1.06); box-shadow: 0 12px 48px rgba(255,193,7,.6); }
/* 返回工作台：低调描边按钮，仅 IDLE 封面显示（游戏投影中不打扰；结束走「结束早读」回 IDLE 再退出） */
.idle-back {
  margin-top: 22px; font-size: 20px; padding: 12px 32px; border-radius: 999px;
  background: rgba(255,255,255,.1); color: rgba(255,255,255,.85);
  border: 1px solid rgba(255,255,255,.35); cursor: pointer; transition: background .15s, color .15s;
}
.idle-back:hover { background: rgba(255,255,255,.24); color: #fff; }
.idle-record { font-size: 20px; color: rgba(255,255,255,.5); margin-top: 32px; }
.idle-err { font-size: 18px; color: #f66; margin-top: 16px; max-width: 500px; text-align: center; }

/* READY 测底噪 */
.ready-cover {
  position: absolute; inset: 0; z-index: 50;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: rgba(0,0,0,.8);
}
.ready-emoji { font-size: 80px; margin-bottom: 24px; }
.ready-text { font-size: 32px; color: #fff; margin-bottom: 32px; }
.ready-bar {
  width: 400px; height: 12px; background: rgba(255,255,255,.12);
  border-radius: 6px; overflow: hidden;
}
.ready-fill { height: 100%; background: #4caf50; border-radius: 6px; transition: width .1s linear; }

/* READY 视频就绪状态 */
.ready-load {
  margin-top: 28px; display: flex; align-items: center; gap: 16px;
  color: rgba(255,255,255,.75); font-size: 20px;
}
.rl-title { color: rgba(255,255,255,.9); font-weight: 700; }
.rl-dot {
  padding: 4px 14px; border-radius: 999px; font-size: 17px;
  background: rgba(255,255,255,.1); transition: background .2s, color .2s;
}
.rl-dot.ok { background: rgba(76,175,80,.25); color: #8aff9e; }
.rl-count { font-weight: 800; color: #ffc107; }
.ready-load-tip { margin-top: 14px; font-size: 18px; color: rgba(255,255,255,.6); }

/* 设置齿轮 */
.gear {
  position: fixed; top: 16px; right: 16px; z-index: 100;
  opacity: 0; transition: opacity .3s;
}
.gear.show { opacity: 1; }
.gear-btn {
  font-size: 28px; background: rgba(255,255,255,.15); border: none;
  border-radius: 50%; width: 48px; height: 48px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
}
.gear-panel {
  position: absolute; top: 56px; right: 0; width: 320px;
  background: rgba(0,0,0,.9); border-radius: 16px; padding: 20px;
  border: 1px solid rgba(255,255,255,.15);
}
.gp-row { margin-bottom: 16px; }
.gp-row label { display: block; color: #fff; font-size: 15px; margin-bottom: 6px; }
.gp-row input[type=range] { width: 100%; accent-color: #ffc107; }
.gp-test {
  width: 100%; padding: 10px; border-radius: 10px; border: none;
  background: rgba(255,193,7,.2); color: #ffc107; font-size: 16px;
  cursor: pointer; transition: background .2s;
}
.gp-test:hover { background: rgba(255,193,7,.35); }
.gp-result { color: rgba(255,255,255,.8); font-size: 14px; margin-top: 10px; text-align: center; }

/* 过渡 */
.fade-enter-active, .fade-leave-active { transition: opacity .4s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
</style>
