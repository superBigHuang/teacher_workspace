<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">✅ 待办事项</h2>
      <el-select v-model="filterCategory" style="width:130px" clearable placeholder="全部分类" @change="load">
        <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
      </el-select>
      <el-select v-model="filterDone" style="width:130px" clearable placeholder="全部状态" @change="load">
        <el-option label="未完成" :value="0" />
        <el-option label="已完成" :value="1" />
      </el-select>
      <el-tag class="push-chip" :type="pushOn ? 'success' : 'warning'" effect="light" @click="openPush">
        {{ pushOn ? '🔔 推送已开启' : '🔔 开启到期提醒' }}
      </el-tag>
      <div class="spacer"></div>
    </div>

    <el-card class="add-card">
      <el-input v-model="form.title" placeholder="新增待办，如：周三班会准备诚信主题PPT" @keyup.enter="add" class="add-input">
        <template #append>
          <el-select v-model="form.category" style="width:96px">
            <el-option v-for="c in categories" :key="c" :label="c" :value="c" />
          </el-select>
        </template>
      </el-input>
      <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日期" style="width:160px" />
      <el-button type="primary" :icon="Plus" @click="add">添加</el-button>
    </el-card>
    <p v-if="pushOn && form.dueDate" class="push-hint">📲 将在 {{ form.dueDate }} 早上 8 点推送提醒你</p>

    <!-- 推送轻量配置弹窗：不跳页，填一个渠道即可开启；完整事件开关在个人设置 -->
    <el-dialog v-model="pushDlg" title="🔔 开启到期提醒推送" width="460px">
      <p class="dlg-tip">每天早 8:00 把「今日到期 / 已逾期」待办推送到手机，配好后就不用惦记了。</p>
      <el-radio-group v-model="pushForm.channel" class="push-channels">
        <el-radio value="wecom">💬 企业微信</el-radio>
        <el-radio value="dingtalk">📌 钉钉</el-radio>
        <el-radio value="bark">📱 Bark(iPhone)</el-radio>
      </el-radio-group>
      <el-input v-if="pushForm.channel === 'wecom'" v-model="pushForm.wecomUrl"
        :placeholder="pushCfg.wecom?.configured ? `已配置 ${pushCfg.wecom.hint}，留空不修改；填新地址覆盖` : '企业微信群里「添加群机器人」→ 复制 Webhook 地址粘到这里'" />
      <template v-else-if="pushForm.channel === 'dingtalk'">
        <el-input v-model="pushForm.dingtalkUrl"
          :placeholder="pushCfg.dingtalk?.configured ? `已配置 ${pushCfg.dingtalk.hint}，留空不修改；填新地址覆盖` : '钉钉群：智能群助手 → 添加机器人 → 复制 Webhook 地址'" />
        <el-input v-model="pushForm.dingtalkSecret" class="dlg-secret"
          :placeholder="pushCfg.dingtalk?.hasSecret ? '已设置加签 Secret，留空不修改' : '加签 Secret（选填；安全设置选「加签」才填）'" />
      </template>
      <template v-else>
        <el-input v-model="pushForm.barkKey"
          :placeholder="pushCfg.bark?.configured ? `已配置 ${pushCfg.bark.hint}，留空不修改；填新 Key 覆盖` : 'App Store 装 Bark，打开 App 复制自己的设备 Key'" />
        <el-input v-model="pushForm.barkServer" class="dlg-secret" placeholder="自建服务器地址（选填，留空用官方 api.day.app）" />
      </template>
      <div class="dlg-actions">
        <el-button size="small" :loading="pushTesting" @click="testPush">发送测试</el-button>
        <a href="https://www.kdocs.cn/l/cer5TG9NdxvQ" target="_blank" class="dlg-guide">📖 不会配置？看图文教程</a>
      </div>
      <p class="dlg-note">💡 完整事件开关（成就早报等）在「个人设置 → 消息推送」。</p>
      <template #footer>
        <el-button @click="pushDlg = false">取消</el-button>
        <el-button type="primary" :loading="pushSaving" @click="savePush">保存并开启</el-button>
      </template>
    </el-dialog>

    <div class="todo-list">
      <div v-for="t in todos" :key="t.id" class="todo-item" :class="{ done: t.done, overdue: isOverdue(t) }">
        <el-checkbox :model-value="!!t.done" size="large" @change="v => toggle(t, v)" />
        <el-tag :color="catColor(t.category)" effect="dark" class="cat-tag">{{ t.category }}</el-tag>
        <span class="todo-title">{{ t.title }}</span>
        <el-tag v-if="t.source === 'QUICK'" size="small" type="warning" effect="plain">速记</el-tag>
        <div class="spacer"></div>
        <span class="due" :class="{ red: isOverdue(t) }">{{ t.dueDate ? '📅 ' + t.dueDate : '' }}{{ isOverdue(t) ? '（已逾期）' : '' }}</span>
        <el-button text type="danger" :icon="Delete" @click="del(t)" />
      </div>
      <el-empty v-if="!todos.length" description="暂无待办" />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import http from '../api/http'

