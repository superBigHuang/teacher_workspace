<template>
  <div class="comment-page">
    <!-- 顶部操作栏 -->
    <div class="candy-card cm-toolbar">
      <div>
        <h3 style="margin:0">📝 期末评语</h3>
        <p class="muted" style="margin:4px 0 0">
          学期区间 {{ data.termStart }} ~ {{ data.termEnd }} ｜ 已保存 <b class="cm-saved-n">{{ savedCount }}</b> / {{ data.students?.length || 0 }} 人
        </p>
      </div>
      <div class="cm-tools">
        <el-button type="primary" plain :loading="batch.loading" @click="batchGenerate">
          ✨ 批量生成草稿（{{ unsavedCount }} 人未保存）
        </el-button>
        <el-button type="warning" plain @click="openBook">📖 打印评语册</el-button>
      </div>
      <div v-if="batch.loading" class="cm-progress">
        <el-progress :percentage="batch.progress" :stroke-width="14" striped striped-flow />
        <span class="rp-tip">AI 正在逐人思考撰写评语（约 1 分钟/人），可离开本页稍后回来，请勿关闭浏览器…（{{ batch.done }}/{{ batch.total }}）</span>
      </div>
    </div>

    <div class="cm-body">
      <!-- 左：学生名单（带状态点） -->
      <div class="candy-card cm-list">
        <el-input v-model="keyword" placeholder="搜索姓名/学号" clearable size="small" class="cm-search">
          <template #prefix>🔍</template>
        </el-input>
        <div class="cm-legend">
          <span><i class="dot dot-saved"></i>已保存</span>
          <span><i class="dot dot-draft"></i>已生成</span>
          <span><i class="dot dot-none"></i>未生成</span>
        </div>
        <div class="cm-items">
          <div v-for="s in filteredStudents" :key="s.studentId"
               class="cm-item" :class="{ active: currentId === s.studentId }"
               @click="selectStudent(s)">
            <i class="dot" :class="'dot-' + statusOf(s.studentId)"></i>
            <span class="cm-name">{{ s.name }}</span>
            <span class="muted cm-no">{{ s.stuNo }}</span>
          </div>
          <el-empty v-if="!filteredStudents.length" description="无学生" :image-size="60" />
        </div>
      </div>

      <!-- 右：素材 + 评语编辑 -->
      <div class="candy-card cm-editor" v-if="current">
        <div class="cm-editor-head">
          <div>
            <el-tag :type="current.gender === '女' ? 'danger' : 'primary'">{{ current.gender }}</el-tag>
            <b class="cm-cur-name">{{ current.name }}</b>
            <span class="muted">学号 {{ current.stuNo }}</span>
          </div>
          <div>
            <el-tag v-if="meta.source === 'ai'" type="success" size="small">✨ AI 生成</el-tag>
            <el-tag v-else-if="meta.source === 'template'" type="warning" size="small">规则模板</el-tag>
            <el-tag v-else-if="meta.source" size="small">手动编辑</el-tag>
            <el-tag v-if="statusOf(current.studentId) === 'saved'" type="success" size="small" effect="dark">
              已保存{{ meta.updatedAt ? ' ' + fmtTime(meta.updatedAt) : '' }}
            </el-tag>
          </div>
        </div>

        <!-- 素材条 -->
        <div class="cm-materials">
          <div class="cm-mat"><label>📊 学业</label><span>{{ matText.study }}</span></div>
          <div class="cm-mat"><label>⏰ 考勤</label><span>{{ matText.attend }}</span></div>
          <div class="cm-mat"><label>📚 作业</label><span>{{ matText.homework }}</span></div>
          <div class="cm-mat"><label>📝 纪律</label><span>{{ matText.discipline }}</span></div>
          <div class="cm-mat"><label>🌟 闪光点</label><span>{{ matText.shine }}</span></div>
        </div>

        <div class="cm-actions">
          <el-button type="primary" :loading="generating" @click="generateOne">✨ 生成草稿</el-button>
          <el-button type="success" @click="save">💾 保存评语</el-button>
          <el-button v-if="statusOf(current.studentId) === 'saved'" type="danger" plain @click="remove">🗑 删除已保存</el-button>
          <span class="muted cm-hint">草稿仅本地保留，保存后才进入评语册；生成内容可直接修改</span>
        </div>
        <el-input v-model="content" type="textarea" :rows="12" maxlength="800" show-word-limit
                  placeholder="点「✨ 生成草稿」自动生成，或在此手写评语（建议 150~250 字）" />
      </div>

      <div class="candy-card cm-editor cm-empty" v-else>
        <el-empty description="请从左侧选择一名学生" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'

