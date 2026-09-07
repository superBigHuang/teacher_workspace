<template>
  <div class="radish-stage" @mousemove="onMouseMove">
    <!-- 顶部音量条（除 IDLE 外全程显示） -->
    <div v-if="state !== 'IDLE'" class="volume-bar">
      <span class="vb-icon">🔊</span>
      <div class="vb-track">
        <div class="vb-fill" :style="{ width: (volume*100).toFixed(0)+'%', background: volumeColor }"></div>
        <div class="vb-threshold" :style="{ left: (cfg.threshold*100).toFixed(0)+'%' }"></div>
      </div>
      <span class="vb-emoji">{{ volumeEmoji }}</span>
      <span class="vb-text">{{ guideText }}</span>
      <span class="vb-mic" :class="{ err: micError }">{{ micError ? '🎤 被禁用' : '🎤 已开' }}</span>
    </div>

    <!-- 主舞台：循环段用双 video 叠化无缝循环；intro/success/ending 用单独 solo 单片 -->
    <div class="video-wrap">
      <!-- 双机叠化：A/B 均加载 loop.mp4，主机播到尾段时副机从头淡入、主机淡出，交叉淡化掩盖循环接缝 -->
      <video ref="loopElA" class="stage-video loop-video" playsinline preload="auto" :src="SRC.loop"></video>
      <video ref="loopElB" class="stage-video loop-video" playsinline preload="auto" :src="SRC.loop"></video>
      <!-- 单片：开头/拔出/感谢成片；仅 INTRO/SUCCESS/ENDING 可见（opacity 瞬时切换，不挡循环双机） -->
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

      <!-- 目标线（叠加在进度条上） -->
      <div v-if="state === 'PULLING'" class="progress-wrap">
        <div class="progress-track">
          <div class="progress-fill" :style="{ width: progress.toFixed(1)+'%' }"></div>
          <div
            v-for="g in goalTicks"
            :key="g"
            class="progress-goal"
            :class="{ hit: carrots >= g }"
            :style="{ left: (g / goalCount * 100)+'%' }"
          >🚩</div>
        </div>
        <div class="progress-label">当前萝卜 {{ progress.toFixed(0) }}% · 已拔 {{ carrots }}/{{ goalCount }}</div>
      </div>

      <!-- 第N根萝卜大字特效 -->
      <transition name="bounce">
        <div v-if="showBanner" class="banner">
          <div class="banner-text">{{ bannerText }}</div>
        </div>
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

      <!-- READY 测底噪 -->
      <transition name="fade">
        <div v-if="state === 'READY'" class="ready-cover">
          <div class="ready-emoji">🤫</div>
          <div class="ready-text">嘘…兔兔在听教室的安静声</div>
          <div class="ready-bar"><div class="ready-fill" :style="{ width: readyPct+'%' }"></div></div>
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
            <input type="range" min="0" max="30" step="1" v-model.number="cfg.countdownMin">
          </div>
          <div class="gp-row">
            <label>目标根数 {{ goalCount }}</label>
            <input type="range" min="3" max="15" step="1" v-model.number="goalCount">
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
// 循环段双机叠化：loopElA/loopElB 都加载 loop.mp4 交叉淡化；soloEl 播 intro/success/ending
const loopElA = ref(null)
const loopElB = ref(null)
const soloEl = ref(null)

// 素材路径（public/radish/ 下）
const SRC = {
  intro: '/radish/intro.mp4',     // 开头（拔萝卜-1，1.19s，每次开始播一次）
  loop: '/radish/loop.mp4',       // 循环用力拔（拔萝卜-2，1.72s，双 video 叠化无缝循环）
  pullout: '/radish/pullout.mp4', // 拔出来动作（拔萝卜-3，3.41s）
  success: '/radish/success.mp4', // 拔出庆祝成片（8.83s）
  ending: '/radish/ending.mp4'    // 感谢成片（7.2s，结束早读）
}
// 拔出萝卜时连播：pullout（拔出来）→ success（庆祝成片），全播完回循环
// 叠化时长（秒）：主机播到尾段时两机交叉淡化的窗口；loop 片段约 1.72s，0.35s 接缝柔和且不频繁
const FADE = 0.35
// 叠化运行态：activeIdx 当前主播放机（0=A/1=B）；crossfade 非 null 表示正在交叉（含起始时间戳）
let activeIdx = 0
let crossfade = null

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
  countdownMin: 15     // 倒计时分钟数（0=不限时）
})
const goalCount = ref(8)       // 全班目标根数（进度条上的小旗）

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
const volumeColor = computed(() => {
  const v = volume.value
  if (v < cfg.threshold) return '#4caf50'
  if (v < cfg.threshold * 1.8) return '#ffc107'
  return '#f44336'
})
const volumeEmoji = computed(() => {
  const v = volume.value
  if (v < cfg.threshold * 0.5) return '😴'
  if (v < cfg.threshold) return '🙂'
  return '😆'
})
const guideText = computed(() => {
  const v = volume.value
  if (state.value === 'SUCCESS') return '太棒了！'
  if (v < cfg.threshold * 0.5) return '兔兔睡着了…大声读！'
  if (v < cfg.threshold) return '小声读兔兔听不见'
  if (combo.value > 300) return '保持住！🔥 连击加成中'
  return '大声读！兔兔在用力！'
})
const goalTicks = computed(() => {
  const arr = []
  for (let i = 1; i <= goalCount.value; i++) arr.push(i)
  return arr
})
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

