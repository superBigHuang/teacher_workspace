<template>
  <!-- 评价项管理弹窗：从 Seat.vue 抽出供座次表与宠物园 /pet 页共用；状态经 api（usePets 实例）传入 -->
  <el-dialog v-model="api.rulesDlg" title="⚙ 评价项管理" width="580px" align-center>
    <el-tabs v-model="api.ruleCat">
      <el-tab-pane v-for="c in CATS" :key="c" :label="c" :name="c">
        <div class="item-form">
          <el-input v-model="api.ruleForm.icon" maxlength="2" style="width: 64px" placeholder="✨" />
          <el-input v-model="api.ruleForm.name" maxlength="20" style="width: 160px" placeholder="评价项名称" />
          <el-input-number v-model="api.ruleForm.score" :min="-10" :max="10" controls-position="right" style="width: 120px" />
          <el-button type="primary" round @click="api.saveRule(c)">添加</el-button>
        </div>
        <div class="form-hint">正分=奖励成长值，负分=扣减（扣分不会降级）</div>
        <div v-for="r in api.rulesOfCat(c)" :key="r.id" class="item-row">
          <span class="shop-icon-sm">{{ r.icon || '✨' }}</span>
          <span class="item-name">{{ r.name }}</span>
          <span class="item-meta" :class="r.score > 0 ? 'good' : 'bad'">
            {{ r.score > 0 ? '+' : '' }}{{ r.score }}
          </span>
          <el-switch :model-value="r.enabled === 1 || r.enabled === true" size="small" @change="v => api.toggleRule(r, v)" />
          <el-button size="small" text type="danger" @click="api.deleteRule(r.id)">删除</el-button>
        </div>
        <div v-if="!api.rulesOfCat(c).length" class="cat-empty">该分类暂无评价项</div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup>
import { PET_CATS } from '../../composables/usePets'

/** 评价项分类（固定四个 tab） */
const CATS = PET_CATS
/** api：usePets() 实例（reactive 包装后嵌套 ref 自动解包），提供评价项弹窗全部状态与操作 */
const props = defineProps({ api: { type: Object, required: true } })
</script>

<style scoped>
.item-form { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 4px; }
.form-hint { font-size: 12px; color: #c0a898; margin-bottom: 10px; }
.item-row { display: flex; align-items: center; gap: 10px; padding: 7px 10px; border-radius: 12px; background: #fff7ee; margin-bottom: 6px; font-size: 13px; }
.shop-icon-sm { font-size: 20px; }
.item-name { font-weight: 700; color: #6b5b73; flex: 1; }
.item-meta { color: #a08a70; font-weight: 700; }
.item-meta.good { color: #4caf7d; }
.item-meta.bad { color: #ef6f6f; }
.cat-empty { text-align: center; color: #b8a8b0; padding: 24px 0; font-size: 13px; }
</style>
