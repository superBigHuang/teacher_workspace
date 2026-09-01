<template>
  <div class="book-root">
    <!-- 屏幕操作条（打印时隐藏） -->
    <div class="toolbar no-print">
      <span class="tb-title">📖 期末评语册（{{ data?.list?.length || 0 }} 人）</span>
      <div>
        <el-button type="primary" @click="doPrint">🖨️ 打印 / 另存 PDF</el-button>
        <el-button @click="close">关闭</el-button>
      </div>
    </div>

    <div v-if="!data" class="empty no-print">
      <el-empty description="未找到评语数据，请从期末评语页重新打开" />
    </div>

    <!-- 评语册正文：每生一块，块内不跨页断开 -->
    <div v-if="data" class="book-page">
      <div class="b-head">
        <div class="b-school">{{ data.school || ' ' }}</div>
        <div class="b-title">期末评语册</div>
        <div class="b-sub">{{ data.className }} ｜ {{ data.termStart }} ~ {{ data.termEnd }} ｜ 班主任：{{ data.teacherName }}</div>
      </div>
      <div v-for="(s, i) in data.list" :key="s.studentId" class="b-item">
        <div class="b-name">{{ i + 1 }}. {{ s.name }}
          <span class="b-no">（学号 {{ s.stuNo }}）</span>
          <span v-if="s.source === 'ai'" class="b-ai">✨</span>
        </div>
        <div class="b-content">{{ s.content }}</div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'

// 评语册数据由评语页写入 sessionStorage（新窗口同源可读）
const data = ref(null)
try {
  const raw = sessionStorage.getItem('commentBook')
  if (raw) data.value = JSON.parse(raw)
} catch (e) { /* 数据损坏显示空态 */ }

function doPrint() { window.print() }
function close() {
  if (window.opener) window.close()
  else window.location.href = '/comment'
}
</script>

<style scoped>
.book-root { background: #ece7e2; padding: 20px 0; }
.toolbar {
  position: sticky; top: 0; z-index: 10; max-width: 800px; margin: 0 auto 16px;
  background: #fff; border-radius: 14px; padding: 12px 18px; display: flex;
  justify-content: space-between; align-items: center; box-shadow: 0 6px 20px rgba(0,0,0,.08);
}
.tb-title { font-weight: 800; color: #6b5b73; }
.empty { max-width: 800px; margin: 60px auto; background: #fff; border-radius: 14px; }

/* A4 纸张：评语册连续排版，每生一块避免跨页断开 */
.book-page {
  width: 210mm; min-height: 297mm; margin: 0 auto; background: #fff;
  padding: 16mm 18mm; box-sizing: border-box; box-shadow: 0 4px 18px rgba(0,0,0,.1);
}
.b-head { text-align: center; border-bottom: 2px solid #6b5b73; padding-bottom: 10px; margin-bottom: 14px; }
.b-school { font-size: 15px; color: #6b5b73; letter-spacing: 2px; }
.b-title { font-size: 26px; font-weight: 800; color: #4a3f55; letter-spacing: 6px; margin: 4px 0; }
.b-sub { font-size: 13px; color: #8a8fa3; }

.b-item { margin: 0 0 16px; page-break-inside: avoid; break-inside: avoid; }
.b-name { font-size: 15px; font-weight: 800; color: #4a3f55; margin-bottom: 4px; }
.b-no { font-size: 12px; color: #a79fb0; font-weight: 400; }
.b-ai { font-size: 12px; }
.b-content {
  font-size: 14px; line-height: 2; color: #4a4455; text-indent: 2em;
  background: #fdfaf5; border-radius: 10px; padding: 10px 14px;
}

@page { size: A4; margin: 0; }
@media print {
  .no-print { display: none !important; }
  .book-root { background: #fff; padding: 0; }
  .book-page { margin: 0; box-shadow: none; }
}
</style>
