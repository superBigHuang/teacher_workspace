<template>
  <div class="ab-root">
    <!-- 操作条 -->
    <div class="ab-toolbar">
      <el-radio-group v-model="viewMode" size="small">
        <el-radio-button value="defs">🏅 成就榜</el-radio-button>
        <el-radio-button value="rank">🏆 点数排行</el-radio-button>
      </el-radio-group>
      <span class="muted" v-if="data">本学期 {{ data.term }} · 全班 {{ data.classSize }} 人</span>
      <div class="spacer"></div>
      <el-button type="warning" plain :icon="Refresh" :loading="scanning" @click="doScan(true)">🔄 扫描</el-button>
      <el-button type="primary" :icon="Plus" @click="openCreate">➕ 创建成就</el-button>
    </div>

    <template v-if="data">
      <!-- 点数排行视图 -->
      <div v-if="viewMode === 'rank'" class="ab-rank-list">
        <div v-for="(r, i) in data.rankings" :key="r.studentId" class="ab-rank-row" :class="{ top3: i < 3 }">
          <span class="rank-no">{{ ['🥇','🥈','🥉'][i] || i + 1 }}</span>
          <span class="rank-name">{{ r.name }}</span>
          <el-tag size="small" :type="r.gender === '女' ? 'danger' : 'primary'">{{ r.gender }}</el-tag>
          <span class="rank-badges">
            <i class="dot c-bronze"></i>{{ r.bronze || 0 }}
            <i class="dot c-silver"></i>{{ r.silver || 0 }}
            <i class="dot c-gold"></i>{{ r.gold || 0 }}
            <i class="dot c-plat"></i>{{ r.platinum || 0 }}
          </span>
          <div class="spacer"></div>
          <b class="rank-pts">{{ r.points }} 分</b>
          <span class="muted">{{ r.cnt }} 枚</span>
        </div>
      </div>

      <!-- 成就榜视图：左定义列表 + 右获得名单 -->
      <div v-else class="ab-body">
        <div class="ab-defs">
          <div v-for="d in data.defs" :key="d.id" class="ab-def-row" :class="{ active: selectedId === d.id, off: !d.enabled }" @click="selectedId = d.id">
            <BadgeIcon :icon="d.icon" :tier="d.tier" :earned="true" :size="40" :animate="false" />
            <div class="ab-def-main">
              <div class="ab-def-name">{{ d.name }}
                <el-tag v-if="d.builtin" size="small" effect="plain" type="info">内置</el-tag>
                <el-tag v-else size="small" effect="plain" type="success">自定义</el-tag>
              </div>
              <div class="ab-def-meta">
                <template v-if="d.tiered">
                  <el-tag size="small" type="warning" effect="plain" class="tier-tag">🥉🥈🥇 三档进阶</el-tag>
                  <span class="muted tier-steps">{{ tierStepsText(d) }}</span>
                </template>
                <el-tag v-else size="small" :color="tierColor(d.tier)" effect="dark" class="tier-tag">{{ tierText(d.tier) }} · {{ d.points }}分</el-tag>
                <el-tag size="small" :type="rarityType(d.rarity)" effect="plain">{{ rarityText(d.rarity) }}</el-tag>
                <span class="muted">{{ d.earnedCount }} 人获得</span>
              </div>
            </div>
            <div class="ab-def-ops" @click.stop>
              <el-switch :model-value="d.enabled === 1" @change="v => toggleDef(d, v)" size="small" />
              <el-button v-if="!d.builtin" size="small" text type="danger" :icon="Delete" @click="removeDef(d)" />
            </div>
          </div>
        </div>

        <div class="ab-detail" v-if="selected">
          <div class="ab-detail-head">
            <BadgeIcon :icon="selected.icon" :tier="selected.tier" :earned="true" :size="56" :animate="false" />
            <div>
              <div class="ab-detail-name">{{ selected.name }}</div>
              <div class="muted">{{ selected.description || '老师自定义成就' }}</div>
              <div class="ab-detail-meta">
                <template v-if="selected.tiered">
                  <el-tag size="small" type="warning" effect="dark">🥉🥈🥇 三档进阶</el-tag>
                  <span class="muted tier-steps">{{ tierStepsText(selected) }}</span>
                </template>
                <el-tag v-else size="small" :color="tierColor(selected.tier)" effect="dark">{{ tierText(selected.tier) }} · {{ selected.points }} 点</el-tag>
                <el-tag size="small" :type="rarityType(selected.rarity)">{{ rarityText(selected.rarity) }}</el-tag>
                <span class="muted">{{ selected.kind === 'AUTO' ? '自动评定' : '手动授予' }}</span>
              </div>
            </div>
            <div class="spacer"></div>
            <el-button type="primary" plain :icon="Present" @click="openAward">🎁 授予学生</el-button>
          </div>

          <!-- 内置可进阶成就：三档门槛编辑（保存后下次扫描生效） -->
          <div v-if="selected.builtin && selected.tiered" class="ab-thresh">
            <span class="muted">三档门槛：</span>
            <span v-for="(t, i) in editTiers" :key="t.tier" class="thresh-item">
              {{ TIER_EMOJI[t.tier] }}<el-input-number v-model="editTiers[i].min" :min="1" :controls="false" size="small" style="width:64px" />
            </span>
            <el-button size="small" type="primary" plain @click="saveTiers">保存门槛</el-button>
          </div>

          <h4>已获得学生（{{ selected.earners.length }}）</h4>
          <div v-if="selected.earners.length" class="ab-earners">
            <div v-for="e in selected.earners" :key="e.recordId" class="ab-earner">
              <span class="earner-name">{{ e.name }}</span>
              <el-tag size="small" :color="tierColor(e.tier)" effect="dark" class="earner-tier">{{ tierText(e.tier) }}</el-tag>
              <el-tag size="small" :type="e.source === 'MANUAL' ? 'warning' : 'success'" effect="plain">
                {{ e.source === 'MANUAL' ? '手动授予' : '自动获得' }}
              </el-tag>
              <span class="muted earner-date">{{ e.awardedAt }}</span>
              <span v-if="e.remark" class="earner-remark">📝 {{ e.remark }}</span>
              <div class="spacer"></div>
              <el-button size="small" text type="danger" @click="revoke(e)">撤销</el-button>
            </div>
          </div>
          <el-empty v-else description="还没有人获得这枚成就，点击右上角「授予学生」手动颁发" :image-size="70" />
        </div>
      </div>
    </template>

    <!-- 创建自定义成就 -->
    <el-dialog v-model="createVisible" title="➕ 创建自定义成就" width="520px">
      <el-form label-width="72px">
        <el-form-item label="名称"><el-input v-model="form.name" maxlength="20" show-word-limit placeholder="如：校运会跳远第一" /></el-form-item>
        <el-form-item label="等级">
          <el-radio-group v-model="form.tier">
            <el-radio-button value="BRONZE">🥉 铜 · 10分</el-radio-button>
            <el-radio-button value="SILVER">🥈 银 · 30分</el-radio-button>
            <el-radio-button value="GOLD">🥇 金 · 60分</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="图标">
          <div class="icon-picker">
            <div v-for="ic in BADGE_ICONS" :key="ic" class="icon-cell" :class="{ on: form.icon === ic }" @click="form.icon = ic">
              <img :src="`/badges/${ic}.png`" :alt="ic" />
            </div>
          </div>
        </el-form-item>
        <el-form-item label="描述"><el-input v-model="form.description" type="textarea" :rows="2" maxlength="100" placeholder="如何获得这枚成就（悬停徽章时显示）" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 授予学生 -->
    <el-dialog v-model="awardVisible" :title="`🎁 授予「${selected?.name || ''}」`" width="480px">
      <el-form label-width="72px">
        <el-form-item label="学生">
          <el-select v-model="awardIds" multiple filterable placeholder="选择学生（可多选）" style="width:100%">
            <el-option v-for="s in students" :key="s.id" :label="`${s.name}（${s.stu_no}）`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注"><el-input v-model="awardRemark" maxlength="50" placeholder="如：校运会跳远第一（可选）" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="awardVisible = false">取消</el-button>
        <el-button type="primary" :disabled="!awardIds.length" @click="submitAward">授予</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { Refresh, Plus, Delete, Present } from '@element-plus/icons-vue'