const categories = ['教学', '班级', '家校', '其他']
const colorMap = { '教学': '#7fb3f3', '班级': '#f78fb3', '家校': '#f0a868', '其他': '#c3a6e8' }
const catColor = c => colorMap[c] || colorMap['其他']

const todos = ref([])
const filterCategory = ref('')
const filterDone = ref(null)
const form = reactive({ title: '', category: '教学', dueDate: '' })

async function load() {
  todos.value = await http.get('/todo', { params: { category: filterCategory.value || undefined, done: filterDone.value ?? undefined } })
}
load()

// ---------- 消息推送（轻量配置弹窗；密钥不明文回显，空串=不修改） ----------
const pushCfg = ref({ enabled: false, events: {}, wecom: {}, dingtalk: {}, bark: {} })
const pushDlg = ref(false)
const pushSaving = ref(false)
const pushTesting = ref(false)
const pushForm = reactive({ channel: 'wecom', wecomUrl: '', dingtalkUrl: '', dingtalkSecret: '', barkKey: '', barkServer: '' })
/** 已开启 = 总开关开且至少一个渠道已配置 */
const pushOn = computed(() => pushCfg.value.enabled &&
  (pushCfg.value.wecom?.configured || pushCfg.value.dingtalk?.configured || pushCfg.value.bark?.configured))

async function loadPushCfg() {
  try { pushCfg.value = await http.get('/push/config') } catch { /* 接口异常按未开启 */ }
}
loadPushCfg()

/** 打开弹窗：默认选中第一个已配置渠道 */
function openPush() {
  const c = pushCfg.value
  pushForm.channel = c.wecom?.configured ? 'wecom' : c.dingtalk?.configured ? 'dingtalk' : c.bark?.configured ? 'bark' : 'wecom'
  pushForm.wecomUrl = pushForm.dingtalkUrl = pushForm.dingtalkSecret = pushForm.barkKey = ''
  pushForm.barkServer = c.bark?.server || ''
  pushDlg.value = true
}

/** 保存并开启：只提交当前选中渠道的字段（其他渠道已配置的不受影响） */
async function savePush() {
  const ch = pushForm.channel
  const cfg = pushCfg.value
  const configured = ch === 'wecom' ? cfg.wecom?.configured : ch === 'dingtalk' ? cfg.dingtalk?.configured : cfg.bark?.configured
  const val = ch === 'wecom' ? pushForm.wecomUrl.trim() : ch === 'dingtalk' ? pushForm.dingtalkUrl.trim() : pushForm.barkKey.trim()
  if (!configured && !val) {
    ElMessage.warning(ch === 'bark' ? '请先粘贴 Bark 设备 Key' : '请先粘贴机器人 Webhook 地址')
    return
  }
  const payload = { enabled: true }
  if (ch === 'wecom') payload.wecomUrl = val
  else if (ch === 'dingtalk') {
    if (val) payload.dingtalkUrl = val
    if (pushForm.dingtalkSecret.trim()) payload.dingtalkSecret = pushForm.dingtalkSecret.trim()
  } else {
    if (val) payload.barkKey = val
    payload.barkServer = pushForm.barkServer.trim()
  }
  pushSaving.value = true
  try {
    await http.put('/push/config', payload)
    ElMessage.success('推送已开启，到期当天早 8 点提醒你 📲')
    pushDlg.value = false
    await loadPushCfg()
  } finally {
    pushSaving.value = false
  }
}

