<template>
  <div class="boss-stage">
    <!-- ============ 战场大厅：新建 / 继续 / 战报 ============ -->
    <div v-if="view === 'list'" class="lobby">
      <button class="boss-exit" @click="goBack">← 返回工作台</button>
      <div class="lobby-head">
        <div class="lobby-title">👾 打怪兽</div>
        <button class="ctrl-btn primary" @click="openCreate">➕ 新建战场</button>
      </div>
      <div class="session-list">
        <div v-for="s in sessions" :key="s.id" class="session-card">
          <div class="sc-main">
            <img v-if="isImgCode(s.bossEmoji)" :src="monsterImg(s.bossEmoji)" class="sc-monster" alt="">
            <div v-else class="sc-monster emoji">{{ s.bossEmoji || '👾' }}</div>
            <div class="sc-info">
              <div class="sc-title">{{ s.title }}</div>
              <div class="sc-meta">
                <span class="sc-mode">{{ modeLabel(s.mode) }}</span>
                <span class="sc-pf">✅{{ s.passCnt }} ❌{{ s.failCnt }}</span>
                <span class="sc-time">{{ fmtTime(s.createdAt) }}</span>
              </div>
            </div>
          </div>
          <div class="sc-side">
            <span class="sc-status" :class="String(s.status).toLowerCase()">{{ statusLabel(s.status) }}</span>
            <button v-if="s.status === 'ACTIVE'" class="ctrl-btn primary small" @click="enterBattle(s.id)">继续战斗</button>
            <button v-else class="ctrl-btn small ghost" @click="openReport(s.id)">查看战报</button>
          </div>
        </div>
        <div v-if="!sessions.length" class="lobby-empty">还没有战场，点「新建战场」开始吧</div>
      </div>
    </div>

    <!-- ============ 战斗大屏 ============ -->
    <template v-else>
      <header class="boss-header">
        <span class="bh-badge">👾 {{ state.modeCn || '打怪兽' }}</span>
        <span class="bh-name">{{ state.title }}</span>
        <span class="bh-round" v-if="state.status === 'ACTIVE'">{{ progressText }}</span>
        <span class="bh-state">{{ stateText }}</span>
      </header>

      <div class="boss-body">
        <!-- 左区：Boss 形象 + HP 条（怪兽靠左） -->
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
          <div v-else-if="state.status === 'ESCAPED'" class="boss-over">💨 怪兽逃跑啦</div>
        </div>

        <!-- 中区：题目卡 / 当前答题人 / 战绩（小组模式为小组前三榜） -->
        <div class="info-zone">
          <div class="q-card">
            <div v-if="state.question && state.status === 'ACTIVE'" class="q-cat">
              {{ state.categoryIcon || '📖' }} {{ state.categoryName }}
            </div>
            <div class="q-text">{{ qText }}</div>
          </div>
          <div class="picker-card" :class="{ pulse: pickPulseOn }">
            <span class="pk-label">👉 当前答题</span>
            <span class="pk-name">{{ state.pickedStudentName || (state.mode === 'MANUAL' ? '请点名 👇' : '— 等待 —') }}</span>
          </div>
          <div class="stats-card">
            <div class="st-row">
              <span class="st-pass">✅ ×{{ stats.pass || 0 }}</span>
              <span class="st-fail">❌ ×{{ stats.fail || 0 }}</span>
              <span v-if="!isGroup" class="st-kill">🏆 击杀王：{{ stats.killStudentName || '—' }}</span>
              <span v-else class="st-kill">👥 第 {{ progressRound }} 组 / 共 {{ groupCount }} 组</span>
            </div>
            <!-- 小组模式：小组分数前三（名次变动有 FLIP 移动，被挤出前三有掉落动画）；其他模式：个人加分前三 -->
            <template v-if="isGroup">
              <div class="grp-title">🏅 小组战绩 · 前三</div>
              <transition-group name="grp" tag="div" class="grp-board">
                <div v-for="g in groupTop3" :key="g.groupCol" class="grp-item"
                     :class="{ cur: g.groupCol === currentGroupCol, killed: g.killed }">
                  <span class="gi-medal">{{ ['🥇', '🥈', '🥉'][g.rank - 1] || g.rank }}</span>
                  <span class="gi-name">{{ g.name }}</span>
                  <span v-if="g.killed" class="gi-crown">👑 已击败</span>
                  <span class="gi-pass">✅ {{ g.pass || 0 }}/{{ g.members }}</span>
                </div>
              </transition-group>
            </template>
            <div v-else-if="stars.length" class="st-stars">
              <div v-for="(s, i) in stars" :key="s.studentId" class="st-star">
                <span class="ss-medal">{{ ['🥇', '🥈', '🥉'][i] }}</span>
                <span class="ss-name">{{ s.name }}</span>
                <span class="ss-score">+{{ s.score }}</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 右区：当前答题学生的宠物（大图，答对时飞向怪兽） -->
        <div class="pet-zone">
          <img v-if="currentPetImg" ref="petEl" :src="currentPetImg" class="big-pet" :class="{ pulse: pickPulseOn }" alt="">
          <div v-else class="big-pet pet-empty">🥚</div>
          <div class="pet-name">{{ state.pickedStudentName || '等待答题' }}</div>
        </div>
      </div>
    </template>

    <!-- 底部操作条：进行中=答对/答错（点名模式带下拉）；结束=战报/返回 -->
    <div v-if="view === 'battle' && state" class="boss-ctrl">
      <template v-if="state.status === 'ACTIVE'">
        <select v-if="state.mode === 'MANUAL'" v-model="form.manualId" class="boss-select mini">
          <option :value="null">点名下拉…</option>
          <option v-for="s in students" :key="s.id" :value="s.id">{{ s.name }}</option>
        </select>
        <button class="ctrl-btn pass" @click="doJudge('PASS')" :disabled="busy || judgeDisabled">✅ 答对 +5</button>
        <button class="ctrl-btn fail" @click="doJudge('FAIL')" :disabled="busy || judgeDisabled">❌ 未答对</button>
      </template>
      <template v-else>
        <button class="ctrl-btn draw" @click="openReport(state.id)">📊 查看战报</button>
        <button class="ctrl-btn pick" @click="backToList">← 返回战场列表</button>
      </template>
    </div>

    <!-- 返回：战斗中回大厅（进行中场不丢失，可从列表继续） -->
    <button v-if="view === 'battle'" class="boss-exit" @click="backToList">← 战场列表</button>

    <!-- ============ 新建战场弹窗 ============ -->
    <div v-if="showCreate" class="modal-mask" @click.self="showCreate = false">
      <div class="modal">
        <div class="modal-title">➕ 新建战场</div>
        <div class="mode-grid">
          <div v-for="m in MODES" :key="m.key" class="mode-card" :class="{ on: form.mode === m.key }" @click="pickMode(m.key)">
            <div class="mode-emoji">{{ m.emoji }}</div>
            <div class="mode-name">{{ m.name }}</div>
            <div class="mode-desc">{{ m.desc }}</div>
          </div>
        </div>
        <label class="fld"><span>标题</span>
          <input v-model="form.title" class="fld-input" placeholder="给这场起个名">
        </label>
        <label class="fld"><span>题库</span>
          <select v-model="form.categoryId" class="fld-input">
            <option :value="null">🎤 抽背（无题，老师口头布置）</option>
            <option v-for="c in cats" :key="c.id" :value="c.id">{{ (c.icon || '📚') + ' ' + c.name }}</option>
          </select>
        </label>
        <label v-if="form.categoryId" class="fld"><span>难度</span>
          <select v-model="form.difficulty" class="fld-input">
            <option :value="null">全部难度</option>
            <option :value="1">简单</option>
            <option :value="2">中等</option>
            <option :value="3">困难</option>
          </select>
        </label>
        <div class="modal-tip" v-if="form.mode === 'GROUP'">👥 小组按座位表的「列」划分，请先在座次表排好座位</div>
        <div class="modal-actions">
          <button class="m-btn" @click="showCreate = false">取消</button>
          <button class="m-btn primary" :disabled="busy || !form.mode" @click="doStart">👾 开始挑战</button>
        </div>
      </div>
    </div>

    <!-- ============ 战报弹窗 ============ -->
    <div v-if="report" class="modal-mask" @click.self="report = null">
      <div class="modal wide">
        <div class="modal-title">📊 战报 · {{ report.title }}</div>
        <div class="rp-meta">
          <span>{{ report.modeCn }}</span>
          <span>✅ {{ report.stats.pass || 0 }} · ❌ {{ report.stats.fail || 0 }}</span>
          <span v-if="report.stats.killStudentName">🏆 击杀王：{{ report.stats.killStudentName }}</span>
          <span :class="'rp-st ' + String(report.status).toLowerCase()">{{ statusLabel(report.status) }}</span>
        </div>
        <div v-if="report.mode === 'GROUP' && report.groups && report.groups.length" class="rp-groups">
          <div class="rp-sub">🏅 小组战绩（整组答对即击败本组怪兽，组员加分）</div>
          <div v-for="g in report.groups" :key="g.groupCol" class="rp-group" :class="{ win: g.killed }">
            <span class="rg-rank">{{ g.killed ? '👑' : (['🥇','🥈','🥉'][g.rank-1] || g.rank) }}</span>
            <span class="rg-name">{{ g.name }}</span>
            <span class="rg-stat">答对 {{ g.pass || 0 }} / {{ g.members }} 人{{ g.killed ? ' · 已击败' : '' }}</span>
          </div>
        </div>
        <div class="rp-sub">📝 答题明细</div>
        <div class="rp-hits">
          <div v-for="h in report.hits" :key="h.id" class="rp-hit">
            <span class="rh-r">{{ h.result === 'PASS' ? '✅' : '❌' }}</span>
            <span class="rh-name">{{ h.studentName }}</span>
            <span class="rh-q">{{ h.question || '🎤 抽背' }}</span>
            <span v-if="h.groupCol" class="rh-g">第{{ h.groupCol }}组</span>
          </div>
          <div v-if="!report.hits.length" class="lobby-empty">本场还没有答题记录</div>
        </div>
        <div class="modal-actions">
          <button class="m-btn primary" @click="report = null">关闭</button>
        </div>
      </div>
    </div>

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
import { petImgOf } from '../composables/usePets'

