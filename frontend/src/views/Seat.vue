<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">🪑 座次表</h2>
      <span class="cfg-label">行数</span>
      <el-input-number v-model="rows" :min="1" :max="15" size="small" @change="saveConfig" />
      <span class="cfg-label">列数</span>
      <el-input-number v-model="cols" :min="1" :max="15" size="small" @change="saveConfig" />
      <span class="cfg-label" v-for="n in 3" :key="n">过道{{ n }}</span>
      <el-select v-model="aisleModel[0]" size="small" style="width:110px" @change="onAisle">
        <el-option label="无" :value="0" /><el-option v-for="c in cols" :key="c" :label="'第'+c+'列后'" :value="c" />
      </el-select>
      <el-select v-model="aisleModel[1]" size="small" style="width:110px" @change="onAisle">
        <el-option label="无" :value="0" /><el-option v-for="c in cols" :key="c" :label="'第'+c+'列后'" :value="c" />
      </el-select>
      <el-select v-model="aisleModel[2]" size="small" style="width:110px" @change="onAisle">
        <el-option label="无" :value="0" /><el-option v-for="c in cols" :key="c" :label="'第'+c+'列后'" :value="c" />
      </el-select>
      <div class="spacer"></div>
      <el-button type="primary" :icon="Refresh" @click="randomSeat">🔀 随机排座</el-button>
      <el-button type="danger" plain :icon="Delete" @click="clearSeat">清空座次</el-button>
    </div>

    <div class="legend">
      <span class="lg"><i class="dot boy"></i> 男生</span>
      <span class="lg"><i class="dot girl"></i> 女生</span>
      <span class="lg"><i class="aisle-bar"></i> 过道</span>
      <span class="tip">拖动学生可交换座位</span>
    </div>

    <div class="podium">🏫 讲台</div>

    <div class="seat-grid" :style="gridStyle">
      <div v-for="aisleCol in aisleDisplayColumns" :key="'a'+aisleCol"
           class="aisle-gutter" :style="{ gridColumn: aisleCol }"></div>
      <div v-for="s in seats" :key="s.row + '-' + s.col"
           class="seat" :class="seatClass(s)"
           :style="{ gridRow: s.row, gridColumn: displayCol(s.col) }"
           :draggable="!!s.studentId"
           @dragstart="onDragStart($event, s)" @dragover.prevent @drop="onDrop($event, s)">
        <template v-if="s.studentId">
          <div class="seat-pos">第{{ s.row }}排{{ s.col }}座</div>
          <div class="seat-name">{{ s.name }}</div>
          <div class="seat-no">{{ s.stuNo }}</div>
          <div class="seat-sex">{{ s.gender === '女' ? '♀ 女' : '♂ 男' }}</div>
        </template>
        <template v-else><div class="empty">— 空 —</div></template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Delete } from '@element-plus/icons-vue'
import http from '../api/http'

const rows = ref(7)
const cols = ref(8)
const aisleModel = reactive([2, 4, 6])
const seats = ref([])
let dragFrom = null

const aisles = computed(() =>
  [...new Set(aisleModel.filter(x => x > 0))].sort((a, b) => a - b))

// 过道在网格中的显示列号：逻辑列 c 之后插入，显示列 = c + 前面过道数 + 1（过道占该列）
const totalDisplayCols = computed(() => cols.value + aisles.value.length)
const gridStyle = computed(() => ({
  gridTemplateColumns: `repeat(${totalDisplayCols.value}, 1fr)`,
  gridTemplateRows: `repeat(${rows.value}, auto)`
}))
// 过道 gutter 所在显示列
const aisleDisplayColumns = computed(() =>
  aisles.value.map(a => a + aisles.value.filter(x => x <= a).length))
function displayCol(logicalCol) {
  return logicalCol + aisles.value.filter(a => a < logicalCol).length
}

async function loadConfig() {
  const c = await http.get('/seat/config')
  rows.value = c.rows; cols.value = c.cols
  const a = c.aisles || []
  for (let i = 0; i < 3; i++) aisleModel[i] = a[i] || 0
}
async function loadGrid() {
  const g = await http.get('/seat/grid')
  seats.value = g.seats
}
loadConfig().then(loadGrid)

function seatClass(s) {
  if (!s.studentId) return 'empty-seat'
  return s.gender === '女' ? 'girl' : 'boy'
}

function aislesPayload() {
  return [...new Set(aisleModel.filter(x => x > 0))].sort((a, b) => a - b)
}
async function saveConfig() {
  await http.post('/seat/config', { rows: rows.value, cols: cols.value, aisles: aislesPayload() })
  await loadGrid()
}
function onAisle() { saveConfig() }

async function randomSeat() {
  await saveConfig()
  await http.post('/seat/auto')
  ElMessage.success('排座完成')
  await loadGrid()
}
async function clearSeat() {
  await ElMessageBox.confirm('清空全部座位？', '提示', { type: 'warning' })
  await http.post('/seat/clear')
  ElMessage.success('已清空')
  await loadGrid()
}

function onDragStart(e, s) { dragFrom = s; e.dataTransfer.setData('text', s.row + '-' + s.col) }
async function onDrop(e, target) {
  if (!dragFrom) return
  if (dragFrom.row === target.row && dragFrom.col === target.col) return
  await http.post('/seat/move', {
    fromRow: dragFrom.row, fromCol: dragFrom.col, toRow: target.row, toCol: target.col
  })
  dragFrom = null
  await loadGrid()
}
</script>

<style scoped>
.cfg-label { color: #8a8fa3; font-size: 13px; margin-left: 6px; }
.legend { display: flex; align-items: center; gap: 18px; margin: 6px 0 14px; color: #6a6f82; font-size: 14px; }
.lg { display: flex; align-items: center; gap: 6px; }
.dot { width: 14px; height: 14px; border-radius: 4px; display: inline-block; }
.dot.boy { background: #d7e8fc; border: 1px solid #9dc4f5; }
.dot.girl { background: #fde4ee; border: 1px solid #f5b8d2; }
.aisle-bar { width: 22px; height: 8px; background: repeating-linear-gradient(90deg,#ccc 0 4px,transparent 4px 8px); border-radius: 2px; }
.tip { color: #b0b3c0; font-size: 12px; }
.podium {
  background: linear-gradient(135deg, #f7a8c8, #f492bb); color: #fff; text-align: center;
  font-weight: 800; font-size: 17px; padding: 14px; border-radius: 12px; margin-bottom: 16px;
}
.seat-grid { display: grid; gap: 10px; }
.aisle-gutter { min-width: 18px; }
.seat { border-radius: 12px; padding: 8px 4px; text-align: center; min-height: 92px; cursor: grab; }
.seat.boy { background: #e3eefc; border: 2px solid #b9d6f7; color: #3a6ea5; }
.seat.girl { background: #fde4ee; border: 2px solid #f7c3d8; color: #c2568a; }
.seat.empty-seat { background: #f7f8fc; border: 2px dashed #e3e6f0; color: #c0c4d0; display: flex; align-items: center; justify-content: center; }
.seat-pos { font-size: 11px; opacity: .7; }
.seat-name { font-size: 16px; font-weight: 800; margin: 2px 0; }
.seat-no { font-size: 12px; opacity: .8; }
.seat-sex { font-size: 12px; margin-top: 2px; }
.empty { font-size: 14px; }
</style>
