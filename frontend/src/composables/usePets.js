import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

/** 评价项分类（固定四个 tab） */
export const PET_CATS = ['学习', '行为', '健康', '其他']

/**
 * 进化演出 6 套特效主题（fx_theme）：
 * beam=光柱/光环色，colors=confetti 色组，particles=蓄力时汇聚的 emoji。
 */
export const FX_THEMES = {
  gold:    { name: '祥云金', beam: '#FFD98A', colors: ['#FFD700', '#FFB347', '#FFF6E5'], particles: ['✨', '⭐'] },
  fire:    { name: '烈焰红', beam: '#FF8A5C', colors: ['#FF5A3C', '#FF9A3C', '#FFD23C'], particles: ['🔥', '💥'] },
  ice:     { name: '冰晶蓝', beam: '#8ECFFF', colors: ['#7EC8FF', '#C9ECFF', '#FFFFFF'], particles: ['❄️', '💎'] },
  thunder: { name: '雷霆紫', beam: '#B78CFF', colors: ['#A06BFF', '#FFE066', '#FFFFFF'], particles: ['⚡', '🌩'] },
  forest:  { name: '翡翠绿', beam: '#7FE0A8', colors: ['#6FD08C', '#FFB3D9', '#FFFFFF'], particles: ['🍃', '🌸'] },
  candy:   { name: '彩虹糖', beam: '#FF9EC4', colors: ['#FF9EC4', '#9EC8FF', '#BFF0C8', '#FFE08A', '#D9B8FF', '#FFC9A3'], particles: ['🌈', '🎉'] },
}

/** 模块级皮肤缓存（不使用 usePets 的页面如 Roster 经 setSkinCache 共享同一份映射） */
let skinCache = []
export function setSkinCache(list) { skinCache = list || [] }

// ---------- 全屏进化演出队列（模块级单例：SSE 遥控指令、座次表页、手机页共享同一队列状态） ----------
const evolveQueue = ref([])     // 待演分幕
const currentEvolve = ref(null) // 正在演的一幕（传给 EvolutionShow 组件）
let evolveBusy = false
let resolveScene = null

/** 遥控 SSE 是否在线：在线时跨页 pending 进化攒入待播列表，等手机指令集中播放（上课不打断投影） */
let remoteActive = false
export function setRemoteActive(v) { remoteActive = !!v }

/** 遥控在线期间攒下的待播进化（手机页显示清单、PC 端收到播放指令后入队） */
const heldEvolves = ref([])

/**
 * 演出事件入队（来源：同步接口 levelUps / 跨页 pendingEvolves / 待播 pendingHatches / 待播列表播放）。
 * kind='hatch' 破壳一幕（蛋→Lv1 幼体，固定图）；kind='evolve' 进化一幕（fromLevel→toLevel）。
 * 去重按 (studentId, kind)：同一学生破壳后可紧随一幕进化（双喜临门），不算重复。
 */
function enqueueEvolves(ups) {
  if (!ups?.length) return
  for (const u of ups) {
    const kind = u.kind || 'evolve'
    const key = u.skinKey || 'dragon'
    let fromLevel, toLevel, fromImg, toImg
    if (kind === 'hatch') {
      // 破壳演出固定 蛋 → Lv1；蛋期间攒分导致的连升由紧随其后的 evolve 幕承担
      fromLevel = 0; toLevel = 1
      fromImg = skinImg(key, 'egg')
      toImg = skinImg(key, 1)
    } else {
      fromLevel = u.fromLevel ?? (u.toLevel - 1)
      toLevel = u.toLevel
      fromImg = skinImg(key, fromLevel)
      toImg = skinImg(key, toLevel)
    }
    const dup = (currentEvolve.value?.studentId === u.studentId && currentEvolve.value?.kind === kind) ||
      evolveQueue.value.some(q => q.studentId === u.studentId && q.kind === kind)
    if (dup) continue
    evolveQueue.value.push({
      kind, studentId: u.studentId, name: u.name, petName: u.petName,
      fromLevel, toLevel, skinKey: key, fxTheme: u.fxTheme || 'gold',
      fromImg, toImg, pending: !!u.pending
    })
  }
  void pumpEvolve()
}

