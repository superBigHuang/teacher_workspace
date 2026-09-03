<template>
  <div class="settings-page">
    <div class="page-head">
      <h2>⚙️ 个人设置</h2>
      <p class="tip">这里的所有内容只属于你的账号 🍬</p>
    </div>

    <div class="cards">
      <!-- 个人资料 -->
      <section class="candy-card pink">
        <h3>👤 个人资料</h3>
        <el-form label-position="top" class="form">
          <div class="row">
            <el-form-item label="账号（不可修改）">
              <el-input v-model="profile.username" disabled />
            </el-form-item>
            <el-form-item label="显示名称（教师姓名）">
              <el-input v-model="profile.displayName" placeholder="如：王老师" />
            </el-form-item>
          </div>
          <div class="row">
            <el-form-item label="邮箱">
              <el-input v-model="profile.email" placeholder="name@example.com" />
            </el-form-item>
            <el-form-item label="学校（输入时会自动提示其他老师填过的学校）">
              <el-autocomplete v-model="profile.school" :fetch-suggestions="fetchSchools"
                placeholder="如：嘉善第四中学" clearable class="school-input">
                <template #default="{ item }">
                  <div class="school-item">🏫 {{ item.value }}<span class="used">{{ item.useCount }} 人在用</span></div>
                </template>
              </el-autocomplete>
            </el-form-item>
          </div>
          <div class="row">
            <el-form-item label="学期">
              <el-input v-model="profile.term" placeholder="如：2026秋季" />
            </el-form-item>
            <el-form-item label="及格线 / 优秀线">
              <div class="line-inputs">
                <el-input-number v-model="profile.passLine" :min="0" :max="150" />
                <el-input-number v-model="profile.excellentLine" :min="0" :max="150" />
              </div>
            </el-form-item>
          </div>
          <el-form-item label="学期起止日期（评语/通知单/请假统计按此区间汇总）">
            <div class="line-inputs">
              <el-date-picker v-model="profile.termStart" type="date" value-format="YYYY-MM-DD" placeholder="开学日期" />
              <span class="date-sep">至</span>
              <el-date-picker v-model="profile.termEnd" type="date" value-format="YYYY-MM-DD" placeholder="放假日期" />
            </div>
          </el-form-item>
          <el-form-item label="病假预警天数（学期累计病假达到该天数，请假台账标 ⚠️ 提醒上报）">
            <el-input-number v-model="profile.sickLeaveWarnDays" :min="1" :max="120" />
          </el-form-item>
          <el-form-item label="🐾 宠物自动扣分（登记迟到/缺勤/作业未交/课堂违纪时自动扣减学生宠物成长值；请假不扣，开关关闭后登记不扣分）">
            <el-switch v-model="profile.petAutoDeduct" active-text="自动扣分" inactive-text="已关闭" />
          </el-form-item>
          <el-form-item label="校训">
            <el-input v-model="profile.schoolMotto" placeholder="如：勤学笃行" />
          </el-form-item>
          <el-form-item label="个人箴言">
            <el-input v-model="profile.personalMotto" type="textarea" :rows="2"
              placeholder="写给同学们的一句话" />
          </el-form-item>
          <el-button type="primary" class="save-btn" :loading="saving" @click="saveProfile">保存资料 💾</el-button>
        </el-form>
      </section>

      <!-- 修改密码 -->
      <section class="candy-card blue">
        <h3>🔒 修改密码</h3>
        <el-form label-position="top">
          <el-form-item label="当前密码">
            <el-input v-model="pwd.oldPassword" type="password" show-password placeholder="当前密码" />
          </el-form-item>
          <el-form-item label="新密码（至少 6 位）">
            <el-input v-model="pwd.newPassword" type="password" show-password placeholder="新密码" />
          </el-form-item>
          <el-form-item label="确认新密码">
            <el-input v-model="pwd.confirm" type="password" show-password placeholder="再输入一次" @keyup.enter="changePassword" />
          </el-form-item>
          <el-button class="save-btn blue-btn" :loading="changingPwd" @click="changePassword">修改密码 🔑</el-button>
        </el-form>
      </section>

      <!-- 我的班级 -->
      <section class="candy-card yellow">
        <h3>🏫 我的班级</h3>
        <div class="tag-list">
          <div v-for="c in classes" :key="c.id" class="cls-tag" :class="c.type === 'HOMEROOM' ? 'home' : 'subject'">
            <span class="cls-name">{{ c.name }}</span>
            <span class="cls-badge">{{ c.type === 'HOMEROOM' ? '班主任班' : '任课班' }}</span>
            <span v-if="c.term" class="cls-term">{{ c.term }}</span>
            <el-popconfirm title="删除班级会同时清空该班课表与座次，确定？" @confirm="delClass(c)">
              <template #reference><span class="cls-del">✕</span></template>
            </el-popconfirm>
          </div>
        </div>
        <div class="add-row">
          <el-input v-model="newClass.name" placeholder="班级名，如：初一(3)班" />
          <el-select v-model="newClass.type" style="width: 108px">
            <el-option label="班主任班" value="HOMEROOM" />
            <el-option label="任课班" value="SUBJECT" />
          </el-select>
          <el-input v-model="newClass.term" placeholder="学期（选填）" style="width: 120px" />
          <el-button type="primary" plain @click="addClass">➕ 添加</el-button>
        </div>
      </section>

      <!-- 我的学科 -->
      <section class="candy-card green">
        <h3>📚 我的学科</h3>
        <div class="tag-list">
          <div v-for="s in subjects" :key="s.id" class="sub-tag">
            <span class="sub-dot" :style="{ background: s.color || '#9fc5f8' }"></span>
            <span class="sub-name">{{ s.name }}</span>
            <span class="sub-teacher">{{ s.teacher || '—' }}</span>
            <el-popconfirm title="确定删除该学科？" @confirm="delSubject(s)">
              <template #reference><span class="cls-del">✕</span></template>
            </el-popconfirm>
          </div>
        </div>
        <div class="add-row">
          <el-input v-model="newSubject.name" placeholder="学科名，如：语文" />
          <el-input v-model="newSubject.teacher" placeholder="任课教师（选填）" />
          <el-color-picker v-model="newSubject.color" />
          <el-button type="primary" plain @click="addSubject">➕ 添加</el-button>
        </div>
      </section>

      <!-- AI 助手 -->
      <section class="candy-card purple">
        <h3>🤖 AI 助手 <el-switch v-model="ai.enabled" class="ai-switch" /></h3>
        <el-form label-position="top" class="form">
          <el-form-item label="服务地址（OpenAI 兼容接口，留空用系统默认服务）">
            <el-input v-model="ai.baseUrl" placeholder="留空 = 使用系统默认的讯飞星火服务" />
          </el-form-item>
          <el-form-item label="API Key（密码不明文回显；留空不修改）">
            <el-input v-model="ai.apiKey" type="password" show-password
              :placeholder="ai.hasUserKey ? `已配置 ${ai.keyHint || ''}，留空表示不修改` : (ai.hasDefaultKey ? '未自配，当前使用系统默认服务 Key' : '未配置，请填入 API Key')" />
          </el-form-item>
          <div class="row">
            <el-form-item label="模型名（留空用默认）">
              <el-input v-model="ai.model" placeholder="如 spark-x2.5-4b / deepseek-chat" />
            </el-form-item>
            <el-form-item label="温度（越高越有创意）">
              <el-slider v-model="ai.temperature" :min="0" :max="1" :step="0.1" show-input />
            </el-form-item>
          </div>
          <div class="ai-actions">
            <el-button type="primary" class="save-btn purple-btn" :loading="ai.saving" @click="saveAi">保存 AI 配置 💾</el-button>
            <el-button :loading="ai.testing" @click="testAi">🔌 测试连接</el-button>
          </div>
          <el-alert v-if="ai.testResult" :type="ai.testResult.ok ? 'success' : 'error'" :closable="false"
            class="ai-test-result" show-icon
            :title="ai.testResult.ok ? `✅ 连接成功（模型：${ai.testResult.model || '默认'}）${ai.testResult.message ? ' · ' + ai.testResult.message : ''}` : `❌ ${ai.testResult.message}`" />
          <p class="privacy-tip">🔒 学生姓名与在校表现数据将发送至你配置的 AI 服务商，请使用合规服务；使用系统默认服务则数据发往管理员预置的服务。未配置或调用失败时自动降级为离线模板生成，功能不受影响。</p>
        </el-form>
      </section>

      <!-- 消息推送（跨两列）：企微/钉钉群机器人 webhook + Bark；密钥不明文回显 -->
      <section class="candy-card wide push-card">
        <h3 class="push-head">
          <span class="push-title">🔔 消息推送
            <el-switch v-model="push.enabled" class="ai-switch" active-text="开启" inactive-text="关闭" />
          </span>
          <a href="https://www.kdocs.cn/l/cer5TG9NdxvQ" target="_blank" class="push-guide">📖 不会配置？看图文教程</a>
        </h3>
        <el-form label-position="top" class="form">
          <!-- 企业微信（主推，无需加签） -->
          <div class="push-ch">
            <div class="push-ch-title">💬 企业微信群机器人
              <span v-if="push.wecomCfg" class="push-state">已配置 {{ push.wecomHint }}</span>
            </div>
            <div class="push-ch-row">
              <el-input v-model="push.wecomUrl"
                :placeholder="push.wecomCfg ? `已配置 ${push.wecomHint}，留空不修改；填新地址则覆盖` : '企业微信群里「添加群机器人」→ 复制 Webhook 地址粘到这里'" />
              <el-button :loading="pushTesting === 'wecom'" @click="testPush('wecom')">发送测试</el-button>
            </div>
          </div>
          <!-- 钉钉（加签选填；关键词安全设置时消息已固定含"班主任"） -->
          <div class="push-ch">
            <div class="push-ch-title">📌 钉钉群机器人
              <span v-if="push.dingCfg" class="push-state">已配置 {{ push.dingHint }}<template v-if="push.dingSecret"> · 已加签</template></span>
            </div>
            <div class="push-ch-row">
              <el-input v-model="push.dingtalkUrl"
                placeholder="钉钉群：群设置 → 智能群助手 → 添加机器人 → 复制 Webhook 地址" />
              <el-button :loading="pushTesting === 'dingtalk'" @click="testPush('dingtalk')">发送测试</el-button>
            </div>
            <el-input v-model="push.dingtalkSecret" class="push-secret"
              :placeholder="push.dingSecret ? '已设置加签 Secret，留空不修改' : '加签 Secret（选填；机器人安全设置选了「加签」才填，选「关键词」不用填）'" />
          </div>
          <!-- Bark（iOS 个人推送） -->
          <div class="push-ch">
            <div class="push-ch-title">📱 Bark（iPhone 个人推送，只推给你自己）
              <span v-if="push.barkCfg" class="push-state">已配置 {{ push.barkHint }}</span>
            </div>
            <div class="push-ch-row">
              <el-input v-model="push.barkKey" placeholder="App Store 安装 Bark，打开 App 复制自己的设备 Key" />
              <el-button :loading="pushTesting === 'bark'" @click="testPush('bark')">发送测试</el-button>
            </div>
            <el-input v-model="push.barkServer" class="push-secret"
              placeholder="自建服务器地址（选填，留空用官方 api.day.app）" />
          </div>

          <el-form-item label="推送哪些消息（每天早 8:00 汇总推送到手机）">
            <el-checkbox-group v-model="push.eventOn" class="push-events">
              <el-checkbox value="todo">✅ 待办到期 / 逾期提醒</el-checkbox>
              <el-checkbox value="achievement">✅ 学生成就早报</el-checkbox>
              <el-checkbox value="leave" disabled>⏳ 病假预警（即将支持）</el-checkbox>
              <el-checkbox value="score" disabled>⏳ 成绩导入（即将支持）</el-checkbox>
              <el-checkbox value="pet" disabled>⏳ 宠物动态（即将支持）</el-checkbox>
              <el-checkbox value="daily" disabled>⏳ 每日早报（即将支持）</el-checkbox>
            </el-checkbox-group>
          </el-form-item>
          <el-button type="primary" class="save-btn push-save" :loading="push.saving" @click="savePush">保存推送设置 💾</el-button>
          <p class="privacy-tip">🔒 Webhook/Key 仅保存在本系统后台、不明文回显；配了几个渠道就都会发，推送失败自动跳过不影响使用。</p>
        </el-form>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

