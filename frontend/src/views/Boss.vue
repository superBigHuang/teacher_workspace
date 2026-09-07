<template>
  <div class="boss-stage">
    <!-- 无 Boss：等待老师在大屏直接开始 -->
    <div v-if="!state" class="wait-cover">
      <div class="wait-emoji">👾</div>
      <div class="wait-title">今日还没有怪兽</div>
      <div class="wait-sub">选好题库范围，点「开始挑战」——学生答题通关扣血，HP 归零全班庆祝加分</div>
      <div class="start-form">
        <select v-model="form.categoryId" class="boss-select">
          <option :value="null">全部分类</option>
          <option v-for="c in cats" :key="c.id" :value="c.id">{{ (c.icon || '📚') + ' ' + c.name }}</option>
        </select>
        <select v-model="form.difficulty" class="boss-select">
          <option :value="null">全部难度</option>
          <option :value="1">简单</option>
          <option :value="2">中等</option>
          <option :value="3">困难</option>
        </select>
        <button class="ctrl-btn primary big" @click="doStart" :disabled="busy">👾 开始挑战</button>
      </div>
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
          <div ref="emojiEl" class="boss-emoji" :class="{ die: bossDie }">
            <img v-if="isImg && !imgErr" :src="bossImg" class="boss-img" alt="" @error="imgErr = true">
            <span v-else>{{ isImg ? '👾' : state.boss_emoji }}</span>
          </div>
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

    <!-- 大屏操作条：老师直接在大屏点（出题/抽人/判定），不用手机遥控；答案不显示，老师听答判定 -->
    <div v-if="state && state.status === 'ACTIVE'" class="boss-ctrl">
      <button class="ctrl-btn draw" @click="doDraw" :disabled="busy">🎲 出题</button>
      <div class="ctrl-group">
        <button class="ctrl-btn pick" @click="doPick('RANDOM')" :disabled="busy">🎲 随机抽人</button>
        <select v-model="form.studentId" class="boss-select mini"
                @change="form.studentId && doPick('MANUAL')">
          <option :value="null">点名下拉…</option>
          <option v-for="s in students" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
      </div>
      <div class="ctrl-group">
        <button class="ctrl-btn pass" @click="doJudge('PASS')"
                :disabled="busy || !curQuizId || !state.pickedStudentId">✅ 答对 +5</button>
        <button class="ctrl-btn fail" @click="doJudge('FAIL')"
                :disabled="busy || !curQuizId || !state.pickedStudentId">❌ 未答对</button>
      </div>
    </div>

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
import { ElMessage } from 'element-plus'
import http from '../api/http'
import confetti from 'canvas-confetti'
import { startRemote, stopRemote, onRemoteCmd } from '../composables/useRemote'

const router = useRouter()
/** 退出大屏回工作台（菜单 /boss 为全屏独立页，需自带出口） */
function goBack() { router.push('/dashboard') }

// ============ 大屏直接操作（老师在大屏电脑点点点，不用手机遥控） ============
/** 分类/学生下拉数据 */
const cats = ref([])
const students = ref([])
/** 开始与出题共用条件 + 点名下拉选中 */
const form = ref({ categoryId: null, difficulty: null, studentId: null })
/** 当前题 id（draw 响应；答案不落大屏、不渲染，judge 时回传 quizId） */
const curQuizId = ref(null)
/** 操作进行中（防连点） */
const busy = ref(false)

async function loadOptions() {
  if (!cats.value.length) {
    try { cats.value = (await http.get('/quiz/categories')).filter(c => c.enabled !== false && c.enabled !== 0) }
    catch { /* 下拉为空仍可全部分类开局 */ }
  }
  if (!students.value.length) {
    try { students.value = await http.get('/roster/students') } catch { /* 点名不可用时仍可随机抽人 */ }
  }
}

/** 开始今日挑战（幂等：已存在则返回现有 Boss） */
async function doStart() {
  if (busy.value) return
  busy.value = true
  try {
    const r = await http.post('/boss/start', { categoryId: form.value.categoryId, difficulty: form.value.difficulty })
    applyState(r)
    ElMessage.success('今日挑战开始！')
  } catch (e) { ElMessage.error(e?.message || '开始失败') } finally { busy.value = false }
}

/** 出题：响应含答案但大屏只存 id（题目经 loadToday/SSE 显示，答案不渲染） */
async function doDraw() {
  if (busy.value) return
  busy.value = true
  try {
    const r = await http.post('/boss/draw', { categoryId: form.value.categoryId, difficulty: form.value.difficulty })
    curQuizId.value = r.id
    await loadToday()
  } catch (e) { ElMessage.error(e?.message || '出题失败') } finally { busy.value = false }
}

