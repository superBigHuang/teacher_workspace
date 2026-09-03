<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">🧹 值日表·班委名单</h2>
      <el-button type="primary" :icon="Plus" @click="addDlg = true">添加值日</el-button>
      <el-button type="success" :icon="Refresh" @click="autoRotate">🔄 自动轮值</el-button>
      <el-button :icon="User" @click="committeeDlg = true">添加班委</el-button>
    </div>

    <!-- 值日事项 -->
    <el-card class="block">
      <div class="task-head">
        <b>📋 值日工作总项（小组自行分配）</b>
        <el-button size="small" type="primary" plain :icon="Plus" @click="addTask">添加</el-button>
      </div>
      <div class="task-tags">
        <el-tag v-for="t in tasks" :key="t.id" closable size="large" class="task-tag" @close="delTask(t.id)">{{ t.name }}</el-tag>
      </div>
    </el-card>

    <!-- 每日人数 -->
    <div class="person-cfg">
      <b>👥 每天值日人数：</b>
      <template v-for="(d, i) in dayNames" :key="i">
        <span class="day-label">{{ d }}</span>
        <el-input-number v-model="personCount[i + 1]" :min="0" :max="20" size="small" @change="saveConfig" />
      </template>
    </div>

    <!-- 五日值日卡 -->
    <div class="day-row">
      <div v-for="(d, i) in dayNames" :key="d" class="day-col">
        <div class="day-card" :style="{ '--c': dayColors[i] }">
          <div class="day-title">{{ d }}</div>
          <div v-for="r in (roster[i + 1] || [])" :key="r.rosterId" class="duty-person">
            <span class="dp-name">{{ r.name }}</span>
            <el-tag size="small" effect="plain" class="dp-task" :type="r.taskName ? 'success' : 'info'">
              {{ r.taskName || '未分配' }}
            </el-tag>
            <el-icon class="x" @click="removeRoster(r.rosterId)"><Close /></el-icon>
          </div>
          <div class="day-foot">小组自行分配工作</div>
        </div>
      </div>
    </div>

    <!-- 班委名单（组长不在此显示，统一到下方「学习小组」按列展示） -->
    <el-card class="block">
      <b>🎖️ 班委名单</b>
      <div v-if="committeeTags.length" class="committee-list">
        <el-tag v-for="c in committeeTags" :key="c.id" closable size="large" class="committee-tag"
                :type="tagType(c.role)" @close="removeCommittee(c.id)">
          {{ c.role }}：{{ c.name }}
        </el-tag>
      </div>
      <el-empty v-else description="暂无班委" :image-size="60" />
    </el-card>

    <!-- 学习小组：组 = 座位一"列"，组长在座次表「👑 组长」模式指派（committee role='组长'）；
         组员 = 同列学生（按 seat_assignment.col_no 实时推导，换座后自动跟随） -->
    <el-card class="block">
      <b>👑 学习小组（按座位列，组长在座次表指派）</b>
      <div v-if="groups.list.length || groups.unplaced.length" class="group-row">
        <div v-for="g in groups.list" :key="'g' + g.col" class="group-card"
             :style="{ '--c': dayColors[(g.col - 1) % dayColors.length] }">
          <div class="group-title">第 {{ g.col }} 列</div>
          <div v-if="g.leader" class="group-leader">
            <span>👑 {{ g.leader.name }}</span>
            <el-icon class="x" @click="removeCommittee(g.leader.cid)"><Close /></el-icon>
          </div>
          <div v-else class="group-noleader">暂无组长</div>
          <div v-for="m in g.members" :key="m.studentId" class="group-member">{{ m.name }}</div>
        </div>
        <!-- 有组长记录但当前未排座（清空座次后记录保留，重新排座即自动归列） -->
        <div v-for="(u, i) in groups.unplaced" :key="'u' + i" class="group-card group-unplaced">
          <div class="group-title gu-title">未排座</div>
          <div class="group-leader">
            <span>👑 {{ u.name }}</span>
            <el-icon class="x" @click="removeCommittee(u.cid)"><Close /></el-icon>
          </div>
        </div>
      </div>
      <el-empty v-else description="还没有座位数据，先去座次表排座" :image-size="60" />
    </el-card>

    <!-- 添加值日 -->
    <el-dialog v-model="addDlg" title="添加值日" width="380px">
      <el-form label-width="70px">
        <el-form-item label="星期">
          <el-select v-model="addForm.day" style="width:100%">
            <el-option v-for="(d, i) in dayNames" :key="d" :label="d" :value="i + 1" />
          </el-select>
        </el-form-item>
        <el-form-item label="学生">
          <el-select v-model="addForm.studentId" filterable style="width:100%">
            <el-option v-for="s in students" :key="s.id" :label="s.name + '（' + s.stu_no + '）'" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="任务">
          <el-select v-model="addForm.taskId" placeholder="选择值日事项（干什么）" style="width:100%">
            <el-option v-for="t in tasks" :key="t.id" :label="t.name" :value="t.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDlg = false">取消</el-button>
        <el-button type="primary" @click="submitAdd">添加</el-button>
      </template>
    </el-dialog>

    <!-- 添加班委 -->
    <el-dialog v-model="committeeDlg" title="添加班委" width="380px">
      <el-form label-width="70px">
        <el-form-item label="学生">
          <el-select v-model="addCommittee.studentId" filterable style="width:100%">
            <el-option v-for="s in students" :key="s.id" :label="s.name + '（' + s.stu_no + '）'" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="职务">
          <el-select v-model="addCommittee.role" filterable allow-create style="width:100%" placeholder="选择或输入职务">
            <el-option v-for="r in roles" :key="r" :label="r" :value="r" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="committeeDlg = false">取消</el-button>
        <el-button type="primary" @click="submitCommittee">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, User, Close } from '@element-plus/icons-vue'
