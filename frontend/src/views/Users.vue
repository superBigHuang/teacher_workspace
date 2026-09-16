<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">👥 用户管理</h2>
      <div class="spacer"></div>
      <el-button type="primary" :icon="Plus" @click="openCreate">新增账号</el-button>
    </div>

    <el-table :data="users" size="small" stripe v-loading="loading" class="user-table"
              :header-cell-style="{ background: '#fdf3f8' }">
      <el-table-column prop="username" label="账号" min-width="140" />
      <el-table-column prop="displayName" label="显示名" min-width="120" />
      <el-table-column label="角色" width="110">
        <template #default="{ row }">
          <el-tag :type="row.role === 'ADMIN' ? 'danger' : 'primary'" size="small" effect="light">
            {{ row.role === 'ADMIN' ? '管理员' : '普通老师' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="注册渠道" width="110">
        <template #default="{ row }">
          <el-tag v-if="sourceMap[row.source]" :type="sourceMap[row.source].type" size="small" effect="plain">
            {{ sourceMap[row.source].label }}
          </el-tag>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="创建时间" width="140">
        <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="最近登录" width="140">
        <template #default="{ row }">{{ fmtTime(row.lastLoginAt) || '从未登录' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button v-if="!isSelf(row)" link type="warning" size="small" @click="resetPwd(row)">重置密码</el-button>
          <!-- 角色切换：自己与初始 admin 不显示 -->
          <el-button v-if="canChangeRole(row)" link :type="row.role === 'ADMIN' ? 'info' : 'danger'" size="small"
                     @click="toggleRole(row)">
            {{ row.role === 'ADMIN' ? '降为老师' : '设为管理员' }}
          </el-button>
          <!-- 停用/启用：自己与初始 admin 不显示 -->
          <el-button v-if="canToggleStatus(row)" link :type="row.enabled ? 'danger' : 'success'" size="small"
                     @click="toggleStatus(row)">
            {{ row.enabled ? '停用' : '启用' }}
          </el-button>
          <span v-if="isSelf(row)" class="muted">当前账号</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新增账号：不提供密码框，初始密码统一 123456 -->
    <el-dialog v-model="dlg" title="新增账号" width="460px" align-center>
      <el-form :model="form" label-width="92px">
        <el-form-item label="账号" required>
          <el-input v-model="form.username" placeholder="3-64 位字母/数字/下划线，登录用" />
        </el-form-item>
        <el-form-item label="显示名" required>
          <el-input v-model="form.displayName" placeholder="教师姓名，如：王老师" />
        </el-form-item>
        <el-form-item label="角色">
          <el-select v-model="form.role" style="width:100%">
            <el-option label="普通老师（全部业务功能）" value="TEACHER" />
            <el-option label="管理员（含用户管理）" value="ADMIN" />
          </el-select>
        </el-form-item>
        <el-form-item label="注册渠道" required>
          <el-select v-model="form.source" style="width:100%" placeholder="必选">
            <el-option label="朋友介绍" value="friend" />
            <el-option label="小红书" value="xhs" />
            <el-option label="同校老师" value="school" />
          </el-select>
        </el-form-item>
        <el-alert type="info" :closable="false" show-icon>
          新账号初始密码为 <b>123456</b>，登录后可在「个人设置」自行修改。
        </el-alert>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import http from '../api/http'

/** 渠道中文映射与标签配色 */
const sourceMap = {
  friend: { label: '朋友介绍', type: 'warning' },
  xhs: { label: '小红书', type: 'danger' },
  school: { label: '同校老师', type: 'primary' },
}

const users = ref([])
const loading = ref(false)
const dlg = ref(false)
const saving = ref(false)
const form = reactive({ username: '', displayName: '', role: 'TEACHER', source: '' })

onMounted(loadUsers)

/** 拉账号列表 */
async function loadUsers() {
  loading.value = true
  try {
    users.value = await http.get('/admin/users')
  } finally {
    loading.value = false
  }
}

/** 当前登录用户名（localStorage 登录时写入） */
function isSelf(row) {
  return row.username === localStorage.getItem('username')
}
/** 初始 admin 行：不可停用/降级 */
function isBuiltinAdmin(row) {
  return row.username === 'admin'
}
function canChangeRole(row) {
  return !isSelf(row) && !isBuiltinAdmin(row)
}
function canToggleStatus(row) {
  return !isSelf(row) && !isBuiltinAdmin(row)
}

/** 时间格式化：与全站口径一致（后端返回 "2026-09-16T15:26:.."），显示 MM/DD HH:mm */
function fmtTime(t) {
  return t ? String(t).slice(5, 16).replace('-', '/').replace('T', ' ') : ''
}

function openCreate() {
  form.username = ''
  form.displayName = ''
  form.role = 'TEACHER'
  form.source = ''
  dlg.value = true
}

/** 提交新增：前端先做规则校验，后端再兜底 */
async function submitCreate() {
  if (!/^[A-Za-z0-9_]{3,64}$/.test(form.username.trim())) {
    return ElMessage.warning('账号为 3-64 位字母、数字或下划线')
  }
  if (!form.displayName.trim()) return ElMessage.warning('请填写显示名')
  if (!form.source) return ElMessage.warning('请选择注册渠道')
  saving.value = true
  try {
    await http.post('/admin/users', {
      username: form.username.trim(),
      displayName: form.displayName.trim(),
      role: form.role,
      source: form.source,
    })
    ElMessage.success(`账号已创建，初始密码 123456`)
    dlg.value = false
    loadUsers()
  } finally {
    saving.value = false
  }
}

/** 重置密码（二次确认，固定 123456） */
async function resetPwd(row) {
  try {
    await ElMessageBox.confirm(`将「${row.displayName || row.username}」的密码重置为 123456？`, '重置密码', {
      type: 'warning', confirmButtonText: '重置', cancelButtonText: '取消',
    })
    await http.post(`/admin/users/${row.id}/reset-password`)
    ElMessage.success('密码已重置为 123456')
  } catch (e) { /* 取消 */ }
}

/** 角色升降切换（二次确认） */
async function toggleRole(row) {
  const next = row.role === 'ADMIN' ? 'TEACHER' : 'ADMIN'
  const action = next === 'ADMIN' ? '设为管理员' : '降为普通老师'
  try {
    await ElMessageBox.confirm(`确定将「${row.displayName || row.username}」${action}？`, '切换角色', {
      type: 'warning', confirmButtonText: '确定', cancelButtonText: '取消',
    })
    await http.put(`/admin/users/${row.id}/role`, { role: next })
    ElMessage.success('角色已更新')
    loadUsers()
  } catch (e) { /* 取消 */ }
}

/** 停用/启用切换（二次确认） */
async function toggleStatus(row) {
  const next = !row.enabled
  try {
    await ElMessageBox.confirm(
      next ? `确定停用「${row.displayName || row.username}」？停用后该账号立即无法登录和操作。`
           : `确定启用「${row.displayName || row.username}」？`,
      next ? '停用账号' : '启用账号',
      { type: next ? 'warning' : 'info', confirmButtonText: '确定', cancelButtonText: '取消' })
    await http.put(`/admin/users/${row.id}/status`, { enabled: next })
    ElMessage.success(next ? '账号已停用' : '账号已启用')
    loadUsers()
  } catch (e) { /* 取消 */ }
}
</script>

<style scoped>
.user-table { border-radius: 14px; overflow: hidden; }
.muted { color: #b0a8b8; font-size: 12px; }
</style>
