<template>
  <div class="pet-page">
    <!-- 顶部工具栏 -->
    <div class="page-toolbar">
      <h2 class="page-title">🐾 班级宠物园</h2>
      <div class="spacer"></div>
      <el-button round @click="openRanking">🏆 荣誉榜</el-button>
      <el-button round @click="openShop">🛍 小商店</el-button>
      <el-button round @click="openRules">⚙ 评价项管理</el-button>
      <el-button round :type="batchMode ? 'success' : 'primary'" @click="toggleBatch">
        {{ batchMode ? `✅ 完成批量（已选 ${selected.size}）` : '📝 批量评价' }}
      </el-button>
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

    <!-- 宠物卡网格 -->
    <div class="pet-grid">
      <div v-for="p in pets" :key="p.studentId" class="pet-card"
        :class="{ dizzy: p.dizzy, frozen: p.frozen, broken: p.broken, picked: selected.has(p.studentId) }"
        @click="onCardClick(p)">
        <el-checkbox v-if="batchMode" class="pick-box" :model-value="selected.has(p.studentId)"
          @click.stop @change="togglePick(p.studentId)" />
        <!-- 负面状态角标 -->
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
          <div v-if="!rulesOfCat(c).length" class="cat-empty">该分类暂无评价项，可在「⚙ 评价项管理」中添加</div>
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
        <div v-for="(r, i) in ranking" :key="r.studentId" class="rank-row"
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
            <div v-if="!shopItems.length" class="cat-empty">还没有商品，去「📦 商品管理」添加吧</div>
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
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

/** 评价项分类（固定四个 tab） */
const CATS = ['学习', '行为', '健康', '其他']

const pets = ref([])           // 全班宠物状态
const rules = ref([])          // 评价项
const selected = ref(new Set()) // 批量模式选中的学生 id
const batchMode = ref(false)

// 弹窗开关
const adoptDlg = ref(false)
const detailDlg = ref(false)
const evalDlg = ref(false)
const graduateDlg = ref(false)
const rankDlg = ref(false)
const shopDlg = ref(false)
const rulesDlg = ref(false)

const adoptName = ref('')
const adoptTarget = ref(null)
const detailStudent = ref(null)
const detailLogs = ref([])
const evalBatch = ref(false)
const evalTarget = ref(null)
const evalCat = ref('学习')
const gradStudent = ref(null)
const ranking = ref([])
const shopTab = ref('exchange')
const shopItems = ref([])
const exchanges = ref([])
const exchangeSid = ref(null)
const itemForm = ref({ icon: '', name: '', price: 10, stock: -1 })
const ruleCat = ref('学习')
const ruleForm = ref({ icon: '', name: '', score: 1 })

// 飘字动画
let floatSeq = 0
const floats = ref([])

/** 宠物图片：未领养显示蛋，已领养按等级显示形态 */
function petImg(p) {
  return p.petId ? `/pets/dragon_${p.level}.webp` : '/pets/dragon_egg.webp'
}

/** 本级经验进度百分比 */
function expPct(p) {
  if (p.level >= 5) return 100
  const span = p.expTarget - p.expStart
  return Math.max(4, Math.min(100, Math.round(((p.exp - p.expStart) / span) * 100)))
}

function floatsOf(sid) {
  return floats.value.filter(f => f.sid === sid)
}

/** 评价后在卡片上飘 +x/-x */
function addFloat(sid, score) {
  const id = ++floatSeq
  floats.value.push({ id, sid, text: (score > 0 ? '+' : '') + score, good: score > 0 })
  setTimeout(() => { floats.value = floats.value.filter(f => f.id !== id) }, 1200)
}

function fmtTime(t) {
  return String(t || '').replace('T', ' ').slice(0, 16)
}

function sourceText(s) {
  return { AUTO_ATTENDANCE: '考勤联动', AUTO_HOMEWORK: '作业联动', AUTO_DISCIPLINE: '纪律联动', AUTO_SCORE: '成绩发奖' }[s] || '评价'
}

