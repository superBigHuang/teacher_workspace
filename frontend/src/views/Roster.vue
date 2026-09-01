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

    <!-- 视图切换：花名册 / 成就榜 -->
    <div class="filter-bar">
      <el-radio-group v-model="viewMode" size="small">
        <el-radio-button value="roster">👥 花名册</el-radio-button>
        <el-radio-button value="board">🏅 成就榜</el-radio-button>
      </el-radio-group>
      <template v-if="viewMode === 'roster'">
        <span style="margin-left:12px">性别筛选：</span>
        <el-radio-group v-model="gender" @change="loadStudents">
          <el-radio-button value="全部">全部</el-radio-button>
          <el-radio-button value="男">男生</el-radio-button>
          <el-radio-button value="女">女生</el-radio-button>
        </el-radio-group>
        <div class="spacer"></div>
        <span class="show-count">当前显示 {{ students.length }} 人</span>
      </template>
    </div>

    <!-- 成就榜视图 -->
    <AchievementBoard v-if="viewMode === 'board'" />

    <!-- 学生卡片 -->
    <div v-else class="stu-grid">
      <div v-for="s in students" :key="s.id" class="stu-card" :class="s.gender === '女' ? 'girl' : 'boy'" @click="openProfile(s)">
        <span v-if="badgeMap[s.id]?.cnt" class="stu-badge">🏅{{ badgeMap[s.id].cnt }}</span>
        <span v-if="petMap[s.id]" class="stu-pet">
          <img :src="petImgOf(petMap[s.id])" alt="宠物">Lv.{{ petMap[s.id].level }}
        </span>
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
          <el-button size="small" text type="danger" @click="deleteStudent">删除</el-button>
        </div>
        <p class="muted" v-if="profile.info.address">住址：{{ profile.info.address }}</p>

        <el-tabs v-model="profileTab" @tab-change="onProfileTab">
        <el-tab-pane label="📇 基本档案" name="basic">
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
        </el-tab-pane>

        <!-- 成长档案：本学期数字卡 + 成绩走势 + 五类记录时间线 -->
        <el-tab-pane label="📈 成长档案" name="archive">
          <div v-if="archive">
            <p class="muted" style="margin:0 0 8px">本学期 {{ archive.termStart }} ~ {{ archive.termEnd }}</p>
            <div class="arc-cards">
              <div class="arc-card"><b>{{ arcMat.late }}</b><span>迟到(次)</span></div>
              <div class="arc-card"><b>{{ arcMat.leaveDays }}</b><span>请假(天)</span></div>
              <div class="arc-card"><b>{{ arcMat.homeworkMissing }}</b><span>作业未交</span></div>
              <div class="arc-card"><b>{{ arcMat.discipline }}</b><span>违纪(条)</span></div>
              <div class="arc-card"><b>{{ arcMat.commCount }}</b><span>家校沟通</span></div>
              <div class="arc-card" :class="rankChangeClass">
                <b>{{ rankChangeText }}</b><span>排名变化</span>
              </div>
            </div>

            <h4>📊 成绩走势</h4>
            <div v-if="archive.trend.length" ref="trendChartEl" class="arc-chart"></div>
            <el-empty v-else description="本学期暂无考试成绩" :image-size="50" />

            <h4>🕘 本学期记录时间线</h4>
            <div v-for="(t, i) in archive.timeline" :key="i" class="arc-tl">
              <span class="arc-emoji">{{ t.emoji }}</span>
              <div>
                <div class="arc-tl-text">{{ t.text }}</div>
                <span class="muted">{{ t.date }}</span>
              </div>
            </div>
            <el-empty v-if="!archive.timeline.length" description="本学期暂无异常记录，表现优秀" :image-size="50" />
          </div>
          <el-empty v-else description="加载中…" :image-size="60" />
        </el-tab-pane>

        <!-- 我的成就：累计统计 + 本学期徽章墙（未获得灰化带进度）+ 历史学期 -->
        <el-tab-pane label="🏅 我的成就" name="achievement" lazy>
          <div v-if="wall">
            <div class="aw-summary">
              <div class="aw-total">🏅 {{ wall.stats.total }} <small>枚</small></div>
              <div class="aw-points">⭐ {{ wall.stats.points }} <small>成就点</small></div>
              <div class="aw-tiers">
                <span><i class="aw-dot" style="background:#c07a3e"></i>铜 {{ wall.stats.bronze }}</span>
                <span><i class="aw-dot" style="background:#9a93b4"></i>银 {{ wall.stats.silver }}</span>
                <span><i class="aw-dot" style="background:#e8a526"></i>金 {{ wall.stats.gold }}</span>
                <span><i class="aw-dot" style="background:#5fb5e8"></i>白金 {{ wall.stats.platinum }}</span>
              </div>
            </div>

            <h4>📦 本学期（{{ wall.termStart }} ~ {{ wall.termEnd }}）</h4>
            <div class="aw-grid">
              <el-popover v-for="b in wall.currentBadges" :key="b.defId" placement="top" :width="230" trigger="hover">
                <template #reference>
                  <div class="aw-cell" :class="{ locked: !b.earned }">
                    <BadgeIcon :icon="b.icon" :tier="b.tier" :earned="b.earned" :size="46" />
                    <span class="aw-bname">{{ b.name }}</span>
                  </div>
                </template>
                <div class="aw-pop">
                  <b>{{ b.name }}</b>
                  <p class="muted" style="margin:6px 0">{{ b.description || '老师自定义成就' }}</p>
                  <p v-if="b.earned" style="margin:0;color:#2e8b57">✅ {{ b.awardedAt }} 获得<span v-if="b.remark">（{{ b.remark }}）</span></p>
                  <p v-else-if="b.progress" style="margin:0;color:#b07a3e">📊 {{ b.progress }}</p>
                  <p v-else-if="b.kind === 'MANUAL'" class="muted" style="margin:0">由老师手动授予</p>
                </div>
              </el-popover>
            </div>

            <template v-if="wall.historyTerms.length">
              <h4>📚 历史学期</h4>
              <el-collapse>
                <el-collapse-item v-for="t in wall.historyTerms" :key="t.term" :name="t.term">
                  <template #title>{{ t.term }} · 🏅 {{ t.badges.length }} 枚</template>
                  <div class="aw-grid">
                    <el-popover v-for="b in t.badges" :key="b.defId" placement="top" :width="230" trigger="hover">
                      <template #reference>
                        <div class="aw-cell">
                          <BadgeIcon :icon="b.icon" :tier="b.tier" :earned="true" :animate="false" :size="42" />
                          <span class="aw-bname">{{ b.name }}</span>
                        </div>
                      </template>
                      <div class="aw-pop">
                        <b>{{ b.name }}</b>
                        <p class="muted" style="margin:6px 0">{{ b.description || '老师自定义成就' }}</p>
                        <p style="margin:0;color:#2e8b57">✅ {{ b.awardedAt }} 获得<span v-if="b.remark">（{{ b.remark }}）</span></p>
                      </div>
                    </el-popover>
                  </div>
                </el-collapse-item>
              </el-collapse>
            </template>
          </div>
          <el-empty v-else description="加载中…" :image-size="60" />
        </el-tab-pane>

        <!-- 我的宠物：形态/等级/成长值/毕业徽章/最近流水 -->
        <el-tab-pane label="🐾 宠物" name="pet" lazy>
          <div v-if="petInfo">
            <div class="pet-box">
              <img class="pet-arc-img" :src="petImgOf(petInfo)" alt="宠物">
              <div class="pet-arc-info">
                <div class="pet-arc-name">{{ petInfo.petName }}
                  <span class="pet-lv">Lv.{{ petInfo.level }}</span>
                </div>
                <div class="muted">⭐ 成长值 <b>{{ petInfo.exp }}</b><template v-if="petInfo.level < 5"> · 距 Lv.{{ petInfo.level + 1 }} 还需 {{ petInfo.expTarget - petInfo.exp }}</template><template v-else> · 已满级可毕业</template></div>
                <div class="pet-arc-badges">🎓 毕业徽章 <b>{{ petInfo.graduatedCount || 0 }}</b> 枚
                  <span v-for="n in petInfo.graduatedCount" :key="n">🎓</span>
                </div>
              </div>
            </div>
            <h4>📖 最近成长流水</h4>
            <div v-for="l in petLogs.slice(0, 5)" :key="l.id" class="pet-log-row">
              <span class="pet-log-score" :class="l.score > 0 ? 'good' : 'bad'">{{ l.score > 0 ? '+' : '' }}{{ l.score }}</span>
              {{ l.remark || l.ruleName || '评价' }}
              <span class="muted">{{ fmtPetTime(l.createdAt) }}</span>
            </div>
            <el-empty v-if="!petLogs.length" description="还没有成长记录，去座次表点击宠物评价吧" :image-size="50" />
          </div>
          <el-empty v-else description="还未领养守护精灵，可在「🪑 座次表」点击精灵蛋领养" :image-size="70" />
        </el-tab-pane>
        </el-tabs>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, nextTick } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { Search, Plus, FolderOpened, Download, Delete } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import http from '../api/http'
