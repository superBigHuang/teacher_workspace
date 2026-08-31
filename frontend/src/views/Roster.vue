<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">📋 学生花名册</h2>
      <el-input v-model="keyword" placeholder="搜索姓名/学号" style="width:220px" clearable :prefix-icon="Search" @input="loadStudents" />
      <div class="spacer"></div>
      <el-button type="primary" :icon="Plus" @click="openAdd">添加学生</el-button>
      <el-upload :show-file-list="false" :http-request="doImport" accept=".xlsx,.xls">
        <el-button :icon="FolderOpened">导入</el-button>
      </el-upload>
      <el-button :icon="Download" @click="doExport">导出</el-button>
      <el-button type="danger" plain :icon="Delete" @click="doClear">清空</el-button>
    </div>

    <!-- 统计卡 -->
    <el-row :gutter="16" class="stat-row">
      <el-col :span="6"><div class="stat-card grad-1"><div class="en">TOTAL STUDENTS</div><div class="num">{{ stats.total }}</div><div class="label">班级总人数</div></div></el-col>
      <el-col :span="6"><div class="stat-card grad-2"><div class="en">BOYS</div><div class="num">{{ stats.boys }}</div><div class="label">男生人数</div></div></el-col>
      <el-col :span="6"><div class="stat-card grad-3"><div class="en">GIRLS</div><div class="num">{{ stats.girls }}</div><div class="label">女生人数</div></div></el-col>
      <el-col :span="6"><div class="stat-card grad-4"><div class="en">RATIO</div><div class="num small">{{ stats.ratio }}</div><div class="label">男女生比例</div></div></el-col>
    </el-row>

    <!-- 性别筛选 -->
    <div class="filter-bar">
      <span>性别筛选：</span>
      <el-radio-group v-model="gender" @change="loadStudents">
        <el-radio-button value="全部">全部</el-radio-button>
        <el-radio-button value="男">男生</el-radio-button>
        <el-radio-button value="女">女生</el-radio-button>
      </el-radio-group>
      <div class="spacer"></div>
      <span class="show-count">当前显示 {{ students.length }} 人</span>
    </div>

    <!-- 学生卡片 -->
    <div class="stu-grid">
      <div v-for="s in students" :key="s.id" class="stu-card" :class="s.gender === '女' ? 'girl' : 'boy'" @click="openProfile(s)">
        <div class="name">{{ s.name }}</div>
        <div class="no">{{ s.stu_no }}</div>
      </div>
    </div>

    <!-- 添加/编辑学生 -->
    <el-dialog v-model="formVisible" :title="editing ? '编辑学生' : '添加学生'" width="520px">
      <el-form :model="form" label-width="80px">
        <el-form-item label="学号"><el-input v-model="form.stuNo" /></el-form-item>
        <el-form-item label="姓名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="性别">
          <el-radio-group v-model="form.gender">
            <el-radio value="男">男</el-radio>
            <el-radio value="女">女</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="住址"><el-input v-model="form.address" /></el-form-item>
        <el-form-item v-for="(p, i) in form.parents" :key="i" :label="p.relation">
          <el-input v-model="p.name" placeholder="姓名" style="width:40%" />
          <el-input v-model="p.phone" placeholder="电话" style="width:55%; margin-left:5%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="formVisible = false">取消</el-button>
        <el-button type="primary" @click="saveStudent">保存</el-button>
      </template>
    </el-dialog>

    <!-- 学生档案抽屉 -->
    <el-drawer v-model="profileVisible" size="460px" :title="profile.info ? profile.info.name + ' 的档案' : '学生档案'">
      <template v-if="profile.info">
        <div class="profile-head">
          <el-tag :type="profile.info.gender === '女' ? 'danger' : 'primary'">{{ profile.info.gender }}</el-tag>
          <b>{{ profile.info.name }}</b>
          <span class="muted">学号 {{ profile.info.stu_no }}</span>
          <el-button size="small" text type="primary" @click="openEditFromProfile">编辑</el-button>
        </div>
        <p class="muted" v-if="profile.info.address">住址：{{ profile.info.address }}</p>

        <h4>📞 家长联系方式</h4>
        <div v-for="(p, i) in profile.parents" :key="i" class="parent-line">
          {{ p.relation }}：{{ p.name }} <el-link type="primary">{{ p.phone }}</el-link>
        </div>
        <el-empty v-if="!profile.parents.length" description="暂无家长信息" :image-size="60" />

        <h4>💬 家校沟通记录</h4>
        <div v-for="(c, i) in profile.comms" :key="i" class="record-line">
          <el-tag size="small">{{ c.method }}</el-tag> {{ c.content }} <span class="muted">{{ c.contact_date }}</span>
        </div>
        <el-empty v-if="!profile.comms.length" description="暂无" :image-size="50" />

        <h4>⏰ 考勤异常</h4>
        <div v-for="(a, i) in profile.attendances" :key="i" class="record-line">
          <el-tag size="small" type="warning">{{ a.status }}</el-tag> <span class="muted">{{ a.record_date }}</span>
        </div>
        <el-empty v-if="!profile.attendances.length" description="全勤记录良好" :image-size="50" />

        <h4>📝 课堂纪律</h4>
        <div v-for="(d, i) in profile.disciplines" :key="i" class="record-line">
          {{ d.content }} <span class="muted">{{ d.record_date }}</span>
        </div>
        <el-empty v-if="!profile.disciplines.length" description="暂无" :image-size="50" />

        <h4>📚 作业未交</h4>
        <div v-for="(h, i) in profile.homeworks" :key="i" class="record-line">
          <el-tag size="small" type="danger">{{ h.subject }}</el-tag> <span class="muted">{{ h.record_date }}</span>
        </div>
        <el-empty v-if="!profile.homeworks.length" description="无未交记录" :image-size="50" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Plus, FolderOpened, Download, Delete } from '@element-plus/icons-vue'
