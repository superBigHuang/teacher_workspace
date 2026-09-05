<template>
  <!-- 宠物详情弹窗（蛋态：显示蛋与孵化提示，隐藏等级/进度，加分照常）；
       从 Seat.vue 抽出供座次表与宠物园 /pet 页共用，状态经 api（usePets 实例）传入 -->
  <el-dialog v-model="api.detailDlg"
    :title="api.detailStudent?.hatched === 0 ? `🥚 ${api.detailStudent?.petName || '神秘蛋'}（孵化中）` : `🐉 ${api.detailStudent?.petName || '宠物详情'}`"
    width="460px" align-center>
    <div v-if="api.detailStudent" class="detail-body">
      <div class="detail-head">
        <img class="detail-img" :class="{ egg: api.detailStudent.hatched === 0 }" :src="petImgOf(api.detailStudent)" alt="宠物">
        <div class="detail-info">
          <div class="detail-name">{{ api.detailStudent.petName }}
            <span v-if="api.detailStudent.hatched === 0" class="lv-badge big egg-badge">🥚 孵化中</span>
            <span v-else class="lv-badge big">Lv.{{ api.detailStudent.level }}</span>
          </div>
          <!-- 蛋态：孵化进度提示代替等级/徽章（破壳后按累计成长值一次性结算） -->
          <div v-if="api.detailStudent.hatched === 0" class="detail-line egg-tip">
            🥚 蛋蛋正在吸收能量，攒够 <b>{{ HATCH_EXP }}</b> 成长值就破壳出生！<br>当前 {{ Math.min(api.detailStudent.exp || 0, HATCH_EXP) }} / {{ HATCH_EXP }}，表扬加分照常累计哦～
          </div>
          <template v-else>
            <div class="detail-line">🎓 毕业徽章：<b>{{ api.detailStudent.graduatedCount || 0 }}</b> 枚
              <span v-for="n in api.detailStudent.graduatedCount" :key="n" class="badge-medal">🎓</span>
            </div>
            <div class="detail-line">⭐ 累计成长值：<b>{{ api.detailStudent.exp }}</b>
              <span v-if="api.detailStudent.level < 5">（距 Lv.{{ api.detailStudent.level + 1 }} 还需 {{ api.detailStudent.expTarget - api.detailStudent.exp }}）</span>
              <span v-else>已满级</span>
            </div>
          </template>
        </div>
      </div>

      <!-- 进度条：蛋态显示孵化进度（exp/HATCH_EXP），破壳后显示本级成长进度 -->
      <div class="detail-exp">
        <div class="detail-exp-fill" :style="{ width: (api.detailStudent.hatched === 0 ? hatchPct(api.detailStudent) : expPct(api.detailStudent)) + '%' }"></div>
      </div>

      <div class="log-title">📖 最近成长流水</div>
      <div class="log-list">
        <div v-for="l in api.detailLogs" :key="l.id" class="log-row">
          <span class="log-score" :class="l.score > 0 ? 'good' : 'bad'">
            {{ l.score > 0 ? '+' : '' }}{{ l.score }}
          </span>
          <span class="log-name">{{ l.remark || l.ruleName || sourceText(l.source) }}</span>
          <span class="log-time">{{ fmtTime(l.createdAt) }}</span>
          <el-button size="small" text type="danger" @click="api.revokeLog(l.id)">撤回</el-button>
        </div>
        <div v-if="!api.detailLogs.length" class="log-empty">还没有成长记录，快给Ta第一次评价吧～</div>
      </div>
    </div>
    <template #footer>
      <el-button v-if="api.detailStudent?.level >= 5" type="warning" round @click="api.doGraduate">🎓 毕业</el-button>
      <el-button type="primary" round @click="api.openEvalSingle(api.detailStudent)">🌟 评价加分</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { petImgOf, hatchPct, expPct, HATCH_EXP, fmtTime, sourceText } from '../../composables/usePets'

/** api：usePets() 实例（reactive 包装后嵌套 ref 自动解包），提供详情弹窗全部状态与操作 */
const props = defineProps({ api: { type: Object, required: true } })
</script>

<style scoped>
/* 「孵化中」徽章与提示文案 */
.lv-badge.egg-badge { background: linear-gradient(135deg, #ffe3a3, #ffc879); color: #8a5a34; }
.egg-tip { color: #c08a4a; font-weight: 700; }
.detail-img.egg {
  filter: drop-shadow(0 0 10px rgba(255, 220, 130, .9));
}
.lv-badge {
  display: inline-block; font-size: 12px; font-weight: 800; border-radius: 10px;
  padding: 1px 9px; background: linear-gradient(135deg, #ffd98a, #ffb46a); color: #7a4a28;
}
.lv-badge.big { font-size: 13px; padding: 2px 10px; }
/* 详情弹窗 */
.detail-head { display: flex; gap: 16px; align-items: center; }
.detail-img { width: 120px; height: 120px; object-fit: contain; animation: pet-breathe 3.2s ease-in-out infinite; filter: drop-shadow(0 6px 10px rgba(220,160,110,.35)); }
.detail-exp { height: 12px; border-radius: 8px; background: #ffe2c8; margin: 14px 4px 4px; overflow: hidden; }
.detail-exp-fill { height: 100%; border-radius: 8px; background: linear-gradient(90deg, #ffd98a, #ff9f5a); transition: width .4s; }
.detail-info { flex: 1; }
.detail-name { font-size: 19px; font-weight: 800; color: #8a5a34; margin-bottom: 8px; }
.detail-line { font-size: 13px; color: #9a7a5c; margin: 5px 0; }
.badge-medal { margin-left: 3px; }
.log-title { font-weight: 800; color: #8a5a34; margin: 16px 0 8px; }
.log-list { max-height: 220px; overflow-y: auto; }
.log-row { display: flex; align-items: center; gap: 8px; padding: 7px 10px; border-radius: 12px; background: #fff7ee; margin-bottom: 6px; font-size: 13px; }
.log-score { font-weight: 900; width: 38px; }
.log-score.good { color: #4caf7d; }
.log-score.bad { color: #ef6f6f; }
.log-name { flex: 1; color: #6b5b73; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.log-time { color: #c0a898; font-size: 12px; }
.log-empty { text-align: center; color: #c0a898; padding: 18px 0; font-size: 13px; }
@keyframes pet-breathe {
  0%, 100% { transform: translateY(0) rotate(-1.2deg); }
  50% { transform: translateY(-7px) rotate(1.2deg); }
}
</style>
