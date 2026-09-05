<template>
  <div class="pet-page">
    <!-- 顶部：标题 + 榜单 tab + 弹窗入口 -->
    <div class="page-toolbar">
      <h2 class="page-title">🐾 宠物园</h2>
      <el-radio-group v-model="boardTab">
        <el-radio-button value="weekly">🏆 本周榜</el-radio-button>
        <el-radio-button value="total">⭐ 总榜</el-radio-button>
        <el-radio-button value="level">🐉 等级榜</el-radio-button>
      </el-radio-group>
      <div class="spacer"></div>
      <el-button round @click="api.openRules()">⚙ 评价项</el-button>
      <el-button round @click="api.openShop()">🛍 商店</el-button>
    </div>

    <!-- 本周前三领奖台（仅 weekly tab 显示） -->
    <div v-if="boardTab === 'weekly'" class="podium-area">
      <div class="podium-title">🏆 本周之星</div>
      <div class="podium-row">
        <!-- 台位顺序：🥈左 / 🥇中(最高) / 🥉右；不足 3 人对应位置虚位以待 -->
        <div v-for="slot in podiumSlots" :key="slot.pos" class="podium-card" :class="slot.cls">
          <img v-if="slot.pos === 1" class="podium-medal" src="/badges/medal-1st.png" alt="第一名">
          <img v-else-if="slot.pos === 2" class="podium-medal" src="/badges/medal-2nd.png" alt="第二名">
          <img v-else class="podium-medal" src="/badges/medal-3rd.png" alt="第三名">
          <template v-if="slot.row">
            <img class="podium-pet" :src="petImgOf(slot.row)" alt="宠物" loading="lazy">
            <div class="podium-name">{{ slot.row.name }}</div>
            <div class="podium-petname">{{ slot.row.petName }}</div>
            <div class="podium-score">本周 +{{ slot.row.weeklyScore }}</div>
            <span v-if="slot.row.isLeader" class="podium-flag">👑</span>
            <span v-if="slot.row.graduatedCount > 0" class="podium-flag grad">🎓×{{ slot.row.graduatedCount }}</span>
          </template>
          <template v-else>
            <div class="podium-empty">虚位以待</div>
          </template>
        </div>
      </div>
    </div>

    <!-- 全员宠物墙 -->
    <div class="wall-head">
      <span class="wall-title">🐾 全员宠物墙（{{ wallRows.length }}）</span>
      <el-radio-group v-model="wallFilter" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="hatched">已破壳</el-radio-button>
        <el-radio-button value="egg">孵化中</el-radio-button>
        <el-radio-button value="progress">本周进步</el-radio-button>
      </el-radio-group>
    </div>
    <div class="wall-grid">
      <div v-for="r in wallRows" :key="r.studentId" class="wall-card" :data-sid="r.studentId"
           :class="{ 'is-egg': r.hatched === 0 }" @click="openBoardDetail(r)">
        <span v-if="r.isLeader" class="wall-flag leader">👑</span>
        <span v-if="r.graduatedCount > 0" class="wall-flag grad">🎓×{{ r.graduatedCount }}</span>
        <img class="wall-pet" :class="{ egg: r.hatched === 0 }" :src="petImgOf(r)" alt="宠物" loading="lazy">
        <div class="wall-name">{{ r.name }}</div>
        <div class="wall-petname">{{ r.petName }}
          <span v-if="r.hatched === 0" class="wall-egg-badge">🥚</span>
          <span v-else class="wall-lv">Lv.{{ r.level }}</span>
        </div>
        <!-- 本周得分：weekly tab 下绿色强调 -->
        <div class="wall-week" :class="{ hot: boardTab === 'weekly' && r.weeklyScore > 0 }">
          {{ r.weeklyScore > 0 ? `本周 +${r.weeklyScore}` : '本周暂无加分' }}
        </div>
        <!-- 成长进度条：蛋=孵化进度(exp/20)，破壳=exp/280（KISS：本级粗略口径） -->
        <div class="wall-exp"><div class="wall-exp-fill" :style="{ width: wallExpPct(r) + '%' }"></div></div>
      </div>
      <div v-if="!wallRows.length" class="wall-empty">还没有宠物，去座次表领养吧～</div>
    </div>

    <!-- 复用弹窗：详情 / 评价 / 评价项管理 / 小商店（组件从 Seat.vue 抽出共享） -->
    <PetDetailDlg :api="api" />
    <RulesDlg :api="api" />
    <ShopDlg :api="api" />

    <!-- 评价弹窗（单人/批量共用；Pet 页仅单人详情入口） -->
    <el-dialog v-model="api.evalDlg" title="🌟 给宠物评价" width="560px" align-center>
      <el-tabs v-model="api.evalCat">
        <el-tab-pane v-for="c in CATS" :key="c" :label="c" :name="c">
          <div class="rule-grid">
            <button v-for="r in api.rulesOfCat(c)" :key="r.id" class="rule-card"
              :class="r.score > 0 ? 'plus' : 'minus'" @click="api.applyRule(r)">
              <span class="rule-icon">{{ r.icon || '✨' }}</span>
              <span class="rule-name">{{ r.name }}</span>
              <span class="rule-score">{{ r.score > 0 ? '+' : '' }}{{ r.score }}</span>
            </button>
          </div>
          <div v-if="!api.rulesOfCat(c).length" class="cat-empty">该分类暂无评价项，可在「⚙ 评价项」中添加</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, reactive, onMounted, onUnmounted } from 'vue'