import http from '../api/http'
import BadgeIcon from '../components/BadgeIcon.vue'

/** 图标库：frontend/public/badges 下 22 枚 Fluent 3D PNG */
const BADGE_ICONS = ['star','glowing-star','sparkles','trophy','medal-1st','medal-2nd','medal-3rd',
  'sports-medal','crown','gem','hundred','check','shield','rocket','sun','books','broom','bell',
  'grad-cap','memo','pencil','party']

const data = ref(null)
const viewMode = ref('defs')
const selectedId = ref(null)
const scanning = ref(false)
const students = ref([])

const selected = computed(() => data.value?.defs.find(d => d.id === selectedId.value) || null)

async function load() {
  data.value = await http.get('/achievement/board')
  if (!selectedId.value && data.value.defs.length) selectedId.value = data.value.defs[0].id
}

/** 静默扫描（manual=false 不弹庆祝通知）；手动扫描弹跳杯通知 */
async function doScan(manual) {
  scanning.value = true
  try {
    const r = await http.post('/achievement/scan')
    if (manual) {
      if (r.newAwards.length) notifyAwards(r.newAwards)
      else ElMessage.success('扫描完成：没有新成就达成')
    } else if (r.newAwards.length) {
      notifyAwards(r.newAwards)
    }
    await load()
  } finally {
    scanning.value = false
  }
}

