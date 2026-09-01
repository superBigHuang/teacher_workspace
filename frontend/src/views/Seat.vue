<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">🪑 座次表 · 🐾 宠物园</h2>
      <span class="cfg-label">行数</span>
      <el-input-number v-model="rows" :min="1" :max="15" size="small" @change="saveConfig" />
      <span class="cfg-label">列数</span>
      <el-input-number v-model="cols" :min="1" :max="15" size="small" @change="saveConfig" />
      <span class="cfg-label" v-for="n in 3" :key="n">过道{{ n }}</span>
      <el-select v-model="aisleModel[0]" size="small" style="width:110px" @change="onAisle">
        <el-option label="无" :value="0" /><el-option v-for="c in cols" :key="c" :label="'第'+c+'列后'" :value="c" />
      </el-select>
      <el-select v-model="aisleModel[1]" size="small" style="width:110px" @change="onAisle">
        <el-option label="无" :value="0" /><el-option v-for="c in cols" :key="c" :label="'第'+c+'列后'" :value="c" />
      </el-select>
      <el-select v-model="aisleModel[2]" size="small" style="width:110px" @change="onAisle">
        <el-option label="无" :value="0" /><el-option v-for="c in cols" :key="c" :label="'第'+c+'列后'" :value="c" />
      </el-select>
      <div class="spacer"></div>
      <el-button type="primary" :icon="Refresh" @click="randomSeat">🔀 随机排座</el-button>
      <el-button type="danger" plain :icon="Delete" @click="clearSeat">清空座次</el-button>
      <el-button round @click="openRanking">🏆 荣誉榜</el-button>
      <el-button round @click="openShop">🛍 小商店</el-button>
      <el-button round @click="openRules">⚙ 评价项</el-button>
      <el-button round :type="batchMode ? 'success' : 'warning'" @click="toggleBatch">
        {{ batchMode ? `✅ 完成批量（已选 ${selected.size}）` : '📝 批量评价' }}
      </el-button>
    </div>

    <div class="legend">
      <span class="lg"><i class="dot boy"></i> 男生</span>
      <span class="lg"><i class="dot girl"></i> 女生</span>
      <span class="lg"><i class="aisle-bar"></i> 过道</span>
      <span class="tip">拖动学生交换座位；点击座位查看/领养宠物</span>
    </div>

    <!-- 批量模式浮动条 -->
    <transition name="bar">
      <div v-if="batchMode" class="batch-bar">
        <span>已选 <b class="pick-num">{{ selected.size }}</b> 名学生</span>
        <el-button size="small" type="primary" round :disabled="!selected.size" @click="openEvalBatch">
          选择评价项 →
        </el-button>
        <el-button size="small" text @click="selected.clear()">清空</el-button>
      </div>
    </transition>

    <div class="podium">🏫 讲台</div>

    <div class="seat-grid" :style="gridStyle">
      <div v-for="aisleCol in aisleDisplayColumns" :key="'a'+aisleCol"
           class="aisle-gutter" :style="{ gridColumn: aisleCol }"></div>
      <div v-for="s in seats" :key="s.row + '-' + s.col"
           class="seat" :class="seatClass(s)"
           :style="{ gridRow: s.row, gridColumn: displayCol(s.col) }"
           :draggable="!!s.studentId && !batchMode"
           @dragstart="onDragStart($event, s)" @dragover.prevent @drop="onDrop($event, s)"
           @pointerdown="onSeatDown($event, s)" @pointerup="onSeatUp($event)">
        <template v-if="s.studentId">
          <!-- 宠物区：宠物"坐"在座位上 -->
          <div class="seat-pet">
            <el-checkbox v-if="batchMode" class="pick-box" :model-value="selected.has(s.studentId)"
              @click.stop @change="togglePick(s.studentId)" />
            <span v-if="petOf(s)?.dizzy" class="seat-flag">💫</span>
            <span v-if="petOf(s)?.frozen" class="seat-flag flag-fz">🥶</span>
            <span v-if="petOf(s)?.broken" class="seat-flag flag-bk">🩹</span>
            <span v-if="petOf(s)?.graduatedCount > 0" class="seat-flag flag-grad">🎓{{ petOf(s).graduatedCount }}</span>
            <img class="seat-pet-img"
              :class="{ alive: petOf(s)?.petId, dizzy: petOf(s)?.dizzy, frozen: petOf(s)?.frozen, broken: petOf(s)?.broken }"
              :src="petImg(petOf(s))" alt="宠物">
            <span v-for="f in floatsOf(s.studentId)" :key="f.id" class="float-score"
              :class="f.good ? 'good' : 'bad'">{{ f.text }}</span>
            <div v-if="petOf(s)?.petId" class="seat-lv"><span class="lv-badge">Lv.{{ petOf(s).level }}</span></div>
            <div v-if="petOf(s)?.petId && petOf(s).level < 5" class="seat-exp">
              <div class="seat-exp-fill" :style="{ width: expPct(petOf(s)) + '%' }"></div>
            </div>
          </div>
          <div class="seat-name">{{ s.name }}</div>
          <div class="seat-pos">第{{ s.row }}排{{ s.col }}座</div>
        </template>
        <template v-else><div class="empty">— 空 —</div></template>
      </div>
    </div>

    <!-- 未排座学生：宠物卡形式，功能与座位上一致（领养/评价/批量勾选） -->
    <div v-if="unSeatedPets.length" class="unseat-wrap">
      <div class="unseat-title">🐾 未排座学生（{{ unSeatedPets.length }} 人，宠物仍可照料）</div>
      <div class="pet-grid">
        <div v-for="p in unSeatedPets" :key="p.studentId" class="pet-card"
          :class="{ dizzy: p.dizzy, frozen: p.frozen, broken: p.broken, picked: selected.has(p.studentId) }"
          @click="onCardClick(p)">
          <el-checkbox v-if="batchMode" class="pick-box" :model-value="selected.has(p.studentId)"
            @click.stop @change="togglePick(p.studentId)" />
          <span v-if="p.dizzy" class="flag flag-dizzy">💫</span>
          <span v-if="p.frozen" class="flag flag-frozen">🥶</span>
          <span v-if="p.broken" class="flag flag-broken">🩹</span>
          <span v-if="p.graduatedCount > 0" class="flag flag-grad">🎓{{ p.graduatedCount }}</span>

          <div class="pet-img-wrap">
            <img class="pet-img" :class="{ alive: p.petId }" :src="petImg(p)" alt="宠物">
            <span v-for="f in floatsOf(p.studentId)" :key="f.id" class="float-score"
              :class="f.good ? 'good' : 'bad'">{{ f.text }}</span>
          </div>

          <template v-if="p.petId">
            <div class="pet-name">{{ p.petName }}<span class="lv-badge">Lv.{{ p.level }}</span></div>
            <div class="exp-bar"><div class="exp-fill" :style="{ width: expPct(p) + '%' }"></div></div>
            <div class="exp-text">{{ p.level >= 5 ? '✨ 已满级，可毕业！' : `成长值 ${p.exp} / ${p.expTarget}` }}</div>
          </template>
          <template v-else>
            <div class="pet-name egg-name">神秘精灵蛋</div>
            <el-button size="small" type="primary" round @click.stop="openAdopt(p)">🥚 领养精灵</el-button>
          </template>
          <div class="stu-name">{{ p.name }}</div>
        </div>
      </div>
    </div>

    <!-- 领养弹窗 -->
    <el-dialog v-model="adoptDlg" title="🥚 领养守护精灵" width="380px" align-center>
      <div class="adopt-body">
        <img class="adopt-img" src="/pets/dragon_1.webp" alt="云小龙">
        <p class="adopt-tip">一颗温热的精灵蛋正在微微发光……<br>给Ta起个名字，一起成长吧！</p>
        <el-input v-model="adoptName" maxlength="12" placeholder="给宠物起个中文名，如：云小龙" />
      </div>
      <template #footer>
        <el-button round @click="adoptDlg = false">再想想</el-button>
        <el-button type="primary" round @click="doAdopt">🥚 破壳领养</el-button>
      </template>
    </el-dialog>

    <!-- 宠物详情弹窗 -->
    <el-dialog v-model="detailDlg" :title="`🐉 ${detailStudent?.petName || '宠物详情'}`" width="460px" align-center>
      <div v-if="detailStudent" class="detail-body">
        <div class="detail-head">
          <img class="detail-img" :src="petImg(detailStudent)" alt="宠物">
          <div class="detail-info">
            <div class="detail-name">{{ detailStudent.petName }}
              <span class="lv-badge big">Lv.{{ detailStudent.level }}</span>
            </div>
            <div class="detail-line">🎓 毕业徽章：<b>{{ detailStudent.graduatedCount || 0 }}</b> 枚
              <span v-for="n in detailStudent.graduatedCount" :key="n" class="badge-medal">🎓</span>
            </div>
            <div class="detail-line">⭐ 累计成长值：<b>{{ detailStudent.exp }}</b>
              <span v-if="detailStudent.level < 5">（距 Lv.{{ detailStudent.level + 1 }} 还需 {{ detailStudent.expTarget - detailStudent.exp }}）</span>
              <span v-else>已满级</span>
            </div>
          </div>
        </div>

        <div class="log-title">📖 最近成长流水</div>
        <div class="log-list">
          <div v-for="l in detailLogs" :key="l.id" class="log-row">
            <span class="log-score" :class="l.score > 0 ? 'good' : 'bad'">
              {{ l.score > 0 ? '+' : '' }}{{ l.score }}
            </span>
            <span class="log-name">{{ l.remark || l.ruleName || sourceText(l.source) }}</span>
            <span class="log-time">{{ fmtTime(l.createdAt) }}</span>
            <el-button size="small" text type="danger" @click="revokeLog(l.id)">撤回</el-button>
          </div>
          <div v-if="!detailLogs.length" class="log-empty">还没有成长记录，快给Ta第一次评价吧～</div>
        </div>
      </div>
      <template #footer>
        <el-button v-if="detailStudent?.level >= 5" type="warning" round @click="doGraduate">🎓 毕业</el-button>
        <el-button type="primary" round @click="openEvalSingle(detailStudent)">🌟 评价加分</el-button>
      </template>
    </el-dialog>

    <!-- 评价弹窗（单人/批量共用） -->
    <el-dialog v-model="evalDlg" :title="evalBatch ? `📝 批量评价（${selected.size} 人）` : '🌟 给宠物评价'"
      width="560px" align-center>
      <el-tabs v-model="evalCat">
        <el-tab-pane v-for="c in CATS" :key="c" :label="c" :name="c">
          <div class="rule-grid">
            <button v-for="r in rulesOfCat(c)" :key="r.id" class="rule-card"
              :class="r.score > 0 ? 'plus' : 'minus'" @click="applyRule(r)">
              <span class="rule-icon">{{ r.icon || '✨' }}</span>
              <span class="rule-name">{{ r.name }}</span>
              <span class="rule-score">{{ r.score > 0 ? '+' : '' }}{{ r.score }}</span>
            </button>
          </div>
          <div v-if="!rulesOfCat(c).length" class="cat-empty">该分类暂无评价项，可在「⚙ 评价项」中添加</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 毕业仪式弹窗 -->
    <el-dialog v-model="graduateDlg" width="420px" align-center :show-close="false" class="graduate-dialog">
      <div class="graduate-stage">
        <div class="graduate-glow"></div>
        <div class="graduate-emoji">🎓✨</div>
        <div class="graduate-title">圆满毕业！</div>
        <p class="graduate-text">
          「<b>{{ gradStudent?.petName }}</b>」陪伴 <b>{{ gradStudent?.name }}</b> 走完了一段成长旅程，<br>
          获得 <b>毕业徽章 🎓 ×{{ (gradStudent?.graduatedCount || 0) + 1 }}</b>！<br>
          新的精灵蛋已经在窝里，继续出发吧～
        </p>
        <el-button type="warning" round size="large" @click="graduateDlg = false">太棒了 🌟</el-button>
      </div>
    </el-dialog>

    <!-- 荣誉榜弹窗 -->
    <el-dialog v-model="rankDlg" title="🏆 宠物荣誉榜" width="520px" align-center>
      <div class="rank-list">
        <div v-for="r in ranking" :key="r.studentId" class="rank-row"
          :class="{ top3: r.rank && r.rank <= 3, unranked: !r.rank }">
          <span class="rank-medal">
            {{ r.rank === 1 ? '🥇' : r.rank === 2 ? '🥈' : r.rank === 3 ? '🥉' : (r.rank ? r.rank : '—') }}
          </span>
          <img class="rank-img" :src="`/pets/dragon_${r.level}.webp`" alt="">
          <span class="rank-pet">{{ r.petName }}</span>
          <span class="rank-stu">{{ r.name }}</span>
          <span class="rank-lv">Lv.{{ r.level }}</span>
          <span class="rank-badges">🎓{{ r.badges }}</span>
          <span class="rank-total">⭐{{ r.total }}</span>
        </div>
      </div>
    </el-dialog>

    <!-- 小商店弹窗 -->
    <el-dialog v-model="shopDlg" title="🛍 宠物小商店" width="600px" align-center>
      <el-tabs v-model="shopTab">
        <!-- 兑换 -->
        <el-tab-pane label="🎁 兑换礼物" name="exchange">
          <div class="shop-stu-pick">
            为谁兑换：
            <el-select v-model="exchangeSid" placeholder="选择学生" style="width: 180px">
              <el-option v-for="p in pets.filter(x => x.petId)" :key="p.studentId"
                :label="`${p.name}（${p.petName} · ⭐${p.exp}）`" :value="p.studentId" />
            </el-select>
          </div>
          <div class="shop-grid">
            <div v-for="it in shopItems" :key="it.id" class="shop-card">
              <div class="shop-icon">{{ it.icon || '🎁' }}</div>
              <div class="shop-name">{{ it.name }}</div>
              <div class="shop-price">⭐{{ it.price }}</div>
              <div class="shop-stock">{{ it.stock < 0 ? '库存充足' : `剩 ${it.stock} 件` }}</div>
              <el-button size="small" round type="primary" :disabled="it.stock === 0" @click="doExchange(it)">
                兑换
              </el-button>
            </div>
            <div v-if="!shopItems.length" class="cat-empty">还没有商品，去「📦 商品与记录」添加吧</div>
          </div>
        </el-tab-pane>
        <!-- 商品管理 + 兑换记录 -->
        <el-tab-pane label="📦 商品与记录" name="manage">
          <div class="manage-title">商品管理</div>
          <div class="item-form">
            <el-input v-model="itemForm.icon" maxlength="2" style="width: 64px" placeholder="🎁" />
            <el-input v-model="itemForm.name" maxlength="20" style="width: 150px" placeholder="商品名称" />
            <el-input-number v-model="itemForm.price" :min="1" :max="280" controls-position="right" style="width: 110px" />
            <el-input-number v-model="itemForm.stock" :min="-1" controls-position="right" style="width: 120px" />
            <el-button type="primary" round @click="saveItem">上架</el-button>
          </div>
          <div class="form-hint">价格=成长值；库存填 -1 表示不限量</div>
          <div v-for="it in shopItems" :key="'m'+it.id" class="item-row">
            <span class="shop-icon-sm">{{ it.icon || '🎁' }}</span>
            <span class="item-name">{{ it.name }}</span>
            <span class="item-meta">⭐{{ it.price }} · {{ it.stock < 0 ? '不限量' : `库存 ${it.stock}` }}</span>
            <el-button size="small" text type="danger" @click="deleteItem(it.id)">下架</el-button>
          </div>

          <div class="manage-title" style="margin-top: 16px">兑换记录</div>
          <div v-for="e in exchanges" :key="e.id" class="ex-row">
            <span>{{ e.itemIcon || '🎁' }} {{ e.itemName }}</span>
            <span class="ex-stu">{{ e.studentName }}</span>
            <span class="ex-cost">-⭐{{ e.cost }}</span>
            <span class="ex-time">{{ fmtTime(e.createdAt) }}</span>
            <el-tag v-if="e.status === '待兑现'" type="warning" size="small">待兑现</el-tag>
            <el-tag v-else type="success" size="small">已兑现</el-tag>
            <el-button v-if="e.status === '待兑现'" size="small" round type="success"
              @click="fulfill(e.id)">兑现</el-button>
          </div>
          <div v-if="!exchanges.length" class="cat-empty">还没有兑换记录</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 评价项管理弹窗 -->
    <el-dialog v-model="rulesDlg" title="⚙ 评价项管理" width="580px" align-center>
      <el-tabs v-model="ruleCat">
        <el-tab-pane v-for="c in CATS" :key="c" :label="c" :name="c">
          <div class="item-form">
            <el-input v-model="ruleForm.icon" maxlength="2" style="width: 64px" placeholder="✨" />
            <el-input v-model="ruleForm.name" maxlength="20" style="width: 160px" placeholder="评价项名称" />
            <el-input-number v-model="ruleForm.score" :min="-10" :max="10" controls-position="right" style="width: 120px" />
            <el-button type="primary" round @click="saveRule(c)">添加</el-button>
          </div>
          <div class="form-hint">正分=奖励成长值，负分=扣减（扣分不会降级）</div>
          <div v-for="r in rulesOfCat(c)" :key="r.id" class="item-row">
            <span class="shop-icon-sm">{{ r.icon || '✨' }}</span>
            <span class="item-name">{{ r.name }}</span>
            <span class="item-meta" :class="r.score > 0 ? 'good' : 'bad'">
              {{ r.score > 0 ? '+' : '' }}{{ r.score }}
            </span>
            <el-switch :model-value="r.enabled === 1 || r.enabled === true" size="small" @change="v => toggleRule(r, v)" />
            <el-button size="small" text type="danger" @click="deleteRule(r.id)">删除</el-button>
          </div>
          <div v-if="!rulesOfCat(c).length" class="cat-empty">该分类暂无评价项</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Delete } from '@element-plus/icons-vue'