import confetti from 'canvas-confetti'
import http from '../api/http'
import { usePets, petImgOf, hatchPct, HATCH_EXP, PET_CATS } from '../composables/usePets'
import { onRemoteCmd } from '../composables/useRemote'
import PetDetailDlg from '../components/pet/PetDetailDlg.vue'
import RulesDlg from '../components/pet/RulesDlg.vue'
import ShopDlg from '../components/pet/ShopDlg.vue'

/** 评价项分类（固定四个 tab） */
const CATS = PET_CATS
/** 满级成长值（与后端 PetService.MAX_EXP 一致，总榜排序口径） */
const MAX_EXP = 280

// 宠物操作复用座次表同一套逻辑（reactive 包装后模板访问 api.xxx 自动解包嵌套 ref）
const api = reactive(usePets())

// ==================== 榜单数据（一次拉全，切 tab 前端复排不重发请求） ====================
const board = ref([])        // GET /pet/board 全量行（含 weeklyScore/exp/level/hatched 等）
const boardTab = ref('weekly') // 榜单口径：weekly=本周得分 / total=累计总值 / level=等级
const wallFilter = ref('all') // 宠物墙筛选：all/hatched/egg/progress

/** 按当前 tab 口径前端复排（字段一套，三种排序键） */
const sortedRows = computed(() => {
  const rows = [...board.value]
  const byExpDesc = (a, b) => b.exp - a.exp || String(a.stuNo).localeCompare(String(b.stuNo))
  if (boardTab.value === 'total') {
    rows.sort((a, b) => (b.exp + b.graduatedCount * MAX_EXP) - (a.exp + a.graduatedCount * MAX_EXP) || byExpDesc(a, b))
  } else if (boardTab.value === 'level') {
    rows.sort((a, b) => b.level - a.level || byExpDesc(a, b))
  } else {
    rows.sort((a, b) => b.weeklyScore - a.weeklyScore || byExpDesc(a, b))
  }
  return rows
})

/** 宠物墙行：排序后的全量按筛选 chip 过滤 */
const wallRows = computed(() => {
  const rows = sortedRows.value
  if (wallFilter.value === 'hatched') return rows.filter(r => r.hatched === 1)
  if (wallFilter.value === 'egg') return rows.filter(r => r.hatched === 0)
  if (wallFilter.value === 'progress') return rows.filter(r => r.weeklyScore > 0)
  return rows
})

