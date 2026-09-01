<template>
  <el-dialog :model-value="modelValue" title="🎨 自创宠物" width="640px" align-center
             @update:model-value="v => emit('update:modelValue', v)" @open="resetForm">
    <el-form label-width="76px">
      <el-form-item label="宠物名">
        <el-input v-model="name" maxlength="12" placeholder="给新种族起个名，如：小火狐" />
      </el-form-item>
      <el-form-item label="特效主题">
        <div class="theme-row">
          <button v-for="(t, key) in FX_THEMES" :key="key" type="button"
                  class="theme-opt" :class="{ active: fxTheme === key }" @click="fxTheme = key">
            <i class="theme-dot" :style="{ background: t.beam }"></i>
            <span>{{ t.particles[0] }} {{ t.name }}</span>
          </button>
        </div>
      </el-form-item>
      <el-form-item label="形态图片">
        <div class="slot-grid">
          <el-upload v-for="slot in slots" :key="slot.stage" :show-file-list="false"
                     accept="image/png,image/jpeg,image/webp"
                     :http-request="opt => customUpload(opt, slot)">
            <div class="slot" :class="{ filled: slot.url }">
              <img v-if="slot.url" :src="slot.url" alt="">
              <template v-else>
                <span class="slot-label">{{ slot.label }}</span>
                <small>点击上传</small>
              </template>
              <span v-if="slot.uploading" class="slot-mask">处理中…</span>
              <span v-if="slot.url" class="slot-re">🔄</span>
            </div>
          </el-upload>
        </div>
        <div class="form-hint">
          蛋可缺省（用默认精灵蛋）；上传时自动一键去白底；要求 1:1 方图、≥1024px、白底全身像
        </div>
      </el-form-item>
    </el-form>

    <!-- AI 造宠教程 -->
    <el-collapse>
      <el-collapse-item title="🎨 不会画？用 AI 生成教程（点开复制提示词）" name="tutor">
        <div class="tutor-tip">
          推荐用 <b>即梦 AI</b>（jimeng.jianying.com）或豆包，免费、中文理解好。
          把下面提示词里的「云龙」形象换成你的宠物（如小火狐/冰麒麟），保持 6 阶段进化结构；
          每张图末尾统一拼上风格后缀，出图后直接在本页上传（自动去白底）；去不干净就用
          <a href="https://www.remove.bg/zh" target="_blank" rel="noopener">remove.bg</a> 抠图后重传。
        </div>
        <div class="style-suffix">
          <b>风格后缀（每条末尾都拼上）：</b>
          <code>{{ STYLE_SUFFIX }}</code>
          <el-button size="small" text @click="copy(STYLE_SUFFIX)">📋 复制</el-button>
        </div>
        <div v-for="(p, i) in PROMPTS" :key="i" class="prompt-row">
          <span class="prompt-stage">{{ p.label }}</span>
          <span class="prompt-text">{{ p.text }}</span>
          <el-button size="small" text @click="copy(p.text + STYLE_SUFFIX)">📋 复制</el-button>
        </div>
      </el-collapse-item>
    </el-collapse>

    <template #footer>
      <el-button round @click="close">取消</el-button>
      <el-button type="primary" round @click="save">💾 保存并选用</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { FX_THEMES } from '../composables/usePets'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'saved'])

const name = ref('')
const fxTheme = ref('gold')
const skinKey = ref(null)
const skinId = ref(null)
const slots = ref([])

/** 6 个上传槽位（蛋可缺省） */
function resetForm() {
  name.value = ''
  fxTheme.value = 'gold'
  skinKey.value = null
  skinId.value = null
  slots.value = [
    { stage: 'egg', label: '🥚蛋', url: null, uploading: false },
    { stage: '1', label: 'Lv1', url: null, uploading: false },
    { stage: '2', label: 'Lv2', url: null, uploading: false },
    { stage: '3', label: 'Lv3', url: null, uploading: false },
    { stage: '4', label: 'Lv4', url: null, uploading: false },
    { stage: '5', label: 'Lv5', url: null, uploading: false }
  ]
}

function close() { emit('update:modelValue', false) }

/** 保存：未建皮肤则建行，已建则更新名称/主题；保存后通知父组件刷新并选中 */
async function save() {
  if (!name.value.trim()) { ElMessage.warning('请填写宠物名称'); return }
  if (!skinKey.value) {
    const r = await http.post('/pet/skin', { name: name.value.trim(), fxTheme: fxTheme.value })
    skinKey.value = r.skinKey
    skinId.value = r.id
  } else {
    await http.put(`/pet/skin/${skinId.value}`, { name: name.value.trim(), fxTheme: fxTheme.value })
  }
  ElMessage.success('宠物皮肤已保存，快去领养吧！')
  emit('saved', skinKey.value)
  close()
}