import { petImgOf, setSkinCache } from '../composables/usePets'
import BadgeIcon from '../components/BadgeIcon.vue'
import AchievementBoard from './AchievementBoard.vue'

const stats = reactive({ total: 0, boys: 0, girls: 0, ratio: '0:0' })
const students = ref([])
const keyword = ref('')
const gender = ref('全部')
const viewMode = ref('roster')

// 成就角标：studentId -> {cnt, points}（进页面静默扫描后拉榜，不弹通知）
const badgeMap = ref({})
async function silentScanForBadges() {
  try {
    await http.post('/achievement/scan')
    const b = await http.get('/achievement/board')
    const m = {}
    b.rankings.forEach(r => { m[r.studentId] = { cnt: r.cnt, points: r.points } })
    badgeMap.value = m
  } catch (e) { /* 成就模块不可用时不影响花名册 */ }
}

// 宠物角标：studentId -> 宠物信息（形态/等级/成长值/毕业数）
const petMap = ref({})
const petLogs = ref([])
/** 抽屉当前学生的宠物（未领养为 undefined） */
const petInfo = computed(() => profile.value.info ? petMap.value[profile.value.info.id] : null)
async function loadPetMap() {
  try {
    // /pet/pets 返回 {pets, pendingEvolves}；同时拉皮肤以支持自创皮肤图片映射
    const data = await http.get('/pet/pets')
    const list = data.pets || data
    const m = {}
    list.forEach(p => { if (p.petId) m[p.studentId] = p })
    petMap.value = m
    try { setSkinCache(await http.get('/pet/skins')) } catch (e) { /* 皮肤接口不可用时兜底内置云龙 */ }
  } catch (e) { /* 宠物模块不可用时不影响花名册 */ }
}
function fmtPetTime(t) { return String(t || '').replace('T', ' ').slice(5, 16) }

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
silentScanForBadges()
loadPetMap()