/** 领奖台三席（仅 weekly 榜取前三）：🥈左 / 🥇中(最高) / 🥉右；空位虚位以待 */
const podiumSlots = computed(() => {
  const top3 = sortedRows.value.slice(0, 3)
  return [
    { pos: 2, row: top3[1] || null, cls: 'pos-2' },
    { pos: 1, row: top3[0] || null, cls: 'pos-1' },
    { pos: 3, row: top3[2] || null, cls: 'pos-3' },
  ]
})

/** 宠物墙进度条：蛋=孵化进度，破壳=exp/280（本级粗略口径，详细进度见详情弹窗） */
function wallExpPct(r) {
  return r.hatched === 0 ? hatchPct(r) : Math.max(3, Math.min(100, Math.round(((r.exp || 0) / MAX_EXP) * 100)))
}

/** 点击宠物墙卡片：弹详情（详情需 expStart/expTarget 完整字段，优先用 usePets 的全班宠物行） */
function openBoardDetail(r) {
  api.openDetail(api.pets.find(x => x.studentId === r.studentId) || r)
}

// ==================== 数据加载 ====================
/** 拉榜单全量（整页只调一次；切 tab/筛选取 computed 复排，不发请求） */
async function loadBoard() {
  board.value = await http.get('/pet/board', { params: { type: 'weekly' } })
}

/** 金色雨：仅首次进入播一次（切 tab/筛选不再播） */
let confettiPlayed = false
function playGoldRain() {
  if (confettiPlayed) return
  confettiPlayed = true
  const colors = ['#FFD700', '#FFB347', '#FFF6E5']
  confetti({ particleCount: 60, angle: 60, spread: 70, origin: { x: 0, y: 0.85 }, colors, zIndex: 3300 })
  confetti({ particleCount: 60, angle: 120, spread: 70, origin: { x: 1, y: 0.85 }, colors, zIndex: 3300 })
  setTimeout(() => confetti({ particleCount: 90, spread: 110, origin: { y: 0.35 }, colors, zIndex: 3300 }), 260)
}

// 手机遥控/其他端写操作后广播 pets.changed：静默重拉榜单与宠物状态
let offRemote = null
onMounted(async () => {
  offRemote = onRemoteCmd((action) => {
    if (action === 'pets.changed') { loadBoard(); api.loadPets() }
  })
  await loadBoard()
  playGoldRain()
})
onUnmounted(() => offRemote?.())
</script>

<style scoped>
.pet-page { max-width: 1280px; }
.spacer { flex: 1; }

/* ===== 领奖台 ===== */
.podium-area { margin: 18px 0 26px; }
.podium-title { text-align: center; font-size: 20px; font-weight: 900; color: #8a5a34; margin-bottom: 16px;
  letter-spacing: 2px; }