import http from '../api/http'

const stats = reactive({ total: 0, boys: 0, girls: 0, ratio: '0:0' })
const students = ref([])
const keyword = ref('')
const gender = ref('全部')

const formVisible = ref(false)
const editing = ref(null)
const form = reactive({ stuNo: '', name: '', gender: '男', address: '', parents: [
  { relation: '父亲', name: '', phone: '' },
  { relation: '母亲', name: '', phone: '' }
] })

const profileVisible = ref(false)
const profile = ref({})

async function loadStats() {
  Object.assign(stats, await http.get('/roster/stats'))
}
async function loadStudents() {
  students.value = await http.get('/roster/students', { params: { gender: gender.value, keyword: keyword.value } })
}
loadStats()
loadStudents()

function openAdd() {
  editing.value = null
  Object.assign(form, { stuNo: '', name: '', gender: '男', address: '', parents: [
    { relation: '父亲', name: '', phone: '' }, { relation: '母亲', name: '', phone: '' }
  ] })
  formVisible.value = true
}

function openProfile(s) {
  http.get(`/roster/student/${s.id}/profile`).then(d => { profile.value = d; profileVisible.value = true })
}

function openEditFromProfile() {
  const s = students.value.find(x => x.id === profile.value.info.id)
  if (!s) return
  profileVisible.value = false
  editing.value = s.id
  Object.assign(form, {
    stuNo: s.stu_no, name: s.name, gender: s.gender, address: s.address || '',
    parents: [
      { relation: '父亲', name: pname(s, '父亲'), phone: pphone(s, '父亲') },
      { relation: '母亲', name: pname(s, '母亲'), phone: pphone(s, '母亲') }
    ]
  })
  formVisible.value = true
}
function pname(s, rel) { const p = (s.parents || []).find(x => x.relation === rel); return p ? p.name : '' }
function pphone(s, rel) { const p = (s.parents || []).find(x => x.relation === rel); return p ? p.phone : '' }

async function saveStudent() {
  if (!form.name) { ElMessage.warning('请输入姓名'); return }
  if (editing.value) await http.put(`/roster/student/${editing.value}`, form)
  else await http.post('/roster/student', form)
  ElMessage.success('已保存')
  formVisible.value = false
  loadStats(); loadStudents()
}

async function doImport(opt) {
  const fd = new FormData()
  fd.append('file', opt.file)
  const r = await http.post('/roster/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  ElMessage.success(`导入成功 ${r.added} 人，跳过重复 ${r.skipped} 人`)
  loadStats(); loadStudents()
}

async function doExport() {
  const resp = await http.get('/roster/export', { responseType: 'blob' })
  const url = URL.createObjectURL(resp.data)
  const a = document.createElement('a')
  a.href = url; a.download = '花名册.xlsx'; a.click()
  URL.revokeObjectURL(url)
}

async function doClear() {
  await ElMessageBox.confirm('确定清空全班学生？此操作不可恢复！', '警告', { type: 'warning' })
  await http.post('/roster/clear')
  ElMessage.success('已清空')
  loadStats(); loadStudents()
}
</script>

<style scoped>
.stat-row { margin-bottom: 16px; }
.stat-card { height: 118px; }
.stat-card .en { font-size: 12px; opacity: .85; letter-spacing: 1px; }
.stat-card .num { font-size: 42px; }
.stat-card .num.small { font-size: 34px; padding-top: 4px; }
.grad-1 { background: linear-gradient(135deg, #7e6cf0, #9d8cf5); }
.grad-2 { background: linear-gradient(135deg, #35b6f0, #5ec8f5); }
.grad-3 { background: linear-gradient(135deg, #f8789c, #fbb03b); }
.grad-4 { background: linear-gradient(135deg, #8fe0d8, #f6c6dd); }
.filter-bar { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; color: #8a8fa3; }
.show-count { color: #b0b3c0; font-size: 13px; }
.stu-grid { display: grid; grid-template-columns: repeat(8, 1fr); gap: 12px; }
.profile-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; font-size: 16px; }
.muted { color: #a0a4b4; font-size: 13px; }
h4 { margin: 18px 0 8px; color: #5a5f72; }
.parent-line { padding: 6px 0; border-bottom: 1px dashed #eee; }
.record-line { padding: 6px 0; font-size: 14px; }
</style>
