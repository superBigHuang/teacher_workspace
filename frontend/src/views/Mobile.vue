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
      <button class="m-btn-primary" @click="doLogin">登 录</button>
      <div class="login-tip">与 PC 端同一账号；登录后投影页打开座次表即可遥控</div>
    </div>

    <template v-else>
      <!-- ============ 顶栏 ============ -->
      <header class="m-header">
        <div class="m-title">🐾 宠物遥控</div>
        <button class="m-logout" @click="logout">退出</button>
      </header>

      <!-- ============ 宠物网格（只列已领养） ============ -->
      <main class="m-grid">
        <button v-for="pet in adoptedPets" :key="pet.studentId" class="m-pet" @click="openSheet(pet)">
          <img :src="p.petImg(pet)" class="m-pet-img" :class="{ frozen: pet.frozen }" alt="" />
          <div class="m-pet-name">{{ pet.name }}</div>
          <div class="m-pet-lv">{{ pet.hatched === 0 ? '🥚 孵化中' : `Lv.${pet.level} · ${pet.petName}` }}</div>
          <div class="m-exp-bar"><div class="m-exp-fill" :style="{ width: (pet.hatched === 0 ? p.hatchPct(pet) : p.expPct(pet)) + '%' }"></div></div>
          <span v-if="pet.starToday" class="m-badge-star">🌈</span>
        </button>
        <div v-if="p && !adoptedPets.length" class="m-empty">还没有领养宠物的学生<br/>请先在 PC 端座次表领养</div>
      </main>

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
  </div>
</template>

<script setup>
import { ref, shallowRef, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { usePets, PET_CATS } from '../composables/usePets'
import { startRemote, stopRemote, onRemoteCmd, sendCmd } from '../composables/useRemote'

/** 评价项四个分类 tab */
const CATS = PET_CATS

const token = ref(localStorage.getItem('token'))
const loginForm = ref({ username: '', password: '' })

/** usePets() 实例（登录后才初始化，避免未登录发 401 请求被拦截器跳走） */
const p = shallowRef(null)
let P = null

const sheetPet = ref(null)      // 当前打开规则弹层的学生
const evalCat = ref('学习')
const heldOpen = ref(false)
const toast = ref(null)

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
  const r = await http.post('/auth/login', {
    username: loginForm.value.username.trim(),
    password: loginForm.value.password
  })
  localStorage.setItem('token', r.token)
  localStorage.setItem('displayName', r.displayName)
  localStorage.setItem('username', r.username || '')
  token.value = r.token
  loginForm.value.password = ''
  boot()
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
</style>
