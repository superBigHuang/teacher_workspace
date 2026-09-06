<template>
  <div class="boss-stage">
    <!-- 无 Boss：等待老师在手机开始 -->
    <div v-if="!state" class="wait-cover">
      <div class="wait-emoji">👾</div>
      <div class="wait-title">今日还没有怪兽</div>
      <div class="wait-sub">等待老师在手机 /m 开始今日挑战…</div>
    </div>

    <template v-else>
      <header class="boss-header">
        <span class="bh-badge">👾 每日打怪兽</span>
        <span class="bh-name">{{ state.boss_name }}</span>
        <span class="bh-state">{{ stateText }}</span>
      </header>

      <div class="boss-body">
        <!-- 左区：Boss 形象 + HP 条 -->
        <div class="boss-zone">
          <div ref="emojiEl" class="boss-emoji" :class="{ die: bossDie }">{{ state.boss_emoji }}</div>
          <div v-if="hurtText" :key="hurtKey" class="hurt-num">-{{ hurtText }}</div>
          <div class="hp-wrap">
            <div class="hp-label">HP {{ state.hp_left }} / {{ state.hp_total }}</div>
            <div class="hp-track">
              <div class="hp-fill" :style="{ width: hpPct + '%' }"></div>
            </div>
          </div>
          <div v-if="state.status === 'KILLED'" class="boss-over">💀 已被 {{ killName || stats.killStudentName || '勇士' }} 击杀！</div>
          <div v-else-if="state.status === 'ESCAPED'" class="boss-over">💨 怪兽逃跑啦，明天再来！</div>
        </div>

        <!-- 右区：题目卡（无答案）/ 当前答题人 / 今日战绩 -->
        <div class="info-zone">
          <div class="q-card">
            <div v-if="state.question && state.status === 'ACTIVE'" class="q-cat">
              {{ state.categoryIcon || '📖' }} {{ state.categoryName }}
            </div>
            <div class="q-text">{{ qText }}</div>
            <div v-if="state.status === 'ACTIVE' && !state.question" class="q-hint">答案只在老师手机上显示</div>
          </div>
          <div class="picker-card" :class="{ pulse: pickPulseOn }">
            <span class="pk-label">👉 当前答题</span>
            <span class="pk-name">{{ state.pickedStudentName || '— 等待抽人 —' }}</span>
          </div>
          <div class="stats-card">
            <div class="st-row">
              <span class="st-pass">✅ ×{{ stats.pass || 0 }}</span>
              <span class="st-fail">❌ ×{{ stats.fail || 0 }}</span>
              <span class="st-kill">🏆 击杀王：{{ stats.killStudentName || '—' }}</span>
            </div>
            <div v-if="stars.length" class="st-stars">
              <div v-for="(s, i) in stars" :key="s.studentId" class="st-star">
                <span class="ss-medal">{{ ['🥇', '🥈', '🥉'][i] }}</span>
                <span class="ss-name">{{ s.name }}</span>
                <span class="ss-score">+{{ s.score }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- 返回工作台（菜单进入的出口；等待态/战斗态都显示，z 高于 header 与播报） -->
    <button class="boss-exit" @click="goBack">← 返回工作台</button>

    <!-- 击杀全屏播报 -->
    <transition name="bounce">
      <div v-if="killBanner" class="banner">
        <div class="banner-text">{{ killName }} 击杀！</div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { useRouter } from 'vue-router'
import http from '../api/http'
import confetti from 'canvas-confetti'
import { startRemote, stopRemote, onRemoteCmd } from '../composables/useRemote'

const router = useRouter()
/** 退出大屏回工作台（菜单 /boss 为全屏独立页，需自带出口） */
function goBack() { router.push('/dashboard') }

/** 今日 Boss 整体状态（/boss/today：boss 全量 + 当前题 + 答题人 + stats；不含答案——防大屏泄题） */
const state = ref(null)
/** Boss emoji 元素引用（扣血抖动动画手动重播用） */
const emojiEl = ref(null)
/** 扣血飘字文案与重播 key（如 -10） */
const hurtText = ref('')
const hurtKey = ref(0)
/** Boss 爆炸动画（击杀后保持消失态，直到新 Boss 出现） */
const bossDie = ref(false)
/** 击杀者姓名 + 全屏播报开关 */
const killName = ref('')
const killBanner = ref(false)
/** 抽人高亮脉冲 */
const pickPulseOn = ref(false)

/** 击杀演出只播一次（SSE boss.killed 与轮询发现 KILLED 双通道防重） */
let killPlayed = false
/** 首次加载完成标记（首次进入已 KILLED 不补播演出） */
let inited = false
let offCmd = null
let pollTimer = null
let pickTimer = null

/** HP 百分比（0~100） */
const hpPct = computed(() => {
  const s = state.value
  if (!s || !s.hp_total) return 0
  return Math.max(0, Math.round((s.hp_left / s.hp_total) * 100))
})
const stats = computed(() => (state.value && state.value.stats) || {})
const stars = computed(() => stats.value.stars || [])
/** 头部状态角标文案 */
const stateText = computed(() => {
  const st = state.value && state.value.status
  if (st === 'KILLED') return '已击败'
  if (st === 'ESCAPED') return '已逃跑'
  return '挑战中'
})
/** 题目卡文案：进行中显示题目（无题显示等待），结束态显示收尾文案 */
const qText = computed(() => {
  const s = state.value
  if (!s) return ''
  if (s.status === 'KILLED') return '🎉 今日怪兽已被击败，挑战结束！'
  if (s.status === 'ESCAPED') return '💨 怪兽逃跑啦，等老师开新挑战'
  return s.question || '⏳ 等待老师出题…'
})

/** 拉取今日状态（进入页面 + 15s 轮询兜底；首次进入已 KILLED 不播击杀演出） */
async function loadToday() {
  try {
    const d = await http.get('/boss/today')
    if (!inited) {
      inited = true
      killPlayed = d ? d.status === 'KILLED' : false
      state.value = d
      return
    }
    applyState(d)
  } catch { /* 网络抖动静默，等下一轮轮询 */ }
}

/** 应用新状态：HP 下降触发扣血演出；运行中发现 KILLED 补播击杀（SSE 丢失兜底） */
function applyState(s) {
  if (!s) { state.value = null; return }
  const old = state.value
  if (old && old.id !== s.id) {          // 新 Boss（次日重开）：重置击杀演出态
    bossDie.value = false
    killPlayed = s.status === 'KILLED'
  }
  if (old && old.id === s.id && Number(s.hp_left) < Number(old.hp_left)) {
    triggerHurt(s.hit_per_pass || Number(old.hp_left) - Number(s.hp_left))
  }
  state.value = s
  if (s.status === 'KILLED' && (!old || old.status !== 'KILLED')) {
    playKilled((s.stats && s.stats.killStudentName) || '勇士')
  }
}

/** 扣血演出：emoji 抖动重播 + 飘 -N 数字 */
function triggerHurt(dmg) {
  hurtKey.value++
  hurtText.value = dmg
  if (emojiEl.value) {
    emojiEl.value.classList.remove('hurt')
    void emojiEl.value.offsetWidth   // 强制重排，保证连续扣血时抖动动画重播
    emojiEl.value.classList.add('hurt')
  }
}

/** 击杀演出：Boss 爆炸 + 金雨 + 全屏播报 3 秒 */
function playKilled(name) {
  if (killPlayed) return
  killPlayed = true
  killName.value = name
  bossDie.value = true
  killBanner.value = true
  fireConfetti(true)
  setTimeout(() => { killBanner.value = false }, 3000)
}

/** 三发金色彩带雨（Radish 同款模式） */
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

onMounted(() => {
  loadToday()
  // 独立路由不经 MainLayout，SSE 通道需自建（同 /m 手机页模式）
  startRemote()
  offCmd = onRemoteCmd((action, payload) => {
    if (action === 'boss.state') applyState(payload)
    else if (action === 'boss.pick') {
      // 抽人即时高亮（下一次 boss.state 会带回同一数据，这里先本地更新）
      if (state.value) {
        state.value.pickedStudentName = payload.name
        state.value.pickedStudentId = payload.studentId
      }
      pickPulseOn.value = true
      clearTimeout(pickTimer)
      pickTimer = setTimeout(() => { pickPulseOn.value = false }, 800)
    } else if (action === 'boss.killed') {
      playKilled(payload.killStudentName || '勇士')
    }
  })
  // 15s 轮询兜底（SSE 断连时大屏仍能追上进度）
  pollTimer = setInterval(() => { if (!document.hidden) loadToday() }, 15000)
})

onBeforeUnmount(() => {
  offCmd?.()
  stopRemote()
  clearInterval(pollTimer)
  clearTimeout(pickTimer)
})
</script>

<style scoped>
.boss-stage {
  position: fixed; inset: 0; overflow: hidden; user-select: none;
  background: linear-gradient(135deg, #1a1a2e 0%, #16213e 50%, #0f3460 100%);
  font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
  color: #fff;
}

/* 返回工作台按钮（全屏独立页的出口；半透明玻璃不抢戏，等待态/战斗态均常驻右上角） */
.boss-exit {
  position: fixed; top: 16px; right: 22px; z-index: 40;
  background: rgba(255,255,255,.12); color: rgba(255,255,255,.9);
  border: 1px solid rgba(255,255,255,.28); border-radius: 999px;
  padding: 9px 20px; font-size: 16px; font-weight: 700; cursor: pointer;
  backdrop-filter: blur(6px); transition: background .15s, color .15s;
}
.boss-exit:hover { background: rgba(255,255,255,.26); color: #fff; }

/* 等待态封面 */
.wait-cover {
  position: absolute; inset: 0; z-index: 10;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.wait-emoji { font-size: 140px; margin-bottom: 24px; animation: float 3s ease-in-out infinite; }
.wait-title { font-size: 56px; font-weight: 900; color: #ffc107; text-shadow: 0 4px 24px rgba(0,0,0,.5); }
.wait-sub { font-size: 26px; color: rgba(255,255,255,.65); margin-top: 16px; }

/* 顶部标题条 */
.boss-header {
  position: absolute; top: 0; left: 0; right: 0; z-index: 20;
  display: flex; align-items: center; gap: 20px;
  /* 右 padding 加大：给右上角常驻「返回工作台」按钮腾位，避免压住状态胶囊 */
  padding: 18px 160px 18px 32px; background: rgba(0,0,0,.45); backdrop-filter: blur(8px);
}
.bh-badge { font-size: 22px; font-weight: 800; color: #ffc107; }
.bh-name { font-size: 22px; font-weight: 700; color: rgba(255,255,255,.9); }
.bh-state { margin-left: auto; font-size: 18px; padding: 4px 16px; border-radius: 999px;
  background: rgba(255,193,7,.18); color: #ffc107; }

/* 主舞台左右分栏 */
.boss-body {
  height: 100%; display: flex; align-items: center; justify-content: center;
  gap: 90px; padding: 90px 60px 40px; box-sizing: border-box;
}
.boss-zone { position: relative; display: flex; flex-direction: column; align-items: center; min-width: 480px; }
.info-zone { display: flex; flex-direction: column; gap: 26px; width: 660px; }

/* Boss 形象：摆动 / 扣血抖动 / 击杀爆炸（均为 transform/opacity） */
.boss-emoji {
  font-size: 220px; line-height: 1.2;
  filter: drop-shadow(0 24px 48px rgba(0,0,0,.55));
  animation: swing 2.6s ease-in-out infinite;
}
@keyframes swing {
  0%, 100% { transform: rotate(-5deg) translateY(0); }
  50% { transform: rotate(5deg) translateY(-14px); }
}
.boss-emoji.hurt { animation: shake .45s ease; }
@keyframes shake {
  0%, 100% { transform: translateX(0); }
  20% { transform: translateX(-18px) rotate(-4deg); }
  40% { transform: translateX(16px) rotate(4deg); }
  60% { transform: translateX(-12px); }
  80% { transform: translateX(10px); }
}
.boss-emoji.die { animation: bossDie .7s ease forwards; }
@keyframes bossDie {
  0% { transform: scale(1); opacity: 1; }
  100% { transform: scale(2.6); opacity: 0; }
}

/* 扣血飘字 */
.hurt-num {
  position: absolute; top: 30px; right: 30px; z-index: 5;
  font-size: 76px; font-weight: 900; color: #ff5252;
  text-shadow: 0 4px 18px rgba(0,0,0,.6);
  animation: hurtFloat 1s ease-out forwards;
}
@keyframes hurtFloat {
  0% { transform: translateY(0) scale(.6); opacity: 0; }
  20% { transform: translateY(-10px) scale(1.15); opacity: 1; }
  100% { transform: translateY(-140px) scale(1); opacity: 0; }
}

/* HP 条 */
.hp-wrap { width: 100%; margin-top: 28px; }
.hp-label { font-size: 30px; font-weight: 800; color: #ff8a80; margin-bottom: 10px; }
.hp-track { height: 30px; border-radius: 15px; background: rgba(255,255,255,.12); overflow: hidden; }
.hp-fill {
  height: 100%; border-radius: 15px;
  background: linear-gradient(90deg, #ff1744, #ff5252);
  box-shadow: 0 0 18px rgba(255,23,68,.55);
  transition: width .6s cubic-bezier(.68,-0.55,.27,1.55);
}
.boss-over {
  margin-top: 24px; font-size: 30px; font-weight: 800; color: #ffc107;
  background: rgba(0,0,0,.4); padding: 12px 32px; border-radius: 14px;
}

/* 题目卡 */
.q-card {
  background: rgba(0,0,0,.45); backdrop-filter: blur(12px);
  border: 1px solid rgba(255,255,255,.15); border-radius: 20px;
  padding: 30px 38px; min-height: 170px;
  display: flex; flex-direction: column; justify-content: center; gap: 12px;
}
.q-cat { font-size: 22px; color: #ffc107; font-weight: 700; }
.q-text { font-size: 40px; font-weight: 800; line-height: 1.4; }
.q-hint { font-size: 18px; color: rgba(255,255,255,.45); }

/* 当前答题人 */
.picker-card {
  display: flex; align-items: center; gap: 24px;
  background: rgba(0,0,0,.45); border-radius: 16px; padding: 20px 32px;
}
.picker-card.pulse { animation: pulse .8s ease; }
@keyframes pulse {
  0% { transform: scale(1); }
  40% { transform: scale(1.06); }
  100% { transform: scale(1); }
}
.pk-label { font-size: 26px; color: rgba(255,255,255,.75); }
.pk-name { font-size: 44px; font-weight: 900; color: #ffc107; text-shadow: 0 0 30px rgba(255,193,7,.4); }

/* 今日战绩 */
.stats-card {
  background: rgba(0,0,0,.45); border-radius: 16px; padding: 20px 32px;
  display: flex; flex-direction: column; gap: 14px;
}
.st-row { display: flex; gap: 36px; font-size: 28px; font-weight: 700; }
.st-pass { color: #69f0ae; }
.st-fail { color: #ff8a80; }
.st-kill { color: #ffc107; }
.st-stars { display: flex; flex-direction: column; gap: 6px; }
.st-star { display: flex; align-items: center; gap: 14px; font-size: 24px; }
.ss-medal { font-size: 26px; }
.ss-name { color: rgba(255,255,255,.9); font-weight: 700; }
.ss-score { color: #69f0ae; font-weight: 800; margin-left: auto; }

/* 击杀全屏播报（Radish banner 同款） */
.banner {
  position: absolute; inset: 0; z-index: 30;
  display: flex; align-items: center; justify-content: center; pointer-events: none;
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

@keyframes float { 0%,100% { transform: translateY(0) } 50% { transform: translateY(-20px) } }
</style>
