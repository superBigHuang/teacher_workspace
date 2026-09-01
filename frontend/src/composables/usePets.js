import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

/** 评价项分类（固定四个 tab） */
export const PET_CATS = ['学习', '行为', '健康', '其他']

/**
 * 班级宠物园逻辑复用（座次表页使用）：
 * 管理全班宠物状态、评价项、各类弹窗开关与全部宠物操作（领养/评价/撤回/毕业/荣誉榜/商店/评价项 CRUD）。
 * 弹窗模板由调用方提供；调用方只需把返回的状态与函数绑定到模板。
 * 创建时即自动加载宠物列表与评价项。
 */
export function usePets() {
  const pets = ref([])            // 全班宠物状态（含未领养，petId 为 null）
  const rules = ref([])           // 评价项
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

  // ---------- 卡片交互（未排座宠物卡用；座位卡点击由调用方区分拖拽后调用） ----------
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
  /** 撤回一条成长流水并回滚成长值，同步刷新详情与列表 */
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

  // 初始加载宠物与评价项
  void Promise.all([loadPets(), loadRules()])

  return {
    // 状态
    pets, rules, selected, batchMode, floats,
    adoptDlg, detailDlg, evalDlg, graduateDlg, rankDlg, shopDlg, rulesDlg,
    adoptName, adoptTarget, detailStudent, detailLogs, evalBatch, evalTarget, evalCat,
    gradStudent, ranking, shopTab, shopItems, exchanges, exchangeSid, itemForm, ruleCat, ruleForm,
    // 工具
    petImg, expPct, floatsOf, fmtTime, sourceText, rulesOfCat,
    // 交互
    onCardClick, togglePick, toggleBatch,
    openAdopt, doAdopt, openDetail, revokeLog,
    openEvalSingle, openEvalBatch, applyRule, doGraduate,
    openRanking, openShop, doExchange, fulfill, saveItem, deleteItem,
    openRules, saveRule, toggleRule, deleteRule,
    loadPets
  }
}