const router = useRouter()
function goBack() { router.push('/dashboard') }

// ============ 模式定义 ============
const MODES = [
  { key: 'RANDOM', name: '随机模式', emoji: '🎲', desc: '随机抽学生，答对答错都自动换人，答对加分' },
  { key: 'CLASS', name: '班级模式', emoji: '🏫', desc: '按座位一列一列挨个答，人人都叫到，一轮全对触发「团结就是力量」' },
  { key: 'GROUP', name: '小组模式', emoji: '👥', desc: '一组一怪：按座位列分组，怪血量=组员数，整组答对击杀本组怪、组员加分' },
  { key: 'MANUAL', name: '点名模式', emoji: '👉', desc: '老师手动点名学生，不自动换人' },
]
const MODE_LABEL = Object.fromEntries(MODES.map(m => [m.key, m.name]))
const modeLabel = k => MODE_LABEL[k] || '挑战'
const statusLabel = s => ({ ACTIVE: '进行中', KILLED: '已击败', ESCAPED: '已逃跑' }[s] || s)

// ============ 视图与数据 ============
const view = ref('list')           // list | battle
const sessions = ref([])
const state = ref(null)
const report = ref(null)
const showCreate = ref(false)
const cats = ref([])
const students = ref([])
const petMap = ref(new Map())      // studentId -> 宠物行（/pet/pets）
const form = ref({ mode: 'RANDOM', title: '', categoryId: null, difficulty: null, manualId: null })
const busy = ref(false)