import http from '../api/http'
import { usePets, PET_CATS } from '../composables/usePets'

/** 评价项分类（固定四个 tab） */
const CATS = PET_CATS

// ==================== 座次表 ====================
const rows = ref(7)
const cols = ref(8)
const aisleModel = reactive([2, 4, 6])
const seats = ref([])
let dragFrom = null

const aisles = computed(() =>
  [...new Set(aisleModel.filter(x => x > 0))].sort((a, b) => a - b))

// 过道在网格中的显示列号：逻辑列 c 之后插入，显示列 = c + 前面过道数 + 1（过道占该列）
const totalDisplayCols = computed(() => cols.value + aisles.value.length)
const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${totalDisplayCols.value}, 1fr)`,
  gridTemplateRows: `repeat(${rows.value}, auto)`
}))
// 过道 gutter 所在显示列
const aisleDisplayColumns = computed(() =>
  aisles.value.map(a => a + aisles.value.filter(x => x <= a).length))
function displayCol(logicalCol) {
  return logicalCol + aisles.value.filter(a => a < logicalCol).length
}

async function loadConfig() {
  const c = await http.get('/seat/config')
  rows.value = c.rows; cols.value = c.cols
  const a = c.aisles || []
  for (let i = 0; i < 3; i++) aisleModel[i] = a[i] || 0
}
async function loadGrid() {
  const g = await http.get('/seat/grid')
  seats.value = g.seats
}
loadConfig().then(loadGrid)

function seatClass(s) {
  if (!s.studentId) return 'empty-seat'
  return s.gender === '女' ? 'girl' : 'boy'
}

function aislesPayload() {
  return [...new Set(aisleModel.filter(x => x > 0))].sort((a, b) => a - b)
}
async function saveConfig() {
  await http.post('/seat/config', { rows: rows.value, cols: cols.value, aisles: aislesPayload() })
  await loadGrid()
}
function onAisle() { saveConfig() }

async function randomSeat() {
  await saveConfig()
  await http.post('/seat/auto')
  ElMessage.success('排座完成')
  await loadGrid()
}
async function clearSeat() {
  await ElMessageBox.confirm('清空全部座位？', '提示', { type: 'warning' })
  await http.post('/seat/clear')
  ElMessage.success('已清空')
  await loadGrid()
}

// 拖拽交换座位；dragstart 时清除点击判定，避免松手误开宠物弹窗
function onDragStart(e, s) {
  dragFrom = s
  downPos = null
  e.dataTransfer.setData('text', s.row + '-' + s.col)
}
async function onDrop(e, target) {
  if (!dragFrom) return
  if (dragFrom.row === target.row && dragFrom.col === target.col) return
  await http.post('/seat/move', {
    fromRow: dragFrom.row, fromCol: dragFrom.col, toRow: target.row, toCol: target.col
  })
  dragFrom = null
  await loadGrid()
}

// ==================== 宠物园（逻辑复用 usePets） ====================
const {
  pets, selected, batchMode, floats,
  adoptDlg, detailDlg, evalDlg, graduateDlg, rankDlg, shopDlg, rulesDlg,
  adoptName, detailStudent, detailLogs, evalBatch, evalCat,
  gradStudent, ranking, shopTab, shopItems, exchanges, exchangeSid, itemForm, ruleCat, ruleForm,
  petImg, expPct, floatsOf, fmtTime, sourceText, rulesOfCat,
  onCardClick, togglePick, toggleBatch,
  openAdopt, doAdopt, revokeLog,
  openEvalSingle, openEvalBatch, applyRule, doGraduate,
  openRanking, openShop, doExchange, fulfill, saveItem, deleteItem,
  openRules, saveRule, toggleRule, deleteRule
} = usePets()

/** 取座位上学生对应的宠物状态（/pet/pets 返回全班，含未领养） */
function petOf(s) {
  return pets.value.find(p => p.studentId === s.studentId)
}
/** 已排座学生 id 集合 */
const seatedIds = computed(() => new Set(seats.value.filter(s => s.studentId).map(s => s.studentId)))
/** 未排座学生（在网格下方以宠物卡形式照料） */
const unSeatedPets = computed(() => pets.value.filter(p => !seatedIds.value.has(p.studentId)))

// 点击与拖拽区分：按下记录位置，抬起时位移 >6px 视为拖拽，否则视为点击查看/领养宠物
let downPos = null
function onSeatDown(e, s) {
  downPos = { x: e.clientX, y: e.clientY, s }
}
function onSeatUp(e) {
  if (!downPos) return
  const { x, y, s } = downPos
  downPos = null
  if (Math.hypot(e.clientX - x, e.clientY - y) > 6) return
  if (!s.studentId) return
  onCardClick(petOf(s))
}
</script>

<style scoped>
.cfg-label { color: #8a8fa3; font-size: 13px; margin-left: 6px; }
.legend { display: flex; align-items: center; gap: 18px; margin: 6px 0 14px; color: #6a6f82; font-size: 14px; }
.lg { display: flex; align-items: center; gap: 6px; }
.dot { width: 14px; height: 14px; border-radius: 4px; display: inline-block; }
.dot.boy { background: #d7e8fc; border: 1px solid #9dc4f5; }
.dot.girl { background: #fde4ee; border: 1px solid #f5b8d2; }
.aisle-bar { width: 22px; height: 8px; background: repeating-linear-gradient(90deg,#ccc 0 4px,transparent 4px 8px); border-radius: 2px; }
.tip { color: #b0b3c0; font-size: 12px; }
.podium {
  background: linear-gradient(135deg, #f7a8c8, #f492bb); color: #fff; text-align: center;
  font-weight: 800; font-size: 17px; padding: 14px; border-radius: 12px; margin-bottom: 16px;
}
.seat-grid { display: grid; gap: 10px; }
.aisle-gutter { min-width: 18px; }

/* 座位卡：宠物"坐"在座位上 */
.seat {
  border-radius: 12px; padding: 6px 4px; text-align: center; min-height: 136px;
  cursor: grab; display: flex; flex-direction: column; align-items: center;
}
.seat.boy { background: #e3eefc; border: 2px solid #b9d6f7; color: #3a6ea5; }
.seat.girl { background: #fde4ee; border: 2px solid #f7c3d8; color: #c2568a; }
.seat.empty-seat { background: #f7f8fc; border: 2px dashed #e3e6f0; color: #c0c4d0; display: flex; align-items: center; justify-content: center; }
.seat-pet { position: relative; width: 100%; display: flex; flex-direction: column; align-items: center; }
.seat-pet-img { width: 52px; height: 52px; object-fit: contain; }
.seat-pet-img.alive { animation: pet-breathe 3.2s ease-in-out infinite; filter: drop-shadow(0 4px 6px rgba(220,160,110,.3)); }
.seat-pet-img:not(.alive) { filter: grayscale(.35) opacity(.8); }
.seat-pet-img.dizzy { animation: pet-shake .5s ease-in-out infinite; }
.seat-pet-img.frozen { filter: hue-rotate(170deg) saturate(1.3) brightness(1.05) drop-shadow(0 0 6px rgba(120,190,255,.8)); }
.seat-pet-img.broken { filter: grayscale(.9) contrast(.92); }
.seat-flag { position: absolute; top: -4px; right: 14px; font-size: 14px; z-index: 2; }
.seat-flag.flag-fz { top: 12px; }
.seat-flag.flag-bk { top: 26px; right: 14px; }
.seat-flag.flag-grad {
  right: 6px; top: -6px; background: linear-gradient(135deg,#ffe28a,#f2a93a); color: #fff;
  border-radius: 9px; padding: 0 5px; font-size: 10px; font-weight: 800;
}
.seat-lv { margin-top: 1px; line-height: 1; }
.seat-exp { width: 78%; height: 5px; border-radius: 4px; background: rgba(120,90,60,.12); margin-top: 4px; overflow: hidden; }
.seat-exp-fill { height: 100%; border-radius: 4px; background: linear-gradient(90deg, #ffd98a, #ff9f5a); transition: width .4s; }
.seat-name { font-size: 15px; font-weight: 800; margin-top: 4px; }
.seat-pos { font-size: 10px; opacity: .65; margin-top: 1px; }
.empty { font-size: 14px; }

/* 未排座学生宠物卡区 */
.unseat-wrap { margin-top: 24px; }
.unseat-title { font-weight: 800; color: #8a5a34; margin-bottom: 12px; font-size: 15px; }
.pet-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 16px;
}
.pet-card {
  position: relative; border-radius: 20px; padding: 14px 12px 12px; text-align: center;
  background: linear-gradient(150deg, #fff7ec, #ffe9d2);
  border: 3px solid #fff; cursor: pointer; user-select: none;
  box-shadow: 0 6px 16px rgba(230,170,120,.22);
  transition: transform .15s, box-shadow .15s;
}
.pet-card:hover { transform: translateY(-4px); box-shadow: 0 12px 24px rgba(230,170,120,.32); }
.pet-card.picked { border-color: #ff9f5a; box-shadow: 0 0 0 3px rgba(255,159,90,.35); }
.pick-box { position: absolute; top: 10px; left: 10px; z-index: 3; }
.flag { position: absolute; top: 8px; z-index: 2; font-size: 18px; }
.flag-dizzy { right: 10px; animation: spin-star 1.4s linear infinite; }
.flag-frozen { right: 10px; top: 30px; }
.flag-broken { left: 10px; bottom: 46px; }
.flag-grad { right: 10px; background: linear-gradient(135deg,#ffe28a,#f2a93a); color: #fff;
  border-radius: 10px; padding: 1px 7px; font-size: 12px; font-weight: 800; }
.pet-img-wrap { position: relative; height: 118px; display: flex; align-items: center; justify-content: center; }
.pet-img { width: 104px; height: 104px; object-fit: contain; }
.pet-img.alive { animation: pet-breathe 3.2s ease-in-out infinite; filter: drop-shadow(0 6px 8px rgba(220,160,110,.3)); }
.pet-img:not(.alive) { filter: grayscale(.35) opacity(.8); }
.pet-card.dizzy .pet-img { animation: pet-shake .5s ease-in-out infinite; }
.pet-card.frozen { background: linear-gradient(150deg, #eaf6ff, #d4ebff); border-color: #bfe0ff; }
.pet-card.frozen .pet-img { filter: hue-rotate(170deg) saturate(1.3) brightness(1.05) drop-shadow(0 0 8px rgba(120,190,255,.8)); }
.pet-card.broken .pet-img { filter: grayscale(.9) contrast(.92); }
.pet-card.broken::after {
  content: ''; position: absolute; inset: 8px; border-radius: 16px; pointer-events: none;
  background: repeating-linear-gradient(45deg, transparent 0 14px, rgba(120,120,140,.12) 14px 15px);
}
.pet-name { font-size: 15px; font-weight: 800; color: #8a5a34; margin-top: 2px; }
.egg-name { color: #b08a66; }
.lv-badge {
  display: inline-block; margin-left: 6px; background: linear-gradient(135deg,#ffb38a,#ff9f5a);
  color: #fff; font-size: 11px; border-radius: 9px; padding: 1px 8px; font-weight: 800; vertical-align: middle;
}
.lv-badge.big { font-size: 13px; padding: 2px 10px; }
.stu-name { font-size: 13px; color: #a07a5c; margin-top: 5px; font-weight: 700; }
.exp-bar { height: 9px; border-radius: 6px; background: #ffe2c8; margin: 7px 10px 3px; overflow: hidden; }
.exp-fill { height: 100%; border-radius: 6px; background: linear-gradient(90deg, #ffd98a, #ff9f5a); transition: width .4s; }
.exp-text { font-size: 11px; color: #b48a68; }

/* 飘字 */
.float-score {
  position: absolute; left: 50%; top: 20px; font-size: 22px; font-weight: 900; pointer-events: none;
  animation: float-up 1.2s ease-out forwards; z-index: 5;
}
.float-score.good { color: #4caf7d; text-shadow: 0 2px 0 #fff; }
.float-score.bad { color: #ef6f6f; text-shadow: 0 2px 0 #fff; }

/* 批量浮动条 */
.batch-bar {
  position: sticky; top: 0; z-index: 20; margin-bottom: 14px;
  background: linear-gradient(135deg, #fff0e0, #ffe3cc); border: 2px solid #ffcf9f;
  border-radius: 16px; padding: 10px 18px; display: flex; align-items: center; gap: 14px;
  box-shadow: 0 6px 16px rgba(230,170,120,.3); font-weight: 700; color: #8a5a34;
}
.pick-num { color: #ff7f32; font-size: 18px; }
.bar-enter-active, .bar-leave-active { transition: all .25s; }
.bar-enter-from, .bar-leave-to { opacity: 0; transform: translateY(-10px); }

/* 领养弹窗 */
.adopt-body { text-align: center; padding: 6px 10px; }
.adopt-img { width: 170px; height: 170px; object-fit: contain; animation: pet-breathe 3s ease-in-out infinite; }
.adopt-tip { color: #a07a5c; font-size: 14px; line-height: 1.7; margin: 8px 0 14px; }

/* 详情弹窗 */
.detail-head { display: flex; gap: 16px; align-items: center; }
.detail-img { width: 120px; height: 120px; object-fit: contain; filter: drop-shadow(0 6px 10px rgba(220,160,110,.35)); }
.detail-info { flex: 1; }
.detail-name { font-size: 19px; font-weight: 800; color: #8a5a34; margin-bottom: 8px; }
.detail-line { font-size: 13px; color: #9a7a5c; margin: 5px 0; }
.badge-medal { margin-left: 3px; }
.log-title { font-weight: 800; color: #8a5a34; margin: 16px 0 8px; }
.log-list { max-height: 220px; overflow-y: auto; }
.log-row { display: flex; align-items: center; gap: 8px; padding: 7px 10px; border-radius: 12px; background: #fff7ee; margin-bottom: 6px; font-size: 13px; }
.log-score { font-weight: 900; width: 38px; }
.log-score.good { color: #4caf7d; }
.log-score.bad { color: #ef6f6f; }
.log-name { flex: 1; color: #6b5b73; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.log-time { color: #c0a898; font-size: 12px; }
.log-empty { text-align: center; color: #c0a898; padding: 18px 0; font-size: 13px; }

/* 评价项卡片 */
.rule-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.rule-card {
  border: 2px solid #fff; border-radius: 16px; padding: 12px 8px; cursor: pointer;
  display: flex; flex-direction: column; align-items: center; gap: 4px; transition: transform .12s;
  background: #f6f7fb;
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

/* 毕业仪式 */
.graduate-stage { text-align: center; padding: 20px 12px 14px; position: relative; overflow: hidden; }
.graduate-glow {
  position: absolute; top: -60px; left: 50%; transform: translateX(-50%);
  width: 320px; height: 320px; border-radius: 50%; pointer-events: none;
  background: radial-gradient(circle, rgba(255,220,120,.75) 0%, rgba(255,200,90,.25) 45%, transparent 70%);
  animation: glow-pulse 2s ease-in-out infinite;
}
.graduate-emoji { font-size: 64px; position: relative; animation: pet-breathe 2.4s ease-in-out infinite; }
.graduate-title {
  font-size: 26px; font-weight: 900; margin: 8px 0; position: relative;
  background: linear-gradient(135deg, #f2a93a, #e07b2a); -webkit-background-clip: text; background-clip: text; color: transparent;
}
.graduate-text { position: relative; color: #8a6a44; line-height: 1.9; font-size: 14px; }

/* 荣誉榜 */
.rank-list { max-height: 480px; overflow-y: auto; padding-right: 4px; }
.rank-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px; border-radius: 14px;
  margin-bottom: 7px; background: #fff7ee; font-size: 14px;
}
.rank-row.top3 { background: linear-gradient(135deg, #fff4d6, #ffe8c2); border: 2px solid #ffd98a; font-weight: 800; }
.rank-row.unranked { opacity: .55; }
.rank-medal { width: 44px; text-align: center; font-size: 17px; font-weight: 900; color: #b08a66; }
.rank-img { width: 38px; height: 38px; object-fit: contain; }
.rank-pet { font-weight: 800; color: #8a5a34; width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-stu { color: #9a7a5c; flex: 1; }
.rank-lv { font-size: 12px; color: #ff7f32; font-weight: 800; }
.rank-badges { font-size: 13px; }
.rank-total { font-weight: 800; color: #d9a52e; width: 64px; text-align: right; }

/* 小商店 */
.shop-stu-pick { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; font-weight: 700; color: #8a5a34; }
.shop-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.shop-card {
  border: 2px solid #fff; border-radius: 16px; padding: 12px 8px; text-align: center;
  background: linear-gradient(150deg, #fff7ec, #ffeeda); display: flex; flex-direction: column; align-items: center; gap: 3px;
}
.shop-icon { font-size: 34px; }
.shop-name { font-weight: 800; color: #8a5a34; font-size: 14px; }
.shop-price { font-weight: 900; color: #ff7f32; }
.shop-stock { font-size: 12px; color: #b09888; margin-bottom: 4px; }
.manage-title { font-weight: 800; color: #8a5a34; margin: 6px 0 8px; }
.item-form { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 4px; }
.form-hint { font-size: 12px; color: #c0a898; margin-bottom: 10px; }
.item-row, .ex-row {
  display: flex; align-items: center; gap: 10px; padding: 7px 10px; border-radius: 12px;
  background: #fff7ee; margin-bottom: 6px; font-size: 13px;
}
.shop-icon-sm { font-size: 20px; }
.item-name { font-weight: 700; color: #6b5b73; flex: 1; }
.item-meta { color: #a08a70; font-weight: 700; }
.item-meta.good { color: #4caf7d; }
.item-meta.bad { color: #ef6f6f; }
.ex-stu { font-weight: 800; color: #8a5a34; }
.ex-cost { color: #ef6f6f; font-weight: 800; }
.ex-time { color: #c0a898; font-size: 12px; flex: 1; }

/* 动画 */
@keyframes pet-breathe {
  0%, 100% { transform: translateY(0) rotate(-1.2deg); }
  50% { transform: translateY(-7px) rotate(1.2deg); }
}
@keyframes pet-shake {
  0%, 100% { transform: translateX(0) rotate(0); }
  25% { transform: translateX(-3px) rotate(-4deg); }
  75% { transform: translateX(3px) rotate(4deg); }
}
@keyframes spin-star {
  0% { transform: rotate(0) scale(1); }
  50% { transform: rotate(180deg) scale(1.2); }
  100% { transform: rotate(360deg) scale(1); }
}
@keyframes float-up {
  0% { opacity: 0; transform: translate(-50%, 10px) scale(.8); }
  20% { opacity: 1; }
  100% { opacity: 0; transform: translate(-50%, -46px) scale(1.25); }
}
@keyframes glow-pulse {
  0%, 100% { opacity: .7; transform: translateX(-50%) scale(1); }
  50% { opacity: 1; transform: translateX(-50%) scale(1.12); }
}
</style>