import http from '../api/http'

const dayNames = ['周一', '周二', '周三', '周四', '周五']
const dayColors = ['#7fb3f3', '#8fd8b0', '#f0c96e', '#c3a6e8', '#f78fb3']
// 注：「组长」不在此列——组长统一在座次表「👑 组长」模式按列指派（committee role='组长'）
const roles = ['班长', '副班长', '学习委员', '纪律委员', '卫生委员', '文体委员', '生活委员', '课代表']

const tasks = ref([])
const personCount = reactive({ 1: 4, 2: 4, 3: 4, 4: 4, 5: 4 })
const roster = ref({})
const committee = ref([])
const students = ref([])
/** 座位网格（/seat/grid）：学习小组按列分组用；组员归属实时取座位，不存组关系 */
const seatGrid = ref({ seats: [] })

const addDlg = ref(false)
const addForm = reactive({ day: 1, studentId: null, taskId: null })
const committeeDlg = ref(false)
const addCommittee = reactive({ studentId: null, role: '班长' })

function tagType(role) {
  const map = { '班长': 'danger', '副班长': 'warning', '学习委员': 'success' }
  return map[role] || 'primary'
}

/** 班委标签：组长不在班委名单显示（单独进学习小组区） */
const committeeTags = computed(() => committee.value.filter(c => c.role !== '组长'))

/**
 * 学习小组：按座位列（col_no）分组。
 * - list：每列一张卡 { col, leader(组长座位+committee id), members(同列其余学生，按排序) }；
 *   组长取自 committee role='组长'，一列至多一个（座次表指派时后端保证）。
 * - unplaced：有组长记录但当前不在任何座位上（清空座次后记录保留，重新排座自动归列）。
 */
const groups = computed(() => {
  const leaderRec = sid => committee.value.find(c => c.role === '组长' && c.studentId === sid)
  const byCol = new Map()
  for (const s of seatGrid.value.seats || []) {
    if (!s.studentId) continue
    if (!byCol.has(s.col)) byCol.set(s.col, [])
    byCol.get(s.col).push(s)
  }
  const placed = new Set()
  const list = [...byCol.keys()].sort((a, b) => a - b).map(col => {
    const seats = byCol.get(col).sort((a, b) => a.row - b.row)
    seats.forEach(s => placed.add(s.studentId))
    const leaderSeat = seats.find(s => leaderRec(s.studentId))
    return {
      col,
      leader: leaderSeat ? { ...leaderSeat, cid: leaderRec(leaderSeat.studentId).id } : null,
      members: leaderSeat ? seats.filter(s => s.studentId !== leaderSeat.studentId) : seats
    }
  })
  const unplaced = committee.value
    .filter(c => c.role === '组长' && !placed.has(c.studentId))
    .map(c => ({ name: c.name, cid: c.id }))
  return { list, unplaced }
})

async function loadAll() {
  tasks.value = await http.get('/duty/tasks')
  const cfg = await http.get('/duty/config')
  cfg.forEach(c => personCount[c.dayOfWeek] = c.personCount)
  roster.value = await http.get('/duty/roster')
  committee.value = await http.get('/duty/committee')
  students.value = await http.get('/roster/students')
  try { seatGrid.value = await http.get('/seat/grid') } catch { seatGrid.value = { seats: [] } }
}
loadAll()