// ============ 怪兽图 / 进度 / 文案 ============
const isImgCode = e => typeof e === 'string' && /^[1-9]$/.test(String(e).trim())
const monsterImg = e => `/boss/monster${String(e).trim()}.png`
const isImg = computed(() => isImgCode(state.value && state.value.boss_emoji))
const bossImg = computed(() => monsterImg(state.value.boss_emoji))
const imgErr = ref(false)
const hpPct = computed(() => {
  const s = state.value
  if (!s || !s.hp_total) return 0
  return Math.max(0, Math.round((s.hp_left / s.hp_total) * 100))
})
const stats = computed(() => (state.value && state.value.stats) || {})
const stars = computed(() => stats.value.stars || [])
const isGroup = computed(() => state.value && state.value.mode === 'GROUP')
const groupTop3 = computed(() => (state.value && state.value.groupTop3) || [])
const currentGroupCol = computed(() => {
  const s = state.value
  if (!s) return null
  return s.groupCol ?? (s.progress && s.progress.groupCol) ?? null
})
const progressRound = computed(() => (state.value && state.value.progress && state.value.progress.round) || 0)
const groupCount = computed(() => (state.value && state.value.progress && state.value.progress.groupCount) || 0)
const stateText = computed(() => statusLabel(state.value && state.value.status))
const progressText = computed(() => {
  const p = state.value && state.value.progress
  if (!p) return ''
  const mode = state.value.mode
  if (mode === 'GROUP') return `第 ${p.round} 组 · 组内 ${p.done}/${p.total}`
  if (mode === 'CLASS') return `第 ${p.round} 轮 · ${p.done}/${p.total}`
  return `已挑战 ${p.done}/${p.total} 人次`
})
const qText = computed(() => {
  const s = state.value
  if (!s) return ''
  if (s.status === 'KILLED') return '🎉 怪兽已被击败，挑战结束！'
  if (s.status === 'ESCAPED') return '💨 怪兽逃跑啦'
  if (s.question) return s.question
  return s.category_id ? '⏳ 准备中…' : '🎤 抽背中：老师口头出题，学生作答'
})
/** 当前答题学生的宠物图（未领养显示蛋） */
const currentPetImg = computed(() => {
  const sid = state.value && state.value.pickedStudentId
  if (!sid) return ''
  return petImgOf(petMap.value.get(sid))
})
/** 判定按钮是否禁用：自动模式需已抽到人；点名模式需选中人 */
const judgeDisabled = computed(() => {
  const s = state.value
  if (!s) return true
  if (s.mode === 'MANUAL') return !form.value.manualId
  return !s.pickedStudentId
})

