# 开发分支开工任务书：宠物园 v1.2 —— 孵蛋阶段与破壳演出

> 2026-09-02。粘贴给开发分支执行。
> 前置：v1.1 已交付（pet_skin 皮肤系统、EvolutionShow 全屏进化演出、evolve_pending 待演通道）。

## 背景（现状）

- 现在领养即 Lv1 幼体：`PetService.adopt()` 直接 INSERT `level=1, exp=0`。
- 蛋图（`/pets/{key}_egg.webp`）只用于**未领养空位**（`petImgOf()` 中 `petId===null` 显示蛋）。
- 需求：领养后先是一颗**神秘蛋**，孵一天后破壳出生，仪式感拉满（小学生期待感）。

## 产品规则

1. 领养成功 → 座位上是一颗"孵化中"的蛋（不再是空位灰蛋）。
2. **破壳条件：跨自然日**（`DATE(adopted_at) < CURDATE()`）。即今天领养，明天第一次打开座次表页时自动播破壳演出。零额外操作；周末/假期跨天也算。
3. 蛋期间成长值**照常累计**（加分/扣分流水照写、飘字照常），但不进化、不播升级演出；破壳时一次性结算等级——若蛋期间攒够 exp，破壳演出后**连播进化演出**（队列天然支持，双喜临门）。
4. 蛋期间不挂 dizzy/frozen/broken/starToday 状态特效（蛋不会头晕）。
5. Lv5 毕业后轮回同样从新蛋开始（reset hatched=0，再孵一天）。

---

## 一、后端

### 1.1 pet 表加列（schema.sql 幂等迁移）

```sql
-- 孵化标记：0=蛋中（领养后到次日破壳前），1=已破壳。DEFAULT 1 保证存量宠物全部视为已破壳
ALTER TABLE pet ADD COLUMN hatched TINYINT NOT NULL DEFAULT 1 COMMENT '是否已破壳：0=孵化中蛋，1=已出生';
```
（用 information_schema 元数据判断列是否存在，与现有加列迁移同一写法。）

### 1.2 PetService 改造

- `adopt()`：INSERT 显式带 `hatched=0`。
- `award()`：SELECT 宠物时多取 `hatched` 字段；
  - `hatched=0`（蛋中）：exp 照常 clamp 更新，**level 保持 1 不动**、不产生 LevelUp、不置 evolve_pending（蛋里看不见进化）；
  - `hatched=1`：逻辑不变。
- `revokeLog()`：同样，hatched=0 时只回退 exp、level 不动。
- 毕业轮回（graduate 的 UPDATE）：reset 时加 `hatched=0`。
- 新增静态方法便于测试：
  ```java
  /** 是否到破壳时间：领养日期早于今天（跨自然日） */
  public static boolean isHatchDue(LocalDateTime adoptedAt, LocalDate today)
  ```

### 1.3 PetController 接口

- `GET /pet/pets`：
  - pets 每行新增 `hatched` 字段；
  - 返回体新增 `pendingHatches`（与 pendingEvolves 同结构风格，限本班学生）：
    ```
    [{studentId, name, petName, skinKey, fxTheme, toLevel}]
    ```
    查询条件：`hatched=0 AND DATE(adopted_at) < CURDATE()`；`toLevel = levelOf(exp)`（破壳后等级，蛋期间攒分可能 >1）。
- 新增 `POST /pet/hatch-seen`，body `{studentId}`：
  - 后端**再次校验跨天条件**（防前端瞎调），满足才执行：
    `UPDATE pet SET hatched=1, level=? WHERE user_id=? AND student_id=? AND hatched=0 AND DATE(adopted_at)<CURDATE()`
    （level = levelOf 当前 exp）；不满足返回不变更。

## 二、前端

### 2.1 座位蛋的两种状态（Seat.vue / usePets.js）

