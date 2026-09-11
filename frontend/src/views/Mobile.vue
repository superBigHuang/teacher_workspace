<template>
  <div class="m-page">
    <!-- ============ 登录卡（无 token 时） ============ -->
    <div v-if="!token" class="m-login">
      <div class="login-logo">🐾</div>
      <div class="login-title">宠物园遥控</div>
      <div class="login-sub">教室投影 · 手机遥控加减分与进化播放</div>
      <input v-model="loginForm.username" class="m-input" placeholder="账号" autocomplete="username"
             @keyup.enter="doLogin" />
      <input v-model="loginForm.password" type="password" class="m-input" placeholder="密码"
             autocomplete="current-password" @keyup.enter="doLogin" />
      <!-- 连续登录失败 3 次后出现：算术图形验证码，点图换一张 -->
      <div v-if="needCaptcha" class="m-captcha">
        <img :src="captchaImg" class="m-captcha-img" title="看不清？点我换一张" @click="loadCaptcha" />
        <input v-model="captchaCode" class="m-input m-captcha-input" placeholder="验证码"
               inputmode="numeric" @keyup.enter="doLogin" />
      </div>
      <button class="m-btn-primary" @click="doLogin">登 录</button>
      <div class="login-tip">与 PC 端同一账号；登录后投影页打开座次表即可遥控</div>
    </div>

    <template v-else>
      <!-- ============ 顶栏 ============ -->
      <header class="m-header">
        <button class="m-back" @click="goBack">← 返回工作台</button>
        <div class="m-title">🐾 宠物遥控</div>
        <button class="m-logout" @click="logout">退出</button>
      </header>

      <!-- ============ 宠物网格（只列已领养） ============ -->
      <main class="m-grid">
        <!-- 打怪兽入口卡片（网格第一格） -->
        <button class="m-pet m-boss-entry" @click="openBoss">
          <div class="boss-entry-emoji">👾</div>
          <div class="m-pet-name">今日打怪兽</div>
          <div class="m-pet-lv">{{ bossEntrySub }}</div>
        </button>
        <button v-for="pet in adoptedPets" :key="pet.studentId" class="m-pet" @click="openSheet(pet)">
          <img :src="p.petImg(pet)" class="m-pet-img" :class="{ frozen: pet.frozen }" alt="" />
          <div class="m-pet-name">{{ pet.name }}</div>
          <div class="m-pet-lv">{{ pet.hatched === 0 ? '🥚 孵化中' : `Lv.${pet.level} · ${pet.petName}` }}</div>
          <div class="m-exp-bar"><div class="m-exp-fill" :style="{ width: (pet.hatched === 0 ? p.hatchPct(pet) : p.expPct(pet)) + '%' }"></div></div>
          <span v-if="pet.starToday" class="m-badge-star">🌈</span>
        </button>
        <div v-if="p && !adoptedPets.length" class="m-empty">还没有领养宠物的学生<br/>请先在 PC 端座次表领养</div>
      </main>

      <!-- ============ 打怪兽控制弹层 ============ -->
      <div v-if="bossOpen" class="m-mask" @click.self="closeBoss">
        <div class="m-sheet">
          <div class="sheet-head">
            <div class="boss-entry-emoji" style="font-size:56px;">{{ bossState.boss_emoji || '👾' }}</div>
            <div class="sheet-id">
              <div class="sheet-name">{{ bossState.boss_name || '每日打怪兽' }}</div>
              <div class="sheet-lv">{{ bossHeadSub }}</div>
            </div>
            <button class="boss-close" @click="closeBoss">✕</button>
          </div>

          <!-- 无 Boss：开始面板（分类 + 难度） -->
          <div v-if="!bossState.id" class="boss-panel">
            <div class="boss-tip">创建今日怪兽：答题通关扣血，HP 归零全班庆祝加分</div>
            <div class="boss-form-row">
              <select v-model="bossForm.categoryId" class="boss-select">
                <option :value="null">全部分类</option>
                <option v-for="c in bossCats" :key="c.id" :value="c.id">{{ (c.icon || '📚') + ' ' + c.name }}</option>
              </select>
              <select v-model="bossForm.difficulty" class="boss-select">
                <option :value="null">全部难度</option>
                <option :value="1">简单</option>
                <option :value="2">中等</option>
                <option :value="3">困难</option>
              </select>
            </div>
            <button class="boss-btn start" @click="bossStart">👾 开始挑战</button>
          </div>

          <!-- 有 Boss：控制面板（出题→抽人→判定） -->
          <template v-else>
            <!-- HP 条 -->
            <div class="boss-hp-row">
              <div class="boss-hp-track"><div class="boss-hp-fill" :style="{ width: bossHpPct + '%' }"></div></div>
              <span class="boss-hp-num">{{ bossState.hp_left }}/{{ bossState.hp_total }}</span>
            </div>
            <div class="boss-mini-stats">✅ {{ bossState.stats?.pass || 0 }} · ❌ {{ bossState.stats?.fail || 0 }}
              <template v-if="bossState.stats?.killStudentName"> · 🏆 {{ bossState.stats.killStudentName }}</template>
            </div>

            <!-- KILLED/ESCAPED：结束态 -->
            <div v-if="bossState.status !== 'ACTIVE'" class="boss-panel">
              <div class="boss-over-tip">{{ bossState.status === 'KILLED' ? '💀 今天的怪兽已被击败，明天再来！' : '💨 怪兽逃跑啦，明天再来！' }}</div>
            </div>

            <!-- ACTIVE：流程按钮区 -->
            <div v-else class="boss-panel">
              <!-- ① 出题：无当前题时显示 -->
              <template v-if="!bossQuiz">
                <button class="boss-btn draw" @click="bossDraw">🎲 出题</button>
              </template>
              <!-- ② 当前题 + 答案（只给老师） -->
              <template v-else>
                <div class="boss-q">
                  <div class="boss-q-meta">{{ bossQuiz.categoryIcon || '📖' }} {{ bossQuiz.categoryName }} · {{ ['', '简单', '中等', '困难'][bossQuiz.difficulty] || '' }}</div>
                  <div class="boss-q-text">{{ bossQuiz.question }}</div>
                  <div class="boss-q-answer">答案：{{ bossQuiz.answer || '—' }}</div>
                </div>
                <!-- ③ 抽人 -->
                <div v-if="!bossState.pickedStudentId" class="boss-form-row">
                  <button class="boss-btn pick" @click="bossPick('RANDOM')">🎲 随机抽人</button>
                  <select v-model="bossManualId" class="boss-select" @change="bossPick('MANUAL')">
                    <option :value="null">点名…</option>
                    <option v-for="s in bossStudents" :key="s.id" :value="s.id">{{ s.name }}</option>
                  </select>
                </div>
                <!-- ④ 判定 -->
                <template v-else>
                  <div class="boss-answer-name">👉 答题人：<b>{{ bossState.pickedStudentName }}</b></div>
                  <div class="boss-form-row">
                    <button class="boss-btn pass" @click="bossJudge('PASS')">✅ 答对 +{{ bossScoreTip }}</button>
                    <button class="boss-btn fail" @click="bossJudge('FAIL')">❌ 未答对</button>
                  </div>
                </template>
              </template>
            </div>
          </template>
        </div>
      </div>

      <!-- ============ 规则底部弹层 ============ -->
      <div v-if="sheetPet" class="m-mask" @click.self="sheetPet = null">
        <div class="m-sheet">
          <div class="sheet-head">
            <img :src="p.petImg(sheetPet)" class="sheet-img" alt="" />
            <div class="sheet-id">
              <div class="sheet-name">{{ sheetPet.name }}</div>
              <div class="sheet-lv">{{ sheetPet.hatched === 0 ? '🥚 孵化中' : `${sheetPet.petName} · Lv.${sheetPet.level}` }}</div>
            </div>
            <div class="sheet-exp">{{ (sheetPet.hatched === 0 ? p.hatchPct(sheetPet) : p.expPct(sheetPet)) }}%</div>
          </div>
          <div class="sheet-cats">
            <button v-for="c in CATS" :key="c" class="cat-btn"
                    :class="{ active: evalCat === c }" @click="evalCat = c">{{ c }}</button>
          </div>
          <div class="sheet-rules">
            <button v-for="r in p.rulesOfCat(evalCat)" :key="r.id"
                    class="rule-btn" :class="r.score > 0 ? 'good' : 'bad'"
                    :disabled="r.enabled === false || r.enabled === 0"
                    @click="quickRule(r)">
              <span class="rule-icon">{{ r.icon || (r.score > 0 ? '👍' : '👎') }}</span>
              <span class="rule-name">{{ r.name }}</span>
              <span class="rule-score">{{ r.score > 0 ? '+' : '' }}{{ r.score }}</span>
            </button>
          </div>
        </div>
      </div>

      <!-- ============ 待播进化底部条 ============ -->
      <div v-if="heldCount" class="m-held-bar">
        <div class="held-row">
          <button class="held-main" @click="heldOpen = !heldOpen">
            🎬 {{ heldCount }} 只宠物待进化 <span class="held-arrow">{{ heldOpen ? '▾' : '▴' }}</span>
          </button>
          <button class="held-play" @click="playAll">▶️ 全部播放</button>
        </div>
        <div v-if="heldOpen" class="held-list">
          <div v-for="h in heldList" :key="h.studentId" class="held-item">
            <span>{{ h.name }} 的{{ h.petName }} → Lv.{{ h.toLevel }}</span>
            <button class="held-one" @click="playOne(h)">播放</button>
          </div>
        </div>
      </div>

      <!-- ============ 操作反馈 toast（含撤销） ============ -->
      <div v-if="toast" class="m-toast">
        <span>{{ toast.text }}</span>
        <button v-if="toast.canUndo" class="toast-undo" @click="undo">↩️ 撤销</button>
      </div>
    </template>

    <!-- ICP 备案号（登录态/未登录均展示，固定底部） -->
    <a class="m-beian" href="https://beian.miit.gov.cn/" target="_blank" rel="noopener">浙ICP备2026075470号</a>
  </div>