/**
 * 自定义上传：先确保皮肤已建行 → canvas 一键去白底 → multipart 上传。
 */
async function customUpload(opt, slot) {
  slot.uploading = true
  try {
    if (!skinKey.value) {
      if (!name.value.trim()) { ElMessage.warning('请先填写宠物名称再上传图片'); return }
      const r = await http.post('/pet/skin', { name: name.value.trim(), fxTheme: fxTheme.value })
      skinKey.value = r.skinKey
      skinId.value = r.id
    }
    const { blob, complexBg } = await removeWhiteBg(opt.file)
    if (complexBg) {
      ElMessage.warning('检测到图片四角不是纯白背景，去底可能不干净，建议用 remove.bg 抠图后重传')
    }
    const fd = new FormData()
    fd.append('skinKey', skinKey.value)
    fd.append('stage', slot.stage)
    fd.append('file', blob, `${skinKey.value}_${slot.stage}.png`)
    const r = await http.post('/pet/skin/upload', fd, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    slot.url = r.url
    ElMessage.success(`${slot.label} 形态已上传`)
  } catch (e) { /* http 拦截器已提示错误 */ } finally {
    slot.uploading = false
  }
}

/**
 * 一键去白底：从四角 flood-fill（BFS），近白像素（RGB 均 ≥225，即与白色差 &lt;30）alpha 置 0；
 * 边缘 1px 羽化（被清像素相邻的不透像素 alpha 乘 .75）。
 * 四角中 ≥3 个不是近白则判定背景复杂（调用方提示 remove.bg）。
 * 图片最大边缩到 1024 控制处理体积。
 */
async function removeWhiteBg(file) {
  const url = URL.createObjectURL(file)
  try {
    const img = await new Promise((res, rej) => {
      const im = new Image()
      im.onload = () => res(im)
      im.onerror = rej
      im.src = url
    })
    const maxSide = 1024
    let w = img.naturalWidth, h = img.naturalHeight
    if (Math.max(w, h) > maxSide) {
      const r = maxSide / Math.max(w, h)
      w = Math.round(w * r); h = Math.round(h * r)
    }
    const canvas = document.createElement('canvas')
    canvas.width = w; canvas.height = h
    const ctx = canvas.getContext('2d', { willReadFrequently: true })
    ctx.drawImage(img, 0, 0, w, h)
    const data = ctx.getImageData(0, 0, w, h)
    const d = data.data
    const nearWhite = i => d[i] >= 225 && d[i + 1] >= 225 && d[i + 2] >= 225
    // 四角背景检测
    const corners = [0, w - 1, (h - 1) * w, (h - 1) * w + w - 1]
    const whiteCorners = corners.filter(p => nearWhite(p * 4)).length
    const complexBg = whiteCorners < 3
    // BFS flood fill（扁平队列，避免 shift 的 O(n)）
    const visited = new Uint8Array(w * h)
    const q = new Int32Array(w * h)
    let qh = 0, qt = 0
    for (const p of corners) {
      if (!visited[p] && nearWhite(p * 4)) { visited[p] = 1; q[qt++] = p }
    }
    const edge = new Set()   // 与背景相邻的不透明像素（羽化用）
    while (qh < qt) {
      const p = q[qh++]
      const x = p % w, y = (p / w) | 0
      d[p * 4 + 3] = 0
      const nb = []
      if (x > 0) nb.push(p - 1)
      if (x < w - 1) nb.push(p + 1)
      if (y > 0) nb.push(p - w)
      if (y < h - 1) nb.push(p + w)
      for (const n of nb) {
        if (visited[n]) continue
        if (nearWhite(n * 4)) { visited[n] = 1; q[qt++] = n }
        else if (d[n * 4 + 3] > 0) edge.add(n)
      }
    }
    // 边缘羽化
    edge.forEach(p => { d[p * 4 + 3] = Math.round(d[p * 4 + 3] * 0.75) })
    ctx.putImageData(data, 0, 0)
    const blob = await new Promise(r => canvas.toBlob(r, 'image/png'))
    return { blob, complexBg }
  } finally {
    URL.revokeObjectURL(url)
  }
}

async function copy(text) {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('提示词已复制，去 AI 工具粘贴吧')
  } catch (e) {
    ElMessage.warning('复制失败，请手动选择文本复制')
  }
}