function openAdd() {
  editing.value = null
  Object.assign(form, { stuNo: '', name: '', gender: '男', address: '', parents: [
    { relation: '父亲', name: '', phone: '' }, { relation: '母亲', name: '', phone: '' }
  ] })
  formVisible.value = true
}

// 成长档案页签数据（数字卡/走势/时间线），切到该页签时才加载
const profileTab = ref('basic')
const archive = ref(null)
const wall = ref(null)
const trendChartEl = ref(null)
let trendChart = null

function openProfile(s) {
  profileTab.value = 'basic'
  archive.value = null
  wall.value = null
  petLogs.value = []
  loadPetMap()   // 刷新宠物角标/档案数据
  http.get(`/roster/student/${s.id}/profile`).then(d => { profile.value = d; profileVisible.value = true })
}

/** 抽屉页签切换：成长档案拉聚合数据；成就页签先静默扫描（新成就弹庆祝）再拉徽章墙 */
async function onProfileTab(name) {
  const sid = profile.value.info.id
  if (name === 'archive') {
    if (archive.value) return
    archive.value = await http.get('/comment/archive', { params: { studentId: sid } })
    await nextTick()
    renderTrend()
  } else if (name === 'achievement') {
    if (wall.value) return
    const r = await http.post('/achievement/scan')
    if (r.newAwards?.length) notifyAwards(r.newAwards)
    wall.value = await http.get(`/achievement/student/${sid}`)
  } else if (name === 'pet') {
    petLogs.value = await http.get('/pet/logs', { params: { studentId: sid } })
  }
}