// ============ 大厅：列表 / 新建 / 进入 / 战报 ============
async function loadSessions() {
  try { sessions.value = await http.get('/boss/sessions') } catch { /* 静默留空 */ }
}
function openCreate() {
  form.value = { mode: 'RANDOM', title: defaultTitle('RANDOM'), categoryId: null, difficulty: null, manualId: null }
  showCreate.value = true
}
function pickMode(key) {
  form.value.mode = key
  form.value.title = defaultTitle(key)
}
function defaultTitle(mode) {
  const d = new Date()
  return `${d.getMonth() + 1}月${d.getDate()}日 · ${modeLabel(mode)}`
}
async function doStart() {
  if (busy.value) return
  busy.value = true
  try {
    const r = await http.post('/boss/start', {
      mode: form.value.mode, title: form.value.title,
      categoryId: form.value.categoryId, difficulty: form.value.difficulty,
    })
    showCreate.value = false
    await enterState(r)
    ElMessage.success('挑战开始！')
  } catch (e) { ElMessage.error(e?.message || '开始失败') } finally { busy.value = false }
}
async function enterBattle(bossId) {
  try {
    const s = await http.get('/boss/state', { params: { bossId } })
    if (!s) { ElMessage.warning('场次不存在'); return }
    await enterState(s)
  } catch (e) { ElMessage.error(e?.message || '进入失败') }
}
/** 进入战斗大屏：重置演出态（已结束的不补播击杀动画） */
async function enterState(s) {
  state.value = s
  view.value = 'battle'
  report.value = null
  imgErr.value = false
  bossDie.value = false
  killPlayed = s.status === 'KILLED'
  if (s.status === 'KILLED') killName.value = (s.stats && s.stats.killStudentName) || '勇士'
}
function backToList() {
  view.value = 'list'
  state.value = null
  loadSessions()
}
async function openReport(bossId) {
  try { report.value = await http.get('/boss/report', { params: { bossId } }) }
  catch (e) { ElMessage.error(e?.message || '战报加载失败') }
}
function fmtTime(t) { return t ? String(t).slice(5, 16).replace('-', '/') : '' }

// ============ 战斗操作 ============
async function doJudge(result) {
  if (busy.value || !state.value) return
  const s = state.value
  const isManual = s.mode === 'MANUAL'
  const studentId = isManual ? form.value.manualId : s.pickedStudentId
  if (!studentId) { ElMessage.warning(isManual ? '请先在下拉点名' : '还没轮到学生答题'); return }
  busy.value = true
  try {
    const r = await http.post('/boss/judge', {
      bossId: s.id, studentId, quizId: s.currentQuizId || null, result,
    })
    if (result === 'PASS') flyPet(studentId)   // 宠物飞出攻击
    form.value.manualId = null
    applyState(r)
  } catch (e) { ElMessage.error(e?.message || '判定失败') } finally { busy.value = false }
}

// ============ 状态应用 / 演出 ============
const emojiEl = ref(null)
const petEl = ref(null)
const hurtText = ref('')
const hurtKey = ref(0)
const bossDie = ref(false)
const killName = ref('')
const killBanner = ref(false)
const pickPulseOn = ref(false)
let killPlayed = false
let offCmd = null
let pollTimer = null
let pickTimer = null