// 个人资料（sys_user 字段 + setting 键值合并）
const profile = reactive({
  username: '', displayName: '', email: '', school: '',
  term: '', termStart: '', termEnd: '', sickLeaveWarnDays: 20,
  schoolMotto: '', personalMotto: '',
  passLine: 60, excellentLine: 85,
  petAutoDeduct: true
})
const saving = ref(false)
const classes = ref([])
const subjects = ref([])
const newClass = reactive({ name: '', type: 'SUBJECT', term: '' })
const newSubject = reactive({ name: '', teacher: '', color: '#9fc5f8' })
const pwd = reactive({ oldPassword: '', newPassword: '', confirm: '' })
const changingPwd = ref(false)

// AI 助手配置（key 不明文回显，靠 placeholder 提示已配置状态）
const ai = reactive({
  enabled: true, baseUrl: '', apiKey: '', model: '', temperature: 0.7,
  hasUserKey: false, hasDefaultKey: false, keyHint: '',
  saving: false, testing: false, testResult: null,
})

/** 学校输入联想：查全站共享的学校记忆 */
async function fetchSchools(query, cb) {
  const list = await http.get('/settings/schools', { params: { q: query } })
  cb(list.map(s => ({ value: s.name || s, useCount: s.useCount })))
}

async function load() {
  const p = await http.get('/settings/profile')
  profile.username = p.username || ''
  profile.displayName = p.displayName || ''
  profile.email = p.email || ''
  profile.school = p.school || ''
  profile.term = p.term || ''
  profile.termStart = p.termStart || p.term_start || ''
  profile.termEnd = p.termEnd || p.term_end || ''
  profile.sickLeaveWarnDays = Number(p.sickLeaveWarnDays ?? p.sick_leave_warn_days ?? 20)
  profile.schoolMotto = p.schoolMotto || p.school_motto || ''
  profile.personalMotto = p.personalMotto || p.personal_motto || ''
  profile.passLine = Number(p.passLine ?? p.pass_line ?? 60)
  profile.excellentLine = Number(p.excellentLine ?? p.excellent_line ?? 85)
  profile.petAutoDeduct = (p.petAutoDeduct ?? p.pet_auto_deduct ?? '1') !== '0'
  classes.value = await http.get('/timetable/classes')
  subjects.value = await http.get('/timetable/subjects')
  loadAi()
  loadPush()
}