- `petImgOf()`：已领养且 `hatched===0` → 返回蛋图（与未领养同图，但行为/特效不同）。
- **未领养空位蛋**：维持现状，点击弹领养弹窗。
- **孵化中蛋**（已领养 hatched=0）：
  - 视觉区分：蛋图加暖黄呼吸光晕（box-shadow/径向光晕 2s 循环）+ 轻微上下浮动 + 右上角小 ✨；
  - 点击：蛋左右摇晃动画 0.5s + ElMessage 提示"🥚 {petName} 还在蛋里睡觉，明天再来看看吧！"，**不弹领养窗**；
  - 屏蔽 dizzy/frozen/broken/starToday 覆盖特效与光环（hatched=0 时这些 class 一律不加）；
  - 加分飘字/⭐ 照常（蛋在吸收能量，有趣）。

### 2.2 破壳演出（EvolutionShow.vue 扩展）

- 演出队列新增 kind 概念：`kind: 'hatch' | 'evolve'`（默认 evolve，现有逻辑不动）。
- hatch 一幕数据：`{kind:'hatch', studentId, name, petName, skinKey, fxTheme}`，fromImg=`skinImg(key,'egg')`，toImg=`skinImg(key,1)`。
- 分镜复用现有 6 秒骨架，差异点：
  - 蓄力段：蛋**左右剧烈摇晃**（rotate ±15° 加速抖动，替代旧形态 shake）+ 主题色光柱 + ✨💫🥚 粒子向蛋汇聚；
  - 爆闪后出现 Lv1 幼体（弹簧 + confetti 三波，主题色，同进化）；
  - 大字字幕"🥚 破 壳 啦 🥚"，副标题"**{name} 的 {petName} 破壳诞生啦！**"。
- 队列处理（usePets.js）：loadPets 后
  1. `pendingHatches` 逐个入队 hatch 幕（排在 evolve 幕之前）；
  2. 若该幕 `toLevel > 1`，紧随其后入队一幕 evolve（fromLevel=1, toLevel=toLevel，图片走 skinImg）；
  3. 每幕 hatch 播完调 `POST /pet/hatch-seen`；evolve 幕仍调 evolve-seen。
- 同步接口（evaluate 等）在蛋期间不返回 levelUps（后端已挡），无需前端特殊处理。

### 2.3 文案与弹窗

- 领养弹窗：底部加一行说明"🥚 领养后是一颗神秘蛋，**明天**就会破壳出生哦！"；领养成功提示"领养成功！明天 {petName} 就会破壳啦~"。
- 宠物详情弹窗：hatched=0 时大图显示蛋 + 文案"孵化中……明天破壳"，隐藏等级/进度条（或进度条显示蛋期间累计能量）。
- 规则/帮助说明补一条："领养后宠物是一颗神秘蛋，第二天破壳出生；蛋期间获得的成长值破壳后一并结算。"

## 三、E2E（e2e/tests/e2e.spec.js 追加，临时生 + afterAll 清理）

1. 临时生领养 → 座位格显示蛋图（img src 含 `egg`）且带"孵化中"区分标记；点击蛋出现"明天再来"提示、不弹领养弹窗。
2. `GET /pet/pets` 该行 `hatched===0`；当天调 `POST /pet/hatch-seen` → 再查 hatched 仍为 0（跨天守卫生效）。
3. 蛋期间给该生加分 → 飘字正常、无进化演出、hatched 仍 0。
4. 破壳演出跨天逻辑：`isHatchDue` 为纯静态方法，后端单测覆盖（今天领养=false、昨天领养=true）；
   手工验收（1 分钟）：本地库把测试宠物 `adopted_at` 改成昨天（SQL 交用户执行），刷新座次表 → 自动播破壳演出、字幕"破壳啦"、白闪后变 Lv1 图、演完 hatched=1；若蛋期间 exp 已 ≥40，破壳后连播 Lv1→Lv2 进化。
5. npm run build 通过。

## 四、约定

- 后端：extends BaseController（uid()）、JdbcTemplate、schema.sql 幂等迁移；后端重启用计划任务 `schtasks /end|/run /tn TeacherBackend`。
- 前端：Vue3 `<script setup>` + Element Plus；动画只用 transform/opacity；EvolutionShow 改动以 kind 分支，不破坏现有 evolve 分镜。
- 存量数据安全：hatched 列 DEFAULT 1，线上已领养的宠物不会回退成蛋。