/** 发送测试：先静默保存弹窗里刚填的内容，再 POST /test */
async function testPush() {
  const ch = pushForm.channel
  const payload = { enabled: true }
  if (ch === 'wecom' && pushForm.wecomUrl.trim()) payload.wecomUrl = pushForm.wecomUrl.trim()
  else if (ch === 'dingtalk') {
    if (pushForm.dingtalkUrl.trim()) payload.dingtalkUrl = pushForm.dingtalkUrl.trim()
    if (pushForm.dingtalkSecret.trim()) payload.dingtalkSecret = pushForm.dingtalkSecret.trim()
  } else {
    if (pushForm.barkKey.trim()) payload.barkKey = pushForm.barkKey.trim()
    payload.barkServer = pushForm.barkServer.trim()
  }
  pushTesting.value = true
  try {
    await http.put('/push/config', payload)
    const r = await http.post('/push/test', { channel: ch })
    if (r.ok) ElMessage.success('测试消息已发送，查看手机 📲')
    else ElMessage.error(r.message || '发送失败，请检查地址/密钥')
    await loadPushCfg()
  } finally {
    pushTesting.value = false
  }
}

async function add() {
  if (!form.title.trim()) { ElMessage.warning('请输入待办内容'); return }
  await http.post('/todo', { title: form.title, category: form.category, dueDate: form.dueDate || null, source: 'MANUAL' })
  form.title = ''; form.dueDate = ''
  ElMessage.success('已添加')
  load()
}
async function toggle(t, done) {
  await http.post(`/todo/${t.id}/toggle`, { done })
  load()
}
async function del(t) {
  await http.delete('/todo/' + t.id)
  load()
}
function isOverdue(t) {
  return !t.done && t.dueDate && t.dueDate < new Date().toISOString().slice(0, 10)
}
</script>

<style scoped>
/* 新增区：flex 换行布局，控件间距统一走 gap（el-card 内容在 .el-card__body 里） */
.add-card { margin-bottom: 10px; }
.add-card :deep(.el-card__body) { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
.add-input { flex: 1; min-width: 220px; max-width: 520px; }
.push-chip { cursor: pointer; margin-left: 12px; }
.push-hint { color: #b7a6b8; font-size: 12px; margin: 0 0 14px 4px; }
.todo-list { background: #fff; border-radius: 14px; padding: 8px 18px; }
.todo-item { display: flex; align-items: center; gap: 12px; padding: 14px 4px; border-bottom: 1px solid #f2f3f7; }
.cat-tag { border: none; color: #fff; }
.todo-title { font-size: 15px; }
.due { color: #aab0c0; font-size: 13px; }
.due.red { color: #f56c6c; font-weight: 600; }
.todo-item.done .todo-title { text-decoration: line-through; color: #c0c4d0; }
.todo-item.overdue { background: #fff5f5; }

/* 推送配置弹窗 */
.dlg-tip { color: #6b5b73; font-size: 13px; margin: 0 0 12px; line-height: 1.6; }
.push-channels { margin-bottom: 4px; }
.dlg-secret { margin-top: 10px; }
.dlg-actions { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
.dlg-guide { font-size: 13px; color: #e08a5e; font-weight: 700; text-decoration: none; }
.dlg-guide:hover { text-decoration: underline; }
.dlg-note { color: #b7a6b8; font-size: 12px; margin: 10px 0 0; }
</style>
