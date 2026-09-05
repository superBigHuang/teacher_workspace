<template>
  <!-- 小商店弹窗：从 Seat.vue 抽出供座次表与宠物园 /pet 页共用；状态经 api（usePets 实例）传入 -->
  <el-dialog v-model="api.shopDlg" title="🛍 宠物小商店" width="600px" align-center>
    <el-tabs v-model="api.shopTab">
      <!-- 兑换 -->
      <el-tab-pane label="🎁 兑换礼物" name="exchange">
        <div class="shop-stu-pick">
          为谁兑换：
          <el-select v-model="api.exchangeSid" placeholder="选择学生" style="width: 180px">
            <el-option v-for="p in api.pets.filter(x => x.petId)" :key="p.studentId"
              :label="`${p.name}（${p.petName} · ⭐${p.exp}）`" :value="p.studentId" />
          </el-select>
        </div>
        <div class="shop-grid">
          <div v-for="it in api.shopItems" :key="it.id" class="shop-card">
            <div class="shop-icon">{{ it.icon || '🎁' }}</div>
            <div class="shop-name">{{ it.name }}</div>
            <div class="shop-price">⭐{{ it.price }}</div>
            <div class="shop-stock">{{ it.stock < 0 ? '库存充足' : `剩 ${it.stock} 件` }}</div>
            <el-button size="small" round type="primary" :disabled="it.stock === 0" @click="api.doExchange(it)">
              兑换
            </el-button>
          </div>
          <div v-if="!api.shopItems.length" class="cat-empty">还没有商品，去「📦 商品与记录」添加吧</div>
        </div>
      </el-tab-pane>
      <!-- 商品管理 + 兑换记录 -->
      <el-tab-pane label="📦 商品与记录" name="manage">
        <div class="manage-title">商品管理</div>
        <div class="item-form">
          <el-input v-model="api.itemForm.icon" maxlength="2" style="width: 64px" placeholder="🎁" />
          <el-input v-model="api.itemForm.name" maxlength="20" style="width: 150px" placeholder="商品名称" />
          <el-input-number v-model="api.itemForm.price" :min="1" :max="280" controls-position="right" style="width: 110px" />
          <el-input-number v-model="api.itemForm.stock" :min="-1" controls-position="right" style="width: 120px" />
          <el-button type="primary" round @click="api.saveItem">上架</el-button>
        </div>
        <div class="form-hint">价格=成长值；库存填 -1 表示不限量</div>
        <div v-for="it in api.shopItems" :key="'m'+it.id" class="item-row">
          <span class="shop-icon-sm">{{ it.icon || '🎁' }}</span>
          <span class="item-name">{{ it.name }}</span>
          <span class="item-meta">⭐{{ it.price }} · {{ it.stock < 0 ? '不限量' : `库存 ${it.stock}` }}</span>
          <el-button size="small" text type="danger" @click="api.deleteItem(it.id)">下架</el-button>
        </div>

        <div class="manage-title" style="margin-top: 16px">兑换记录</div>
        <div v-for="e in api.exchanges" :key="e.id" class="ex-row">
          <span>{{ e.itemIcon || '🎁' }} {{ e.itemName }}</span>
          <span class="ex-stu">{{ e.studentName }}</span>
          <span class="ex-cost">-⭐{{ e.cost }}</span>
          <span class="ex-time">{{ fmtTime(e.createdAt) }}</span>
          <el-tag v-if="e.status === '待兑现'" type="warning" size="small">待兑现</el-tag>
          <el-tag v-else type="success" size="small">已兑现</el-tag>
          <el-button v-if="e.status === '待兑现'" size="small" round type="success"
            @click="api.fulfill(e.id)">兑现</el-button>
        </div>
        <div v-if="!api.exchanges.length" class="cat-empty">还没有兑换记录</div>
      </el-tab-pane>
    </el-tabs>
  </el-dialog>
</template>

<script setup>
import { fmtTime } from '../../composables/usePets'

/** api：usePets() 实例（reactive 包装后嵌套 ref 自动解包），提供小商店弹窗全部状态与操作 */
const props = defineProps({ api: { type: Object, required: true } })
</script>

<style scoped>
.shop-stu-pick { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; font-weight: 700; color: #8a5a34; }
.shop-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.shop-card {
  border: 2px solid #fff; border-radius: 16px; padding: 12px 8px; text-align: center;
  background: linear-gradient(150deg, #fff7ec, #ffeeda); display: flex; flex-direction: column; align-items: center; gap: 3px;
}
.shop-icon { font-size: 34px; }
.shop-name { font-weight: 800; color: #8a5a34; font-size: 14px; }
.shop-price { font-weight: 900; color: #ff7f32; }
.shop-stock { font-size: 12px; color: #b09888; margin-bottom: 4px; }
.manage-title { font-weight: 800; color: #8a5a34; margin: 6px 0 8px; }
.item-form { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-bottom: 4px; }
.form-hint { font-size: 12px; color: #c0a898; margin-bottom: 10px; }
.item-row, .ex-row {
  display: flex; align-items: center; gap: 10px; padding: 7px 10px; border-radius: 12px;
  background: #fff7ee; margin-bottom: 6px; font-size: 13px;
}
.shop-icon-sm { font-size: 20px; }
.item-name { font-weight: 700; color: #6b5b73; flex: 1; }
.item-meta { color: #a08a70; font-weight: 700; }
.ex-stu { font-weight: 800; color: #8a5a34; }
.ex-cost { color: #ef6f6f; font-weight: 800; }
.ex-time { color: #c0a898; font-size: 12px; flex: 1; }
.cat-empty { text-align: center; color: #b8a8b0; padding: 24px 0; font-size: 13px; }
</style>