// 页面数据：materials 接口返回 {students, saved, termStart, termEnd}
const data = ref({ students: [], saved: {} })
const keyword = ref('')
const currentId = ref(null)
const content = ref('')
const generating = ref(false)
// 已生成但未保存的草稿：studentId -> {content, source}
const drafts = reactive({})
// 当前文本来源信息（source/updatedAt）
const meta = reactive({ source: '', updatedAt: '' })
const batch = reactive({ loading: false, progress: 0, done: 0, total: 0 })

onMounted(load)

async function load() {
  data.value = await http.get('/comment/materials')
  if (!currentId.value && data.value.students.length) {
    selectStudent(data.value.students[0])
  }
}

const current = computed(() =>
  data.value.students.find(s => s.studentId === currentId.value))

const filteredStudents = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) return data.value.students
  return data.value.students.filter(s => s.name.includes(kw) || String(s.stuNo).includes(kw))
})

const savedCount = computed(() => Object.keys(data.value.saved || {}).length)
const unsavedCount = computed(() =>
  data.value.students.filter(s => !data.value.saved[s.studentId]).length)

/** 状态点：saved 已保存(绿) / draft 已生成草稿(黄) / none 未生成(灰) */
function statusOf(sid) {
  if (data.value.saved[sid]) return 'saved'
  if (drafts[sid]) return 'draft'
  return 'none'
}

function selectStudent(s) {
  currentId.value = s.studentId
  const saved = data.value.saved[s.studentId]
  const draft = drafts[s.studentId]
  if (draft) {
    content.value = draft.content
    meta.source = draft.source
    meta.updatedAt = saved?.updatedAt || ''
  } else if (saved) {
    content.value = saved.content
    meta.source = saved.source
    meta.updatedAt = saved.updatedAt
  } else {
    content.value = ''
    meta.source = ''
    meta.updatedAt = ''
  }
}

/** 素材条文案（由后端聚合的学期数据拼装） */
const matText = computed(() => {
  const m = current.value
  if (!m) return {}
  const change = m.rankChange
  const changeTxt = change > 0 ? `，较上次↑${change} 名` : change < 0 ? `，较上次↓${-change} 名` : ''
  return {
    study: m.rank ? `班级第 ${m.rank} 名${changeTxt}（${m.rankSegment || '—'}），最近总分 ${m.totalScore}` : '本学期暂无完整考试排名记录',
    attend: `迟到 ${m.late} 次、缺勤 ${m.absent} 次、请假 ${m.leaveDays} 天`,
    homework: m.homeworkMissing > 0 ? `未交 ${m.homeworkMissing} 次（最近：${m.lastMissingSubject || '—'}）` : '作业全部按时上交',
    discipline: m.discipline > 0 ? `课堂违纪记录 ${m.discipline} 条` : '无违纪记录',
    shine: [m.roles ? `班委：${m.roles}` : '', m.dutyCount ? `值日 ${m.dutyCount} 次` : '',
            m.commCount ? `家校沟通 ${m.commCount} 次` : ''].filter(Boolean).join('；') || '暂无特别记录'
  }
})

/** 单人生成草稿（AI 优先，失败自动降级规则模板） */
async function generateOne() {
  const sid = currentId.value
  if (!sid) return
  generating.value = true
  try {
    const r = await http.post('/comment/generate', { studentId: sid })
    drafts[sid] = { content: r.content, source: r.source }
    content.value = r.content
    meta.source = r.source
    ElMessage[r.source === 'ai' ? 'success' : 'info'](
      r.source === 'ai' ? '✨ AI 评语已生成' : '已用规则模板生成（AI 未启用/不可用）')
  } finally {
    generating.value = false
  }
}

/** 保存评语（后端按 学期+学生 唯一键 upsert） */
async function save() {
  const sid = currentId.value
  if (!sid || !content.value.trim()) {
    ElMessage.warning('评语内容为空')
    return
  }
  await http.put('/comment/save', { studentId: sid, content: content.value.trim(), source: meta.source || 'manual' })
  delete drafts[sid]
  // 本地刷新已保存映射，避免整页重拉丢草稿
  data.value.saved[sid] = { content: content.value.trim(), source: meta.source || 'manual', updatedAt: new Date().toISOString() }
  meta.updatedAt = data.value.saved[sid].updatedAt
  ElMessage.success('评语已保存')
}

