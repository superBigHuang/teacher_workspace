<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">📈 成绩分析</h2>
      <span>本次考试</span>
      <el-select v-model="curExam" style="width:170px" @change="loadAll">
        <el-option v-for="e in exams" :key="e.id" :label="e.name" :value="e.id" />
      </el-select>
      <span>对比考试</span>
      <el-select v-model="cmpExam" style="width:170px" @change="loadCompare" clearable>
        <el-option v-for="e in exams" :key="e.id" :label="e.name" :value="e.id" />
      </el-select>
      <el-button type="primary" :icon="Upload" @click="importDlg = true">导入成绩</el-button>
      <el-button :icon="Setting" @click="openStd">标准</el-button>
      <el-button type="warning" plain @click="openReport">📄 家长通知单</el-button>
      <el-button type="success" plain @click="openPetAwards">🎁 宠物发奖</el-button>
      <el-button type="danger" plain :icon="Delete" @click="delExam">删除考试</el-button>
    </div>

    <!-- 各科统计卡 -->
    <div class="score-grid">
      <div v-for="s in subjectStats" :key="s.subject" class="score-col">
        <div class="score-card">
          <div class="sc-subject">{{ s.subject }}</div>
          <div class="sc-avg">{{ s.avg }}</div>
          <div class="sc-range">最高 {{ s.max }} ｜ 最低 {{ s.min }}</div>
          <div class="sc-rates">
            <el-progress :percentage="s.passRate" :stroke-width="8" color="#67c23a" :show-text="false" />
            <span>及格率 {{ s.passRate }}%</span>
          </div>
          <div class="sc-rates">
            <el-progress :percentage="s.excellentRate" :stroke-width="8" color="#f78fb3" :show-text="false" />
            <span>优秀率 {{ s.excellentRate }}%</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 分数段柱状图（可选科目；选了对比考试时叠加对比虚线，图例可点击开关） -->
    <el-card class="block">
      <div class="block-head">
        <h3 class="block-title">📊 成绩分析图表 · 分数段分布</h3>
        <el-select v-model="barSubject" size="small" style="width:140px" @change="loadDistribution">
          <el-option label="全部科目" value="" />
          <el-option v-for="s in subjectStats" :key="s.subject" :label="s.subject" :value="s.subject" />
        </el-select>
      </div>
      <div ref="barEl" class="chart"></div>
    </el-card>

    <!-- 折线图 -->
    <el-card class="block">
      <div class="block-head">
        <h3 class="block-title">📉 本次考试成绩分布</h3>
        <el-select v-model="lineSubject" size="small" style="width:140px" @change="loadLine">
          <el-option v-for="s in subjectStats" :key="s.subject" :label="s.subject" :value="s.subject" />
        </el-select>
      </div>
      <div ref="lineEl" class="chart tall"></div>
      <div v-if="lineData.stats" class="line-stat">
        平均分：<b>{{ lineData.stats.avg }}</b> ｜ 最高分：<b>{{ lineData.stats.max }}</b> ｜
        最低分：<b>{{ lineData.stats.min }}</b> ｜
        <span class="up">↑ 高于平均 {{ lineData.stats.above }} 人</span> ｜
        <span class="down">↓ 低于平均 {{ lineData.stats.below }} 人</span>
      </div>
    </el-card>

    <!-- 明细表 -->
    <el-card class="block">
      <h3 class="block-title">📋 成绩明细</h3>
      <el-table :data="detail.rows" size="small" stripe max-height="420">
        <el-table-column prop="rank" label="排名" width="70" />
        <el-table-column prop="stuNo" label="学号" width="100" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column v-for="sub in detail.subjects" :key="sub" :label="sub" width="80">
          <template #default="{ row }">{{ row.scores[sub] ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="total" label="总分" width="90" />
        <el-table-column prop="avg" label="平均分" width="90" />
      </el-table>
    </el-card>

    <!-- 对比表 -->
    <el-card v-if="cmpExam" class="block">
      <h3 class="block-title">🔁 考试进退步对比（本次 - 对比）</h3>
      <el-table :data="compare.rows" size="small" stripe max-height="420">
        <el-table-column prop="stuNo" label="学号" width="100" />
        <el-table-column prop="name" label="姓名" width="90" />
        <el-table-column v-for="sub in compare.subjects" :key="sub" :label="sub" width="85">
          <template #default="{ row }">
            <span :class="diffClass(row.diffs[sub])">{{ formatDiff(row.diffs[sub]) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="总分变化" width="100">
          <template #default="{ row }">
            <span :class="diffClass(row.totalDiff)"><b>{{ formatDiff(row.totalDiff) }}</b></span>
          </template>
        </el-table-column>
        <el-table-column label="排名" width="110">
          <template #default="{ row }">
            <span>第{{ row.rankA }}名</span>
            <el-tag v-if="row.rankChange" size="small" :type="row.rankChange > 0 ? 'success' : 'danger'">
              {{ row.rankChange > 0 ? '↑' : '↓' }}{{ Math.abs(row.rankChange) }}
            </el-tag>
            <el-tag v-else-if="row.rankB" size="small" type="info">—</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 导入弹窗 -->
    <el-dialog v-model="importDlg" title="导入成绩" width="520px">
      <el-form label-width="90px">
        <el-form-item label="考试名称"><el-input v-model="importForm.name" placeholder="如：2026秋季第一次月考" /></el-form-item>
        <el-form-item label="考试日期"><el-date-picker v-model="importForm.date" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
        <el-form-item label="成绩文件">
          <el-upload :show-file-list="false" :http-request="doImport" accept=".xlsx,.xls" :limit="1">
            <el-button :icon="Upload">选择 Excel 文件</el-button>
          </el-upload>
          <div class="import-tip">
            支持 .xls / .xlsx。表头格式：<b>学号 | 姓名 | 科目1 | 科目2 …</b>（如：学号|姓名|语文|数学|英语，科目列名即科目名，可自行增减）<br>
            匹配规则：<b>先按姓名匹配</b>，姓名匹配不上再按学号；重名时以学号区分。
          </div>
        </el-form-item>
      </el-form>
    </el-dialog>

    <!-- 标准弹窗 -->
    <el-dialog v-model="stdDlg" title="评分标准" width="360px">
      <el-form label-width="90px">
        <el-form-item label="及格线"><el-input-number v-model="stdForm.passLine" :min="0" :max="100" /></el-form-item>
        <el-form-item label="优秀线"><el-input-number v-model="stdForm.excellentLine" :min="0" :max="100" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stdDlg = false">取消</el-button>
        <el-button type="primary" @click="saveStd">保存</el-button>
      </template>
    </el-dialog>

    <!-- 家长通知单配置弹窗 -->
    <el-dialog v-model="reportDlg" title="📄 家长成绩通知单" width="540px">
      <el-form label-width="100px">
        <el-form-item label="考试">
          <el-select v-model="reportForm.examId" style="width:100%">
            <el-option v-for="e in exams" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="对比考试">
          <el-select v-model="reportForm.compareExamId" clearable style="width:100%" placeholder="不选则不显示进退步">
            <el-option v-for="e in exams" :key="e.id" :label="e.name" :value="e.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="附带内容">
          <el-checkbox v-model="reportForm.showDaily">本学期在校表现（迟到/请假/作业/纪律）</el-checkbox>
          <el-checkbox v-model="reportForm.showMessage">教师寄语</el-checkbox>
        </el-form-item>
        <template v-if="reportForm.showMessage">
          <el-form-item label="寄语方式">
            <el-radio-group v-model="reportForm.messageMode">
              <el-radio value="ai">✨ AI 逐人生成</el-radio>
              <el-radio value="uniform">统一寄语</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="统一寄语">
            <el-input v-model="reportForm.uniformMessage" type="textarea" :rows="2"
              placeholder="AI 失败时的兜底；选「统一寄语」时全员使用。留空则用个人设置中的通知单统一寄语" />
          </el-form-item>
        </template>
        <el-form-item v-if="reportLoading" label="生成进度">
          <el-progress :percentage="reportProgress" :stroke-width="14" striped striped-flow />
          <span class="rp-tip">AI 正在逐人撰写寄语（约 1 分钟/人），可离开本页稍后回来，请勿关闭浏览器…</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reportDlg = false">取消</el-button>
        <el-button type="primary" :loading="reportLoading" @click="genReport">生成并打开打印版</el-button>
      </template>
    </el-dialog>

    <!-- 宠物成绩发奖：满分+3 / 全科优秀+2 / 排名进步≥5名+2，每人取最高一项 -->
    <el-dialog v-model="awardDlg" title="🎁 宠物成绩发奖" width="520px">
      <p class="award-tip">规则：单科满分 <b>+3</b>、全科达优秀线 <b>+2</b>、较上场排名进步≥5名 <b>+2</b>（每人取最高一项；已发过的自动跳过，未领养宠物的学生不产生积分）</p>
      <div v-if="!awardList.length" class="award-empty">本场考试暂无可发奖的学生 🍬</div>
      <div v-for="a in awardList" :key="a.studentId" class="award-row" :class="{ granted: a.granted }">
        <el-checkbox v-model="a.checked" :disabled="a.granted" />
        <span class="award-name">{{ a.name }}</span>
        <span class="award-reason">{{ a.reason }}</span>
        <el-tag :type="a.score >= 3 ? 'warning' : 'success'" effect="dark">+{{ a.score }}</el-tag>
        <span v-if="a.granted" class="award-done">✅ 已发</span>
      </div>
      <template #footer>
        <el-button @click="awardDlg = false">关闭</el-button>
        <el-button type="primary" :disabled="!awardList.some(a => a.checked && !a.granted)" @click="grantAwards">
          🐉 发放所选（{{ awardList.filter(a => a.checked && !a.granted).length }} 人）
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, nextTick, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox, ElNotification } from 'element-plus'
import { Upload, Setting, Delete } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import http from '../api/http'

const exams = ref([])
const curExam = ref(null)
const cmpExam = ref(null)
const subjectStats = ref([])
const lineSubject = ref('')
const lineData = ref({})
const barSubject = ref('') // 分数段图选中科目（'' = 全部科目合并）
const barData = ref({})
const detail = reactive({ subjects: [], rows: [] })
const compare = reactive({ subjects: [], rows: [] })

const barEl = ref(null), lineEl = ref(null)
let barChart = null, lineChart = null

const importDlg = ref(false)
const importForm = reactive({ name: '', date: '', file: null })
const stdDlg = ref(false)
const stdForm = reactive({ passLine: 60, excellentLine: 85 })

// 家长通知单
const reportDlg = ref(false)
const reportLoading = ref(false)
const reportProgress = ref(0)
const reportForm = reactive({
  examId: null, compareExamId: null,
  showDaily: true, showMessage: true,
  messageMode: 'ai', uniformMessage: '',
})

function openReport() {
  if (!curExam.value) { ElMessage.warning('请先选择考试'); return }
  reportForm.examId = curExam.value
  reportForm.compareExamId = cmpExam.value || null
  reportDlg.value = true
}

// 宠物成绩发奖（满分/优秀/进步三类，每人取最高一项，幂等可重复打开）
const awardDlg = ref(false)
const awardList = ref([])
async function openPetAwards() {
  if (!curExam.value) { ElMessage.warning('请先选择考试'); return }
  awardDlg.value = true
  const list = await http.get('/pet/score-awards', { params: { examId: curExam.value } })
  awardList.value = list.map(a => ({ ...a, checked: !a.granted }))
}
async function grantAwards() {
  const items = awardList.value.filter(a => a.checked && !a.granted)
    .map(a => ({ studentId: a.studentId, score: a.score, reason: a.reason }))
  if (!items.length) return
  const r = await http.post('/pet/score-awards/grant', { examId: curExam.value, items })
  ElMessage.success(`发奖完成，${r.affected} 只宠物获得成长值 🐉✨`)
  const list = await http.get('/pet/score-awards', { params: { examId: curExam.value } })
  awardList.value = list.map(a => ({ ...a, checked: false }))
}

/**
 * 生成通知单：拉报告数据包 → 可选 AI 并发(3)逐人生成寄语（单人失败保留统一寄语）
 * → 写 sessionStorage → 新窗口打开 A4 打印页。
 */
async function genReport() {
  reportLoading.value = true
  reportProgress.value = 0
  try {
    const data = await http.get('/score/report', {
      params: { examId: reportForm.examId, compareExamId: reportForm.compareExamId || undefined },
    })
    // 统一寄语兜底：弹窗输入优先，其次个人设置中的 report_message
    const uniform = reportForm.uniformMessage.trim() || data.reportMessage || ''
    data.students.forEach(s => { s.comment = uniform; s.aiSource = false })

    if (reportForm.showMessage && reportForm.messageMode === 'ai') {
      // 并发 3 逐人生成寄语；失败的人保留统一寄语
      const queue = [...data.students]
      let done = 0
      const worker = async () => {
        while (queue.length) {
          const s = queue.shift()
          try {
            const r = await http.post('/ai/generate', { scene: 'message', studentId: s.studentId })
            if (r && r.content) {
              s.comment = r.content
              s.aiSource = r.source === 'ai'
            }
          } catch (e) { /* 单人失败不阻断，保留统一寄语 */ }
          done++
          reportProgress.value = Math.round(done * 100 / data.students.length)
        }
      }
      await Promise.all([worker(), worker(), worker()])
    }
    if (!reportForm.showMessage) data.students.forEach(s => { s.comment = '' })

    sessionStorage.setItem('scoreReportData', JSON.stringify({
      data,
      opts: { showDaily: reportForm.showDaily, showMessage: reportForm.showMessage },
    }))
    window.open('/print/report', '_blank')
    reportDlg.value = false
  } finally {
    reportLoading.value = false
    reportProgress.value = 0
  }
}

async function loadExams() {
  exams.value = await http.get('/score/exams')
  if (!curExam.value && exams.value.length) curExam.value = exams.value[0].id
  await loadAll()
}

async function loadAll() {
  if (!curExam.value) { subjectStats.value = []; detail.subjects = []; detail.rows = []; barData.value = {}; return }
  subjectStats.value = await http.get('/score/subject-stats', { params: { examId: curExam.value } })
  const d = await http.get('/score/detail', { params: { examId: curExam.value } })
  detail.subjects = d.subjects; detail.rows = d.rows
  if (!lineSubject.value && subjectStats.value.length) lineSubject.value = subjectStats.value[0].subject
  await Promise.all([loadLine(), loadDistribution()])
}

/** 分数段数据：带选中科目与对比考试；到位后 nextTick 重绘（drawBar） */
async function loadDistribution() {
  if (!curExam.value) return
  const params = { examId: curExam.value }
  if (barSubject.value) params.subject = barSubject.value
  if (cmpExam.value) params.compareExamId = cmpExam.value
  barData.value = await http.get('/score/distribution', { params })
  nextTick(drawBar)
}

/** 分数段柱状图：本次为柱、对比考试为虚线（图例可点击开关）；setOption notMerge 防切换后旧系列残留 */
function drawBar() {
  if (!barEl.value || !barData.value.labels) return
  barChart ||= echarts.init(barEl.value)
  const colors = ['#f78fb3', '#f6a86b', '#f5d76e', '#67d39a', '#6fa8f5']
  const hasCmp = !!barData.value.cmpCounts
  const series = [
    { name: '本次考试', type: 'bar', barWidth: hasCmp ? '40%' : '55%',
      data: barData.value.counts.map((v, i) => ({ value: v, itemStyle: { color: colors[i] } })),
      label: { show: true, position: 'top' } }
  ]
  if (hasCmp) {
    series.push({ name: '对比考试', type: 'line', data: barData.value.cmpCounts, smooth: true, symbolSize: 8,
      lineStyle: { color: '#909399', width: 2, type: 'dashed' }, itemStyle: { color: '#909399' },
      label: { show: true, fontSize: 10, color: '#909399' } })
  }
  barChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { top: 0, data: hasCmp ? ['本次考试', '对比考试'] : [] },
    grid: { left: 40, right: 20, top: hasCmp ? 40 : 20, bottom: 30 },
    xAxis: { type: 'category', data: barData.value.labels },
    yAxis: { type: 'value', minInterval: 1 },
    series
  }, true)
}