async function addTask() {
  const { value } = await ElMessageBox.prompt('输入值日事项', '添加事项', { inputPattern: /.+/, inputErrorMessage: '不能为空' })
  await http.post('/duty/task', { name: value })
  tasks.value = await http.get('/duty/tasks')
}
async function delTask(id) {
  await http.delete('/duty/task/' + id)
  tasks.value = await http.get('/duty/tasks')
}
async function saveConfig() {
  const configs = Object.keys(personCount).map(d => ({ dayOfWeek: Number(d), personCount: personCount[d] }))
  await http.post('/duty/config', { configs })
}
async function autoRotate() {
  if (!students.value.length) { ElMessage.warning('花名册为空'); return }
  await saveConfig()
  await http.post('/duty/auto')
  ElMessage.success('轮值完成')
  roster.value = await http.get('/duty/roster')
}
async function removeRoster(id) {
  await http.delete('/duty/roster/' + id)
  roster.value = await http.get('/duty/roster')
}
async function submitAdd() {
  if (!addForm.studentId) { ElMessage.warning('请选择学生'); return }
  if (!addForm.taskId) { ElMessage.warning('请选择值日事项（干什么）'); return }
  await http.post('/duty/roster', { dayOfWeek: addForm.day, studentId: addForm.studentId, taskId: addForm.taskId })
  ElMessage.success('已添加')
  addDlg.value = false
  addForm.studentId = null; addForm.taskId = null
  roster.value = await http.get('/duty/roster')
}
async function submitCommittee() {
  if (!addCommittee.studentId) { ElMessage.warning('请选择学生'); return }
  await http.post('/duty/committee', { studentId: addCommittee.studentId, role: addCommittee.role })
  ElMessage.success('已添加')
  committeeDlg.value = false
  committee.value = await http.get('/duty/committee')
}
async function removeCommittee(id) {
  await http.delete('/duty/committee/' + id)
  committee.value = await http.get('/duty/committee')
}
</script>

<style scoped>
.block { margin-bottom: 16px; }
.task-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.task-tags { display: flex; flex-wrap: wrap; gap: 10px; }
.task-tag { font-size: 15px; padding: 10px 14px; }
.person-cfg { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin: 16px 0; color: #6a6f82; }
.day-label { font-size: 13px; color: #8a8fa3; }
.day-row { display: flex; gap: 14px; }
.day-col { flex: 1; min-width: 0; }
.day-card { border-radius: 14px; background: #fff; border: 2px solid var(--c); overflow: hidden; min-height: 200px; }
.day-title { background: var(--c); color: #fff; text-align: center; font-weight: 800; padding: 8px; }
.duty-person { display: flex; align-items: center; gap: 8px; padding: 8px 14px; border-bottom: 1px dashed #eef0f5; font-weight: 600; }
.dp-name { flex-shrink: 0; }
.dp-task { margin-right: auto; }
.duty-person .x { color: #e88; cursor: pointer; flex-shrink: 0; }
.day-foot { text-align: center; color: #b0b3c0; font-size: 12px; padding: 12px; border-top: 1px dashed #ccc; border-style: dashed none none none; }
.committee-list { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 10px; }
.committee-tag { font-size: 15px; padding: 8px 14px; }
/* 学习小组：按座位列的彩色卡片（配色复用值日卡五色循环） */
.group-row { margin-top: 12px; display: grid; grid-template-columns: repeat(auto-fill, minmax(132px, 1fr)); gap: 12px; }
.group-card { border-radius: 14px; background: #fff; border: 2px solid var(--c, #dfe3ec); overflow: hidden; }
.group-title { background: var(--c, #eef0f5); color: #fff; text-align: center; font-weight: 800; padding: 6px; font-size: 14px; }
.group-leader {
  display: flex; align-items: center; justify-content: space-between; gap: 6px;
  padding: 7px 10px; font-weight: 800; color: #8a5a1a; background: #fff6e0;
  border-bottom: 1px dashed #f0e2c0;
}
.group-leader .x { color: #e88; cursor: pointer; flex-shrink: 0; }
.group-noleader { padding: 8px 10px; color: #b0b3c0; font-size: 13px; text-align: center; }
.group-member { padding: 6px 12px; font-weight: 600; color: #5a5f6e; border-bottom: 1px dashed #eef0f5; }
.group-member:last-child { border-bottom: none; }
/* 未排座组长卡：灰色系 */
.group-unplaced { border-color: #dfe3ec; }
.group-unplaced .gu-title { background: #e6e9f0; color: #9098a8; }
</style>