/** 加载 AI 配置（apiKey 不回显，仅掩码与状态） */
async function loadAi() {
  const c = await http.get('/ai/config')
  ai.enabled = c.enabled !== false
  ai.baseUrl = c.baseUrl || ''
  ai.model = c.model || ''
  ai.temperature = Number(c.temperature ?? 0.7)
  ai.hasUserKey = !!c.hasUserKey
  ai.hasDefaultKey = !!c.hasDefaultKey
  ai.keyHint = c.keyHint || ''
  ai.testResult = null
}

async function saveAi() {
  ai.saving = true
  try {
    await http.put('/ai/config', {
      enabled: ai.enabled,
      baseUrl: ai.baseUrl,
      model: ai.model,
      temperature: ai.temperature,
      apiKey: ai.apiKey || '',   // 空串 = 不修改；清除由测试/单独操作走 __clear__
    })
    ElMessage.success('AI 配置已保存 🤖')
    ai.apiKey = ''
    await loadAi()
  } finally {
    ai.saving = false
  }
}

/** 测试连接：成功显示模型名，失败显示服务商返回的错误摘要 */
async function testAi() {
  ai.testing = true
  try {
    // 先保存再测，保证测的是界面上刚填的配置
    await http.put('/ai/config', {
      enabled: ai.enabled, baseUrl: ai.baseUrl, model: ai.model,
      temperature: ai.temperature, apiKey: ai.apiKey || '',
    })
    ai.testResult = await http.post('/ai/test', {})
  } finally {
    ai.testing = false
  }
}