/** 拉取单科折线数据（选了对比考试则带 compareExamId，后端按学生对齐返回 cmpScores）；数据写入 lineData 后由 watch(lineData) 触发 drawLine 重绘（勿在末尾再触发加载，会成请求死循环） */
async function loadLine() {
  if (!lineSubject.value) return
  const params = { examId: curExam.value, subject: lineSubject.value }
  if (cmpExam.value) params.compareExamId = cmpExam.value
  lineData.value = await http.get('/score/line', { params })
}

/** 折线图：本次分数/本次平均常态；选了对比考试加对比分数曲线；图例可点击开关各曲线 */
function drawLine() {
  if (!lineEl.value || !lineData.value.names) return
  lineChart ||= echarts.init(lineEl.value)
  const hasCmp = !!lineData.value.cmpScores
  const series = [
    { name: '本次分数', type: 'line', data: lineData.value.scores, smooth: true,
      itemStyle: { color: '#6fa8f5' }, label: { show: true, fontSize: 9 } },
    { name: '本次平均', type: 'line', data: lineData.value.names.map(() => lineData.value.avg),
      lineStyle: { type: 'dashed', color: '#f78fb3' }, itemStyle: { color: '#f78fb3' }, symbol: 'none' }
  ]
  if (hasCmp) {
    series.push({ name: '对比分数', type: 'line', data: lineData.value.cmpScores, smooth: true, symbolSize: 5,
      lineStyle: { color: '#e6a23c', width: 2 }, itemStyle: { color: '#e6a23c' }, connectNulls: false })
  }
  lineChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { top: 0, data: hasCmp ? ['本次分数', '对比分数', '本次平均'] : ['本次分数', '本次平均'] },
    grid: { left: 40, right: 20, top: 40, bottom: 60 },
    xAxis: { type: 'category', data: lineData.value.names, axisLabel: { rotate: 45, fontSize: 10 } },
    yAxis: { type: 'value', min: 0, max: 100 },
    series
  }, true)
}