// ---------- 数据加载 ----------
async function loadPets() {
  pets.value = await http.get('/pet/pets')
}
async function loadRules() {
  rules.value = await http.get('/pet/rules')
}
function rulesOfCat(c) {
  return rules.value.filter(r => r.category === c)
}

// ---------- 卡片交互 ----------
function onCardClick(p) {
  if (batchMode.value) { togglePick(p.studentId); return }
  if (p.petId) openDetail(p)
  else openAdopt(p)
}
function togglePick(sid) {
  const s = new Set(selected.value)
  s.has(sid) ? s.delete(sid) : s.add(sid)
  selected.value = s
}
function toggleBatch() {
  batchMode.value = !batchMode.value
  if (!batchMode.value) selected.value = new Set()
}

// ---------- 领养 ----------
function openAdopt(p) {
  adoptTarget.value = p
  adoptName.value = ''
  adoptDlg.value = true
}
async function doAdopt() {
  await http.post('/pet/adopt', { studentId: adoptTarget.value.studentId, petKey: 'dragon', petName: adoptName.value })
  ElMessage.success('领养成功！精灵破壳啦 🐣')
  adoptDlg.value = false
  await loadPets()
}

// ---------- 详情 ----------
async function openDetail(p) {
  detailStudent.value = p
  detailDlg.value = true
  detailLogs.value = await http.get('/pet/logs', { params: { studentId: p.studentId } })
}
async function revokeLog(logId) {
  await http.post('/pet/revoke', { logId })
  ElMessage.success('已撤回，成长值回退')
  detailLogs.value = await http.get('/pet/logs', { params: { studentId: detailStudent.value.studentId } })
  await loadPets()
  detailStudent.value = pets.value.find(x => x.studentId === detailStudent.value.studentId)
}

// ---------- 评价 ----------
function openEvalSingle(p) {
  evalBatch.value = false
  evalTarget.value = p
  evalCat.value = '学习'
  evalDlg.value = true
}
function openEvalBatch() {
  if (!selected.value.size) return
  evalBatch.value = true
  evalCat.value = '学习'
  evalDlg.value = true
}
/** 点评价项：单人即时生效 / 批量一次生效，并飘字 */
async function applyRule(rule) {
  if (evalBatch.value) {
    const r = await http.post('/pet/evaluate-batch', { ruleId: rule.id, studentIds: [...selected.value] })
    ElMessage.success(`已对 ${r.affected} 名学生的宠物生效`)
    selected.value = new Set()
    batchMode.value = false
  } else {
    const r = await http.post('/pet/evaluate', { studentId: evalTarget.value.studentId, ruleId: rule.id })
    if (!r.affected) { ElMessage.warning('该学生还未领养宠物'); return }
    addFloat(evalTarget.value.studentId, rule.score)
  }
  evalDlg.value = false
  await loadPets()
  if (detailDlg.value && detailStudent.value) {
    detailStudent.value = pets.value.find(x => x.studentId === detailStudent.value.studentId)
    detailLogs.value = await http.get('/pet/logs', { params: { studentId: detailStudent.value.studentId } })
  }
}

// ---------- 毕业 ----------
async function doGraduate() {
  try {
    await ElMessageBox.confirm('宠物满级啦！确认举行毕业典礼？毕业后将获得毕业徽章并重新开始成长。',
      '🎓 毕业典礼', { confirmButtonText: '举行毕业典礼', cancelButtonText: '再等等', type: 'warning' })
  } catch { return }
  gradStudent.value = { ...detailStudent.value }
  await http.post('/pet/graduate', { studentId: detailStudent.value.studentId })
  detailDlg.value = false
  graduateDlg.value = true
  await loadPets()
}