// 消息推送配置（webhook/key 不明文回显，仅靠掩码占位提示已配置；空串=不修改）
const push = reactive({
  enabled: false,
  wecomUrl: '', dingtalkUrl: '', dingtalkSecret: '', barkKey: '', barkServer: '',
  eventOn: ['todo', 'achievement'],   // checkbox-group 绑定勾选的事件键
  wecomCfg: false, wecomHint: '',
  dingCfg: false, dingSecret: false, dingHint: '',
  barkCfg: false, barkHint: '',
  saving: false,
})
const pushTesting = ref('')
const PUSH_EVENT_KEYS = ['todo', 'achievement', 'leave', 'score', 'pet', 'daily']

/** 加载推送配置（密钥不回显，只取掩码与已配置标记） */
async function loadPush() {
  try {
    const c = await http.get('/push/config')
    push.enabled = !!c.enabled
    push.wecomCfg = !!c.wecom?.configured;  push.wecomHint = c.wecom?.hint || ''
    push.dingCfg = !!c.dingtalk?.configured; push.dingSecret = !!c.dingtalk?.hasSecret
    push.dingHint = c.dingtalk?.hint || ''
    push.barkCfg = !!c.bark?.configured;    push.barkHint = c.bark?.hint || ''
    push.barkServer = c.bark?.server || ''
    push.eventOn = PUSH_EVENT_KEYS.filter(k => c.events?.[k] === 1)
    // 密钥输入框回填后清空，留空=不修改
    push.wecomUrl = push.dingtalkUrl = push.dingtalkSecret = push.barkKey = ''
  } catch (e) { /* 接口异常按未配置处理，不阻塞设置页 */ }
}

