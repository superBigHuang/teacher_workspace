<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">📞 家长联系方式</h2>
      <el-input v-model="keyword" placeholder="搜索学生/家长/电话" style="width:240px" clearable :prefix-icon="Search" @input="loadContacts" />
      <div class="spacer"></div>
      <el-button type="primary" :icon="ChatDotRound" @click="openQuickLog">沟通记录</el-button>
      <el-button type="danger" plain :icon="Delete" @click="clearLogs">清空记录</el-button>
    </div>

    <el-card class="block">
      <h3 class="block-title">📇 家长通讯录</h3>
      <el-table :data="contacts" size="small" stripe>
        <el-table-column prop="stu_no" label="学号" width="100" />
        <el-table-column prop="student_name" label="学生姓名" width="100" />
        <el-table-column prop="gender" label="性别" width="70" />
        <el-table-column label="家长信息" min-width="280">
          <template #default="{ row }">
            <div v-for="(p, i) in row.parents" :key="i" class="parent-info">
              <b>{{ p.relation }}：{{ p.name }}</b>
              <el-link type="danger" :href="'tel:' + p.phone" class="phone">📞 {{ p.phone }}</el-link>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="address" label="住址" min-width="140" />
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openComm(row)">💬 沟通</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-card class="block">
      <h3 class="block-title">📝 家校沟通记录</h3>
      <el-table :data="logs" size="small" stripe>
        <el-table-column prop="contact_date" label="日期" width="120" />
        <el-table-column prop="student_name" label="学生" width="100" />
        <el-table-column prop="method" label="方式" width="90" />
        <el-table-column prop="content" label="沟通内容" min-width="260" />
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button size="small" type="danger" text @click="delLog(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 沟通弹窗 -->
    <el-dialog v-model="dlg" :title="'家校沟通管理'" width="640px">
      <div v-if="cur" class="comm-student">
        <b>{{ cur.student_name }}</b>（{{ cur.stu_no }}）
        <div v-for="(p, i) in cur.parents" :key="i" class="parent-info">
          家长{{ i + 1 }}（{{ p.relation }}）：{{ p.name }} <el-link type="danger">📞 {{ p.phone }}</el-link>
        </div>
      </div>
      <h4>📒 全部沟通记录（{{ curLogs.length }} 条）</h4>
      <el-table :data="curLogs" size="small" max-height="200">
        <el-table-column prop="contact_date" label="日期" width="100" />
        <el-table-column prop="method" label="方式" width="70" />
        <el-table-column prop="content" label="内容" min-width="200" />
        <el-table-column label="操作" width="60">
          <template #default="{ row }"><el-button type="danger" text size="small" @click="delLog(row.id, true)">删除</el-button></template>
        </el-table-column>
      </el-table>

      <h4>➕ 添加沟通记录</h4>
      <el-form :model="logForm" label-width="60px">
        <el-form-item label="日期"><el-date-picker v-model="logForm.contactDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="家长">
          <el-select v-model="logForm.parentId" style="width:100%" clearable placeholder="选择家长（可不选）">
            <el-option v-for="p in (cur ? cur.parents : [])" :key="p.id" :label="p.relation + '·' + p.name" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="方式">
          <el-select v-model="logForm.method" style="width:100%">
            <el-option v-for="m in ['电话','微信','面谈','家访','短信']" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容"><el-input v-model="logForm.content" type="textarea" rows="2" placeholder="沟通内容..." /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">关闭</el-button>
        <el-button type="primary" @click="addLog">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, ChatDotRound, Delete } from '@element-plus/icons-vue'
import http from '../api/http'

const contacts = ref([])
const logs = ref([])
const keyword = ref('')

const dlg = ref(false)
const cur = ref(null)
const curLogs = ref([])
const logForm = reactive({ contactDate: today(), method: '电话', parentId: null, content: '' })

function today() { return new Date().toISOString().slice(0, 10) }

async function loadContacts() {
  contacts.value = await http.get('/parent/contacts', { params: { keyword: keyword.value } })
}
async function loadLogs() {
  logs.value = await http.get('/parent/logs')
}
loadContacts(); loadLogs()

async function openComm(row) {
  cur.value = row
  logForm.contactDate = today()
  logForm.parentId = null
  logForm.content = ''
  dlg.value = true
  curLogs.value = await http.get('/parent/logs', { params: { studentId: row.student_id } })
}

function openQuickLog() {
  if (!contacts.value.length) { ElMessage.warning('通讯录为空'); return }
  openComm(contacts.value[0])
}

async function addLog() {
  if (!cur.value) return
  if (!logForm.content) { ElMessage.warning('请输入沟通内容'); return }
  await http.post('/parent/log', {
    studentId: cur.value.student_id, parentId: logForm.parentId,
    contactDate: logForm.contactDate, method: logForm.method, content: logForm.content
  })
  ElMessage.success('已添加')
  logForm.content = ''
  curLogs.value = await http.get('/parent/logs', { params: { studentId: cur.value.student_id } })
  loadLogs()
}

async function delLog(id, inDlg) {
  await http.delete('/parent/log/' + id)
  ElMessage.success('已删除')
  loadLogs()
  if (inDlg && cur.value) curLogs.value = await http.get('/parent/logs', { params: { studentId: cur.value.student_id } })
}

async function clearLogs() {
  await ElMessageBox.confirm('确定清空全部沟通记录？', '警告', { type: 'warning' })
  await http.post('/parent/logs/clear')
  ElMessage.success('已清空')
  loadLogs(); curLogs.value = []
}
</script>

<style scoped>
.block { margin-bottom: 18px; }
.block-title { margin: 0 0 12px; color: #5a5f72; }
.parent-info { display: flex; align-items: center; gap: 12px; padding: 2px 0; font-size: 14px; }
.parent-info .phone { margin-left: 6px; }
h4 { margin: 14px 0 8px; color: #5a5f72; }
.comm-student { background: #f6f8fc; border-radius: 10px; padding: 10px 14px; margin-bottom: 6px; }
</style>