/** 抽人：RANDOM 班主任班随机；MANUAL 用下拉选中学生（选中即抽） */
async function doPick(mode) {
  if (busy.value) return
  const studentId = mode === 'MANUAL' ? form.value.studentId : null
  if (mode === 'MANUAL' && !studentId) { ElMessage.warning('请先在下拉选择学生'); return }
  busy.value = true
  try {
    const r = await http.post('/boss/pick-student', { mode, studentId })
    if (state.value) { state.value.pickedStudentId = r.studentId; state.value.pickedStudentName = r.name }
  } catch (e) { ElMessage.error(e?.message || '抽人失败') } finally { busy.value = false }
}

/** 判定：需已出题(curQuizId)且已抽人；PASS/FAIL 由后端结算扣血/发奖，响应带回整体状态 */
async function doJudge(result) {
  if (busy.value) return
  const studentId = state.value && state.value.pickedStudentId
  if (!curQuizId.value) { ElMessage.warning('请先出题'); return }
  if (!studentId) { ElMessage.warning('请先抽人'); return }
  busy.value = true
  try {
    const r = await http.post('/boss/judge', { studentId, quizId: curQuizId.value, result })
    curQuizId.value = null   // 一题一判，消费后清空
    applyState(r)
    if (r.killed) ElMessage.success((r.killStudentName || '勇士') + ' 击杀怪兽！🎉')
  } catch (e) { ElMessage.error(e?.message || '判定失败') } finally { busy.value = false }
}

/** 今日 Boss 整体状态（/boss/today：boss 全量 + 当前题 + 答题人 + stats；不含答案——防大屏泄题） */
const state = ref(null)
/** 怪兽图片加载失败标记（回退 emoji 👾）；新 Boss 时重置 */
const imgErr = ref(false)
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

/** 是否为图片怪兽：boss_emoji 存图片编号 "1"~"4"（列仅 VARCHAR(8)）；历史 emoji 字符按文本渲染 */
const isImg = computed(() => {
  const e = state.value && state.value.boss_emoji
  return typeof e === 'string' && /^[1-9]$/.test(e.trim())
})
/** 图片怪兽地址：编号拼 /boss/monster{n}.png（frontend/public/boss/） */
const bossImg = computed(() => `/boss/monster${String(state.value.boss_emoji).trim()}.png`)
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
    imgErr.value = false
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
  loadOptions()
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

/* 大屏操作条（老师直接在大屏点：出题/抽人/判定） */
.boss-ctrl {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 25;
  display: flex; align-items: center; justify-content: center; gap: 30px; flex-wrap: wrap;
  padding: 16px 24px; background: rgba(0,0,0,.52); backdrop-filter: blur(8px);
  border-top: 1px solid rgba(255,255,255,.14);
}
.ctrl-group { display: flex; align-items: center; gap: 12px; }
.ctrl-btn {
  font-size: 26px; font-weight: 800; color: #fff; border: none; border-radius: 14px;
  padding: 14px 30px; cursor: pointer; transition: transform .12s, filter .12s, opacity .12s;
}
.ctrl-btn:hover:not(:disabled) { transform: translateY(-2px); filter: brightness(1.1); }
.ctrl-btn:disabled { opacity: .38; cursor: not-allowed; }
.ctrl-btn.primary { background: linear-gradient(135deg, #7c5cbf, #9b6dd6); box-shadow: 0 8px 26px rgba(124,92,191,.5); }
.ctrl-btn.big { font-size: 30px; padding: 18px 52px; }
.ctrl-btn.draw { background: linear-gradient(135deg, #3a9bd1, #2fa8a0); }
.ctrl-btn.pick { background: linear-gradient(135deg, #f0a030, #ef7d4d); }
.ctrl-btn.pass { background: linear-gradient(135deg, #2e9e5b, #43b581); }
.ctrl-btn.fail { background: linear-gradient(135deg, #d05060, #e06a7a); }
/* 下拉（开始表单 + 点名）：深色玻璃底，option 用深色文字 */
.boss-select {
  font-size: 20px; padding: 12px 16px; border-radius: 12px;
  border: 1px solid rgba(255,255,255,.32); background: rgba(255,255,255,.14); color: #fff;
  cursor: pointer;
}
.boss-select option { color: #222; }
.boss-select.mini { font-size: 18px; padding: 11px 14px; }
/* 等待封面开始表单 */
.start-form { display: flex; gap: 16px; margin-top: 42px; align-items: center; flex-wrap: wrap; justify-content: center; }

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
  gap: 90px; padding: 90px 60px 150px; box-sizing: border-box;   /* 底部 150px 留给操作条 */
}
.boss-zone { position: relative; display: flex; flex-direction: column; align-items: center; min-width: 480px; }
.info-zone { display: flex; flex-direction: column; gap: 26px; width: 660px; }

/* Boss 形象：摆动 / 扣血抖动 / 击杀爆炸（均为 transform/opacity） */
.boss-emoji {
  font-size: 220px; line-height: 1.2;
  filter: drop-shadow(0 24px 48px rgba(0,0,0,.55));
  animation: swing 2.6s ease-in-out infinite;
}
/* 图片怪兽：透明底抠图，高度固定，随父级一起摆动/抖动/爆炸 */
.boss-img { height: 360px; width: auto; max-width: 480px; object-fit: contain; display: block; }
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