/** 保存推送设置；silent=true 时为测试前静默保存 */
async function savePush(silent) {
  push.saving = true
  try {
    const events = {}
    for (const k of PUSH_EVENT_KEYS) events[k] = push.eventOn.includes(k) ? 1 : 0
    await http.put('/push/config', {
      enabled: push.enabled,
      events,
      wecomUrl: push.wecomUrl || '',
      dingtalkUrl: push.dingtalkUrl || '',
      dingtalkSecret: push.dingtalkSecret || '',
      barkKey: push.barkKey || '',
      barkServer: push.barkServer || '',
    })
    if (!silent) ElMessage.success('推送设置已保存 🔔')
    await loadPush()
  } finally {
    push.saving = false
  }
}

/** 发送测试：先静默保存界面上刚填的地址，再 POST /test，结果直接弹消息 */
async function testPush(channel) {
  pushTesting.value = channel
  try {
    await savePush(true)
    const r = await http.post('/push/test', { channel })
    if (r.ok) ElMessage.success('测试消息已发送，查看手机 📲')
    else ElMessage.error(r.message || '发送失败，请检查地址/密钥')
  } finally {
    pushTesting.value = ''
  }
}

async function saveProfile() {
  saving.value = true
  try {
    await http.put('/settings/profile', {
      displayName: profile.displayName,
      email: profile.email,
      school: profile.school,
      term: profile.term,
      termStart: profile.termStart,
      termEnd: profile.termEnd,
      sickLeaveWarnDays: profile.sickLeaveWarnDays,
      schoolMotto: profile.schoolMotto,
      personalMotto: profile.personalMotto,
      passLine: profile.passLine,
      excellentLine: profile.excellentLine,
      petAutoDeduct: profile.petAutoDeduct ? '1' : '0'
    })
    localStorage.setItem('displayName', profile.displayName)
    ElMessage.success('资料已保存 🎉')
  } finally {
    saving.value = false
  }
}

async function changePassword() {
  if (!pwd.oldPassword) return ElMessage.warning('请输入当前密码')
  if (pwd.newPassword.length < 6) return ElMessage.warning('新密码至少 6 位')
  if (pwd.newPassword !== pwd.confirm) return ElMessage.warning('两次输入的新密码不一致')
  changingPwd.value = true
  try {
    await http.post('/auth/change-password', {
      oldPassword: pwd.oldPassword,
      newPassword: pwd.newPassword
    })
    ElMessage.success('密码修改成功 🔑')
    pwd.oldPassword = pwd.newPassword = pwd.confirm = ''
  } finally {
    changingPwd.value = false
  }
}

// ---------- 班级 ----------
async function addClass() {
  if (!newClass.name.trim()) return ElMessage.warning('请输入班级名')
  await http.post('/timetable/class', { ...newClass })
  ElMessage.success('班级已添加')
  newClass.name = ''; newClass.term = ''
  classes.value = await http.get('/timetable/classes')
}
async function delClass(c) {
  await http.delete(`/timetable/class/${c.id}`)
  classes.value = await http.get('/timetable/classes')
  ElMessage.success('已删除')
}

// ---------- 学科 ----------
async function addSubject() {
  if (!newSubject.name.trim()) return ElMessage.warning('请输入学科名')
  await http.post('/timetable/subject', { ...newSubject })
  ElMessage.success('学科已添加')
  newSubject.name = ''; newSubject.teacher = ''
  subjects.value = await http.get('/timetable/subjects')
}
async function delSubject(s) {
  await http.delete(`/timetable/subject/${s.id}`)
  subjects.value = await http.get('/timetable/subjects')
}

onMounted(load)
</script>

