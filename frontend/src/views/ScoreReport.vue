<template>
  <div class="report-root">
    <!-- 屏幕操作条（打印时隐藏） -->
    <div class="toolbar no-print">
      <span class="tb-title">📄 家长成绩通知单（{{ data?.students?.length || 0 }} 人）</span>
      <div>
        <el-button type="primary" @click="doPrint">🖨️ 打印 / 另存 PDF</el-button>
        <el-button @click="close">关闭</el-button>
      </div>
    </div>

    <div v-if="!data" class="empty no-print">
      <el-empty description="未找到报告数据，请从成绩分析页重新生成" />
    </div>

    <!-- 每生一页 A4 -->
    <div v-for="(s, i) in (data?.students || [])" :key="s.studentId" class="report-page">
      <div class="r-head">
        <div class="r-school">{{ data.school || ' ' }}</div>
        <div class="r-title">家长成绩通知单</div>
        <div class="r-sub">{{ data.className }} ｜ {{ data.term }} ｜ {{ data.examName }}<template v-if="data.examDate && data.examDate !== 'null'">（{{ data.examDate }}）</template></div>
      </div>

      <div class="r-stu">
        <span><b>姓名：</b>{{ s.name }}</span>
        <span><b>学号：</b>{{ s.stuNo }}</span>
        <span><b>班主任：</b>{{ data.teacherName }}</span>
        <span class="r-no">第 {{ i + 1 }} 页 / 共 {{ data.students.length }} 页</span>
      </div>

      <table class="r-table">
        <thead>
          <tr><th>科目</th><th>得分</th><th>班级均分</th><th>班级最高</th><th>等级</th></tr>
        </thead>
        <tbody>
          <tr v-for="sub in s.subjects" :key="sub.subject">
            <td>{{ sub.subject }}</td>
            <td :class="{ miss: sub.score === null || sub.score === undefined }">{{ sub.score === null || sub.score === undefined ? '—' : sub.score }}</td>
            <td>{{ sub.classAvg }}</td>
            <td>{{ sub.classMax }}</td>
            <td><span class="grade" :class="'g-' + sub.grade">{{ sub.grade }}</span></td>
          </tr>
        </tbody>
      </table>

      <div class="r-total">
        <span><b>总分：</b>{{ s.total === null || s.total === undefined ? '—' : s.total }}</span>
        <span><b>班级排名：</b>{{ s.rank ? s.rank + ' / ' + s.totalStudents : '—' }}</span>
        <span v-if="data.compareExamId"><b>进退步：</b>
          <template v-if="s.rankChange === null || s.rankChange === undefined">—</template>
          <template v-else-if="s.rankChange > 0"><span class="up">↑ 进步 {{ s.rankChange }} 名</span></template>
          <template v-else-if="s.rankChange < 0"><span class="down">↓ 退步 {{ -s.rankChange }} 名</span></template>
          <template v-else>持平</template>
        </span>
        <span class="r-legend">等级：A≥{{ data.excellentLine }} 分　B≥{{ data.passLine }} 分　C＜{{ data.passLine }} 分</span>
      </div>

      <div v-if="opts.showDaily" class="r-daily">
        <div class="r-sec-title">本学期在校表现</div>
        <div class="r-daily-grid">
          <span>迟到：<b>{{ s.attendance.late }}</b> 次</span>
          <span>请假：<b>{{ s.attendance.leaveDays }}</b> 天</span>
          <span>作业未交：<b>{{ s.homeworkMissing }}</b> 次</span>
          <span>纪律记录：<b>{{ s.disciplineCount }}</b> 条</span>
        </div>
      </div>

      <div v-if="opts.showMessage" class="r-message">
        <div class="r-sec-title">教师寄语</div>
        <div class="r-message-text">{{ s.comment || ' ' }}</div>
        <div v-if="s.aiSource" class="r-ai-tag">✨ AI 生成</div>
      </div>

      <div class="r-foot">
        <span>班主任签名：______________</span>
        <span>{{ today }}</span>
        <span class="r-sign">家长签字：______________</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import http from '../api/http'

// 报告数据与配置由成绩页生成后写入 sessionStorage（新窗口同源可读）
const data = ref(null)
const opts = reactive({ showDaily: true, showMessage: true })
const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric' })

