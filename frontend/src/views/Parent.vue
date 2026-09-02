<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">📞 家长联系方式</h2>
      <div class="spacer"></div>
      <el-input v-model="keyword" placeholder="搜索学生/家长/电话" style="width:240px" clearable :prefix-icon="Search" @input="loadContacts" />
    </div>

    <el-card class="block">
      <h3 class="block-title">📇 家长通讯录</h3>
      <el-table :data="contacts" size="small" stripe>
        <el-table-column prop="stu_no" label="学号" width="100" />
        <el-table-column prop="student_name" label="学生姓名" width="100" />
        <el-table-column prop="gender" label="性别" width="70" />
        <el-table-column label="家长信息" min-width="210">
          <template #default="{ row }">
            <div v-for="(p, i) in row.parents" :key="i" class="parent-info">
              <b>{{ p.relation }}：{{ p.name }}</b>
              <el-link type="danger" :href="'tel:' + p.phone" class="phone">📞 {{ p.phone }}</el-link>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="address" label="住址" min-width="120" />
        <el-table-column label="操作" width="170">
          <template #default="{ row }">
            <div class="op-btns">
              <el-button size="small" type="primary" plain @click="openEdit(row)">✏️ 编辑</el-button>
              <el-button size="small" type="success" plain @click="openComm(row)">💬 沟通</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 编辑家长联系方式弹窗（学号/姓名/性别只读带入，可改住址与父亲/母亲姓名电话；复用花名册保存接口） -->
    <el-dialog v-model="editVisible" title="✏️ 编辑家长联系方式" width="480px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="学生">
          <el-input :model-value="editForm.stuNo + '　' + editForm.name + '（' + editForm.gender + '）'" disabled />
        </el-form-item>
        <el-form-item label="住址"><el-input v-model="editForm.address" placeholder="家庭住址" /></el-form-item>
        <el-form-item v-for="(p, i) in editForm.parents" :key="i" :label="p.relation">
          <el-input v-model="p.name" placeholder="姓名" style="width:40%" />
          <el-input v-model="p.phone" placeholder="电话" style="width:55%; margin-left:5%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 沟通弹窗：该生沟通记录仅在此查看/添加/删除（不再平铺到页面） -->
    <el-dialog v-model="dlg" :title="'家校沟通管理'" width="640px">
      <div v-if="cur" class="comm-student">
        <b>{{ cur.student_name }}</b>（{{ cur.stu_no }}）
        <div v-for="(p, i) in cur.parents" :key="i" class="parent-info">
          家长{{ i + 1 }}（{{ p.relation }}）：{{ p.name }} <el-link type="danger">📞 {{ p.phone }}</el-link>
        </div>
      </div>
      <h4>📒 沟通记录（{{ curLogs.length }} 条）</h4>
      <el-table :data="curLogs" size="small" max-height="200">
        <el-table-column prop="contact_date" label="日期" width="100" />
        <el-table-column prop="method" label="方式" width="70" />
        <el-table-column prop="content" label="内容" min-width="200" />
        <el-table-column label="操作" width="60">
          <template #default="{ row }"><el-button type="danger" text size="small" @click="delLog(row.id)">删除</el-button></template>
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
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import http from '../api/http'

const contacts = ref([])
const keyword = ref('')

const dlg = ref(false)
const cur = ref(null)
const curLogs = ref([])
const logForm = reactive({ contactDate: today(), method: '电话', parentId: null, content: '' })

// 编辑家长联系方式
const editVisible = ref(false)
const editForm = reactive({ id: null, stuNo: '', name: '', gender: '男', address: '', parents: [
  { relation: '父亲', name: '', phone: '' },
  { relation: '母亲', name: '', phone: '' }
] })

function today() { return new Date().toISOString().slice(0, 10) }

async function loadContacts() {
  contacts.value = await http.get('/parent/contacts', { params: { keyword: keyword.value } })
}
loadContacts()

/** 编辑：从通讯录行带入（学号/姓名/性别只读，住址与父母姓名电话可改） */
function openEdit(row) {
  const pick = rel => {
    const p = (row.parents || []).find(x => x.relation === rel)
    return { name: p?.name || '', phone: p?.phone || '' }
  }
  const dad = pick('父亲'), mom = pick('母亲')
  Object.assign(editForm, {
    id: row.student_id, stuNo: row.stu_no, name: row.student_name, gender: row.gender,
    address: row.address || '',
    parents: [
      { relation: '父亲', name: dad.name, phone: dad.phone },
      { relation: '母亲', name: mom.name, phone: mom.phone }
    ]
  })
  editVisible.value = true
}

/** 保存：复用花名册学生修改接口（家长整体覆盖；姓名电话全空的行会被后端跳过） */
async function saveEdit() {
  await http.put('/roster/student/' + editForm.id, {
    stuNo: editForm.stuNo, name: editForm.name, gender: editForm.gender,
    address: editForm.address, parents: editForm.parents
  })
  ElMessage.success('已保存')
  editVisible.value = false
  loadContacts()
}

async function openComm(row) {
  cur.value = row
  logForm.contactDate = today()
  logForm.parentId = null
  logForm.content = ''
  dlg.value = true
  curLogs.value = await http.get('/parent/logs', { params: { studentId: row.student_id } })
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
}

async function delLog(id) {
  await http.delete('/parent/log/' + id)
  ElMessage.success('已删除')
  if (cur.value) curLogs.value = await http.get('/parent/logs', { params: { studentId: cur.value.student_id } })
}
</script>

<style scoped>
.block { margin-bottom: 18px; }
.block-title { margin: 0 0 12px; color: #5a5f72; }
.parent-info { display: flex; align-items: center; gap: 12px; padding: 2px 0; font-size: 14px; }
.parent-info .phone { margin-left: 6px; }
.op-btns { display: flex; flex-wrap: nowrap; gap: 6px; }
.op-btns .el-button { margin-left: 0; }
h4 { margin: 14px 0 8px; color: #5a5f72; }
.comm-student { background: #f6f8fc; border-radius: 10px; padding: 10px 14px; margin-bottom: 6px; }
</style>
