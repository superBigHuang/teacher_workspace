<template>
  <!-- 题库管理大弹窗：/pet 工具栏「📚 题库」入口；左栏分类卡片 + 右栏题目表格（数据自拉，不依赖 usePets） -->
  <el-dialog :model-value="modelValue" title="📚 题库管理" width="960px" align-center destroy-on-close
             @update:model-value="v => emit('update:modelValue', v)" @open="onOpen">
    <div class="quiz-wrap">
      <!-- 左栏：分类卡片（含"全部分类"虚拟项） -->
      <div class="cat-col">
        <div class="cat-head">
          <span class="cat-title">分类</span>
          <el-button size="small" round type="primary" @click="newCat">＋ 新建</el-button>
        </div>
        <div class="cat-card" :class="{ active: !activeCat }" @click="pickCat(null)">
          <span class="cat-icon">📖</span>
          <div class="cat-body">
            <div class="cat-name">全部分类</div>
            <div class="cat-count">{{ total }} 题</div>
          </div>
        </div>
        <div v-for="c in cats" :key="c.id" class="cat-card" :class="{ active: activeCat && activeCat.id === c.id, off: c.enabled !== 1 }"
             @click="pickCat(c)">
          <span class="cat-icon">{{ c.icon || '📚' }}</span>
          <div class="cat-body">
            <div class="cat-name">{{ c.name }}</div>
            <div class="cat-count">{{ c.questionCount }} 题</div>
          </div>
          <el-dropdown trigger="click" @command="cmd => onCatCmd(cmd, c)" @click.stop>
            <span class="cat-more">⋯</span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="edit">✏️ 改名 / 换图标</el-dropdown-item>
                <el-dropdown-item command="toggle">{{ c.enabled === 1 ? '⏸ 停用（抽题跳过）' : '▶️ 启用' }}</el-dropdown-item>
                <el-dropdown-item command="del" divided>🗑 删除分类</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
        <div v-if="!cats.length" class="cat-empty">还没有分类<br>点右上「＋ 新建」或直接 Excel 导入</div>
      </div>

      <!-- 右栏：题目筛选 + 表格 + 导入 -->
      <div class="quiz-col">
        <div class="quiz-toolbar">
          <el-select v-model="diffFilter" clearable placeholder="难度" style="width: 92px" @change="loadList(1)">
            <el-option label="易" :value="1" /><el-option label="中" :value="2" /><el-option label="难" :value="3" />
          </el-select>
          <el-input v-model="kw" clearable placeholder="搜题干 / 答案" style="width: 170px" @keyup.enter="loadList(1)" @clear="loadList(1)" />
          <el-button round @click="loadList(1)">🔍 查询</el-button>
          <div class="quiz-spacer"></div>
          <el-upload :show-file-list="false" :http-request="doImport" accept=".xls,.xlsx">
            <el-button round>📥 Excel 导入</el-button>
          </el-upload>
          <el-button type="primary" round @click="newQuiz">＋ 新增题目</el-button>
        </div>

        <el-table :data="rows" size="small" height="380" empty-text="暂无题目">
          <el-table-column label="题干" min-width="220" show-overflow-tooltip>
            <template #default="{ row }">
              <span class="q-cat">{{ row.categoryIcon || '📚' }}{{ row.categoryName }}</span>
              <span class="q-text">{{ row.question }}</span>
            </template>
          </el-table-column>
          <el-table-column label="难度" width="60" align="center">
            <template #default="{ row }">
              <span class="diff-badge" :class="'d' + row.difficulty">{{ ['', '易', '中', '难'][row.difficulty] || '易' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="answer" label="答案" min-width="110" show-overflow-tooltip />
          <el-table-column prop="useCount" label="已用" width="60" align="center" />
          <el-table-column prop="lastUsedAt" label="最近使用" width="150">
            <template #default="{ row }">{{ row.lastUsedAt ? String(row.lastUsedAt).replace('T', ' ').slice(5, 16) : '—' }}</template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="center">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="editQuiz(row)">编辑</el-button>
              <el-button size="small" text type="danger" @click="delQuiz(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="quiz-foot">
          <span class="foot-hint">导入列格式：分类 | 题目 | 答案 | 难度（难度 1易/2中/3难，空默认易）；分类不存在自动创建</span>
          <el-pagination v-model:current-page="page" :page-size="size" :total="total" layout="total, prev, pager, next"
                         small @current-change="loadList()" />
        </div>
      </div>
    </div>

    <!-- 分类编辑小弹窗 -->
    <el-dialog v-model="catDlg" :title="catForm.id ? '✏️ 编辑分类' : '＋ 新建分类'" width="380px" append-to-body align-center>
      <el-form label-width="70px">
        <el-form-item label="图标">
          <el-input v-model="catForm.icon" maxlength="2" style="width: 90px" placeholder="📚" />
        </el-form-item>
        <el-form-item label="名称">
          <el-input v-model="catForm.name" maxlength="20" placeholder="如：语文背诵" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button round @click="catDlg = false">取消</el-button>
        <el-button type="primary" round @click="saveCat">保存</el-button>
      </template>
    </el-dialog>

    <!-- 题目编辑小弹窗 -->
    <el-dialog v-model="quizDlg" :title="quizForm.id ? '✏️ 编辑题目' : '＋ 新增题目'" width="520px" append-to-body align-center>
      <el-form label-width="70px">
        <el-form-item label="分类">
          <el-select v-model="quizForm.categoryId" placeholder="选择分类" style="width: 220px">
            <el-option v-for="c in cats" :key="c.id" :label="(c.icon || '📚') + ' ' + c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="题干">
          <el-input v-model="quizForm.question" type="textarea" :rows="2" maxlength="500" placeholder="题目内容" />
        </el-form-item>
        <el-form-item label="答案">
          <el-input v-model="quizForm.answer" maxlength="500" placeholder="答案（老师端可见）" />
        </el-form-item>
        <el-form-item label="难度">
          <el-radio-group v-model="quizForm.difficulty">
            <el-radio-button :value="1">易</el-radio-button>
            <el-radio-button :value="2">中</el-radio-button>
            <el-radio-button :value="3">难</el-radio-button>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button round @click="quizDlg = false">取消</el-button>
        <el-button type="primary" round @click="saveQuiz">保存</el-button>
      </template>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../../api/http'

/** v-model 控制显隐；打开时拉分类 + 题目列表 */
const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue'])

// ==================== 分类 ====================
const cats = ref([])        // 左栏分类卡片（含 questionCount）
const activeCat = ref(null) // 当前选中分类；null=全部分类
const catDlg = ref(false)
const catForm = reactive({ id: null, name: '', icon: '' })

/** 拉分类列表 */
async function loadCats() {
  cats.value = await http.get('/quiz/categories')
}

/** 新建分类（弹空表单） */
function newCat() {
  Object.assign(catForm, { id: null, name: '', icon: '' })
  catDlg.value = true
}

/** 分类卡片 ⋯ 菜单：改名 / 停启用 / 删除（空分类才可删） */
function onCatCmd(cmd, c) {
  if (cmd === 'edit') {
    Object.assign(catForm, { id: c.id, name: c.name, icon: c.icon || '' })
    catDlg.value = true
  } else if (cmd === 'toggle') {
    http.post('/quiz/category', { id: c.id, name: c.name, icon: c.icon || '📚', enabled: c.enabled === 1 ? 0 : 1 })
      .then(() => { ElMessage.success(c.enabled === 1 ? '已停用（抽题跳过）' : '已启用'); loadCats() })
  } else if (cmd === 'del') {
    ElMessageBox.confirm(`删除分类「${c.name}」？仅空分类可删除`, '删除分类', { type: 'warning' })
      .then(() => http.post(`/quiz/category/delete/${c.id}`))
      .then(() => {
        ElMessage.success('已删除')
        if (activeCat.value && activeCat.value.id === c.id) activeCat.value = null
        loadCats(); loadList(1)
      })
      .catch(() => {})
  }
}

/** 保存分类（新增/编辑） */
async function saveCat() {
  if (!catForm.name.trim()) return ElMessage.warning('分类名称不能为空')
  await http.post('/quiz/category', { id: catForm.id, name: catForm.name, icon: catForm.icon || '📚' })
  ElMessage.success('已保存')
  catDlg.value = false
  loadCats()
}

/** 选中分类：刷新题目列表（清掉难度/关键字筛选，避免叠加出空集） */
function pickCat(c) {
  activeCat.value = c
  diffFilter.value = null
  kw.value = ''
  loadList(1)
}

// ==================== 题目 ====================
const rows = ref([])
const total = ref(0)
const page = ref(1)
const size = 50
const diffFilter = ref(null)
const kw = ref('')
const quizDlg = ref(false)
const quizForm = reactive({ id: null, categoryId: null, question: '', answer: '', difficulty: 1 })

/** 拉题目列表（keepPage=true 翻页时保留当前页码） */
async function loadList(keepPage) {
  if (keepPage === 1) page.value = 1
  const data = await http.get('/quiz/list', {
    params: {
      categoryId: activeCat.value?.id || null,
      difficulty: diffFilter.value || null,
      kw: kw.value || null,
      page: page.value, size,
    },
  })
  rows.value = data.rows
  total.value = data.total
}

/** 新增题目（弹空表单，分类预选当前选中分类） */
function newQuiz() {
  Object.assign(quizForm, { id: null, categoryId: activeCat.value?.id || null, question: '', answer: '', difficulty: 1 })
  quizDlg.value = true
}

/** 编辑题目（表单回填） */
function editQuiz(row) {
  Object.assign(quizForm, { id: row.id, categoryId: row.categoryId, question: row.question, answer: row.answer, difficulty: row.difficulty })
  quizDlg.value = true
}

/** 保存题目（新增/编辑） */
async function saveQuiz() {
  if (!quizForm.question.trim()) return ElMessage.warning('题干不能为空')
  if (!quizForm.categoryId) return ElMessage.warning('请选择分类')
  await http.post('/quiz/save', { ...quizForm })
  ElMessage.success('已保存')
  quizDlg.value = false
  loadCats(); loadList()
}

/** 删除题目 */
function delQuiz(row) {
  ElMessageBox.confirm(`删除题目「${row.question.slice(0, 20)}」？`, '删除题目', { type: 'warning' })
    .then(() => http.delete(`/quiz/${row.id}`))
    .then(() => { ElMessage.success('已删除'); loadCats(); loadList() })
    .catch(() => {})
}

// ==================== Excel 导入 ====================
/** 自定义上传：multipart POST /quiz/import，返回 {added, skipped, categoriesCreated} */
async function doImport(opt) {
  const fd = new FormData()
  fd.append('file', opt.file)
  const r = await http.post('/quiz/import', fd)
  ElMessage.success(`导入 ${r.added} 条${r.skipped ? `，跳过 ${r.skipped} 条` : ''}${r.categoriesCreated ? `，自动建分类 ${r.categoriesCreated} 个` : ''}`)
  loadCats(); loadList(1)
}

// ==================== 打开时初始化 ====================
function onOpen() {
  activeCat.value = null
  diffFilter.value = null
  kw.value = ''
  loadCats()
  loadList(1)
}
</script>

<style scoped>
.quiz-wrap { display: flex; gap: 14px; }
/* 左栏分类 */
.cat-col { width: 190px; flex-shrink: 0; display: flex; flex-direction: column; gap: 8px; max-height: 500px; overflow-y: auto; }
.cat-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 2px; }
.cat-title { font-weight: 900; color: #8a5a34; font-size: 14px; }
.cat-card {
  position: relative; display: flex; align-items: center; gap: 9px; padding: 9px 10px; border-radius: 12px;
  background: #fff7ee; border: 2px solid #f0e4d4; cursor: pointer; transition: border-color .15s;
}
.cat-card:hover { border-color: #ffc9a3; }
.cat-card.active { border-color: #ff9f5a; background: linear-gradient(150deg, #fff3e0, #ffe9c9); }
.cat-card.off { opacity: .55; }
.cat-icon { font-size: 22px; }
.cat-body { flex: 1; min-width: 0; }
.cat-name { font-size: 13px; font-weight: 800; color: #6a4a2c; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.cat-count { font-size: 11px; color: #b09888; font-weight: 700; }
.cat-more {
  position: absolute; top: 4px; right: 6px; font-size: 15px; color: #c0a898; cursor: pointer;
  padding: 0 4px; border-radius: 6px; line-height: 1;
}
.cat-more:hover { color: #ff7f32; background: #fff0e0; }
.cat-empty { text-align: center; color: #c0a898; font-size: 12px; padding: 22px 0; line-height: 1.8; }
/* 右栏题目 */
.quiz-col { flex: 1; min-width: 0; }
.quiz-toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
.quiz-spacer { flex: 1; }
.q-cat { font-size: 11px; color: #b09888; margin-right: 6px; white-space: nowrap; }
.q-text { font-weight: 600; color: #5a4a3c; }
.diff-badge { display: inline-block; font-size: 11px; font-weight: 800; border-radius: 8px; padding: 1px 8px; color: #fff; }
.diff-badge.d1 { background: #5cbb8c; }
.diff-badge.d2 { background: #f2a93a; }
.diff-badge.d3 { background: #ef7f7f; }
.quiz-foot { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.foot-hint { font-size: 11px; color: #c0a898; }
</style>