// ---------- 荣誉榜 ----------
async function openRanking() {
  rankDlg.value = true
  ranking.value = await http.get('/pet/ranking')
}

// ---------- 小商店 ----------
async function openShop() {
  shopDlg.value = true
  shopTab.value = 'exchange'
  await Promise.all([loadShop(), loadExchanges()])
  if (!exchangeSid.value) {
    const first = pets.value.find(p => p.petId)
    if (first) exchangeSid.value = first.studentId
  }
}
async function loadShop() { shopItems.value = await http.get('/pet/shop-items') }
async function loadExchanges() { exchanges.value = await http.get('/pet/exchanges') }

async function doExchange(it) {
  if (!exchangeSid.value) { ElMessage.warning('请先选择学生'); return }
  await http.post('/pet/exchange', { studentId: exchangeSid.value, itemId: it.id })
  ElMessage.success(`兑换成功：${it.name}，记得线下兑现哦 🎁`)
  await Promise.all([loadShop(), loadExchanges(), loadPets()])
}
async function fulfill(id) {
  await http.put(`/pet/exchange/${id}/fulfill`)
  ElMessage.success('已兑现 ✅')
  await loadExchanges()
}
async function saveItem() {
  if (!itemForm.value.name.trim()) { ElMessage.warning('请填写商品名称'); return }
  await http.post('/pet/shop-item', { ...itemForm.value })
  ElMessage.success('商品已上架')
  itemForm.value = { icon: '', name: '', price: 10, stock: -1 }
  await loadShop()
}
async function deleteItem(id) {
  try {
    await ElMessageBox.confirm('确认下架该商品？', '提示', { type: 'warning' })
  } catch { return }
  await http.delete(`/pet/shop-item/${id}`)
  await loadShop()
}

// ---------- 评价项管理 ----------
function openRules() {
  rulesDlg.value = true
  ruleCat.value = '学习'
  loadRules()
}
async function saveRule(cat) {
  if (!ruleForm.value.name.trim()) { ElMessage.warning('请填写评价项名称'); return }
  await http.post('/pet/rule', { category: cat, ...ruleForm.value })
  ElMessage.success('评价项已添加')
  ruleForm.value = { icon: '', name: '', score: 1 }
  await loadRules()
}
async function toggleRule(r, enabled) {
  await http.put(`/pet/rule/${r.id}`,
    { category: r.category, name: r.name, score: r.score, icon: r.icon, enabled })
  await loadRules()
}
async function deleteRule(id) {
  try {
    await ElMessageBox.confirm('确认删除该评价项？', '提示', { type: 'warning' })
  } catch { return }
  await http.delete(`/pet/rule/${id}`)
  await loadRules()
}

onMounted(async () => {
  await Promise.all([loadPets(), loadRules()])
})
</script>

<style scoped>
.pet-page { padding-bottom: 30px; }

/* 宠物卡网格 */
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

/* 负面状态角标 */
.flag { position: absolute; top: 8px; z-index: 2; font-size: 18px; }
.flag-dizzy { right: 10px; animation: spin-star 1.4s linear infinite; }
.flag-frozen { right: 10px; top: 30px; }
.flag-broken { left: 10px; bottom: 46px; }
.flag-grad { right: 10px; background: linear-gradient(135deg,#ffe28a,#f2a93a); color: #fff;
  border-radius: 10px; padding: 1px 7px; font-size: 12px; font-weight: 800; }

.pet-img-wrap { position: relative; height: 118px; display: flex; align-items: center; justify-content: center; }
.pet-img { width: 104px; height: 104px; object-fit: contain; }
/* 未领养蛋：稍暗；已领养：呼吸浮动 */
.pet-img.alive { animation: pet-breathe 3.2s ease-in-out infinite; filter: drop-shadow(0 6px 8px rgba(220,160,110,.3)); }
.pet-img:not(.alive) { filter: grayscale(.35) opacity(.8); }

/* 负面状态特效 */
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
