<template>
  <Teleport to="body">
    <div v-if="scene" class="evo-overlay" @click="skip">
      <!-- 暗化遮罩（聚光灯在目标座位处挖洞） -->
      <div class="evo-mask" :style="maskStyle"></div>
      <!-- 主题光柱（蓄力阶段自顶部射下） -->
      <div v-if="phase >= 3" class="evo-beam" :style="beamStyle"></div>
      <!-- 脚下旋转光环 -->
      <div v-if="phase >= 3" class="evo-aura" :style="{ background: auraBg }"></div>
      <!-- 汇聚粒子 -->
      <div v-if="phase === 3" class="evo-particles">
        <span v-for="(p, i) in particles" :key="i" class="evo-pt"
              :style="{ '--dx': p.dx + 'px', '--dy': p.dy + 'px', animationDelay: p.delay + 'ms' }">{{ p.emoji }}</span>
      </div>
      <!-- 飞行宠物（外层定位负责 FLIP，内层 img 负责形态/抖动/弹簧） -->
      <div v-if="flyVisible" class="evo-fly-wrap" :style="wrapStyle">
        <img class="evo-fly-img" :class="imgClass" :src="flySrc" alt=""
             :style="imgStyle" draggable="false">
      </div>
      <!-- 爆闪白屏 -->
      <div v-if="phase === 4" class="evo-flash" :class="{ on: flashOn }"></div>
      <!-- 阶段字幕 -->
      <div v-if="caption" class="evo-caption">{{ caption }}</div>
      <!-- 揭晓文案 -->
      <div v-if="phase >= 5" class="evo-success">
        <div class="evo-title">✨ 进 化 成 功 ✨</div>
        <div class="evo-sub">
          <b>{{ scene.name }}</b> 的 {{ scene.petName }} 从 Lv.{{ scene.fromLevel }} 进化为 Lv.{{ scene.toLevel }}！
        </div>
      </div>
      <div class="evo-skip">点击任意处跳过 ≫</div>
    </div>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import confetti from 'canvas-confetti'
import { FX_THEMES } from '../composables/usePets'

const props = defineProps({
  /** 一幕升级事件 {studentId, name, petName, fromLevel, toLevel, skinKey, fxTheme, fromImg, toImg} */
  scene: { type: Object, default: null }
})
const emit = defineEmits(['done'])

// 分镜阶段：1 聚光 / 2 起飞 / 3 蓄力 / 4 爆闪 / 5 揭晓 / 6 回落
const phase = ref(0)
const flyVisible = ref(false)
const flySrc = ref('')
const wrapStyle = ref({})
const imgClass = ref('')
const imgStyle = ref({})
const flashOn = ref(false)
const particles = ref([])

let targetEl = null
let startRect = null
let timers = []
let waiters = []
let skipped = false

const theme = computed(() => FX_THEMES[props.scene?.fxTheme] || FX_THEMES.gold)

/** 聚光灯遮罩：在目标座位中心挖透明圆洞 */
const maskStyle = computed(() => {
  if (!startRect) return { background: 'rgba(10,8,20,.72)' }
  const cx = startRect.left + startRect.width / 2
  const cy = startRect.top + startRect.height / 2
  return {
    background: `radial-gradient(circle 120px at ${cx}px ${cy}px, transparent 0%, rgba(10,8,20,.72) 190px)`
  }
})
const beamStyle = computed(() => ({
  background: `linear-gradient(180deg, ${theme.value.beam}66 0%, ${theme.value.beam}22 55%, transparent 100%)`
}))
const auraBg = computed(() =>
  `conic-gradient(from 0deg, transparent 0deg, ${theme.value.beam}cc 60deg, transparent 130deg, transparent 220deg, ${theme.value.beam}88 280deg, transparent 340deg)`)

const caption = computed(() => ({
  1: '⚠ 进化能量汇聚中……',
  2: '🚀 宠物飞向进化之光！',
  3: '⚡ 能量蓄力中……',
  4: '💥 进 化 ！'
}[phase.value] || ''))

/** 可跳过的等待：跳过点击后所有等待立即放行 */
function wait(ms) {
  return new Promise(res => {
    const t = setTimeout(() => { waiters = waiters.filter(w => w.res !== res); res() }, ms)
    waiters.push({ res, t })
  })
}
const nextFrame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)))

/** 跳过：清掉全部等待，检查点立即走到 finish */
function skip() {
  if (skipped) return
  skipped = true
  waiters.forEach(w => { clearTimeout(w.t); w.res() })
  waiters = []
}

