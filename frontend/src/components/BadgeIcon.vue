<template>
  <!-- 徽章：Fluent 3D PNG 图标 + tier 圆形渐变底托；未获得灰化；新获得播跳杯动画 -->
  <span class="badge-icon" :class="[tierClass, { locked: !earned, pop: earned && animate }]" :style="{ width: size + 'px', height: size + 'px' }">
    <img :src="`/badges/${icon || 'star'}.png`" :alt="name || ''" />
  </span>
</template>

<script setup>
import { computed } from 'vue'
const props = defineProps({
  icon: String,           // public/badges 下 PNG 文件名（不含扩展名）
  tier: String,           // BRONZE/SILVER/GOLD/PLATINUM
  earned: { type: Boolean, default: true },
  size: { type: Number, default: 48 },
  name: String,
  animate: { type: Boolean, default: true }   // 获得时是否播跳杯动画
})
const tierClass = computed(() => 'tier-' + (props.tier || 'BRONZE').toLowerCase())
</script>

<style scoped>
.badge-icon {
  display: inline-flex; align-items: center; justify-content: center;
  border-radius: 50%; flex-shrink: 0; position: relative;
  box-shadow: 0 2px 6px rgba(120, 100, 140, .25), inset 0 1px 2px rgba(255,255,255,.6);
}
.badge-icon img { width: 78%; height: 78%; object-fit: contain; position: relative; z-index: 1; }

/* 四级底托渐变：古铜棕 / 纯银灰（去紫）/ 亮金黄 / 白金炫彩——四色一眼可分 */
.tier-bronze   { background: linear-gradient(135deg, #c98a5b, #8a5326); }
.tier-silver   { background: linear-gradient(135deg, #f4f6fa, #a9b3c2); }
.tier-gold     { background: linear-gradient(135deg, #fff0a0, #f5b800); }
.tier-platinum { background: linear-gradient(135deg, #b8eeff, #e6dcff 45%, #ffd4ec); }

/* 未获得：灰底托 + 图标灰化半透明，"灰扑扑" */
.badge-icon.locked { background: linear-gradient(135deg, #eceaf1, #d8d5e0); box-shadow: none; }
.badge-icon.locked img { filter: grayscale(1); opacity: .3; }

/* 跳杯动画：缩放弹入 + 彩色发光 */
@keyframes badge-pop {
  0%   { transform: scale(.2) rotate(-18deg); }
  55%  { transform: scale(1.25) rotate(6deg); }
  75%  { transform: scale(.94); }
  100% { transform: scale(1); }
}
.badge-icon.pop { animation: badge-pop .55s cubic-bezier(.34,1.56,.64,1) both; }
.tier-bronze.pop   { animation: badge-pop .55s cubic-bezier(.34,1.56,.64,1) both, glow-bronze 1.4s .5s ease-out; }
.tier-silver.pop   { animation: badge-pop .55s cubic-bezier(.34,1.56,.64,1) both, glow-silver 1.4s .5s ease-out; }
.tier-gold.pop     { animation: badge-pop .55s cubic-bezier(.34,1.56,.64,1) both, glow-gold 1.4s .5s ease-out; }
.tier-platinum.pop { animation: badge-pop .55s cubic-bezier(.34,1.56,.64,1) both, glow-plat 1.6s .5s ease-out; }
@keyframes glow-bronze   { 0% { box-shadow: 0 0 0 0 rgba(168,100,47,.8); } 100% { box-shadow: 0 0 0 18px rgba(168,100,47,0); } }
@keyframes glow-silver   { 0% { box-shadow: 0 0 0 0 rgba(154,166,184,.85); } 100% { box-shadow: 0 0 0 18px rgba(154,166,184,0); } }
@keyframes glow-gold     { 0% { box-shadow: 0 0 0 0 rgba(245,184,0,.95); } 100% { box-shadow: 0 0 0 20px rgba(245,184,0,0); } }
@keyframes glow-plat     { 0% { box-shadow: 0 0 0 0 rgba(170,220,255,.95); } 100% { box-shadow: 0 0 0 22px rgba(200,180,255,0); } }
</style>