/** 新颁发/升级成就弹庆祝通知（每条一行，最多 5 条；区分新获得与升级、大满贯突出） */
function awardLine(a) {
  const emoji = { BRONZE: '🥉', SILVER: '🥈', GOLD: '🥇', PLATINUM: '🏆' }[a.tier] || '🏅'
  const tierName = { BRONZE: '铜牌', SILVER: '银牌', GOLD: '金牌', PLATINUM: '白金' }[a.tier] || ''
  if (a.defName === '大满贯') {
    if (a.kind === 'upgrade') {
      const pts = { BRONZE: 50, SILVER: 150, GOLD: 300 }[a.tier] || ''
      return `🏆 ${a.name} 的大满贯升至${emoji}${tierName}（${pts} 点）！`
    }
    return `🏆 ${a.name} 集齐全部成就，获得大满贯${emoji}！`
  }
  if (a.kind === 'upgrade') return `⬆️ ${a.name} 的「${a.defName}」升级为${emoji}${tierName}！`
  return `🎉 ${a.name} 获得「${a.defName}」${emoji}`
}
function notifyAwards(list) {
  const lines = list.slice(0, 5).map(awardLine)
  ElNotification({
    title: `🎉 成就播报（共 ${list.length} 条）`,
    message: lines.join('<br>'),
    dangerouslyUseHTMLString: true, duration: 8000, type: 'success'
  })
}

/** 启用/停用（内置仅可停用；停用后不再自动评定、徽章墙不显示） */
async function toggleDef(d, v) {
  await http.put(`/achievement/def/${d.id}`, { enabled: v })
  ElMessage.success(v ? '已启用' : '已停用')
  load()
}