function applyState(s) {
  if (!s) return
  const old = state.value
  if (old && old.id !== s.id) { bossDie.value = false; imgErr.value = false; killPlayed = s.status === 'KILLED' }
  if (old && old.id === s.id && Number(s.hp_left) < Number(old.hp_left)) {
    triggerHurt(s.hit_per_pass || Number(old.hp_left) - Number(s.hp_left))
  }
  state.value = s
  if (s.status === 'KILLED' && (!old || old.status !== 'KILLED')) {
    playKilled((s.stats && s.stats.killStudentName) || '勇士')
  }
}
function triggerHurt(dmg) {
  hurtKey.value++
  hurtText.value = dmg
  if (emojiEl.value) {
    emojiEl.value.classList.remove('hurt')
    void emojiEl.value.offsetWidth
    emojiEl.value.classList.add('hurt')
  }
}
function playKilled(name) {
  if (killPlayed) return
  killPlayed = true
  killName.value = name
  bossDie.value = true
  killBanner.value = true
  fireConfetti(true)
  setTimeout(() => { killBanner.value = false }, 3000)
}
function fireConfetti(big = false) {
  const colors = ['#ffc107', '#ff9800', '#ff5722', '#fff']
  const z = 9999
  const count = big ? 200 : 60
  confetti({ particleCount: count, angle: 60, spread: 70, origin: { x: 0, y: 0.85 }, colors, zIndex: z })
  confetti({ particleCount: count, angle: 120, spread: 70, origin: { x: 1, y: 0.85 }, colors, zIndex: z })
  setTimeout(() => confetti({ particleCount: big ? 180 : 90, spread: 110, origin: { y: 0.35 }, colors, zIndex: z }), 260)
}

/**
 * 宠物攻击飞行：答题学生宠物从右侧大宠物中心飞向怪兽中心（fixed + transform，~0.7s）。
 * 照 EvolutionShow 飞行模式：双 rAF 触发过渡，结束移除 DOM。未领养显示蛋。
 */
function flyPet(studentId) {
  const p = petMap.value.get(studentId)
  const src = petEl.value
  if (!p || !src || !emojiEl.value) return
  const r1 = src.getBoundingClientRect()
  const r2 = emojiEl.value.getBoundingClientRect()
  const img = document.createElement('img')
  img.src = petImgOf(p)
  const size = 64
  img.style.cssText = `position:fixed;left:${r1.left + r1.width / 2 - size / 2}px;top:${r1.top + r1.height / 2 - size / 2}px;` +
    `width:${size}px;height:${size}px;object-fit:contain;z-index:9998;pointer-events:none;` +
    `transition:transform .7s cubic-bezier(.45,-.15,.35,1.15),opacity .7s;will-change:transform;`
  document.body.appendChild(img)
  const dx = (r2.left + r2.width / 2) - (r1.left + r1.width / 2)
  const dy = (r2.top + r2.height / 2) - (r1.top + r1.height / 2)
  requestAnimationFrame(() => requestAnimationFrame(() => {
    img.style.transform = `translate(${dx}px,${dy}px) scale(1.25) rotate(20deg)`
  }))
  setTimeout(() => img.remove(), 720)
}

// ============ 下拉数据 ============
async function loadOptions() {
  if (!cats.value.length) {
    try { cats.value = (await http.get('/quiz/categories')).filter(c => c.enabled !== false && c.enabled !== 0) } catch { /* 空仍可抽背 */ }
  }
  if (!students.value.length) {
    try { students.value = await http.get('/roster/students') } catch { /* 点名不可用 */ }
  }
  if (!petMap.value.size) {
    try {
      const d = await http.get('/pet/pets')
      const list = d?.pets || d || []
      petMap.value = new Map(list.map(p => [p.studentId, p]))
    } catch { /* 无宠物数据不阻断 */ }
  }
}