/** 风格后缀（每条单体 prompt 末尾拼接，保证 6 张风格统一） */
const STYLE_SUFFIX = '，Q版2D插画，柔和圆润描边，柔光渐变上色，盲盒贴纸质感，马卡龙奶油配色，纯白背景，全身像居中，高清8K'

/** 6 阶段单体提示词（以云小龙为范例，替换主体即可自创种族） */
const PROMPTS = [
  { label: '🥚蛋', text: '一颗神秘龙蛋坐在小云朵上，淡奶油金色蛋壳带祥云微光纹路，蛋顶一道浅裂纹透出暖光，周围薄云雾和细小星光' },
  { label: 'Lv1', text: '刚孵化的云团小神兽，圆滚滚奶白棉花糖身体，头顶两只小米粒淡金龙角，黑豆大眼睛，小短腿，蓬松小云尾，呆萌表情' },
  { label: 'Lv2', text: '云朵小龙，奶白身体长出浅蓝色小鬃毛，背上一对胖乎乎小翅膀，长尾巴带蓝色云尖，大眼睛开心笑，蹦跳姿态' },
  { label: 'Lv3', text: '四足站立的少年神龙，玉白身体，蓝色鬃毛和云焰尾巴，小巧鹿角，背上小翅膀，淡金色祥云纹饰，神气自信' },
  { label: 'Lv4', text: '华丽的玉色精灵龙，修长优雅身体，白云丝绸飘带环绕，金色鹿角，半透明蓝玉翅膀，脚踏祥云，周身金色光点，灵动高贵' },
  { label: 'Lv5', text: '威严的金色东方神龙，修长龙身金白相间鳞片，金色鹿角，白色长须，环绕祥云与金色光点，神性光芒，华丽又可爱' }
]
</script>

<style scoped>
.theme-row { display: flex; flex-wrap: wrap; gap: 8px; }
.theme-opt {
  display: flex; align-items: center; gap: 6px; border: 2px solid #eee; border-radius: 999px;
  background: #fff; padding: 5px 12px; cursor: pointer; font-size: 13px; color: #6b5b73;
  transition: all .15s;
}
.theme-opt:hover { transform: scale(1.05); }
.theme-opt.active { border-color: #f78fb3; background: #fde4ee; font-weight: 800; }
.theme-dot { width: 14px; height: 14px; border-radius: 50%; display: inline-block; box-shadow: 0 1px 4px rgba(0,0,0,.2); }
.slot-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 8px; width: 100%; }
.slot {
  position: relative; width: 100%; aspect-ratio: 1; border-radius: 12px; border: 2px dashed #e3d9c8;
  background: #fffaf2; display: flex; flex-direction: column; align-items: center; justify-content: center;
  cursor: pointer; overflow: hidden; transition: border-color .15s, transform .15s;
}
.slot:hover { border-color: #ffb38a; transform: scale(1.05); }
.slot.filled { border-style: solid; border-color: #ffc9a3; background: #fff; }
.slot img { width: 100%; height: 100%; object-fit: contain; }
.slot-label { font-size: 16px; font-weight: 800; color: #b08a66; }
.slot small { font-size: 10px; color: #c0a898; margin-top: 2px; }
.slot-re { position: absolute; right: 2px; bottom: 0; font-size: 12px; }
.slot-mask {
  position: absolute; inset: 0; background: rgba(255,255,255,.8); display: flex; align-items: center;
  justify-content: center; font-size: 12px; color: #ff7f32; font-weight: 700;
}
.form-hint { font-size: 12px; color: #c0a898; margin-top: 6px; line-height: 1.6; }
.tutor-tip { font-size: 13px; color: #8a6a44; line-height: 1.8; background: #fff7ee; border-radius: 10px; padding: 8px 12px; }
.tutor-tip a { color: #ff7f32; }
.style-suffix { display: flex; align-items: flex-start; gap: 6px; margin: 10px 0; font-size: 12px; color: #8a6a44; }
.style-suffix code {
  flex: 1; background: #f6f4ef; border-radius: 8px; padding: 6px 8px; font-size: 11px;
  color: #6b5b73; word-break: break-all;
}
.prompt-row { display: flex; align-items: flex-start; gap: 8px; padding: 7px 0; border-top: 1px dashed #f0e4d4; font-size: 12px; }
.prompt-stage { flex-shrink: 0; width: 38px; font-weight: 800; color: #ff7f32; }
.prompt-text { flex: 1; color: #6b5b73; line-height: 1.6; }
</style>