/** 删除已保存评语（回到未生成状态） */
async function remove() {
  const sid = currentId.value
  await ElMessageBox.confirm('确定删除该生本学期已保存的评语？', '确认', { type: 'warning' })
  await http.delete(`/comment/${sid}`)
  delete data.value.saved[sid]
  delete drafts[sid]
  content.value = ''
  meta.source = ''
  meta.updatedAt = ''
  ElMessage.success('已删除')
}

/**
 * 批量生成：对所有"未保存"的学生 3 路并发生成草稿（约 2 分钟）。
 * 失败的学生保留空草稿位并在结束时提示数量。
 */
async function batchGenerate() {
  const queue = data.value.students.filter(s => !data.value.saved[s.studentId])
  if (!queue.length) { ElMessage.success('全部学生都已保存评语'); return }
  batch.loading = true
  batch.done = 0
  batch.total = queue.length
  batch.progress = 0
  let fail = 0
  const worker = async () => {
    while (queue.length) {
      const s = queue.shift()
      try {
        const r = await http.post('/comment/generate', { studentId: s.studentId })
        drafts[s.studentId] = { content: r.content, source: r.source }
      } catch (e) {
        fail++
      }
      batch.done++
      batch.progress = Math.round(batch.done * 100 / batch.total)
    }
  }
  await Promise.all([worker(), worker(), worker()])
  batch.loading = false
  ElMessage[fail ? 'warning' : 'success'](
    fail ? `批量生成完成，${fail} 人失败，可单独重试` : `✨ 已为 ${batch.total} 名同学生成草稿，请逐人查看微调后保存`)
}

/** 打开评语册打印页（数据经 sessionStorage 传新窗口） */
async function openBook() {
  const d = await http.get('/comment/list')
  if (!d.list.length) {
    ElMessage.warning('还没有已保存的评语，请先生成并保存')
    return
  }
  sessionStorage.setItem('commentBook', JSON.stringify(d))
  window.open('/print/comments', '_blank')
}

function fmtTime(t) {
  return String(t || '').replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.comment-page { padding: 4px; }
.candy-card {
  background: #fff; border-radius: 16px; padding: 16px 18px;
  box-shadow: 0 4px 16px rgba(180, 150, 200, .12);
}
.cm-toolbar {
  display: flex; align-items: center; gap: 16px; flex-wrap: wrap;
  background: linear-gradient(135deg, #fde7f3, #fdf3fa);
  margin-bottom: 14px;
}
.cm-tools { margin-left: auto; display: flex; gap: 10px; }
.cm-saved-n { color: #e06aa8; font-size: 16px; }
.cm-progress { width: 100%; }
.rp-tip { display: block; margin-top: 6px; color: #b06a8e; font-size: 12px; }

.cm-body { display: flex; gap: 14px; align-items: stretch; }
.cm-list { width: 260px; flex-shrink: 0; display: flex; flex-direction: column; }
.cm-search { margin-bottom: 8px; }
.cm-legend { display: flex; gap: 10px; font-size: 12px; color: #8a7f92; margin-bottom: 6px; }
.cm-legend .dot, .cm-item .dot { margin-right: 4px; }
.dot { display: inline-block; width: 9px; height: 9px; border-radius: 50%; vertical-align: middle; }
.dot-saved { background: #4caf7d; }
.dot-draft { background: #f0b54a; }
.dot-none { background: #cfc8d6; }
.cm-items { flex: 1; overflow-y: auto; max-height: 62vh; }
.cm-item {
  display: flex; align-items: center; gap: 6px; padding: 8px 10px;
  border-radius: 10px; cursor: pointer; font-size: 14px;
}
.cm-item:hover { background: #faf5fb; }
.cm-item.active { background: #f6e4f2; font-weight: 700; }
.cm-name { color: #4a3f55; }
.cm-no { font-size: 12px; margin-left: auto; }

.cm-editor { flex: 1; }
.cm-empty { display: flex; align-items: center; justify-content: center; min-height: 300px; }
.cm-editor-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.cm-cur-name { font-size: 17px; margin: 0 8px 0 6px; color: #4a3f55; }
.cm-materials {
  background: #faf7fc; border-radius: 12px; padding: 10px 14px; margin-bottom: 12px;
  display: grid; grid-template-columns: 1fr 1fr; gap: 6px 18px;
}
.cm-mat { font-size: 13px; color: #5a5060; line-height: 1.6; }
.cm-mat label { font-weight: 700; color: #6b5b73; margin-right: 6px; }
.cm-actions { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.cm-hint { font-size: 12px; }
.muted { color: #a79fb0; }
</style>