</template>

<script setup>
import { ref, shallowRef, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { usePets, PET_CATS } from '../composables/usePets'
import { startRemote, stopRemote, onRemoteCmd, sendCmd } from '../composables/useRemote'

/** 评价项四个分类 tab */
const CATS = PET_CATS

const token = ref(localStorage.getItem('token'))
const router = useRouter()
/** PC 端从菜单进入全屏遥控页的出口（手机窄屏不显示此按钮，手机上 /m 即主页） */
function goBack() { router.push('/dashboard') }
const loginForm = ref({ username: '', password: '' })
// 验证码（失败 3 次后由后端 needCaptcha 拉起）
const needCaptcha = ref(false)
const captchaImg = ref('')
const captchaId = ref('')
const captchaCode = ref('')

/** 拉取一张算术验证码图（点击图片也可刷新） */
async function loadCaptcha() {
  const d = await http.get('/auth/captcha')
  captchaId.value = d.captchaId
  captchaImg.value = d.img
  captchaCode.value = ''
}

/** usePets() 实例（登录后才初始化，避免未登录发 401 请求被拦截器跳走） */
const p = shallowRef(null)
let P = null

const sheetPet = ref(null)      // 当前打开规则弹层的学生
const evalCat = ref('学习')
const heldOpen = ref(false)
const toast = ref(null)

/* ---------- 打怪兽控制卡状态 ---------- */
/** 弹层开关 */
const bossOpen = ref(false)
/** /boss/today 状态（boss 全量 + stats；ACTIVE 流程靠它驱动） */
const bossState = ref({})
/** 当前题（draw 响应，含答案——只在本页展示，绝不推大屏） */
const bossQuiz = ref(null)
/** 点名抽人的学生 id（select 双用：值与触发） */
const bossManualId = ref(null)
/** 开始面板：分类/难度（null=不过滤） */
const bossForm = ref({ categoryId: null, difficulty: null })
/** 分类下拉数据（GET /quiz/categories） */
const bossCats = ref([])
/** 点名下拉的班级学生（GET /roster/students） */
const bossStudents = ref([])

/** 入口卡片副标题：有 Boss 显示 HP/结束态，无则引导开始 */
const bossEntrySub = computed(() => {
  if (!bossState.value.id) return '👉 点击开始'
  if (bossState.value.status === 'KILLED') return '💀 已击败'
  if (bossState.value.status === 'ESCAPED') return '💨 已逃跑'
  return `HP ${bossState.value.hp_left}/${bossState.value.hp_total}`
})
/** 弹层头部副标题 */
const bossHeadSub = computed(() => {
  if (!bossState.value.id) return '每日一题闯关 · 全班同乐'
  const st = bossState.value.stats || {}
  return `HP ${bossState.value.hp_left}/${bossState.value.hp_total} · ✅${st.pass || 0} ❌${st.fail || 0}`
})
const bossHpPct = computed(() => {
  const s = bossState.value
  if (!s.hp_total) return 0
  return Math.max(0, Math.round((s.hp_left / s.hp_total) * 100))
})
/** 答对按钮分值提示（与后端 setting 默认一致，仅展示用） */
const bossScoreTip = computed(() => (bossState.value.hit_per_pass || 10) + ' 成长值')

const adoptedPets = computed(() => (p.value ? p.value.pets.value.filter(x => x.petId) : []))
const heldList = computed(() => (p.value ? p.value.heldEvolves.value : []))
const heldCount = computed(() => heldList.value.length)

let offCmd = null
let pollTimer = null
let toastTimer = null
let lastOp = null

onMounted(() => {
  if (token.value) boot()
})
onUnmounted(() => teardown())

/** 登录成功/已登录进入：初始化宠物数据 + SSE 遥控通道 + 轮询兜底 */
function boot() {
  if (!P) {
    // 手机页不本地演破壳/进化（shows=false）：pending 进化仍攒待播清单，由手机指挥投影播放
    P = usePets({ shows: false })
    p.value = P
  }
  startRemote()
  offCmd = onRemoteCmd((action) => {
    // 其他端（PC/另一部手机）写操作后广播：静默刷新；pending 进化自动攒入待播列表
    if (action === 'pets.changed') P.loadPets()
  })
  pollTimer = setInterval(() => { if (!document.hidden) P.loadPets() }, 15000)
  document.addEventListener('visibilitychange', onVisible)
}

function onVisible() {
  if (!document.hidden && P) P.loadPets()
}

function teardown() {
  stopRemote()
  offCmd?.()
  clearInterval(pollTimer)
  clearTimeout(toastTimer)
  document.removeEventListener('visibilitychange', onVisible)
}

async function doLogin() {
  const payload = {
    username: loginForm.value.username.trim(),
    password: loginForm.value.password
  }
  if (needCaptcha.value) {
    payload.captchaId = captchaId.value
    payload.captchaCode = captchaCode.value.trim()
  }
  try {
    const r = await http.post('/auth/login', payload)
    needCaptcha.value = false
    localStorage.setItem('token', r.token)
    localStorage.setItem('displayName', r.displayName)
    localStorage.setItem('username', r.username || '')
    token.value = r.token
    loginForm.value.password = ''
    boot()
  } catch (e) {
    // 后端要求验证码（失败达阈值/验证码错误）：拉起并刷新验证码图
    if (e.data?.needCaptcha) {
      needCaptcha.value = true
      loadCaptcha()
    }
  }
}

function logout() {
  teardown()
  P = null
  p.value = null
  localStorage.removeItem('token')
  token.value = null
}

function openSheet(pet) {
  sheetPet.value = pet
  evalCat.value = '学习'
}

/**
 * 快捷评价：defer=true 走手机遥控路径（后端记 REMOTE 来源，
 * 升级置 evolve_pending 攒队列、不立即弹演出），生效后后端广播 pets.changed 刷新 PC。
 */
async function quickRule(rule) {
  const sid = sheetPet.value.studentId
  const r = await http.post('/pet/evaluate', { studentId: sid, ruleId: rule.id, defer: true })
  if (!r.affected) { ElMessage.warning('该学生还未领养宠物'); return }
  navigator.vibrate?.(rule.score > 0 ? 30 : 60)
  lastOp = { sid, ruleName: rule.name, score: rule.score }
  sheetPet.value = null
  toast.value = {
    text: `${rule.score > 0 ? '👍 已加分 +' + rule.score : '😢 已扣分 ' + rule.score} · ${rule.name}`,
    canUndo: true
  }
  clearTimeout(toastTimer)
  toastTimer = setTimeout(() => { toast.value = null; lastOp = null }, 5000)
  P.loadPets()
}

/** 撤销最近一次评价：取该生最新流水撤回，并广播 PC 刷新 */
async function undo() {
  if (!lastOp) return
  try {
    const logs = await http.get('/pet/logs', { params: { studentId: lastOp.sid } })
    if (logs[0]) {
      await http.post('/pet/revoke', { logId: logs[0].id })
      sendCmd('pets.changed', {})
      ElMessage.success('已撤销')
    }
  } catch (e) { /* 撤销失败提示已由拦截器给出 */ }
  toast.value = null
  lastOp = null
  P.loadPets()
}

/** 全部播放：指挥投影端连播待播进化；本地乐观清空待播列表（PC 演完会调 evolve-seen） */
function playAll() {
  sendCmd('evolve.playAll', {})
  P.heldEvolves.value = []
  heldOpen.value = false
  ElMessage.success('已指挥投影播放 🎬')
}

/** 播放指定一条 */
function playOne(h) {
  sendCmd('evolve.play', { studentId: h.studentId })
  P.heldEvolves.value = P.heldEvolves.value.filter(x => x.studentId !== h.studentId)
}

/* ---------- 打怪兽控制卡 ---------- */

/** 打开弹层：拉今日 Boss 状态；分类/学生下拉数据只拉一次（懒加载） */
async function openBoss() {
  bossOpen.value = true
  bossQuiz.value = null
  bossManualId.value = null
  refreshBoss()
  if (!bossCats.value.length) {
    try { bossCats.value = (await http.get('/quiz/categories')).filter(c => c.enabled !== false && c.enabled !== 0) } catch { /* 下拉为空仍可全部分类开局 */ }
  }
  if (!bossStudents.value.length) {
    try { bossStudents.value = await http.get('/roster/students') } catch { /* 点名不可用时仍可随机抽人 */ }
  }
}

/** 关闭弹层：清当前题与点名选择（内存态当前题在后端仍保留，重开可继续判定） */
function closeBoss() {
  bossOpen.value = false
  bossQuiz.value = null
  bossManualId.value = null
}

/** 刷新今日 Boss 状态（null 归一为空对象，驱动 template 的 !bossState.id 分支） */
async function refreshBoss() {
  try {
    const d = await http.get('/boss/today')
    bossState.value = d || {}
  } catch { /* 网络抖动保持原状态，下次操作响应会覆盖 */ }
}

/** 开始挑战：幂等（当日已存在直接返回），成功后进入控制面板 */
async function bossStart() {
  const r = await http.post('/boss/start', {
    categoryId: bossForm.value.categoryId,
    difficulty: bossForm.value.difficulty
  })
  bossState.value = r
  navigator.vibrate?.(30)
}

/** 出题：返回题 + 答案（只在本页展示）；同时刷新 state 带回当前题快照 */
async function bossDraw() {
  const r = await http.post('/boss/draw', {
    categoryId: bossForm.value.categoryId,
    difficulty: bossForm.value.difficulty
  })
  bossQuiz.value = r
  bossManualId.value = null
  refreshBoss()
}

/** 抽人：RANDOM 班级随机 / MANUAL 点名下拉 @change 触发；成功即更新答题人（大屏靠 SSE 高亮） */
async function bossPick(mode) {
  if (mode === 'MANUAL' && !bossManualId.value) return
  const body = mode === 'MANUAL' ? { mode, studentId: bossManualId.value } : { mode }
  const r = await http.post('/boss/pick-student', body)
  bossState.value.pickedStudentId = r.studentId
  bossState.value.pickedStudentName = r.name
  navigator.vibrate?.(30)
}

/**
 * 判定：PASS/FAIL → 后端一题一判；响应为最新 state（击杀时附 killed/killStudentName）。
 * 判定后清当前题回到「出题」步；击杀时震动 + 提示。
 */
async function bossJudge(result) {
  const r = await http.post('/boss/judge', {
    studentId: bossState.value.pickedStudentId,
    quizId: bossQuiz.value.id,
    result
  })
  bossState.value = r
  bossQuiz.value = null
  bossManualId.value = null
  if (r.killed) {
    navigator.vibrate?.(200)
    ElMessage.success(`🎉 ${r.killStudentName} 击杀了怪兽！`)
    P?.loadPets()
  }
}
</script>

<style scoped>
.m-page { min-height: 100vh; background: linear-gradient(180deg, #fff7f0 0%, #f4f8ff 100%); }

/* ---------- 登录 ---------- */
.m-login { max-width: 340px; margin: 0 auto; padding: 64px 24px; text-align: center; }
.login-logo { font-size: 56px; }
.login-title { font-size: 24px; font-weight: 800; color: #4a4a55; margin-top: 8px; }
.login-sub { font-size: 13px; color: #a0a4b4; margin: 6px 0 28px; }
.m-input {
  width: 100%; box-sizing: border-box; padding: 14px 16px; margin-bottom: 12px;
  border: 2px solid #ffe0ec; border-radius: 14px; font-size: 16px; background: #fff; outline: none;
}
.m-input:focus { border-color: #f78fb3; }
.m-captcha { display: flex; gap: 10px; align-items: center; margin-bottom: 12px; }
.m-captcha-img {
  height: 48px; width: 116px; border-radius: 12px; cursor: pointer; flex-shrink: 0;
  border: 2px solid #ffe0ec; background: #fff;
}
.m-captcha-input { margin-bottom: 0; flex: 1; }
.m-btn-primary {
  width: 100%; padding: 14px; border: none; border-radius: 14px; font-size: 17px; font-weight: 800;
  color: #fff; background: linear-gradient(135deg, #ff9ec4, #ffb38a);
  box-shadow: 0 6px 16px rgba(255, 160, 190, .45); cursor: pointer;
}
.login-tip { font-size: 12px; color: #b0b3c0; margin-top: 18px; line-height: 1.6; }

/* ---------- 顶栏 ---------- */
.m-header {
  position: sticky; top: 0; z-index: 20; display: flex; align-items: center; justify-content: space-between;
  padding: 14px 16px; background: rgba(255, 253, 248, .92); backdrop-filter: blur(8px);
  border-bottom: 1px solid #ffe9f0;
}
.m-title { font-size: 18px; font-weight: 800; color: #5a5060; }
.m-logout {
  border: 1px solid #f0d0dc; background: #fff; color: #c2708a; border-radius: 999px;
  padding: 6px 14px; font-size: 13px; cursor: pointer;
}
/* 返回工作台：仅 PC 宽屏显示（手机上 /m 即主页无工作台可回）；三栏布局时标题居中 */
.m-back {
  display: none;
  border: 1px solid #c7d4f5; background: #fff; color: #5a76c8; border-radius: 999px;
  padding: 6px 14px; font-size: 13px; cursor: pointer;
}
@media (min-width: 768px) {
  .m-back { display: inline-block; }
  .m-title { flex: 1; text-align: center; padding: 0 8px; }
}

/* ---------- 宠物网格 ---------- */
.m-grid {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px;
  padding: 16px 14px 120px;
}
.m-pet {
  position: relative; border: 2px solid #ffe0ec; background: #fff; border-radius: 18px;
  padding: 12px 6px 10px; cursor: pointer; transition: transform .12s, box-shadow .12s;
}
.m-pet:active { transform: scale(.94); box-shadow: 0 4px 14px rgba(247, 143, 179, .35); }
.m-pet-img { width: 72px; height: 72px; object-fit: contain; }
.m-pet-img.frozen { filter: hue-rotate(160deg) saturate(.7); }
.m-pet-name { font-size: 15px; font-weight: 800; color: #4a4a55; margin-top: 2px; }
.m-pet-lv { font-size: 11px; color: #a89aa8; margin-top: 1px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.m-exp-bar { height: 5px; background: #f0e8ef; border-radius: 99px; margin-top: 6px; overflow: hidden; }
.m-exp-fill { height: 100%; background: linear-gradient(90deg, #ffd166, #ff9ec4); border-radius: 99px; }
.m-badge-star { position: absolute; top: 4px; right: 6px; font-size: 16px; }
.m-empty { grid-column: 1 / -1; text-align: center; color: #b0b3c0; font-size: 14px; line-height: 2; padding-top: 60px; }

/* ---------- 规则底部弹层 ---------- */
.m-mask { position: fixed; inset: 0; background: rgba(60, 40, 50, .35); z-index: 50; display: flex; align-items: flex-end; }
.m-sheet {
  width: 100%; box-sizing: border-box; background: #fffdf8; border-radius: 22px 22px 0 0;
  padding: 18px 16px calc(20px + env(safe-area-inset-bottom));
  max-height: 72vh; overflow-y: auto; animation: sheet-up .22s ease-out;
}
@keyframes sheet-up { from { transform: translateY(100%); } to { transform: translateY(0); } }
.sheet-head { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.sheet-img { width: 56px; height: 56px; object-fit: contain; }
.sheet-id { flex: 1; }
.sheet-name { font-size: 18px; font-weight: 800; color: #4a4a55; }
.sheet-lv { font-size: 13px; color: #a89aa8; margin-top: 2px; }
.sheet-exp { font-size: 14px; font-weight: 800; color: #f0903a; }
.sheet-cats { display: flex; gap: 8px; margin-bottom: 12px; }
.cat-btn {
  flex: 1; padding: 9px 0; border: 2px solid #f0e4ec; background: #fff; border-radius: 12px;
  font-size: 15px; font-weight: 700; color: #9a8fa0; cursor: pointer;
}
.cat-btn.active { border-color: #f78fb3; background: #fde4ee; color: #e06aa8; }
.sheet-rules { display: grid; grid-template-columns: repeat(2, 1fr); gap: 10px; }
.rule-btn {
  display: flex; align-items: center; gap: 8px; padding: 14px 12px; border-radius: 14px;
  border: 2px solid; cursor: pointer; background: #fff; text-align: left;
}
.rule-btn.good { border-color: #bfe8cf; background: #f0fbf4; }
.rule-btn.bad { border-color: #f3d0d0; background: #fdf3f3; }
.rule-btn:disabled { opacity: .4; cursor: not-allowed; }
.rule-btn:active:not(:disabled) { transform: scale(.96); }
.rule-icon { font-size: 22px; }
.rule-name { flex: 1; font-size: 15px; font-weight: 700; color: #5a5a66; }
.rule-btn.good .rule-score { color: #2faa62; font-weight: 800; font-size: 16px; }
.rule-btn.bad .rule-score { color: #d06060; font-weight: 800; font-size: 16px; }

/* ---------- 待播进化条 ---------- */
.m-held-bar {
  position: fixed; left: 12px; right: 12px; bottom: calc(12px + env(safe-area-inset-bottom)); z-index: 40;
  background: linear-gradient(135deg, #fff4d6, #ffe6c8); border: 2px solid #ffcf8a;
  border-radius: 18px; padding: 10px 12px; box-shadow: 0 8px 22px rgba(240, 160, 60, .35);
}
.held-row { display: flex; gap: 10px; align-items: center; }
.held-main {
  flex: 1; border: none; background: transparent; font-size: 16px; font-weight: 800;
  color: #9a6220; text-align: left; cursor: pointer; padding: 6px 2px;
}
.held-arrow { font-size: 12px; }
.held-play {
  border: none; background: linear-gradient(135deg, #ffb347, #ff8a5c); color: #fff;
  border-radius: 999px; padding: 10px 18px; font-size: 15px; font-weight: 800; cursor: pointer;
  box-shadow: 0 4px 12px rgba(255, 140, 90, .4);
}
.held-list { margin-top: 8px; border-top: 1px dashed #e8c48a; padding-top: 8px; max-height: 180px; overflow-y: auto; }
.held-item { display: flex; align-items: center; justify-content: space-between; padding: 6px 2px; font-size: 14px; color: #7a5a30; }
.held-one { border: 1px solid #e8b86a; background: #fff; color: #b07a28; border-radius: 999px; padding: 4px 12px; font-size: 13px; cursor: pointer; }

/* ---------- toast ---------- */
.m-toast {
  position: fixed; left: 50%; top: 72px; transform: translateX(-50%); z-index: 60;
  display: flex; align-items: center; gap: 12px; background: rgba(60, 50, 58, .92); color: #fff;
  border-radius: 999px; padding: 10px 16px; font-size: 14px; max-width: 92vw;
  box-shadow: 0 6px 18px rgba(0, 0, 0, .25); animation: toast-in .2s ease-out;
}
@keyframes toast-in { from { opacity: 0; transform: translate(-50%, -10px); } to { opacity: 1; transform: translate(-50%, 0); } }
.toast-undo { border: none; background: #ffd166; color: #6a4a10; border-radius: 999px; padding: 6px 12px; font-weight: 800; font-size: 13px; cursor: pointer; white-space: nowrap; }

/* ---------- 打怪兽入口卡（网格第一格，区别于宠物卡的暗紫渐变） ---------- */
.m-boss-entry {
  border-color: #d8d0f0; background: linear-gradient(150deg, #f3efff, #e8f0ff);
}
.boss-entry-emoji { font-size: 56px; line-height: 1.2; }

/* ---------- 打怪兽控制弹层 ---------- */
.boss-close {
  border: none; background: #f0e8ef; color: #9a8fa0; border-radius: 999px;
  width: 34px; height: 34px; font-size: 15px; cursor: pointer; flex-shrink: 0;
}
.boss-panel { display: flex; flex-direction: column; gap: 12px; }
.boss-tip { font-size: 13px; color: #a89aa8; line-height: 1.6; }
.boss-form-row { display: flex; gap: 10px; }
.boss-select {
  flex: 1; min-width: 0; padding: 12px 10px; border: 2px solid #e4dcf0; border-radius: 14px;
  font-size: 15px; color: #5a5a66; background: #fff; outline: none;
}
.boss-btn {
  border: none; border-radius: 14px; padding: 14px; font-size: 16px; font-weight: 800;
  cursor: pointer; color: #fff; transition: transform .12s;
}
.boss-btn:active { transform: scale(.96); }
.boss-btn.start { background: linear-gradient(135deg, #a78bfa, #f78fb3); box-shadow: 0 6px 16px rgba(167, 139, 250, .4); }
.boss-btn.draw { background: linear-gradient(135deg, #ffd166, #ff9a76); box-shadow: 0 6px 16px rgba(255, 160, 90, .35); }
.boss-btn.pick { flex: 1; background: linear-gradient(135deg, #74d3d8, #7ba7f5); box-shadow: 0 6px 16px rgba(116, 190, 235, .4); }

/* HP 条（沿用宠物经验条配色，血条红） */
.boss-hp-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.boss-hp-track { flex: 1; height: 14px; background: #f0e8ef; border-radius: 99px; overflow: hidden; }
.boss-hp-fill { height: 100%; background: linear-gradient(90deg, #ff7a8a, #ff5252); border-radius: 99px; transition: width .5s ease; }
.boss-hp-num { font-size: 14px; font-weight: 800; color: #e06070; white-space: nowrap; }
.boss-mini-stats { font-size: 13px; font-weight: 700; color: #8a8090; margin-bottom: 12px; }

.boss-over-tip {
  text-align: center; font-size: 16px; font-weight: 800; color: #9a8fa0;
  background: #f6f2f4; border-radius: 14px; padding: 22px;
}
.boss-q { background: #f8f5ff; border: 2px solid #e4dcf0; border-radius: 14px; padding: 14px; }
.boss-q-meta { font-size: 12px; font-weight: 700; color: #a78bfa; margin-bottom: 6px; }
.boss-q-text { font-size: 17px; font-weight: 800; color: #4a4a55; line-height: 1.5; }
.boss-q-answer {
  margin-top: 10px; font-size: 15px; font-weight: 800; color: #2faa62;
  background: #f0fbf4; border: 1px dashed #bfe8cf; border-radius: 10px; padding: 8px 10px;
}
.boss-answer-name { font-size: 15px; font-weight: 700; color: #5a5a66; }
.boss-answer-name b { color: #e06aa8; }
.boss-btn.pass { flex: 1; background: linear-gradient(135deg, #7bd88f, #4fb86e); box-shadow: 0 6px 16px rgba(80, 190, 110, .35); }
.boss-btn.fail { flex: 1; background: linear-gradient(135deg, #f0909a, #e06070); box-shadow: 0 6px 16px rgba(224, 100, 110, .35); }

/* ICP 备案号：固定底部居中，弹窗遮罩层级更高，打开弹层时自动被盖住 */
.m-beian {
  position: fixed; bottom: 8px; left: 0; right: 0; z-index: 50;
  text-align: center; font-size: 11px; color: #b39db0; text-decoration: none;
}
</style>
