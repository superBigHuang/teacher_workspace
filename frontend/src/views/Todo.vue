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
      <el-date-picker v-model="form.dueDate" type="date" value-format="YYYY-MM-DD" placeholder="截止日期" style="width:160px; margin-left:10px" />
      <el-button type="primary" :icon="Plus" style="margin-left:10px" @click="add">添加</el-button>
    </el-card>

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
import { ref, reactive } from 'vue'
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
.add-card { margin-bottom: 16px; display: flex; align-items: center; }
.add-input { max-width: 520px; }
.todo-list { background: #fff; border-radius: 14px; padding: 8px 18px; }
.todo-item { display: flex; align-items: center; gap: 12px; padding: 14px 4px; border-bottom: 1px solid #f2f3f7; }
.cat-tag { border: none; color: #fff; }
.todo-title { font-size: 15px; }
.due { color: #aab0c0; font-size: 13px; }
.due.red { color: #f56c6c; font-weight: 600; }
.todo-item.done .todo-title { text-decoration: line-through; color: #c0c4d0; }
.todo-item.overdue { background: #fff5f5; }
</style>