// ============ READY 测底噪 ============
async function start() {
  const ok = await initMic()
  if (!ok) return
  state.value = 'READY'
  readyPct.value = 0
  const samples = []
  const t0 = performance.now()
  const tick = () => {
    const raw = readVolume()
    samples.push(raw)
    readyPct.value = Math.min(100, ((performance.now() - t0) / 2000) * 100)
    if (performance.now() - t0 < 2000) {
      requestAnimationFrame(tick)
    } else {
      samples.sort((a, b) => a - b)
      // 取 90 分位作为底噪（排除突发响动）
      noiseFloor.value = samples[Math.floor(samples.length * 0.9)] || 0.05
      startIntro()
    }
  }
  tick()
}

// ============ INTRO 开头片 → PULLING 叠化循环 ============
let rafId = null
let lastT = 0
let quietFrames = 0   // 连续低音量帧计数（静音回退的 1.5s 宽限用，60fps）
let successStage = '' // SUCCESS 连播阶段：'pullout'（拔出来）→ 'celebrate'（庆祝成片）

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

/** 启动叠化循环：A 机从头主播放（不透明），B 机暂停待命（透明） */
function startLoop() {
  activeIdx = 0
  crossfade = null
  const a = loopElA.value, b = loopElB.value
  if (a) {
    a.currentTime = 0
    a.playbackRate = 1
    a.volume = 1
    a.style.opacity = '1'
    a.play().catch(() => {})
  }
  if (b) {
    b.pause()
    b.currentTime = 0
    b.playbackRate = 1
    b.volume = 1
    b.style.opacity = '0'
  }
}

/** 停止叠化循环：双机暂停、复位透明（切 solo 单片或重置/结束时调用） */
function stopLoop() {
  crossfade = null
  for (const el of [loopElA.value, loopElB.value]) {
    if (el) {
      el.pause()
      el.currentTime = 0
      el.style.opacity = '0'
    }
  }
}