watch(() => props.scene, async (s) => {
  if (!s) return
  skipped = false
  timers = []
  phase.value = 0
  flyVisible.value = false
  particles.value = []
  flashOn.value = false

  // 定位目标座位格 / 未排座宠物卡（Seat.vue 中均带 data-sid）
  targetEl = document.querySelector(`[data-sid="${s.studentId}"]`)
  targetEl?.classList.add('evo-spot')
  const elRect = targetEl?.getBoundingClientRect()
  const imgEl = targetEl?.querySelector('img')
  startRect = (imgEl || targetEl)?.getBoundingClientRect() || elRect || null

  // —— 阶段 1：聚光（0.5s）——
  phase.value = 1
  await wait(500); if (skipped) return finish()

  // —— 阶段 2：起飞（1s）：从座位位置 FLIP 弧线飞到屏幕中央放大 ——
  flySrc.value = s.fromImg
  const cx = window.innerWidth / 2
  const cy = window.innerHeight * 0.46
  const size = 280
  if (startRect) {
    wrapStyle.value = {
      left: startRect.left + startRect.width / 2 + 'px',
      top: startRect.top + startRect.height / 2 + 'px',
      width: startRect.width + 'px',
      height: startRect.height + 'px'
    }
  } else {
    wrapStyle.value = { left: cx + 'px', top: cy + 'px', width: size + 'px', height: size + 'px' }
  }
  flyVisible.value = true
  await nextFrame()
  imgStyle.value = { transform: 'rotate(10deg)' }
  imgClass.value = 'flying'
  wrapStyle.value = { ...wrapStyle.value, left: cx + 'px', top: cy + 'px', width: size + 'px', height: size + 'px' }
  phase.value = 2
  await wait(950); if (skipped) return finish()

  // —— 阶段 3：蓄力（1s）：抖动加剧 + 光环加速 + 光柱 + 粒子汇聚 ——
  phase.value = 3
  imgClass.value = 'charging'
  imgStyle.value = {}
  const pts = []
  for (let i = 0; i < 14; i++) {
    const ang = (Math.PI * 2 * i) / 14 + Math.random() * 0.3
    const dist = 230 + Math.random() * 130
    pts.push({
      emoji: theme.value.particles[i % theme.value.particles.length],
      dx: Math.round(-Math.cos(ang) * dist),
      dy: Math.round(-Math.sin(ang) * dist),
      delay: Math.round(Math.random() * 220)
    })
  }
  particles.value = pts
  await wait(1000); if (skipped) return finish()

  // —— 阶段 4：爆闪（0.5s）：白屏脉冲 2 次，第 2 闪瞬间换新形态 ——
  phase.value = 4
  imgClass.value = ''
  flashOn.value = true
  await wait(170)
  flashOn.value = false
  await wait(150)
  flySrc.value = s.toImg
  flashOn.value = true
  await wait(180); if (skipped) return finish()

  // —— 阶段 5：揭晓（1.5s）：新形态弹簧定格 + confetti 三波 ——
  phase.value = 5
  flashOn.value = false
  imgClass.value = 'revealed'
  fireConfetti()
  await wait(1500); if (skipped) return finish()

  // —— 阶段 6：回落（1.5s）：中央图淡出，目标座位金光闪 3 次 ——
  phase.value = 6
  imgClass.value = 'falling'
  targetEl?.classList.remove('evo-spot')
  targetEl?.classList.add('evo-glow-target')
  await wait(1500)
  finish()
}, { immediate: true })

/** confetti 三波：左斜喷 / 右斜喷 / 中央散落（主题色） */
function fireConfetti() {
  const colors = theme.value.colors
  const z = 3300
  confetti({ particleCount: 60, angle: 60, spread: 70, origin: { x: 0, y: 0.85 }, colors, zIndex: z })
  confetti({ particleCount: 60, angle: 120, spread: 70, origin: { x: 1, y: 0.85 }, colors, zIndex: z })
  setTimeout(() => confetti({ particleCount: 90, spread: 110, origin: { y: 0.35 }, colors, zIndex: z }), 260)
}

/** 收尾：清理全局 class / 计时器 / confetti canvas，通知父组件出队 */
function finish() {
  timers.forEach(clearTimeout)
  waiters.forEach(w => clearTimeout(w.t))
  timers = []
  waiters = []
  targetEl?.classList.remove('evo-spot')
  targetEl?.classList.remove('evo-glow-target')
  confetti.reset()
  phase.value = 0
  flyVisible.value = false
  particles.value = []
  emit('done')
}

onBeforeUnmount(finish)
</script>