async function removeDef(d) {
  await ElMessageBox.confirm(`删除自定义成就「${d.name}」？其 ${d.earnedCount} 条获得记录将一并删除，不可恢复！`,
    '删除确认', { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' })
  await http.delete(`/achievement/def/${d.id}`)
  ElMessage.success('已删除')
  load()
}

/** 撤销获得记录（误发回收） */
async function revoke(e) {
  await ElMessageBox.confirm(`撤销「${e.name}」的「${selected.value.name}」成就？`, '撤销确认', {
    type: 'warning', confirmButtonText: '确定撤销', cancelButtonText: '取消'
  })
  await http.delete(`/achievement/revoke/${e.recordId}`)
  ElMessage.success('已撤销')
  load()
}

// ---------- 创建自定义成就 ----------
const createVisible = ref(false)
const form = ref({ name: '', tier: 'BRONZE', icon: 'star', description: '' })
function openCreate() {
  form.value = { name: '', tier: 'BRONZE', icon: 'star', description: '' }
  createVisible.value = true
}
async function submitCreate() {
  if (!form.value.name.trim()) { ElMessage.warning('请输入成就名称'); return }
  await http.post('/achievement/def', form.value)
  ElMessage.success('已创建')
  createVisible.value = false
  load()
}

// ---------- 授予学生 ----------
const awardVisible = ref(false)
const awardIds = ref([])
const awardRemark = ref('')
async function openAward() {
  if (!students.value.length) students.value = await http.get('/roster/students')
  awardIds.value = []
  awardRemark.value = ''
  awardVisible.value = true
}
async function submitAward() {
  const r = await http.post('/achievement/award', {
    defId: selected.value.id, studentIds: awardIds.value, remark: awardRemark.value
  })
  ElMessage.success(`已授予 ${r.awarded} 名学生（已获得的自动跳过）`)
  awardVisible.value = false
  load()
}

// ---------- 三档门槛 ----------
const TIER_EMOJI = { BRONZE: '🥉', SILVER: '🥈', GOLD: '🥇', PLATINUM: '🏆' }
/** 解析定义 rule_params（保留 fullScore 等非阈值字段） */
function defParams(d) { try { return JSON.parse(d.rule_params || '{}') } catch { return {} } }
function defTiers(d) { return defParams(d).tiers || [] }
/** 定义行三档门槛短文本，如"🥉10次 / 🥈20次 / 🥇30次" */
function tierStepsText(d) {
  return defTiers(d).map(t => tierStepLabel(d.rule_type, t)).join(' / ')
}
/** 单档门槛标签：按规则类型带单位 */
function tierStepLabel(ruleType, t) {
  const e = TIER_EMOJI[t.tier] || ''
  const v = t.min
  switch (ruleType) {
    case 'RANK_TOP': return `${e}前${v}`
    case 'PET_LEVEL': return `${e}Lv.${v}`
    case 'RANK_UP': return `${e}升${v}名`
    case 'SCORE_FULL': return `${e}${v}科满分`
    case 'CONTINUOUS_ATTENDANCE': return v >= 20 ? `${e}整学期` : `${e}${v}周`
    default: return `${e}${v}次`
  }
}
// 内置可进阶成就阈值编辑：选中定义变化时拷贝三档 min
const editTiers = ref([])
watch(selectedId, () => {
  editTiers.value = defTiers(selected.value || {}).map(t => ({ tier: t.tier, min: Number(t.min) }))
}, { immediate: true })
async function saveTiers() {
  const p = defParams(selected.value)
  p.tiers = editTiers.value
  await http.put(`/achievement/def/${selected.value.id}`, { ruleParams: p })
  ElMessage.success('三档门槛已保存，下次扫描生效')
  load()
}

// ---------- 展示辅助 ----------
const TIER_TEXT = { BRONZE: '铜', SILVER: '银', GOLD: '金', PLATINUM: '白金' }
const TIER_COLOR = { BRONZE: '#a8642f', SILVER: '#9aa6b8', GOLD: '#f0b400', PLATINUM: '#5fb5e8' }
const tierText = t => TIER_TEXT[t] || t
const tierColor = t => TIER_COLOR[t] || '#999'
function rarityText(r) {
  return { COMMON: '常见', RARE: '稀有', EPIC: '史诗', LEGENDARY: '传说', NONE: '暂无获得' }[r] || r
}
function rarityType(r) {
  return { COMMON: 'success', RARE: '', EPIC: 'warning', LEGENDARY: 'danger', NONE: 'info' }[r] || 'info'
}

// 打开成就榜：静默自动扫描（有新成就弹庆祝）后加载
onMounted(() => doScan(false))
defineExpose({ doScan, load })
</script>

<style scoped>
.ab-root { margin-top: 4px; }
.ab-toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 14px; }
.spacer { flex: 1; }
.muted { color: #a0a4b4; font-size: 13px; }

/* 排行榜 */
.ab-rank-list { display: flex; flex-direction: column; gap: 8px; }
.ab-rank-row {
  display: flex; align-items: center; gap: 10px; padding: 10px 16px;
  background: #fff; border-radius: 12px; box-shadow: 0 2px 8px rgba(160,140,180,.12);
}
.ab-rank-row.top3 { background: linear-gradient(135deg, #fff8e8, #fdf3ff); }
.rank-no { width: 34px; text-align: center; font-weight: 700; font-size: 16px; color: #8a7fa0; }
.rank-name { font-weight: 600; color: #4a4455; min-width: 70px; }
.rank-badges { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: #6b6480; margin-left: 8px; }
.rank-pts { color: #e08a3c; font-size: 16px; }
.dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-left: 6px; }
.c-bronze { background: #a8642f; } .c-silver { background: #9aa6b8; }
.c-gold { background: #f0b400; } .c-plat { background: #5fb5e8; }
.tier-steps { font-size: 12px; white-space: nowrap; }
/* 三档门槛编辑行 */
.ab-thresh {
  display: flex; align-items: center; gap: 12px; margin: 12px 0 4px;
  padding: 8px 12px; background: #faf8fd; border-radius: 10px; flex-wrap: wrap;
}
.thresh-item { display: inline-flex; align-items: center; gap: 4px; font-size: 15px; }
.earner-tier { border: none; color: #fff; min-width: 34px; justify-content: center; }

/* 左右两栏 */
.ab-body { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; align-items: start; }
.ab-defs { display: flex; flex-direction: column; gap: 8px; max-height: 68vh; overflow-y: auto; padding-right: 4px; }
.ab-def-row {
  display: flex; align-items: center; gap: 10px; padding: 10px 12px;
  background: #fff; border-radius: 12px; cursor: pointer;
  border: 2px solid transparent; box-shadow: 0 2px 8px rgba(160,140,180,.1);
}
.ab-def-row:hover { background: #fdfaff; }
.ab-def-row.active { border-color: #c9a7eb; background: #fbf6ff; }
.ab-def-row.off { opacity: .55; }
.ab-def-main { flex: 1; min-width: 0; }
.ab-def-name { font-weight: 600; color: #4a4455; display: flex; align-items: center; gap: 6px; }
.ab-def-meta { display: flex; align-items: center; gap: 6px; margin-top: 4px; flex-wrap: wrap; }
.ab-def-ops { display: flex; align-items: center; gap: 6px; }
.tier-tag { border: none; color: #fff; }

.ab-detail {
  background: #fff; border-radius: 14px; padding: 16px;
  box-shadow: 0 2px 10px rgba(160,140,180,.12); position: sticky; top: 0;
}
.ab-detail-head { display: flex; align-items: center; gap: 12px; }
.ab-detail-name { font-size: 17px; font-weight: 700; color: #4a4455; }
.ab-detail-meta { display: flex; gap: 6px; margin-top: 6px; align-items: center; }
.ab-detail h4 { margin: 16px 0 8px; color: #5a5f72; }
.ab-earners { display: flex; flex-direction: column; gap: 6px; max-height: 46vh; overflow-y: auto; }
.ab-earner {
  display: flex; align-items: center; gap: 8px; padding: 8px 10px;
  background: #faf8fd; border-radius: 10px; font-size: 13px;
}
.earner-name { font-weight: 600; color: #4a4455; min-width: 64px; }
.earner-date { font-size: 12px; }
.earner-remark { color: #8a6db0; }

/* 图标选择网格 */
.icon-picker { display: grid; grid-template-columns: repeat(8, 1fr); gap: 6px; width: 100%; }
.icon-cell {
  aspect-ratio: 1; border-radius: 10px; background: #f6f2fb; cursor: pointer;
  display: flex; align-items: center; justify-content: center; border: 2px solid transparent;
}
.icon-cell img { width: 80%; height: 80%; object-fit: contain; }
.icon-cell.on { border-color: #b07fe8; background: #f3e8ff; box-shadow: 0 0 0 2px rgba(176,127,232,.25); }
</style>