/** 新颁发成就合并弹庆祝通知（按成就分组，最多列 5 条） */
function notifyAwards(list) {
  const byDef = {}
  list.forEach(a => { (byDef[a.defName] ||= []).push(a.name) })
  const lines = Object.entries(byDef).slice(0, 5).map(([def, names]) => {
    const shown = names.slice(0, 3).join('、')
    return `🏅 ${shown}${names.length > 3 ? ` 等 ${names.length} 人` : ''} 获得「${def}」`
  })
  ElNotification({
    title: `🎉 新成就解锁（共 ${list.length} 枚）`,
    message: lines.join('<br>'),
    dangerouslyUseHTMLString: true, duration: 8000, type: 'success'
  })
}

/** 档案数字卡素材（后端缺失字段兜底 0） */
const arcMat = computed(() => {
  const m = archive.value?.material || {}
  return {
    late: m.late ?? 0, leaveDays: m.leaveDays ?? 0, homeworkMissing: m.homeworkMissing ?? 0,
    discipline: m.discipline ?? 0, commCount: m.commCount ?? 0
  }
})
const rankChangeText = computed(() => {
  const c = archive.value?.material?.rankChange
  if (c === null || c === undefined) return '—'
  if (c > 0) return '↑' + c
  if (c < 0) return '↓' + (-c)
  return '0'
})
const rankChangeClass = computed(() => {
  const c = archive.value?.material?.rankChange
  return c > 0 ? 'arc-up' : c < 0 ? 'arc-down' : ''
})