/** 逐幕播放，幕间 0.3s；组件播完/跳过调 onEvolveDone 放行 */
async function pumpEvolve() {
  if (evolveBusy) return
  evolveBusy = true
  while (evolveQueue.value.length) {
    currentEvolve.value = evolveQueue.value.shift()
    await new Promise(res => { resolveScene = res })
    await new Promise(r => setTimeout(r, 300))
  }
  currentEvolve.value = null
  evolveBusy = false
}

/** 一幕结束：pending 来源通知后端——破壳幕调 hatch-seen（置 hatched=1 并结算等级），进化幕调 evolve-seen 清标记 */
async function onEvolveDone() {
  const scene = currentEvolve.value
  currentEvolve.value = null
  if (scene?.pending) {
    try {
      if (scene.kind === 'hatch') await http.post('/pet/hatch-seen', { studentId: scene.studentId })
      else await http.post('/pet/evolve-seen', { studentId: scene.studentId })
    } catch (e) { /* 标记清理失败不阻塞下一幕 */ }
  }
  resolveScene?.()
  resolveScene = null
}

/** pending 进化攒入待播列表（遥控在线时 loadPets 走这里），按学生去重 */
function holdEvolves(ups) {
  for (const u of ups || []) {
    if (heldEvolves.value.some(h => h.studentId === u.studentId)) continue
    heldEvolves.value.push({ ...u, pending: true })
  }
}

/**
 * 播放待播进化：不传 studentId 播全部（手机"全部播放"），传则只播指定一条。
 * 从待播列表移除并入演出队列；演完由 onEvolveDone 调 evolve-seen 清后端标记。
 */
export function playHeld(studentId) {
  const pick = studentId
    ? heldEvolves.value.filter(h => h.studentId === studentId)
    : [...heldEvolves.value]
  if (!pick.length) return
  heldEvolves.value = heldEvolves.value.filter(h => !pick.includes(h))
  enqueueEvolves(pick)
}

/** 皮肤某阶段图片 URL（stage: 'egg' 或 1~5）；缺图兜底内置云龙 */
export function skinImg(skinKey, stage) {
  const sk = skinCache.find(s => s.skinKey === (skinKey || 'dragon'))
  const url = sk?.imgs?.[String(stage)]
  if (url) return url
  return stage === 'egg' ? '/pets/dragon_egg.webp' : `/pets/dragon_${stage}.webp`
}

/**
 * 宠物行（/pet/pets 或 /pet/ranking 返回）→ 当前形态图。
 * 未领养（petId===null）显示空位蛋；已领养但孵化中（hatched===0）也显示蛋（神秘蛋，视觉另加暖光）；
 * 已破壳按 level 取形态图。ranking 行无 petId/hatched 字段，按 level 取图。
 */
export function petImgOf(p) {
  const key = p?.skinKey || p?.petKey || 'dragon'
  if (!p || p.petId === null) return skinImg(key, 'egg')
  if (p.hatched === 0) return skinImg(key, 'egg')
  return skinImg(key, (p && p.level) || 1)
}

/**
 * 班级宠物园逻辑复用（座次表页使用）：
 * 管理全班宠物状态、评价项、皮肤、进化演出队列、各类弹窗开关与全部宠物操作
 * （领养/评价/撤回/毕业/荣誉榜/商店/评价项 CRUD）。
 * 弹窗模板由调用方提供；调用方只需把返回的状态与函数绑定到模板。
 * 创建时即自动加载宠物列表、评价项与皮肤。
 * @param {object} [opts]
 * @param {boolean} [opts.shows=true] 是否本地播放全屏演出（挂了 EvolutionShow 的 PC 座次表页为 true；
 *   手机页传 false——手机不演破壳/进化，pending 进化仍攒 heldEvolves 清单供手机指挥投影播放）
 */