async function loadCompare() {
  if (!cmpExam.value) {
    compare.subjects = []; compare.rows = []
  } else {
    const r = await http.get('/score/compare', { params: { examA: curExam.value, examB: cmpExam.value } })
    compare.subjects = r.subjects; compare.rows = r.rows
  }
  // 对比考试变化（含清空）同步刷新两图表的对比曲线
  loadDistribution()
  loadLine()
}

async function doImport(opt) {
  if (!importForm.name) { ElMessage.warning('请填写考试名称'); return }
  const fd = new FormData()
  fd.append('file', opt.file)
  fd.append('examName', importForm.name)
  fd.append('examDate', importForm.date || '')
  const r = await http.post('/score/import', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  ElMessage.success(`导入成功：${r.students} 名学生，${r.subjects} 个科目`)
  // 导入后后端自动扫描成就，有新颁发则弹庆祝通知
  if (r.newAwards?.length) notifyAchievements(r.newAwards)
  importDlg.value = false
  importForm.name = ''; importForm.date = ''
  await loadExams()
}

/** 成绩导入触发的新成就合并庆祝通知（按成就分组，最多列 5 条） */
function notifyAchievements(list) {
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

async function delExam() {
  if (!curExam.value) return
  await ElMessageBox.confirm('确定删除本次考试及其成绩？', '警告', { type: 'warning' })
  await http.delete('/score/exam/' + curExam.value)
  ElMessage.success('已删除')
  curExam.value = null
  loadExams()
}

async function openStd() {
  const s = await http.get('/dashboard/settings')
  stdForm.passLine = Number(s.pass_line || 60)
  stdForm.excellentLine = Number(s.excellent_line || 85)
  stdDlg.value = true
}
async function saveStd() {
  await http.post('/dashboard/settings', { pass_line: String(stdForm.passLine), excellent_line: String(stdForm.excellentLine) })
  ElMessage.success('已保存')
  stdDlg.value = false
  loadAll()
}

function formatDiff(v) { if (v == null) return '—'; return (v > 0 ? '+' : '') + v }
function diffClass(v) { if (v == null) return ''; return v > 0 ? 'up' : v < 0 ? 'down' : '' }

// 监听折线数据后绘图
watch(lineData, () => nextTick(drawLine), { deep: true })

// 图表依赖 DOM 引用，挂载后再加载
onMounted(loadExams)
</script>

<style scoped>
.import-tip { margin-top: 8px; font-size: 12px; line-height: 1.8; color: #8a8fa3; background: #f6f8fc; border-radius: 8px; padding: 8px 12px; }
.score-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; margin-bottom: 8px; }
.score-col { margin-bottom: 8px; }
.score-card { background: #fff; border-radius: 14px; padding: 14px; box-shadow: 0 4px 14px rgba(120,130,160,.08); border-top: 3px solid #f78fb3; }
.sc-subject { font-weight: 800; color: #5a5f72; }
.sc-avg { font-size: 30px; font-weight: 800; color: #f06292; margin: 4px 0; }
.sc-range { font-size: 12px; color: #aab0c0; margin-bottom: 8px; }
.sc-rates { display: flex; align-items: center; gap: 8px; font-size: 12px; color: #8a8fa3; margin-top: 4px; }
.sc-rates .el-progress { flex: 1; }
.block { margin-bottom: 18px; }
.block-title { margin: 0 0 12px; color: #5a5f72; }
.block-head { display: flex; align-items: center; justify-content: space-between; }
.chart { height: 240px; }
.chart.tall { height: 320px; }
.line-stat { margin-top: 10px; color: #6a6f82; font-size: 14px; }
.up { color: #67c23a; font-weight: 700; }
.down { color: #f56c6c; font-weight: 700; }
:deep(.up) { color: #67c23a; font-weight: 700; }
:deep(.down) { color: #f56c6c; font-weight: 700; }
.rp-tip { display: block; margin-top: 6px; color: #b08a2e; font-size: 12px; }

/* 宠物成绩发奖弹窗 */
.award-tip { font-size: 12px; color: #a08a70; line-height: 1.7; background: #fff7ec; border-radius: 12px; padding: 8px 12px; margin: 0 0 12px; }
.award-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px; border-radius: 12px;
  background: #f6f9f7; margin-bottom: 7px; font-size: 14px;
}
.award-row.granted { opacity: .55; }
.award-name { font-weight: 800; color: #5a5f72; width: 76px; }
.award-reason { flex: 1; color: #8a7f8a; font-size: 13px; }
.award-done { color: #67c23a; font-size: 12px; font-weight: 700; }
.award-empty { text-align: center; color: #b8a8b0; padding: 30px 0; }
</style>