onMounted(() => {
  loadSessions()
  loadOptions()
  startRemote()
  offCmd = onRemoteCmd((action, payload) => {
    if (view.value !== 'battle' || !state.value) return
    if (action === 'boss.state' && payload.id === state.value.id) applyState(payload)
    else if (action === 'boss.pick' && (!payload.bossId || payload.bossId === state.value.id)) {
      state.value.pickedStudentName = payload.name
      state.value.pickedStudentId = payload.studentId
      pickPulseOn.value = true
      clearTimeout(pickTimer)
      pickTimer = setTimeout(() => { pickPulseOn.value = false }, 800)
    } else if (action === 'boss.killed' && (!payload.bossId || payload.bossId === state.value.id)) {
      playKilled(payload.killStudentName || '勇士')
    }
  })
  // 15s 轮询兜底（只在战斗中追当前场）
  pollTimer = setInterval(() => {
    if (document.hidden || view.value !== 'battle' || !state.value) return
    http.get('/boss/state', { params: { bossId: state.value.id } })
      .then(s => { if (s && view.value === 'battle') applyState(s) }).catch(() => {})
  }, 15000)
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

.boss-exit {
  position: fixed; top: 16px; right: 22px; z-index: 40;
  background: rgba(255,255,255,.12); color: rgba(255,255,255,.9);
  border: 1px solid rgba(255,255,255,.28); border-radius: 999px;
  padding: 9px 20px; font-size: 16px; font-weight: 700; cursor: pointer;
  backdrop-filter: blur(6px); transition: background .15s, color .15s;
}
.boss-exit:hover { background: rgba(255,255,255,.26); color: #fff; }

/* ---------- 战场大厅 ---------- */
.lobby { position: absolute; inset: 0; overflow-y: auto; padding: 70px 40px 40px; box-sizing: border-box; }
.lobby-head { display: flex; align-items: center; justify-content: space-between; max-width: 900px; margin: 0 auto 28px; }
.lobby-title { font-size: 40px; font-weight: 900; color: #ffc107; text-shadow: 0 4px 20px rgba(0,0,0,.5); }
.session-list { max-width: 900px; margin: 0 auto; display: flex; flex-direction: column; gap: 16px; }
.session-card {
  display: flex; align-items: center; justify-content: space-between;
  background: rgba(255,255,255,.08); border: 1px solid rgba(255,255,255,.14);
  border-radius: 18px; padding: 18px 24px; backdrop-filter: blur(8px);
}
.sc-main { display: flex; align-items: center; gap: 20px; }
.sc-monster { width: 64px; height: 64px; object-fit: contain; }
.sc-monster.emoji { font-size: 48px; display: flex; align-items: center; justify-content: center; }
.sc-title { font-size: 26px; font-weight: 800; color: #fff; }
.sc-meta { display: flex; gap: 16px; margin-top: 6px; font-size: 17px; color: rgba(255,255,255,.7); }
.sc-mode { color: #ffc107; font-weight: 700; }
.sc-side { display: flex; flex-direction: column; align-items: flex-end; gap: 10px; }
.sc-status { font-size: 16px; font-weight: 700; padding: 3px 14px; border-radius: 999px; }
.sc-status.active { background: rgba(105,240,174,.18); color: #69f0ae; }
.sc-status.killed { background: rgba(255,193,7,.18); color: #ffc107; }
.sc-status.escaped { background: rgba(255,255,255,.12); color: rgba(255,255,255,.6); }
.lobby-empty { text-align: center; color: rgba(255,255,255,.5); font-size: 20px; padding: 60px 0; }

/* ---------- 弹窗 ---------- */
.modal-mask {
  position: fixed; inset: 0; z-index: 60; background: rgba(0,0,0,.6);
  display: flex; align-items: center; justify-content: center; padding: 24px;
}
.modal {
  background: #fff; color: #333; border-radius: 22px; padding: 30px 34px;
  width: 640px; max-width: 96vw; max-height: 90vh; overflow-y: auto;
  box-shadow: 0 24px 70px rgba(0,0,0,.5);
}
.modal.wide { width: 720px; }
.modal-title { font-size: 26px; font-weight: 900; margin-bottom: 20px; color: #2a2a3a; }
.mode-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin-bottom: 22px; }
.mode-card {
  border: 2px solid #e6e6f0; border-radius: 16px; padding: 16px; cursor: pointer;
  transition: border-color .15s, background .15s;
}
.mode-card:hover { border-color: #b9a7e6; }
.mode-card.on { border-color: #7c5cbf; background: #f3eefc; }
.mode-emoji { font-size: 34px; }
.mode-name { font-size: 19px; font-weight: 800; margin: 6px 0 4px; color: #2a2a3a; }
.mode-desc { font-size: 13px; color: #7a7a8a; line-height: 1.5; }
.fld { display: flex; align-items: center; gap: 14px; margin-bottom: 14px; }
.fld > span { width: 48px; font-weight: 700; color: #5a5a6a; flex-shrink: 0; }
.fld-input {
  flex: 1; font-size: 16px; padding: 11px 14px; border: 2px solid #e6e6f0;
  border-radius: 12px; outline: none; background: #fff; color: #333; box-sizing: border-box;
}
.fld-input:focus { border-color: #7c5cbf; }
.modal-tip { font-size: 14px; color: #b07a2a; background: #fff7e8; border-radius: 10px; padding: 10px 14px; margin-bottom: 16px; }
.modal-actions { display: flex; justify-content: flex-end; gap: 12px; margin-top: 8px; }
.m-btn {
  font-size: 17px; font-weight: 800; padding: 11px 26px; border-radius: 12px;
  border: 2px solid #ddd; background: #fff; color: #555; cursor: pointer;
}
.m-btn.primary { background: linear-gradient(135deg, #7c5cbf, #9b6dd6); border-color: transparent; color: #fff; }
.m-btn:disabled { opacity: .5; cursor: not-allowed; }

/* ---------- 战报 ---------- */
.rp-meta { display: flex; flex-wrap: wrap; gap: 18px; font-size: 17px; font-weight: 700; color: #555; margin-bottom: 18px; }
.rp-st { padding: 2px 12px; border-radius: 999px; }
.rp-st.killed { background: #fff3d0; color: #b07a2a; }
.rp-st.active { background: #e2f8ec; color: #2e9e5b; }
.rp-st.escaped { background: #eee; color: #888; }
.rp-sub { font-size: 17px; font-weight: 800; color: #2a2a3a; margin: 16px 0 10px; }
.rp-groups { display: flex; flex-direction: column; gap: 8px; margin-bottom: 8px; }
.rp-group { display: flex; align-items: center; gap: 14px; background: #f6f6fa; border-radius: 12px; padding: 10px 16px; font-size: 16px; }
.rp-group.win { background: #fff3d0; font-weight: 800; }
.rg-rank { font-size: 22px; }
.rg-name { font-weight: 800; color: #2a2a3a; }
.rg-stat { margin-left: auto; color: #666; }
.rp-hits { max-height: 300px; overflow-y: auto; display: flex; flex-direction: column; gap: 6px; }
.rp-hit { display: flex; align-items: center; gap: 12px; font-size: 15px; color: #444; background: #fafafe; border-radius: 10px; padding: 8px 14px; }
.rh-name { font-weight: 800; min-width: 70px; }
.rh-q { color: #777; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rh-g { margin-left: auto; color: #b07a2a; font-weight: 700; flex-shrink: 0; }

/* ---------- 战斗大屏 ---------- */
.boss-ctrl {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 25;
  display: flex; align-items: center; justify-content: center; gap: 30px; flex-wrap: wrap;
  padding: 16px 24px; background: rgba(0,0,0,.52); backdrop-filter: blur(8px);
  border-top: 1px solid rgba(255,255,255,.14);
}
.ctrl-btn {
  font-size: 26px; font-weight: 800; color: #fff; border: none; border-radius: 14px;
  padding: 14px 30px; cursor: pointer; transition: transform .12s, filter .12s, opacity .12s;
}
.ctrl-btn:hover:not(:disabled) { transform: translateY(-2px); filter: brightness(1.1); }
.ctrl-btn:disabled { opacity: .38; cursor: not-allowed; }
.ctrl-btn.small { font-size: 16px; padding: 9px 18px; }
.ctrl-btn.primary { background: linear-gradient(135deg, #7c5cbf, #9b6dd6); box-shadow: 0 8px 26px rgba(124,92,191,.5); }
.ctrl-btn.ghost { background: rgba(255,255,255,.14); }
.ctrl-btn.draw { background: linear-gradient(135deg, #3a9bd1, #2fa8a0); }
.ctrl-btn.pick { background: linear-gradient(135deg, #f0a030, #ef7d4d); }
.ctrl-btn.pass { background: linear-gradient(135deg, #2e9e5b, #43b581); }
.ctrl-btn.fail { background: linear-gradient(135deg, #d05060, #e06a7a); }
.boss-select {
  font-size: 20px; padding: 12px 16px; border-radius: 12px;
  border: 1px solid rgba(255,255,255,.32); background: rgba(255,255,255,.14); color: #fff; cursor: pointer;
}
.boss-select option { color: #222; }
.boss-select.mini { font-size: 18px; padding: 11px 14px; }

.boss-header {
  position: absolute; top: 0; left: 0; right: 0; z-index: 20;
  display: flex; align-items: center; gap: 20px;
  padding: 18px 160px 18px 32px; background: rgba(0,0,0,.45); backdrop-filter: blur(8px);
}
.bh-badge { font-size: 22px; font-weight: 800; color: #ffc107; }
.bh-name { font-size: 22px; font-weight: 700; color: rgba(255,255,255,.9); }
.bh-round { font-size: 18px; font-weight: 700; color: #7fd4ff; background: rgba(127,212,255,.14); padding: 4px 16px; border-radius: 999px; }
.bh-state { margin-left: auto; font-size: 18px; padding: 4px 16px; border-radius: 999px; background: rgba(255,193,7,.18); color: #ffc107; }

.boss-body {
  height: 100%; display: flex; align-items: center; justify-content: space-between;
  gap: 36px; padding: 90px 48px 150px; box-sizing: border-box;
}
.boss-zone { position: relative; display: flex; flex-direction: column; align-items: center; flex: 0 0 auto; }
.info-zone { display: flex; flex-direction: column; gap: 24px; flex: 1 1 auto; min-width: 0; max-width: 680px; }
.pet-zone { display: flex; flex-direction: column; align-items: center; gap: 12px; flex: 0 0 auto; width: 280px; }
.big-pet {
  height: 240px; width: auto; max-width: 280px; object-fit: contain;
  filter: drop-shadow(0 18px 40px rgba(0,0,0,.5));
  animation: petBob 3s ease-in-out infinite;
}
.big-pet.pulse { animation: pulse .8s ease; }
.big-pet.pet-empty { display: flex; align-items: center; justify-content: center; font-size: 160px; }
@keyframes petBob {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-16px); }
}
.pet-name {
  font-size: 32px; font-weight: 900; color: #7fd4ff;
  text-shadow: 0 0 26px rgba(127,212,255,.5); min-height: 40px; text-align: center;
}

.boss-emoji {
  font-size: 220px; line-height: 1.2;
  filter: drop-shadow(0 24px 48px rgba(0,0,0,.55));
  animation: swing 2.6s ease-in-out infinite;
}
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

.q-card {
  background: rgba(0,0,0,.45); backdrop-filter: blur(12px);
  border: 1px solid rgba(255,255,255,.15); border-radius: 20px;
  padding: 30px 38px; min-height: 170px;
  display: flex; flex-direction: column; justify-content: center; gap: 12px;
}
.q-cat { font-size: 22px; color: #ffc107; font-weight: 700; }
.q-text { font-size: 38px; font-weight: 800; line-height: 1.4; }

.picker-card {
  display: flex; align-items: center; gap: 20px;
  background: rgba(0,0,0,.45); border-radius: 16px; padding: 18px 32px;
}
.picker-card.pulse { animation: pulse .8s ease; }
@keyframes pulse {
  0% { transform: scale(1); }
  40% { transform: scale(1.06); }
  100% { transform: scale(1); }
}
.pk-label { font-size: 26px; color: rgba(255,255,255,.75); }
.pk-name { font-size: 42px; font-weight: 900; color: #ffc107; text-shadow: 0 0 30px rgba(255,193,7,.4); }

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

/* 小组前三榜：名次变动 FLIP 平滑移动，被挤出前三掉落淡出 */
.grp-title { font-size: 22px; font-weight: 800; color: #ffc107; }
.grp-board { position: relative; display: flex; flex-direction: column; gap: 8px; }
.grp-item {
  display: flex; align-items: center; gap: 14px; font-size: 24px; font-weight: 800;
  background: rgba(255,255,255,.08); border: 1px solid rgba(255,255,255,.14);
  border-radius: 12px; padding: 10px 18px;
}
.grp-item.cur { border-color: #7fd4ff; background: rgba(127,212,255,.16); box-shadow: 0 0 18px rgba(127,212,255,.3); }
.grp-item.killed { border-color: rgba(255,193,7,.55); }
.gi-medal { font-size: 26px; }
.gi-name { color: #fff; }
.gi-crown { color: #ffc107; font-size: 20px; font-weight: 800; }
.gi-pass { margin-left: auto; color: #69f0ae; font-weight: 800; }
.grp-move { transition: transform .6s cubic-bezier(.4,0,.2,1); }
.grp-enter-active { transition: all .45s ease; }
.grp-leave-active { transition: all .55s cubic-bezier(.5,0,.7,.4); position: absolute; left: 0; right: 0; }
.grp-enter-from { opacity: 0; transform: translateY(-28px) scale(.85); }
.grp-leave-to { opacity: 0; transform: translateY(52px) scale(.8) rotate(-6deg); }

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
</style>