<style scoped>
.evo-overlay {
  position: fixed; inset: 0; z-index: 3000; overflow: hidden;
  cursor: pointer; user-select: none;
}
.evo-mask { position: absolute; inset: 0; transition: background .4s ease-out; }
.evo-beam {
  position: absolute; top: 0; left: 50%; width: 210px; height: 62%;
  transform: translateX(-50%); pointer-events: none;
  animation: evo-beam-in .6s ease-out forwards; opacity: 0;
  filter: blur(2px);
}
.evo-aura {
  position: absolute; left: 50%; top: 64%; width: 230px; height: 70px;
  transform: translate(-50%, -50%); border-radius: 50%; pointer-events: none;
  filter: blur(6px); opacity: .8;
  animation: evo-aura-spin 1.1s linear infinite;
}
.evo-fly-wrap {
  position: fixed; transform: translate(-50%, -50%);
  transition: left .9s cubic-bezier(.45, -.15, .35, 1.15),
              top .9s cubic-bezier(.45, -.15, .35, 1.15),
              width .9s cubic-bezier(.45, -.15, .35, 1.15),
              height .9s cubic-bezier(.45, -.15, .35, 1.15);
  pointer-events: none; z-index: 2;
}
.evo-fly-img {
  width: 100%; height: 100%; object-fit: contain;
  filter: drop-shadow(0 10px 24px rgba(0, 0, 0, .45));
}
.evo-fly-img.flying { transition: transform .9s cubic-bezier(.45, -.15, .35, 1.15); }
.evo-fly-img.charging { animation: evo-shake .11s linear infinite; }
.evo-fly-img.revealed { animation: evo-pop .6s cubic-bezier(.2, 1.7, .4, 1) forwards; }
.evo-fly-img.falling { transition: opacity .9s ease-in, transform .9s ease-in; opacity: 0; transform: scale(.8); }
.evo-pt {
  position: fixed; left: 50%; top: 46%; font-size: 26px; z-index: 3; pointer-events: none;
  animation: evo-pt-in .9s ease-in forwards;
}
.evo-flash {
  position: absolute; inset: 0; background: #fff; opacity: 0; pointer-events: none; z-index: 4;
}
.evo-flash.on { animation: evo-flash .18s ease-out; }
.evo-caption {
  position: absolute; bottom: 18%; left: 0; right: 0; text-align: center;
  color: #ffe9a8; font-size: 26px; font-weight: 900; letter-spacing: 4px;
  text-shadow: 0 0 18px rgba(255, 200, 90, .8), 0 2px 6px rgba(0, 0, 0, .6);
  animation: evo-caption-in .4s ease-out;
}
.evo-success {
  position: absolute; top: 70%; left: 0; right: 0; text-align: center; z-index: 5;
  animation: evo-success-in .5s cubic-bezier(.2, 1.6, .4, 1);
}
.evo-title {
  font-size: 44px; font-weight: 900; letter-spacing: 8px;
  background: linear-gradient(135deg, #ffd98a, #ff9f5a, #ffd98a);
  -webkit-background-clip: text; background-clip: text; color: transparent;
  text-shadow: 0 0 30px rgba(255, 210, 120, .35);
}
.evo-sub { margin-top: 12px; font-size: 19px; font-weight: 700; color: #fff; text-shadow: 0 2px 8px rgba(0, 0, 0, .7); }
.evo-sub b { color: #ffd98a; }
.evo-skip {
  position: absolute; right: 22px; bottom: 18px; color: rgba(255, 255, 255, .55);
  font-size: 13px; z-index: 6;
}

@keyframes evo-beam-in { from { opacity: 0; } to { opacity: 1; } }
@keyframes evo-aura-spin { from { transform: translate(-50%, -50%) rotate(0deg); } to { transform: translate(-50%, -50%) rotate(360deg); } }
@keyframes evo-shake {
  0%, 100% { transform: translateX(0) rotate(-3deg) scale(1.03); }
  50% { transform: translateX(5px) rotate(3deg) scale(1.06); }
}
@keyframes evo-pt-in {
  from { transform: translate(var(--dx), var(--dy)) scale(.3); opacity: 0; }
  to { transform: translate(0, 0) scale(1.15); opacity: 1; }
}
@keyframes evo-flash { 0% { opacity: 0; } 35% { opacity: .95; } 100% { opacity: 0; } }
@keyframes evo-pop {
  0% { transform: scale(.55); }
  60% { transform: scale(1.12); }
  100% { transform: scale(1); }
}
@keyframes evo-caption-in { from { opacity: 0; transform: translateY(14px); } to { opacity: 1; transform: translateY(0); } }
@keyframes evo-success-in { from { opacity: 0; transform: scale(.7); } to { opacity: 1; transform: scale(1); } }
</style>

<!-- 非 scoped：作用到座位格/宠物卡（Seat.vue 的 DOM） -->
<style>
/* 聚光下的目标格：高亮放大 + 金光描边 */
.evo-spot {
  outline: 3px solid rgba(255, 214, 110, .95) !important;
  outline-offset: 2px;
  transform: scale(1.07);
  box-shadow: 0 0 36px rgba(255, 205, 110, .85) !important;
  border-radius: 16px;
  transition: transform .3s ease-out, box-shadow .3s ease-out;
  position: relative;
  z-index: 5;
}
/* 回落时目标格金光脉冲 3 次 */
.evo-glow-target { animation: evo-target-glow .5s ease-in-out 3; border-radius: 16px; }
@keyframes evo-target-glow {
  0%, 100% { box-shadow: 0 0 0 rgba(255, 210, 120, 0); }
  50% { box-shadow: 0 0 38px rgba(255, 210, 120, .9); }
}
</style>