export function usePets(opts = {}) {
  const showsEnabled = opts.shows !== false
  const pets = ref([])            // 全班宠物状态（含未领养，petId 为 null）
  const rules = ref([])           // 评价项
  const skins = ref([])           // 皮肤（内置 + 本账号自创）
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
  const skinMakerDlg = ref(false) // 自创宠物弹窗

  const adoptName = ref('')
  const adoptSkin = ref('dragon') // 领养弹窗选中的皮肤 skinKey
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

  // 飘字/迸溅动画：text=加减分文字，ems=迸溅 emoji（dx=水平偏移，delay=错峰毫秒）
  let floatSeq = 0
  const floats = ref([])
  // 宠物图 Q 弹/缩一下：sid -> 'pop'（加分）| 'shrink'（扣分）
  const popKind = ref(new Map())
  // 孵化中蛋被点击：sid -> true（左右摇晃 0.5s 后清除）
  const eggWiggle = ref(new Map())

  /** 宠物图片：按皮肤映射，未领养显示蛋 */
  function petImg(p) { return petImgOf(p) }

  /** 本级经验进度百分比 */
  function expPct(p) {
    if (p.level >= 5) return 100
    const span = p.expTarget - p.expStart
    return Math.max(4, Math.min(100, Math.round(((p.exp - p.expStart) / span) * 100)))
  }

  /** 破壳所需累计成长值（与后端 PetService.HATCH_EXP 保持一致） */
  const HATCH_EXP = 20
  /** 蛋孵化进度百分比：累计成长值 / HATCH_EXP（蛋期间加分照常累计，攒够即破壳） */
  function hatchPct(p) {
    return Math.max(0, Math.min(100, Math.round(((p.exp || 0) / HATCH_EXP) * 100)))
  }

  function floatsOf(sid) {
    return floats.value.filter(f => f.sid === sid)
  }

  /**
   * 评价/兑换后在卡片上飘反馈：
   * 加分飘金色 +N + 2~3 个 ⭐✨ 迸溅 + 宠物 Q 弹；扣分飘灰红 -N + 💦 + 图缩一下；
   * opts.coins=兑换成功，三枚 🪙 抛物线蹦出。
   */
  function addFloat(sid, score, opts = {}) {
    const id = ++floatSeq
    const good = score > 0
    const ems = []
    if (opts.coins) {
      ems.push(
        { emoji: '🪙', dx: -30, delay: 0, cls: 'coin' },
        { emoji: '🪙', dx: 0, delay: 100, cls: 'coin' },
        { emoji: '🪙', dx: 30, delay: 200, cls: 'coin' })
    } else if (good) {
      const n = 2 + Math.floor(Math.random() * 2)
      for (let i = 0; i < n; i++) {
        ems.push({ emoji: i % 2 ? '✨' : '⭐', dx: Math.round(Math.random() * 60 - 30), delay: i * 60 })
      }
    } else if (score < 0) {
      ems.push({ emoji: '💦', dx: 22, delay: 0 })
    }
    floats.value.push({ id, sid, text: score ? (good ? '+' : '') + score : '', good, ems })
    if (score !== 0) {
      popKind.value.set(sid, good ? 'pop' : 'shrink')
      const kind = good ? 'pop' : 'shrink'
      setTimeout(() => { if (popKind.value.get(sid) === kind) popKind.value.delete(sid) }, good ? 430 : 330)
    }
    setTimeout(() => { floats.value = floats.value.filter(f => f.id !== id) }, 1500)
  }

  function fmtTime(t) {
    return String(t || '').replace('T', ' ').slice(0, 16)
  }

  function sourceText(s) {
    return { AUTO_ATTENDANCE: '考勤联动', AUTO_HOMEWORK: '作业联动', AUTO_DISCIPLINE: '纪律联动', AUTO_SCORE: '成绩发奖', REMOTE: '手机遥控' }[s] || '评价'
  }

  // ---------- 数据加载 ----------
  /**
   * 加载全班宠物；返回体为 {pets, pendingEvolves, pendingHatches}：
   * - pendingHatches：攒够成长值（exp>=HATCH_EXP）待破壳的蛋，逐个入队 hatch 幕；若蛋期间攒够分（toLevel>1），
   *   紧随一幕 evolve（破壳即连升，双喜临门）；破壳幕优先于进化幕播放，演完调 hatch-seen。
   * - pendingEvolves：跨页面/自动联动/手机遥控导致的待演升级——遥控在线时攒入 heldEvolves 待播列表
   *   （等手机"播放"指令），否则入队补播（演完调 evolve-seen）。
   */
  async function loadPets() {
    const data = await http.get('/pet/pets')
    pets.value = data.pets || data
    // 破壳幕（蛋只在 PC 座次表播，不走手机遥控待播）：每枚蛋一幕 hatch，toLevel>1 再加一幕 evolve
    const hatchScenes = []
    for (const h of (data.pendingHatches || [])) {
      hatchScenes.push({ kind: 'hatch', ...h, pending: true })
      if (h.toLevel > 1) {
        hatchScenes.push({
          kind: 'evolve', studentId: h.studentId, name: h.name, petName: h.petName,
          skinKey: h.skinKey, fxTheme: h.fxTheme, fromLevel: 1, toLevel: h.toLevel, pending: true
        })
      }
    }
    // 破壳演出只在 PC 座次表页自动播放（手机页 shows=false：不入队，由投影端演完调 hatch-seen）
    if (showsEnabled && hatchScenes.length) enqueueEvolves(hatchScenes)
    const pending = (data.pendingEvolves || []).map(u => ({ ...u, kind: 'evolve', pending: true }))
    if (remoteActive) holdEvolves(pending)
    else enqueueEvolves(pending)
  }
  async function loadRules() {
    rules.value = await http.get('/pet/rules')
  }
  /** 加载皮肤（内置 + 本账号自创），并同步模块级缓存供 petImg 映射 */
  async function loadSkins() {
    skins.value = await http.get('/pet/skins')
    setSkinCache(skins.value)
  }
  function rulesOfCat(c) {
    return rules.value.filter(r => r.category === c)
  }

  // 进化演出队列/待播列表为模块级单例（enqueueEvolves/currentEvolve/onEvolveDone/heldEvolves/playHeld），
  // 供 SSE 遥控指令与座次表页共享；此处直接引用。

  // ---------- 卡片交互（未排座宠物卡用；座位卡点击由调用方区分拖拽后调用） ----------
  function onCardClick(p) {
    if (batchMode.value) { togglePick(p.studentId); return }
    if (!p.petId) { openAdopt(p); return }
    // 孵化中的蛋：摇晃 + 提示攒能量进度，不弹领养/详情窗（保持期待感）
    if (p.hatched === 0) { wiggleEgg(p); return }
    openDetail(p)
  }

  /** 孵化中蛋点击反馈：左右摇晃 0.5s + ElMessage 提示还差多少成长值破壳；加分飘字等照常，不受影响 */
  function wiggleEgg(p) {
    eggWiggle.value.set(p.studentId, true)
    setTimeout(() => eggWiggle.value.delete(p.studentId), 500)
    const left = Math.max(0, HATCH_EXP - (p.exp || 0))
    ElMessage.info(left > 0
      ? `🥚 ${p.petName || '小精灵'} 还在蛋里攒能量，再得 ${left} 成长值就破壳啦！`
      : `🥚 ${p.petName || '小精灵'} 能量已满，回到座位上就会破壳啦！`)
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
  async function openAdopt(p) {
    adoptTarget.value = p
    adoptName.value = ''
    adoptSkin.value = 'dragon'
    await loadSkins()
    adoptDlg.value = true
  }
  /** 打开「自创宠物」弹窗（保存后由 SkinMakerDlg emit saved 触发刷新与选中） */
  function openSkinMaker() {
    skinMakerDlg.value = true
  }
  /** 自创皮肤保存回调：刷新皮肤列表并选中新皮肤 */
  async function onSkinSaved(skinKey) {
    await loadSkins()
    if (skinKey) adoptSkin.value = skinKey
  }
  /** 删除自创皮肤（后端禁删已被引用的）；删除后选中项回退内置云龙 */
  async function deleteSkin(sk) {
    try {
      await ElMessageBox.confirm(`确认删除自创皮肤「${sk.name}」？`, '提示', { type: 'warning' })
    } catch { return }
    await http.delete(`/pet/skin/${sk.id}`)
    ElMessage.success('皮肤已删除')
    if (adoptSkin.value === sk.skinKey) adoptSkin.value = 'dragon'
    await loadSkins()
  }
  async function doAdopt() {
    await http.post('/pet/adopt', {
      studentId: adoptTarget.value.studentId,
      petKey: adoptSkin.value,
      petName: adoptName.value
    })
    ElMessage.success(`领养成功！给 ${adoptName.value.trim() || '小精灵'} 攒够 ${HATCH_EXP} 成长值就会破壳啦~ 🥚`)
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
  /**
   * 点评价项：单人即时生效 / 批量一次生效。
   * 飘字错峰 0.1s（批量像放小鞭炮）；响应 levelUps 排入全屏进化演出。
   */
  async function applyRule(rule) {
    const sids = evalBatch.value ? [...selected.value] : [evalTarget.value.studentId]
    const r = evalBatch.value
      ? await http.post('/pet/evaluate-batch', { ruleId: rule.id, studentIds: sids })
      : await http.post('/pet/evaluate', { studentId: sids[0], ruleId: rule.id })
    if (!evalBatch.value && !r.affected) { ElMessage.warning('该学生还未领养宠物'); return }
    sids.forEach((sid, i) => setTimeout(() => addFloat(sid, rule.score), i * 100))
    if (evalBatch.value) {
      ElMessage.success(`已对 ${r.affected} 名学生的宠物生效`)
      selected.value = new Set()
      batchMode.value = false
    }
    evalDlg.value = false
    await loadPets()
    enqueueEvolves(r.levelUps)
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
    addFloat(exchangeSid.value, 0, { coins: true })
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

  // 初始加载宠物、评价项与皮肤
  void Promise.all([loadPets(), loadRules(), loadSkins()])

  return {
    // 状态
    pets, rules, skins, selected, batchMode, floats, popKind, eggWiggle,
    adoptDlg, detailDlg, evalDlg, graduateDlg, rankDlg, shopDlg, rulesDlg, skinMakerDlg,
    adoptName, adoptSkin, adoptTarget, detailStudent, detailLogs, evalBatch, evalTarget, evalCat,
    gradStudent, ranking, shopTab, shopItems, exchanges, exchangeSid, itemForm, ruleCat, ruleForm,
    // 进化演出（模块级单例）
    currentEvolve, enqueueEvolves, onEvolveDone, heldEvolves, playHeld,
    // 工具
    petImg, skinImg, expPct, hatchPct, HATCH_EXP, floatsOf, fmtTime, sourceText, rulesOfCat, FX_THEMES,
    // 交互
    onCardClick, togglePick, toggleBatch,
    openAdopt, openSkinMaker, onSkinSaved, deleteSkin, doAdopt, openDetail, revokeLog,
    openEvalSingle, openEvalBatch, applyRule, doGraduate,
    openRanking, openShop, doExchange, fulfill, saveItem, deleteItem,
    openRules, saveRule, toggleRule, deleteRule,
    loadPets, loadSkins
  }
}