function startPulling() {
  state.value = 'PULLING'
  progress.value = 0
  combo.value = 0
  quietFrames = 0
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

  // 音量非对称平滑：上升响应快（跟得上朗读），下降缓慢（字间停顿/换气不暴跌）。
  // 旧的对称平滑(0.15/0.85)在朗读停顿的瞬间音量掉底，表现为音量条"跳动"并误触发静音回退。
  const raw = readVolume()
  const effective = Math.max(0, raw - noiseFloor.value)
  if (effective >= volume.value) {
    volume.value = volume.value * 0.5 + effective * 0.5     // attack：约 2 帧即跟上
  } else {
    volume.value = volume.value * 0.94 + effective * 0.06   // decay：时间常数约 0.28s，停顿不暴跌
  }

  // 连击：持续超阈值 5 秒 → 1.5 倍速
  if (volume.value >= cfg.threshold) {
    combo.value++
  } else {
    combo.value = 0
  }
  const speedMul = combo.value >= 300 ? 1.5 : 1.0

  // 进度积分：每秒增量 = (volume - threshold) * 速度系数 * 难度倒数
  // 标定：全班正常朗读 volume≈0.4，threshold 0.15 → 每秒 + (0.25*K)
  // 目标 targetSec 秒满 100 → K = 100 / (0.25 * targetSec)
  const K = 100 / (0.25 * cfg.targetSec)
  const gain = Math.max(0, volume.value - cfg.threshold) * K * speedMul / difficulty.value * dt
  progress.value = Math.min(100, progress.value + gain)

  // 静音回退：需"持续"低于阈值一半约 1.5 秒才开始（宽限，朗读换气/字间停顿不误触发）；
  // 回退缓慢（每秒约 -8%，旧值 -18% 太急），下限 30% 保护积极性。
  if (volume.value < cfg.threshold * 0.5) {
    quietFrames++
  } else {
    quietFrames = 0
  }
  if (quietFrames > 90 && progress.value > 30) {
    progress.value = Math.max(30, progress.value - 0.13 * dt * 60)
  }

  // 峰值纪录
  if (volume.value > peakVolume.value) {
    peakVolume.value = volume.value
    const d = new Date()
    peakTime.value = `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}`
  }

  // 双机叠化循环：视频始终恒速 1x 播放，不随音量变速（变速会让兔子动作滑稽）。
  // "声音越大拔得越快"由进度积分表达——volume 越大 progress 涨越快，拔一根所需的循环次数越少。
  // 循环不靠 seek 回跳（硬切突兀）：主机播到尾段时副机从头淡入、主机淡出，结尾"融进"开头。
  const active = activeIdx === 0 ? loopElA.value : loopElB.value
  const standby = activeIdx === 0 ? loopElB.value : loopElA.value
  if (active && standby) {
    const dur = active.duration
    // 主机剩 FADE 秒到结尾时开始叠化（恒速播放，触发点固定在 dur-FADE）
    if (!crossfade && dur && isFinite(dur) && active.currentTime >= dur - FADE) {
      standby.currentTime = 0
      standby.volume = 0
      standby.style.opacity = '0'
      standby.play().catch(() => {})
      requestAnimationFrame(() => { standby.style.opacity = '1' }) // 下一帧再设 1，触发 CSS 淡入
      active.style.opacity = '0'                                  // 主机同步淡出
      crossfade = { t0: performance.now() }
    }
    if (crossfade) {
      const t = Math.min(1, (performance.now() - crossfade.t0) / (FADE * 1000))
      standby.volume = t        // 声音也交叉淡化，避免双机音轨重叠
      active.volume = 1 - t
      if (t >= 1) {
        // 收尾：旧主机暂停复位为待命态，主副角色互换
        active.pause()
        active.currentTime = 0
        active.volume = 1
        active.style.opacity = '0'
        standby.volume = 1
        standby.style.opacity = '1'
        activeIdx = 1 - activeIdx
        crossfade = null
      }
    }
  }

  // 拔出判定
  if (progress.value >= 100) {
    onSuccess()
    return
  }

  rafId = requestAnimationFrame(pullLoop)
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

  // 撒花（复用 EvolutionShow 三发模式）
  fireConfetti()

  // 拔出连播：先播"拔出来"动作片(pullout)，onSoloEnded 里自动接"庆祝成片"(success)，全播完回循环
  successStage = 'pullout'
  playSolo(SRC.pullout)

  // 目标达成：萝卜变金
  if (carrots.value === goalCount.value) {
    setTimeout(() => fireConfetti(true), 800)
  }
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

// ============ 生命周期 ============
onMounted(() => {
  record.value = parseInt(localStorage.getItem('radish_record') || '0', 10)
})

onBeforeUnmount(() => {
  cancelAnimationFrame(rafId)
  clearInterval(countdownTimer)
  stopLoop()
  if (soloEl.value) soloEl.value.pause()
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

/* 顶部音量条 */
.volume-bar {
  position: absolute; top: 0; left: 0; right: 0; z-index: 20;
  display: flex; align-items: center; gap: 12px;
  padding: 14px 24px; background: rgba(0,0,0,.55); backdrop-filter: blur(8px);
}
.vb-icon { font-size: 28px; }
.vb-track {
  flex: 1; height: 36px; background: rgba(255,255,255,.12);
  border-radius: 18px; overflow: hidden; position: relative;
}
.vb-fill {
  height: 100%; border-radius: 18px;
  transition: width .08s linear, background .3s;
}
.vb-threshold {
  position: absolute; top: 0; bottom: 0; width: 3px;
  background: #fff; opacity: .6;
}
.vb-emoji { font-size: 32px; }
.vb-text { font-size: 22px; color: #fff; font-weight: 600; white-space: nowrap; }
.vb-mic { font-size: 14px; color: #8f8; margin-left: auto; }
.vb-mic.err { color: #f66; }

/* 视频区：三个 video 绝对定位重叠铺满（循环双机 A/B + 单片机 solo） */
.video-wrap { position: absolute; inset: 0; }
.stage-video {
  position: absolute; inset: 0;
  width: 100%; height: 100%; object-fit: contain;
  background: #1a1a2e;
}
/* 循环双机叠化：opacity 交叉淡化 0.35s（与 JS 的 FADE 一致）；solo 单片瞬时显隐、层级压过循环机 */
.loop-video { opacity: 0; transition: opacity .35s linear; }
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
.progress-goal {
  position: absolute; top: -8px; font-size: 20px;
  transform: translateX(-50%); opacity: .35; transition: opacity .3s;
}
.progress-goal.hit { opacity: 1; filter: drop-shadow(0 0 6px gold); }
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