try {
  const raw = sessionStorage.getItem('scoreReportData')
  if (raw) {
    const parsed = JSON.parse(raw)
    data.value = parsed.data
    Object.assign(opts, parsed.opts || {})
  }
} catch (e) { /* 数据损坏时显示空态 */ }

function doPrint() {
  window.print()
}
function close() {
  // 独立新窗口打开时关闭自身；路由跳转打开时回成绩页
  if (window.opener) window.close()
  else http && (window.location.href = '/score')
}
</script>

<style scoped>
.report-root { background: #ece7e2; padding: 20px 0; }
.toolbar {
  position: sticky; top: 0; z-index: 10; max-width: 800px; margin: 0 auto 16px;
  background: #fff; border-radius: 14px; padding: 12px 18px; display: flex;
  justify-content: space-between; align-items: center; box-shadow: 0 6px 20px rgba(0,0,0,.08);
}
.tb-title { font-weight: 800; color: #6b5b73; }
.empty { max-width: 800px; margin: 60px auto; background: #fff; border-radius: 14px; }

/* A4 单页：210mm×297mm；每生一页 */
.report-page {
  width: 210mm; min-height: 297mm; margin: 0 auto 16px; background: #fff;
  padding: 16mm 18mm; box-sizing: border-box; display: flex; flex-direction: column;
  box-shadow: 0 4px 18px rgba(0,0,0,.1);
}
.r-head { text-align: center; border-bottom: 2px solid #6b5b73; padding-bottom: 10px; }
.r-school { font-size: 15px; color: #6b5b73; letter-spacing: 2px; }
.r-title { font-size: 26px; font-weight: 800; color: #4a3f55; letter-spacing: 6px; margin: 4px 0; }
.r-sub { font-size: 13px; color: #8a8fa3; }
.r-stu { display: flex; gap: 26px; margin: 16px 0 10px; font-size: 14px; color: #4a4a55; }
.r-stu .r-no { margin-left: auto; color: #b0b3c0; font-size: 12px; }

.r-table { width: 100%; border-collapse: collapse; font-size: 14px; }
.r-table th, .r-table td { border: 1px solid #d8d2de; padding: 8px 10px; text-align: center; }
.r-table th { background: #f6f0fa; color: #6b5b73; font-weight: 700; }
.r-table td.miss { color: #b0b3c0; }
.grade { display: inline-block; width: 24px; height: 24px; line-height: 24px; border-radius: 50%; font-weight: 800; }
.g-A { background: #d6f5e3; color: #2e8b57; }
.g-B { background: #e3f0fd; color: #3a76c4; }
.g-C { background: #fdecec; color: #d06060; }
.g-— { background: #f2f0f4; color: #b0b3c0; }

.r-total { display: flex; gap: 24px; align-items: center; margin: 14px 0; font-size: 14px; flex-wrap: wrap; }
.r-total .r-legend { margin-left: auto; color: #b0b3c0; font-size: 12px; }
.up { color: #2e8b57; font-weight: 700; }
.down { color: #d06060; font-weight: 700; }

.r-sec-title { font-weight: 800; color: #6b5b73; border-left: 4px solid #f78fb3; padding-left: 8px; margin: 12px 0 8px; font-size: 14px; }
.r-daily-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 8px; font-size: 13px; color: #5a5f72; background: #faf7fc; border-radius: 10px; padding: 12px; }
.r-daily-grid b { color: #4a3f55; }
.r-message { margin-top: 10px; position: relative; }
.r-message-text {
  background: #fffdf5; border: 1px dashed #e8d9a8; border-radius: 10px;
  padding: 14px 16px; font-size: 14px; line-height: 1.9; color: #5a5060; min-height: 70px;
}
.r-ai-tag { position: absolute; top: 10px; right: 10px; font-size: 11px; color: #b08a2e; }

.r-foot { margin-top: auto; padding-top: 30px; display: flex; justify-content: space-between; font-size: 14px; color: #4a4a55; }
.r-foot .r-sign { font-weight: 700; }

@page { size: A4; margin: 0; }
@media print {
  .no-print { display: none !important; }
  .report-root { background: #fff; padding: 0; }
  .report-page { margin: 0; box-shadow: none; page-break-after: always; }
  .report-page:last-child { page-break-after: auto; }
}
</style>
