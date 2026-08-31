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
            {{ r.name }} <el-icon class="x" @click="removeRoster(r.rosterId)"><Close /></el-icon>
          </div>
          <div class="day-foot">小组自行分配工作</div>
        </div>
      </div>
    </div>

    <!-- 班委名单 -->
    <el-card class="block">
      <b>🎖️ 班委名单</b>
      <div v-if="committee.length" class="committee-list">
        <el-tag v-for="c in committee" :key="c.id" closable size="large" class="committee-tag"
                :type="tagType(c.role)" @close="removeCommittee(c.id)">
          {{ c.role }}：{{ c.name }}
        </el-tag>
      </div>
      <el-empty v-else description="暂无班委" :image-size="60" />
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
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, User, Close } from '@element-plus/icons-vue'
import http from '../api/http'

const dayNames = ['周一', '周二', '周三', '周四', '周五']
const dayColors = ['#7fb3f3', '#8fd8b0', '#f0c96e', '#c3a6e8', '#f78fb3']
const roles = ['班长', '副班长', '学习委员', '纪律委员', '卫生委员', '文体委员', '生活委员', '课代表', '组长']

const tasks = ref([])
const personCount = reactive({ 1: 4, 2: 4, 3: 4, 4: 4, 5: 4 })
const roster = ref({})
const committee = ref([])
const students = ref([])

const addDlg = ref(false)
const addForm = reactive({ day: 1, studentId: null })
const committeeDlg = ref(false)
const addCommittee = reactive({ studentId: null, role: '班长' })

function tagType(role) {
  const map = { '班长': 'danger', '副班长': 'warning', '学习委员': 'success' }
  return map[role] || 'primary'
}

async function loadAll() {
  tasks.value = await http.get('/duty/tasks')
  const cfg = await http.get('/duty/config')
  cfg.forEach(c => personCount[c.dayOfWeek] = c.personCount)
  roster.value = await http.get('/duty/roster')
  committee.value = await http.get('/duty/committee')
  students.value = await http.get('/roster/students')
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
  await http.post('/duty/roster', { dayOfWeek: addForm.day, studentId: addForm.studentId })
  ElMessage.success('已添加')
  addDlg.value = false
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
.duty-person { display: flex; justify-content: space-between; align-items: center; padding: 8px 14px; border-bottom: 1px dashed #eef0f5; font-weight: 600; }
.duty-person .x { color: #e88; cursor: pointer; }
.day-foot { text-align: center; color: #b0b3c0; font-size: 12px; padding: 12px; border-top: 1px dashed #ccc; border-style: dashed none none none; }
.committee-list { margin-top: 12px; display: flex; flex-wrap: wrap; gap: 10px; }
.committee-tag { font-size: 15px; padding: 8px 14px; }
</style>