<style scoped>
.settings-page { max-width: 1080px; margin: 0 auto; }
.page-head h2 { margin: 0 0 4px; color: #6b5b73; }
.page-head .tip { color: #b7a6b8; margin: 0 0 18px; font-size: 13px; }

/* 两列瀑布流（CSS columns）：卡片按自身高度紧凑排列，不再因同行最高卡片撑开大空隙；
   宽卡片用 column-span:all 跨两列。860px 以下单列 */
.cards { columns: 2; column-gap: 18px; }
.candy-card {
  background: #fff; border-radius: 22px; padding: 22px 24px; margin-bottom: 18px;
  box-shadow: 0 10px 28px rgba(200,180,200,.18);
  border: 3px solid #fff; position: relative; overflow: hidden;
  break-inside: avoid; -webkit-column-break-inside: avoid;
}
.candy-card::before {
  content: ''; position: absolute; top: -34px; right: -34px; width: 110px; height: 110px;
  border-radius: 50%; opacity: .35;
}
.candy-card.pink::before { background: #ffd9e8; }
.candy-card.blue::before { background: #d9e8ff; }
.candy-card.yellow::before { background: #fff0c9; }
.candy-card.green::before { background: #d6f5e3; }
.candy-card.purple::before { background: #e8d9ff; }
.candy-card h3 { margin: 0 0 16px; color: #6b5b73; font-size: 17px; }

.form .row { display: flex; gap: 14px; }
.form .row .el-form-item { flex: 1; }
.school-input { width: 100%; }
.school-item { display: flex; justify-content: space-between; }
.school-item .used { color: #c9b3c4; font-size: 12px; }
.line-inputs { display: flex; gap: 10px; align-items: center; }
.date-sep { color: #b7a6b8; font-size: 13px; }
.save-btn {
  width: 100%; height: 42px; border-radius: 14px; font-weight: 800; border: none;
  background: linear-gradient(135deg, #ff9ec4, #ffb38a);
}
.blue-btn { background: linear-gradient(135deg, #8fb8f0, #8fd3d3); }

.tag-list { display: flex; flex-wrap: wrap; gap: 10px; margin-bottom: 16px; }
.cls-tag, .sub-tag {
  display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 14px;
  background: #fff7e6; font-size: 14px; color: #7a6a55;
}
.cls-tag.home { background: #ffeef5; color: #a05a78; }
.cls-badge {
  font-size: 11px; background: #ffd9e8; color: #c2558a; border-radius: 8px; padding: 1px 8px;
}
.cls-tag.subject .cls-badge { background: #e3eefc; color: #4a7bb8; }
.cls-term { font-size: 12px; color: #b9a98f; }
.cls-del { cursor: pointer; color: #d9a0a0; font-weight: 700; padding: 0 2px; }
.cls-del:hover { color: #f06292; }

.sub-tag { background: #eefaf2; color: #4a7a5c; }
.sub-dot { width: 12px; height: 12px; border-radius: 50%; }
.sub-teacher { font-size: 12px; color: #8fb59e; }

.add-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.add-row .el-input { flex: 1; min-width: 120px; }

/* AI 助手卡 */
.candy-card h3:has(.ai-switch) { display: flex; align-items: center; gap: 8px; }
.ai-actions { display: flex; gap: 10px; margin-bottom: 12px; }
.ai-actions .save-btn { flex: 1; width: auto; }
.purple-btn { background: linear-gradient(135deg, #b89ff0, #f0a8d8); }
.ai-test-result { margin-bottom: 12px; }
.privacy-tip { color: #b7a6b8; font-size: 12px; line-height: 1.6; margin: 4px 0 0; }

/* 消息推送卡（瀑布流中跨两列） */
.candy-card.wide { column-span: all; -webkit-column-span: all; }
.push-card::before { background: #ffe0c9; }
.push-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; margin-bottom: 16px; }
.push-title { display: flex; align-items: center; gap: 8px; }
.push-guide { font-size: 13px; color: #e08a5e; font-weight: 700; text-decoration: none; }
.push-guide:hover { text-decoration: underline; }
.push-ch { background: #faf7ff; border-radius: 14px; padding: 12px 14px; margin-bottom: 12px; }
.push-ch-title { font-weight: 800; color: #6b5b73; font-size: 14px; margin-bottom: 8px; }
.push-state { color: #b7a6b8; font-weight: 600; font-size: 12px; margin-left: 6px; }
.push-ch-row { display: flex; gap: 10px; }
.push-ch-row .el-input { flex: 1; }
.push-secret { margin-top: 8px; }
.push-events { display: flex; flex-wrap: wrap; gap: 4px 16px; }
.push-save { width: auto; min-width: 220px; }

@media (max-width: 860px) { .cards { columns: 1; } }
</style>