.podium-row { display: flex; justify-content: center; align-items: flex-end; gap: 26px; }
.podium-card {
  position: relative; width: 190px; border-radius: 18px; padding: 16px 10px 14px; text-align: center;
  background: linear-gradient(160deg, #fff6e0, #ffe9c2); border: 3px solid #ffd98a;
  box-shadow: 0 10px 26px rgba(230,170,90,.28);
  animation: podiumIn .6s ease-out backwards;
}
.podium-card.pos-1 { height: 200px; transform: scale(1.15); z-index: 2; animation-delay: .2s; }
.podium-card.pos-2 { height: 170px; animation-delay: .1s; }
.podium-card.pos-3 { height: 158px; animation-delay: .3s; }
/* 入屏：从底部升台（只用 transform/opacity） */
@keyframes podiumIn {
  from { transform: translateY(40px); opacity: 0; }
  to { transform: translateY(0); opacity: 1; }
}
.podium-card.pos-1 { animation-name: podiumInCenter; }
@keyframes podiumInCenter {
  from { transform: translateY(40px) scale(1.15); opacity: 0; }
  to { transform: translateY(0) scale(1.15); opacity: 1; }
}
.podium-medal { position: absolute; top: -18px; left: -14px; width: 44px; height: 44px;
  filter: drop-shadow(0 3px 5px rgba(180,120,40,.4)); }
.podium-pet { width: 96px; height: 96px; object-fit: contain; margin-top: 6px;
  animation: pet-breathe 3.2s ease-in-out infinite; }
.podium-name { font-size: 16px; font-weight: 900; color: #6a4a2c; margin-top: 4px; }
.podium-petname { font-size: 12px; color: #a07a5c; font-weight: 700; margin-top: 2px; }
.podium-score { font-size: 15px; font-weight: 900; color: #4caf7d; margin-top: 5px; }
.podium-empty { margin-top: 56px; color: #c9ab84; font-weight: 700; font-size: 14px; }
.podium-flag { position: absolute; top: 8px; right: 8px; font-size: 17px; }
.podium-flag.grad { right: 34px; font-size: 13px; background: linear-gradient(135deg,#ffe28a,#f2a93a);
  color: #fff; border-radius: 9px; padding: 1px 6px; font-weight: 800; }

/* ===== 宠物墙 ===== */
.wall-head { display: flex; align-items: center; gap: 14px; margin: 4px 0 14px; }
.wall-title { font-weight: 900; color: #8a5a34; font-size: 16px; }
.wall-grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 12px; }
.wall-card {
  position: relative; border-radius: 14px; padding: 10px 6px 9px; text-align: center; cursor: pointer;
  background: #fffaf2; border: 2px solid #f0e4d4; transition: transform .15s, border-color .15s;
}
.wall-card:hover { transform: translateY(-4px); border-color: #ffc9a3; }
.wall-card.is-egg { background: linear-gradient(160deg, #fffbe8, #fff3d0); }
.wall-pet { width: 88px; height: 88px; object-fit: contain; animation: pet-breathe 3.2s ease-in-out infinite; }
.wall-pet.egg { filter: drop-shadow(0 0 8px rgba(255,220,130,.9)); }
.wall-name { font-size: 13px; font-weight: 900; color: #6a4a2c; margin-top: 3px; }
.wall-petname { font-size: 11px; color: #a07a5c; font-weight: 700; }
.wall-egg-badge { font-size: 11px; }
.wall-lv { color: #ff7f32; font-weight: 900; font-size: 11px; }
.wall-week { font-size: 11px; color: #b09888; font-weight: 700; margin-top: 2px; }
.wall-week.hot { color: #4caf7d; }
.wall-exp { height: 7px; border-radius: 6px; background: #ffe2c8; margin: 6px 8px 0; overflow: hidden; }
.wall-exp-fill { height: 100%; border-radius: 6px; background: linear-gradient(90deg, #ffd98a, #ff9f5a); transition: width .4s; }
.wall-empty { grid-column: 1 / -1; text-align: center; color: #c0a898; padding: 40px 0; font-size: 14px; }
.wall-flag { position: absolute; top: 6px; right: 6px; font-size: 15px; }
.wall-flag.grad { right: 32px; font-size: 11px; background: linear-gradient(135deg,#ffe28a,#f2a93a);
  color: #fff; border-radius: 8px; padding: 1px 5px; font-weight: 800; }

/* ===== 评价弹窗（与座次表同款样式） ===== */
.rule-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.rule-card {
  border: 2px solid #fff; border-radius: 14px; padding: 10px 6px; cursor: pointer;
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  transition: transform .15s;
}
.rule-card:hover { transform: scale(1.05); }
.rule-card.plus { background: linear-gradient(150deg, #e9f9f0, #d8f3e4); }
.rule-card.minus { background: linear-gradient(150deg, #fdeeee, #fbdfdf); }
.rule-icon { font-size: 26px; }
.rule-name { font-size: 13px; font-weight: 700; color: #6b5b73; text-align: center; min-height: 36px; display: flex; align-items: center; }
.rule-score { font-size: 14px; font-weight: 900; border-radius: 9px; padding: 0 8px; color: #fff; }
.rule-card.plus .rule-score { background: #5cbb8c; }
.rule-card.minus .rule-score { background: #ef7f7f; }
.cat-empty { text-align: center; color: #b8a8b0; padding: 24px 0; font-size: 13px; }

@keyframes pet-breathe {
  0%, 100% { transform: translateY(0) rotate(-1.2deg); }
  50% { transform: translateY(-7px) rotate(1.2deg); }
}
</style>