/** 成绩走势：总分折线 + 排名副轴（名次越小越好，故逆序） */
function renderTrend() {
  const t = archive.value?.trend
  if (!t || !t.length || !trendChartEl.value) return
  trendChart?.dispose()
  trendChart = echarts.init(trendChartEl.value)
  trendChart.setOption({
    grid: { left: 42, right: 40, top: 34, bottom: 28 },
    tooltip: { trigger: 'axis' },
    legend: { data: ['总分', '班级排名'], top: 0, textStyle: { fontSize: 11 } },
    xAxis: { type: 'category', data: t.map(x => x.name), axisLabel: { fontSize: 10, interval: 0 } },
    yAxis: [
      { type: 'value', name: '总分', scale: true },
      { type: 'value', name: '排名', inverse: true, min: 1, splitLine: { show: false } }
    ],
    series: [
      { name: '总分', type: 'line', smooth: true, data: t.map(x => x.total),
        itemStyle: { color: '#9b6dd6' }, label: { show: true, fontSize: 10 } },
      { name: '班级排名', type: 'line', smooth: true, yAxisIndex: 1, data: t.map(x => x.rank),
        itemStyle: { color: '#f0a050' } }
    ]
  })
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

// 从档案抽屉删除学生（级联删除家长/沟通/考勤/纪律/作业/座次/值日/班委/成绩）
async function deleteStudent() {
  const info = profile.value.info
  await ElMessageBox.confirm(
    `确定删除学生「${info.name}」？其家长、沟通、考勤、纪律、作业等关联信息将一并删除，不可恢复！`,
    '删除确认', { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '取消' })
  await http.delete(`/roster/student/${info.id}`)
  ElMessage.success('已删除')
  profileVisible.value = false
  loadStats(); loadStudents()
}

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
  ElMessage.success(`导入成功 ${r.added} 人，跳过重复 ${r.skipped} 人，信息不全未导 ${r.incomplete ?? 0} 人`)
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

/* 成长档案 */
.arc-cards { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 8px 0; }
.arc-card {
  background: linear-gradient(135deg, #f3eefb, #faf7fc); border-radius: 10px;
  padding: 8px 4px; text-align: center;
}
.arc-card b { display: block; font-size: 20px; color: #6b5b73; }
.arc-card span { font-size: 11px; color: #9a93a6; }
.arc-card.arc-up b { color: #2e8b57; }
.arc-card.arc-down b { color: #d06060; }
.arc-chart { width: 100%; height: 200px; }
.arc-tl { display: flex; gap: 8px; padding: 7px 0; border-bottom: 1px dashed #f0ecf4; font-size: 13px; }
.arc-emoji { font-size: 16px; }
.arc-tl-text { color: #4a4455; line-height: 1.5; }

/* 学生卡成就角标 */
.stu-card { position: relative; }
.stu-badge {
  position: absolute; top: -6px; right: -6px;
  background: linear-gradient(135deg, #ffd870, #f0a530); color: #7a4a00;
  font-size: 11px; font-weight: 700; padding: 2px 7px; border-radius: 10px;
  box-shadow: 0 2px 6px rgba(220,160,60,.5); z-index: 2;
}
/* 学生卡宠物角标 */
.stu-pet {
  position: absolute; bottom: -8px; left: -6px; z-index: 2;
  display: flex; align-items: center; gap: 1px;
  background: #fff7ec; border: 2px solid #ffd9b8; border-radius: 12px;
  padding: 0 5px 0 1px; font-size: 10px; font-weight: 800; color: #ff7f32;
  box-shadow: 0 2px 6px rgba(230,170,120,.4);
}
.stu-pet img { width: 22px; height: 22px; object-fit: contain; }

/* 档案抽屉·宠物 */
.pet-box {
  display: flex; gap: 14px; align-items: center;
  background: linear-gradient(135deg, #fff7ec, #ffeeda); border-radius: 14px; padding: 12px;
}
.pet-arc-img { width: 84px; height: 84px; object-fit: contain; filter: drop-shadow(0 4px 6px rgba(220,160,110,.3)); }
.pet-arc-info { flex: 1; }
.pet-arc-name { font-size: 17px; font-weight: 800; color: #8a5a34; margin-bottom: 5px; }
.pet-lv {
  background: linear-gradient(135deg, #ffb38a, #ff9f5a); color: #fff; font-size: 11px;
  border-radius: 9px; padding: 1px 8px; margin-left: 6px;
}
.pet-arc-badges { font-size: 13px; color: #9a7a5c; margin-top: 5px; }
.pet-log-row { padding: 6px 4px; font-size: 13px; border-bottom: 1px dashed #f3e8da; display: flex; gap: 8px; align-items: baseline; }
.pet-log-score { font-weight: 900; min-width: 30px; }
.pet-log-score.good { color: #4caf7d; }
.pet-log-score.bad { color: #ef6f6f; }

/* 徽章墙 */
.aw-summary {
  display: flex; align-items: center; gap: 14px; padding: 12px 14px;
  background: linear-gradient(135deg, #fff8e8, #f6efff); border-radius: 12px;
}
.aw-total { font-size: 24px; font-weight: 800; color: #b07a2a; }
.aw-points { font-size: 24px; font-weight: 800; color: #8a6dbf; }
.aw-total small, .aw-points small { font-size: 12px; font-weight: 400; color: #9a93a6; }
.aw-tiers { margin-left: auto; display: flex; gap: 10px; font-size: 12px; color: #6b6480; flex-wrap: wrap; }
.aw-dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; margin-right: 3px; }
.aw-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.aw-cell {
  display: flex; flex-direction: column; align-items: center; gap: 4px;
  padding: 8px 4px; border-radius: 10px; background: #faf8fd; cursor: default;
}
.aw-cell.locked { background: #f6f5f8; }
.aw-bname { font-size: 11px; color: #5a5470; text-align: center; line-height: 1.2; }
.aw-cell.locked .aw-bname { color: #b0aec0; }
.aw-pop b { color: #4a4455; }
:deep(.aw-pop .muted) { font-size: 12px; line-height: 1.5; }
</style>
