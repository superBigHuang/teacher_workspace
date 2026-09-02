<template>
  <div>
    <div class="page-toolbar">
      <h2 class="page-title">🪑 座次表 · 🐾 宠物园</h2>
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
      <el-button round @click="openRanking">🏆 荣誉榜</el-button>
      <el-button round @click="openShop">🛍 小商店</el-button>
      <el-button round @click="openRules">⚙ 评价项</el-button>
      <el-button round :type="batchMode ? 'success' : 'warning'" @click="toggleBatch">
        {{ batchMode ? `✅ 完成批量（已选 ${selected.size}）` : '📝 批量评价' }}
      </el-button>
    </div>

    <div class="legend">
      <span class="lg"><i class="dot boy"></i> 男生</span>
      <span class="lg"><i class="dot girl"></i> 女生</span>
      <span class="lg"><i class="aisle-bar"></i> 过道</span>
      <span class="tip">拖动学生交换座位；点击座位查看/领养宠物</span>
    </div>

    <!-- 批量模式浮动条 -->
    <transition name="bar">
      <div v-if="batchMode" class="batch-bar">
        <span>已选 <b class="pick-num">{{ selected.size }}</b> 名学生</span>
        <el-button size="small" type="primary" round :disabled="!selected.size" @click="openEvalBatch">
          选择评价项 →
        </el-button>
        <el-button size="small" text @click="selected.clear()">清空</el-button>
      </div>
    </transition>

    <div class="podium">🏫 讲台</div>

    <!-- 座位棋盘：左列行名、顶行列名（都在格子外）；格子内只放宠物，名字竖排贴格子左侧 -->
    <div class="board-grid" :style="boardGridStyle">
      <!-- 列名（讲台下方） -->
      <div class="board-corner"></div>
      <div v-for="c in cols" :key="'col'+c" class="col-label"
           :style="{ gridRow: 1, gridColumn: displayCol(c) + 1 }">第{{ c }}列</div>
      <div v-for="aisleCol in aisleDisplayColumns" :key="'al'+aisleCol" class="col-label aisle-label"
           :style="{ gridRow: 1, gridColumn: aisleCol + 1 }">过道</div>
      <!-- 行名（每行左侧） -->
      <div v-for="r in rows" :key="'row'+r" class="row-label"
           :style="{ gridRow: r + 1, gridColumn: 1 }">第{{ r }}排</div>
      <!-- 过道（贯穿所有排） -->
      <div v-for="aisleCol in aisleDisplayColumns" :key="'ag'+aisleCol" class="aisle-gutter"
           :style="{ gridRow: `2 / ${rows + 2}`, gridColumn: aisleCol + 1 }"></div>
      <!-- 座位（data-sid 供全屏进化演出定位；格内只放宠物与天气特效） -->
      <div v-for="s in seats" :key="s.row + '-' + s.col"
           class="seat" :class="[seatClass(s), { 'star-today': petOf(s)?.starToday }]"
           :style="{ gridRow: s.row + 1, gridColumn: displayCol(s.col) + 1 }"
           :data-sid="s.studentId || undefined"
           :draggable="!!s.studentId && !batchMode"
           @dragstart="onDragStart($event, s)" @dragover.prevent @drop="onDrop($event, s)"
           @pointerdown="onSeatDown($event, s)" @pointerup="onSeatUp($event)">
        <template v-if="s.studentId">
          <span class="seat-name-v">{{ s.name }}</span>
          <div class="seat-pet">
            <el-checkbox v-if="batchMode" class="pick-box" :model-value="selected.has(s.studentId)"
              @click.stop @change="togglePick(s.studentId)" />
            <!-- 当日状态：小乌云下雨 / 雪花 / 碎蛋 / 今日之星彩虹 -->
            <span v-if="petOf(s)?.dizzy" class="wx-cloud">☁️<i class="wx-drop">💧</i></span>
            <template v-if="petOf(s)?.frozen">
              <i v-for="n in 3" :key="'fz'+n" class="wx-snow" :style="snowStyle(n)">❄️</i>
            </template>
            <span v-if="petOf(s)?.broken" class="seat-flag flag-bk">🩹</span>
            <span v-if="petOf(s)?.starToday" class="wx-star">🌈</span>
            <span v-if="petOf(s)?.graduatedCount > 0" class="seat-flag flag-grad">🎓{{ petOf(s).graduatedCount }}</span>
            <!-- Lv4/5 常驻光环 + Lv5 闪点 -->
            <span v-if="petOf(s)?.petId && petOf(s).level >= 4" class="wx-aura" :class="{ lv5: petOf(s).level >= 5 }"><i class="aura-rot"></i></span>
            <template v-if="petOf(s)?.petId && petOf(s).level >= 5">
              <i v-for="n in 3" :key="'sp'+n" class="wx-spark" :style="sparkStyle(n)"></i>
            </template>
            <!-- 孵化中蛋：暖黄呼吸光晕 + 浮动（egg），点击左右摇晃（egg-wiggle）；蛋不挂 frozen/broken 特效 -->
            <img class="seat-pet-img"
              :class="[{ alive: petOf(s)?.petId && petOf(s)?.hatched !== 0,
                         egg: petOf(s)?.petId && petOf(s)?.hatched === 0,
                         'egg-wiggle': eggWiggle.has(s.studentId),
                         frozen: petOf(s)?.frozen, broken: petOf(s)?.broken }, popClass(s.studentId)]"
              :src="petImg(petOf(s))" alt="宠物">
            <span v-if="petOf(s)?.petId && petOf(s)?.hatched === 0" class="egg-spark">✨</span>
            <span v-for="f in floatsOf(s.studentId)" :key="f.id" class="float-score"
              :class="f.good ? 'good' : 'bad'">{{ f.text }}
              <i v-for="(e, ei) in f.ems" :key="ei" class="float-em"
                 :class="e.cls" :style="{ '--dx': e.dx + 'px', animationDelay: e.delay + 'ms' }">{{ e.emoji }}</i>
            </span>
          </div>
        </template>
        <template v-else><div class="empty">— 空 —</div></template>
      </div>
    </div>

    <!-- 未排座学生：宠物卡形式，功能与座位上一致（领养/评价/批量勾选） -->
    <div v-if="unSeatedPets.length" class="unseat-wrap">
      <div class="unseat-title">🐾 未排座学生（{{ unSeatedPets.length }} 人，宠物仍可照料）</div>
      <div class="pet-grid">
        <div v-for="p in unSeatedPets" :key="p.studentId" class="pet-card"
          :class="{ frozen: p.frozen, broken: p.broken, picked: selected.has(p.studentId), 'star-today': p.starToday }"
          :data-sid="p.studentId"
          @click="onCardClick(p)">
          <el-checkbox v-if="batchMode" class="pick-box" :model-value="selected.has(p.studentId)"
            @click.stop @change="togglePick(p.studentId)" />
          <!-- 当日状态：小乌云下雨 / 雪花 / 碎蛋 / 今日之星彩虹 -->
          <span v-if="p.dizzy" class="wx-cloud"><i class="wx-drop">💧</i>☁️</span>
          <template v-if="p.frozen">
            <i v-for="n in 3" :key="'fz'+n" class="wx-snow" :style="snowStyle(n)">❄️</i>
          </template>
          <span v-if="p.broken" class="flag flag-broken">🩹</span>
          <span v-if="p.starToday" class="wx-star">🌈</span>
          <span v-if="p.graduatedCount > 0" class="flag flag-grad">🎓{{ p.graduatedCount }}</span>

          <div class="pet-img-wrap">
            <span v-if="p.petId && p.level >= 4" class="wx-aura" :class="{ lv5: p.level >= 5 }"><i class="aura-rot"></i></span>
            <template v-if="p.petId && p.level >= 5">
              <i v-for="n in 3" :key="'sp'+n" class="wx-spark" :style="sparkStyle(n)"></i>
            </template>
            <!-- 孵化中蛋：暖黄呼吸光晕 + 浮动（egg），点击左右摇晃（egg-wiggle） -->
            <img class="pet-img"
              :class="[{ alive: p.petId && p.hatched !== 0, egg: p.petId && p.hatched === 0,
                         'egg-wiggle': eggWiggle.has(p.studentId),
                         frozen: p.frozen, broken: p.broken }, popClass(p.studentId)]"
              :src="petImg(p)" alt="宠物">
            <span v-if="p.petId && p.hatched === 0" class="egg-spark">✨</span>
            <span v-for="f in floatsOf(p.studentId)" :key="f.id" class="float-score"
              :class="f.good ? 'good' : 'bad'">{{ f.text }}
              <i v-for="(e, ei) in f.ems" :key="ei" class="float-em"
                 :class="e.cls" :style="{ '--dx': e.dx + 'px', animationDelay: e.delay + 'ms' }">{{ e.emoji }}</i>
            </span>
          </div>

          <template v-if="p.petId">
            <div class="pet-name">{{ p.petName }}
              <span v-if="p.hatched === 0" class="lv-badge egg-badge">🥚 孵化中</span>
              <span v-else class="lv-badge">Lv.{{ p.level }}</span>
            </div>
            <!-- 蛋态：隐藏等级与进度条，只留孵化提示（成长值照常累计，破壳时结算） -->
            <template v-if="p.hatched === 0">
              <div class="exp-text egg-tip">明天破壳，成长值照常累计～</div>
            </template>
            <template v-else>
              <div class="exp-bar"><div class="exp-fill" :style="{ width: expPct(p) + '%' }"></div></div>
              <div class="exp-text">{{ p.level >= 5 ? '✨ 已满级，可毕业！' : `成长值 ${p.exp} / ${p.expTarget}` }}</div>
            </template>
          </template>
          <template v-else>
            <div class="pet-name egg-name">神秘精灵蛋</div>
            <el-button size="small" type="primary" round @click.stop="openAdopt(p)">🥚 领养精灵</el-button>
          </template>
          <div class="stu-name">{{ p.name }}</div>
        </div>
      </div>
    </div>

    <!-- 领养弹窗：选种族（内置+自创皮肤）→ 预览 6 阶段 → 起名 -->
    <el-dialog v-model="adoptDlg" title="🥚 领养守护精灵" width="620px" align-center>
      <div class="skin-pick">
        <div v-for="sk in skins" :key="sk.skinKey" class="skin-card"
             :class="{ active: adoptSkin === sk.skinKey }" @click="adoptSkin = sk.skinKey">
          <img :src="skinImg(sk.skinKey, 5)" :alt="sk.name">
          <div class="skin-card-name">{{ sk.name }}</div>
          <i class="skin-card-dot" :style="{ background: FX_THEMES[sk.fxTheme]?.beam || '#FFD98A' }"></i>
          <span v-if="!sk.builtIn" class="skin-card-del" title="删除该自创皮肤" @click.stop="deleteSkin(sk)">🗑</span>
        </div>
        <div class="skin-card skin-add" @click="openSkinMaker">
          <div class="skin-add-plus">➕</div>
          <div class="skin-card-name">自创宠物</div>
        </div>
      </div>
      <div class="skin-preview">
        <div v-for="st in SKIN_STAGES" :key="st.k" class="skin-preview-item">
          <img :src="skinImg(adoptSkin, st.k)" alt="">
          <span>{{ st.label }}</span>
        </div>
      </div>
      <div class="adopt-body">
        <p class="adopt-tip">一颗温热的精灵蛋正在微微发光……<br>🥚 领养后是一颗神秘蛋，<b>明天</b>就会破壳出生哦！<br>给Ta起个名字，一起成长吧！</p>
        <el-input v-model="adoptName" maxlength="12" placeholder="给宠物起个中文名，如：云小龙" />
      </div>
      <template #footer>
        <el-button round @click="adoptDlg = false">再想想</el-button>
        <el-button type="primary" round @click="doAdopt">🥚 破壳领养</el-button>
      </template>
    </el-dialog>

    <!-- 宠物详情弹窗（蛋态：显示蛋与孵化提示，隐藏等级/进度，加分照常） -->
    <el-dialog v-model="detailDlg"
      :title="detailStudent?.hatched === 0 ? `🥚 ${detailStudent?.petName || '神秘蛋'}（孵化中）` : `🐉 ${detailStudent?.petName || '宠物详情'}`"
      width="460px" align-center>
      <div v-if="detailStudent" class="detail-body">
        <div class="detail-head">
          <img class="detail-img" :class="{ egg: detailStudent.hatched === 0 }" :src="petImg(detailStudent)" alt="宠物">
          <div class="detail-info">
            <div class="detail-name">{{ detailStudent.petName }}
              <span v-if="detailStudent.hatched === 0" class="lv-badge big egg-badge">🥚 孵化中</span>
              <span v-else class="lv-badge big">Lv.{{ detailStudent.level }}</span>
            </div>
            <!-- 蛋态：孵化提示代替等级/徽章/成长值（破壳后按累计成长值一次性结算） -->
            <div v-if="detailStudent.hatched === 0" class="detail-line egg-tip">
              🥚 蛋蛋正在吸收能量，<b>明天</b>就会破壳出生！<br>这期间加分照常累计哦～
            </div>
            <template v-else>
              <div class="detail-line">🎓 毕业徽章：<b>{{ detailStudent.graduatedCount || 0 }}</b> 枚
                <span v-for="n in detailStudent.graduatedCount" :key="n" class="badge-medal">🎓</span>
              </div>
              <div class="detail-line">⭐ 累计成长值：<b>{{ detailStudent.exp }}</b>
                <span v-if="detailStudent.level < 5">（距 Lv.{{ detailStudent.level + 1 }} 还需 {{ detailStudent.expTarget - detailStudent.exp }}）</span>
                <span v-else>已满级</span>
              </div>
            </template>
          </div>
        </div>

        <!-- 成长进度条（蛋态隐藏；座位格上不显示等级/进度，统一收进详情） -->
        <div v-if="detailStudent.hatched !== 0" class="detail-exp">
          <div class="detail-exp-fill" :style="{ width: expPct(detailStudent) + '%' }"></div>
        </div>

        <div class="log-title">📖 最近成长流水</div>
        <div class="log-list">
          <div v-for="l in detailLogs" :key="l.id" class="log-row">
            <span class="log-score" :class="l.score > 0 ? 'good' : 'bad'">
              {{ l.score > 0 ? '+' : '' }}{{ l.score }}
            </span>
            <span class="log-name">{{ l.remark || l.ruleName || sourceText(l.source) }}</span>
            <span class="log-time">{{ fmtTime(l.createdAt) }}</span>
            <el-button size="small" text type="danger" @click="revokeLog(l.id)">撤回</el-button>
          </div>
          <div v-if="!detailLogs.length" class="log-empty">还没有成长记录，快给Ta第一次评价吧～</div>
        </div>
      </div>
      <template #footer>
        <el-button v-if="detailStudent?.level >= 5" type="warning" round @click="doGraduate">🎓 毕业</el-button>
        <el-button type="primary" round @click="openEvalSingle(detailStudent)">🌟 评价加分</el-button>
      </template>
    </el-dialog>

    <!-- 评价弹窗（单人/批量共用） -->
    <el-dialog v-model="evalDlg" :title="evalBatch ? `📝 批量评价（${selected.size} 人）` : '🌟 给宠物评价'"
      width="560px" align-center>
      <el-tabs v-model="evalCat">
        <el-tab-pane v-for="c in CATS" :key="c" :label="c" :name="c">
          <div class="rule-grid">
            <button v-for="r in rulesOfCat(c)" :key="r.id" class="rule-card"
              :class="r.score > 0 ? 'plus' : 'minus'" @click="applyRule(r)">
              <span class="rule-icon">{{ r.icon || '✨' }}</span>
              <span class="rule-name">{{ r.name }}</span>
              <span class="rule-score">{{ r.score > 0 ? '+' : '' }}{{ r.score }}</span>
            </button>
          </div>
          <div v-if="!rulesOfCat(c).length" class="cat-empty">该分类暂无评价项，可在「⚙ 评价项」中添加</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 毕业仪式弹窗 -->
    <el-dialog v-model="graduateDlg" width="420px" align-center :show-close="false" class="graduate-dialog">
      <div class="graduate-stage">
        <div class="graduate-glow"></div>
        <div class="graduate-emoji">🎓✨</div>
        <div class="graduate-title">圆满毕业！</div>
        <p class="graduate-text">
          「<b>{{ gradStudent?.petName }}</b>」陪伴 <b>{{ gradStudent?.name }}</b> 走完了一段成长旅程，<br>
          获得 <b>毕业徽章 🎓 ×{{ (gradStudent?.graduatedCount || 0) + 1 }}</b>！<br>
          新的精灵蛋已经在窝里，继续出发吧～
        </p>
        <el-button type="warning" round size="large" @click="graduateDlg = false">太棒了 🌟</el-button>
      </div>
    </el-dialog>

    <!-- 荣誉榜弹窗 -->
    <el-dialog v-model="rankDlg" title="🏆 宠物荣誉榜" width="520px" align-center>
      <div class="rank-list">
        <div v-for="r in ranking" :key="r.studentId" class="rank-row"
          :class="{ top3: r.rank && r.rank <= 3, unranked: !r.rank }">
          <span class="rank-medal">
            {{ r.rank === 1 ? '🥇' : r.rank === 2 ? '🥈' : r.rank === 3 ? '🥉' : (r.rank ? r.rank : '—') }}
          </span>
          <img class="rank-img" :src="petImg(r)" alt="">
          <span class="rank-pet">{{ r.petName }}</span>
          <span class="rank-stu">{{ r.name }}</span>
          <span class="rank-lv">Lv.{{ r.level }}</span>
          <span class="rank-badges">🎓{{ r.badges }}</span>
          <span class="rank-total">⭐{{ r.total }}</span>
        </div>
      </div>
    </el-dialog>

    <!-- 小商店弹窗 -->
    <el-dialog v-model="shopDlg" title="🛍 宠物小商店" width="600px" align-center>
      <el-tabs v-model="shopTab">
        <!-- 兑换 -->
        <el-tab-pane label="🎁 兑换礼物" name="exchange">
          <div class="shop-stu-pick">
            为谁兑换：
            <el-select v-model="exchangeSid" placeholder="选择学生" style="width: 180px">
              <el-option v-for="p in pets.filter(x => x.petId)" :key="p.studentId"
                :label="`${p.name}（${p.petName} · ⭐${p.exp}）`" :value="p.studentId" />
            </el-select>
          </div>
          <div class="shop-grid">
            <div v-for="it in shopItems" :key="it.id" class="shop-card">
              <div class="shop-icon">{{ it.icon || '🎁' }}</div>
              <div class="shop-name">{{ it.name }}</div>
              <div class="shop-price">⭐{{ it.price }}</div>
              <div class="shop-stock">{{ it.stock < 0 ? '库存充足' : `剩 ${it.stock} 件` }}</div>
              <el-button size="small" round type="primary" :disabled="it.stock === 0" @click="doExchange(it)">
                兑换
              </el-button>
            </div>
            <div v-if="!shopItems.length" class="cat-empty">还没有商品，去「📦 商品与记录」添加吧</div>
          </div>
        </el-tab-pane>
        <!-- 商品管理 + 兑换记录 -->
        <el-tab-pane label="📦 商品与记录" name="manage">
          <div class="manage-title">商品管理</div>
          <div class="item-form">
            <el-input v-model="itemForm.icon" maxlength="2" style="width: 64px" placeholder="🎁" />
            <el-input v-model="itemForm.name" maxlength="20" style="width: 150px" placeholder="商品名称" />
            <el-input-number v-model="itemForm.price" :min="1" :max="280" controls-position="right" style="width: 110px" />
            <el-input-number v-model="itemForm.stock" :min="-1" controls-position="right" style="width: 120px" />
            <el-button type="primary" round @click="saveItem">上架</el-button>
          </div>
          <div class="form-hint">价格=成长值；库存填 -1 表示不限量</div>
          <div v-for="it in shopItems" :key="'m'+it.id" class="item-row">
            <span class="shop-icon-sm">{{ it.icon || '🎁' }}</span>
            <span class="item-name">{{ it.name }}</span>
            <span class="item-meta">⭐{{ it.price }} · {{ it.stock < 0 ? '不限量' : `库存 ${it.stock}` }}</span>
            <el-button size="small" text type="danger" @click="deleteItem(it.id)">下架</el-button>
          </div>

          <div class="manage-title" style="margin-top: 16px">兑换记录</div>
          <div v-for="e in exchanges" :key="e.id" class="ex-row">
            <span>{{ e.itemIcon || '🎁' }} {{ e.itemName }}</span>
            <span class="ex-stu">{{ e.studentName }}</span>
            <span class="ex-cost">-⭐{{ e.cost }}</span>
            <span class="ex-time">{{ fmtTime(e.createdAt) }}</span>
            <el-tag v-if="e.status === '待兑现'" type="warning" size="small">待兑现</el-tag>
            <el-tag v-else type="success" size="small">已兑现</el-tag>
            <el-button v-if="e.status === '待兑现'" size="small" round type="success"
              @click="fulfill(e.id)">兑现</el-button>
          </div>
          <div v-if="!exchanges.length" class="cat-empty">还没有兑换记录</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 评价项管理弹窗 -->
    <el-dialog v-model="rulesDlg" title="⚙ 评价项管理" width="580px" align-center>
      <el-tabs v-model="ruleCat">
        <el-tab-pane v-for="c in CATS" :key="c" :label="c" :name="c">
          <div class="item-form">
            <el-input v-model="ruleForm.icon" maxlength="2" style="width: 64px" placeholder="✨" />
            <el-input v-model="ruleForm.name" maxlength="20" style="width: 160px" placeholder="评价项名称" />
            <el-input-number v-model="ruleForm.score" :min="-10" :max="10" controls-position="right" style="width: 120px" />
            <el-button type="primary" round @click="saveRule(c)">添加</el-button>
          </div>
          <div class="form-hint">正分=奖励成长值，负分=扣减（扣分不会降级）</div>
          <div v-for="r in rulesOfCat(c)" :key="r.id" class="item-row">
            <span class="shop-icon-sm">{{ r.icon || '✨' }}</span>
            <span class="item-name">{{ r.name }}</span>
            <span class="item-meta" :class="r.score > 0 ? 'good' : 'bad'">
              {{ r.score > 0 ? '+' : '' }}{{ r.score }}
            </span>
            <el-switch :model-value="r.enabled === 1 || r.enabled === true" size="small" @change="v => toggleRule(r, v)" />
            <el-button size="small" text type="danger" @click="deleteRule(r.id)">删除</el-button>
          </div>
          <div v-if="!rulesOfCat(c).length" class="cat-empty">该分类暂无评价项</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 自创宠物皮肤弹窗 -->
    <SkinMakerDlg v-model="skinMakerDlg" @saved="onSkinSaved" />
    <!-- 全屏进化演出（升级时 6 秒分镜，点击跳过） -->
    <EvolutionShow :scene="currentEvolve" @done="onEvolveDone" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Delete } from '@element-plus/icons-vue'
import http from '../api/http'
import { usePets, PET_CATS } from '../composables/usePets'
import { onRemoteCmd } from '../composables/useRemote'
import EvolutionShow from '../components/EvolutionShow.vue'
import SkinMakerDlg from '../components/SkinMakerDlg.vue'

/** 评价项分类（固定四个 tab） */
const CATS = PET_CATS

/** 皮肤预览的 6 个阶段（蛋 + Lv1~5） */
const SKIN_STAGES = [
  { k: 'egg', label: '蛋' },
  { k: 1, label: 'Lv1' }, { k: 2, label: 'Lv2' }, { k: 3, label: 'Lv3' },
  { k: 4, label: 'Lv4' }, { k: 5, label: 'Lv5' }
]

// ==================== 座次表 ====================
const rows = ref(7)
const cols = ref(8)
const aisleModel = reactive([2, 4, 6])
const seats = ref([])
let dragFrom = null

const aisles = computed(() =>
  [...new Set(aisleModel.filter(x => x > 0))].sort((a, b) => a - b))

// 过道在网格中的显示列号：逻辑列 c 之后插入，显示列 = c + 前面过道数 + 1（过道占该列）
const totalDisplayCols = computed(() => cols.value + aisles.value.length)
// 棋盘网格：左侧多一列行名（40px）、顶部多一行列名（30px），座位坐标整体 +1
const boardGridStyle = computed(() => ({
  gridTemplateColumns: `40px repeat(${totalDisplayCols.value}, minmax(0, 1fr))`,
  gridTemplateRows: `30px repeat(${rows.value}, auto)`
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

// 拖拽交换座位；dragstart 时清除点击判定，避免松手误开宠物弹窗
function onDragStart(e, s) {
  dragFrom = s
  downPos = null
  e.dataTransfer.setData('text', s.row + '-' + s.col)
}
async function onDrop(e, target) {
  if (!dragFrom) return
  if (dragFrom.row === target.row && dragFrom.col === target.col) return
  await http.post('/seat/move', {
    fromRow: dragFrom.row, fromCol: dragFrom.col, toRow: target.row, toCol: target.col
  })
  dragFrom = null
  await loadGrid()
}

// ==================== 宠物园（逻辑复用 usePets） ====================
const {
  pets, skins, selected, batchMode, floats, popKind, eggWiggle,
  adoptDlg, detailDlg, evalDlg, graduateDlg, rankDlg, shopDlg, rulesDlg, skinMakerDlg,
  adoptName, adoptSkin, detailStudent, detailLogs, evalBatch, evalCat,
  gradStudent, ranking, shopTab, shopItems, exchanges, exchangeSid, itemForm, ruleCat, ruleForm,
  currentEvolve, onEvolveDone,
  petImg, skinImg, expPct, floatsOf, fmtTime, sourceText, rulesOfCat, FX_THEMES,
  onCardClick, togglePick, toggleBatch,
  openAdopt, openSkinMaker, onSkinSaved, deleteSkin, doAdopt, revokeLog,
  openEvalSingle, openEvalBatch, applyRule, doGraduate,
  openRanking, openShop, doExchange, fulfill, saveItem, deleteItem,
  openRules, saveRule, toggleRule, deleteRule,
  loadPets
} = usePets()

// 手机遥控：手机端加减分/兑换等写操作后服务器广播 pets.changed，PC 静默重拉宠物状态
// （遥控在线期间 loadPets 的 pending 进化攒入待播列表，不打断投影；spotlight 高亮样式见 .remote-spot）
let offRemote = null
onMounted(() => {
  offRemote = onRemoteCmd((action) => {
    if (action === 'pets.changed') loadPets()
  })
})
onUnmounted(() => offRemote?.())

/** 取座位上学生对应的宠物状态（/pet/pets 返回全班，含未领养） */
function petOf(s) {
  return pets.value.find(p => p.studentId === s.studentId)
}
/** 评价后宠物图 Q 弹（加分）/ 缩一下（扣分）的 class */
function popClass(sid) {
  const k = popKind.value.get(sid)
  return k === 'pop' ? 'pet-pop' : k === 'shrink' ? 'pet-shrink' : ''
}
/** frozen 状态 3 片 ❄️ 的错峰位置/延迟 */
function snowStyle(n) {
  return { left: [16, 50, 80][n - 1] + '%', animationDelay: [0, 1.1, 2.0][n - 1] + 's' }
}
/** Lv5 周身 3 个 ✨ 闪点的错峰位置/延迟 */
function sparkStyle(n) {
  return { left: [10, 72, 44][n - 1] + '%', top: [10, 18, -4][n - 1] + '%', animationDelay: [0, 0.7, 1.4][n - 1] + 's' }
}
/** 已排座学生 id 集合 */
const seatedIds = computed(() => new Set(seats.value.filter(s => s.studentId).map(s => s.studentId)))
/** 未排座学生（在网格下方以宠物卡形式照料） */
const unSeatedPets = computed(() => pets.value.filter(p => !seatedIds.value.has(p.studentId)))

// 点击与拖拽区分：按下记录位置，抬起时位移 >6px 视为拖拽，否则视为点击查看/领养宠物
let downPos = null
function onSeatDown(e, s) {
  downPos = { x: e.clientX, y: e.clientY, s }
}
function onSeatUp(e) {
  if (!downPos) return
  const { x, y, s } = downPos
  downPos = null
  if (Math.hypot(e.clientX - x, e.clientY - y) > 6) return
  if (!s.studentId) return
  onCardClick(petOf(s))
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
/* 座位棋盘：左列行名、顶行列名 */
.board-grid { display: grid; gap: 10px; }
.board-corner { grid-row: 1; grid-column: 1; }
.col-label {
  display: flex; align-items: center; justify-content: center;
  font-size: 12px; font-weight: 800; color: #a08a78;
}
.col-label.aisle-label { color: #c3b8a8; font-weight: 700; }
.row-label {
  display: flex; align-items: center; justify-content: center;
  writing-mode: vertical-rl; text-orientation: upright; letter-spacing: 3px;
  font-size: 12px; font-weight: 800; color: #a08a78;
}
.aisle-gutter { min-width: 18px; }

/* 座位卡：格子内只放宠物，名字竖排贴左 */
.seat {
  position: relative; border-radius: 12px; padding: 6px 4px; text-align: center; min-height: 168px;
  cursor: grab; display: flex; align-items: center; justify-content: center;
}
.seat.boy { background: #e3eefc; border: 2px solid #b9d6f7; color: #3a6ea5; }
.seat.girl { background: #fde4ee; border: 2px solid #f7c3d8; color: #c2568a; }
.seat.empty-seat { background: #f7f8fc; border: 2px dashed #e3e6f0; color: #c0c4d0; display: flex; align-items: center; justify-content: center; }
/* 手机遥控 spotlight「找人」：目标座位金色脉冲 3 秒 */
.seat.remote-spot { animation: remote-spot 1s ease-in-out 3; z-index: 5; }
@keyframes remote-spot {
  0%, 100% { box-shadow: 0 0 0 0 rgba(255,180,60,0); transform: scale(1); }
  50% { box-shadow: 0 0 0 6px rgba(255,180,60,.55), 0 0 26px rgba(255,180,60,.85); transform: scale(1.04); }
}
.seat-name-v {
  position: absolute; left: 3px; top: 50%; transform: translateY(-50%);
  writing-mode: vertical-rl; text-orientation: upright; letter-spacing: 2px;
  font-size: 14px; font-weight: 800; z-index: 2;
}
.seat-pet { position: relative; width: 100%; display: flex; align-items: center; justify-content: center; }
.seat-pet-img { width: 96px; height: 96px; object-fit: contain; }
.seat-pet-img.alive { animation: pet-breathe 3.2s ease-in-out infinite; filter: drop-shadow(0 4px 6px rgba(220,160,110,.3)); }
.seat-pet-img:not(.alive) { filter: grayscale(.35) opacity(.8); }
.seat-pet-img.frozen { filter: hue-rotate(170deg) saturate(1.3) brightness(1.05) drop-shadow(0 0 6px rgba(120,190,255,.8)); }
.seat-pet-img.broken { filter: grayscale(.9) contrast(.92); }
/* 孵化中蛋：暖黄呼吸光晕 + 轻轻上下浮动（座位格） */
.seat-pet-img.egg {
  animation: egg-float 2.4s ease-in-out infinite;
  filter: drop-shadow(0 0 8px rgba(255, 214, 130, .9)) drop-shadow(0 0 20px rgba(255, 190, 90, .5));
}
@keyframes egg-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-5px); }
}
/* 点击蛋：左右摇晃 0.5s（覆盖浮动动画，结束后自动恢复） */
.seat-pet-img.egg-wiggle { animation: egg-click-wiggle .5s ease-in-out; }
@keyframes egg-click-wiggle {
  0%, 100% { transform: rotate(0); }
  20% { transform: rotate(-14deg); }
  40% { transform: rotate(12deg); }
  60% { transform: rotate(-9deg); }
  80% { transform: rotate(6deg); }
}
/* 蛋右上角小 ✨（明暗闪烁） */
.egg-spark {
  position: absolute; top: 4px; right: 10px; font-size: 15px; z-index: 2;
  pointer-events: none; animation: egg-spark-twinkle 1.6s ease-in-out infinite;
}
@keyframes egg-spark-twinkle {
  0%, 100% { opacity: .3; transform: scale(.8) rotate(-8deg); }
  50% { opacity: 1; transform: scale(1.2) rotate(8deg); }
}
/* 「孵化中」徽章与提示文案 */
.lv-badge.egg-badge { background: linear-gradient(135deg, #ffe3a3, #ffc879); color: #8a5a34; }
.egg-tip { color: #c08a4a; font-weight: 700; }
.detail-img.egg {
  animation: egg-float 2.4s ease-in-out infinite;
  filter: drop-shadow(0 0 14px rgba(255, 214, 130, .9)) drop-shadow(0 0 28px rgba(255, 190, 90, .45));
}
/* Lv5 金描边（.wx-aura.lv5 与 img 为兄弟节点） */
.wx-aura.lv5 ~ img { filter: drop-shadow(0 0 6px rgba(255,210,120,.95)) drop-shadow(0 4px 6px rgba(220,160,110,.3)); }
.seat-flag { position: absolute; top: -6px; right: 16px; font-size: 16px; z-index: 2; }
.seat-flag.flag-bk { top: 32px; right: 16px; }
.seat-flag.flag-grad {
  right: 8px; top: -8px; background: linear-gradient(135deg,#ffe28a,#f2a93a); color: #fff;
  border-radius: 9px; padding: 0 5px; font-size: 10px; font-weight: 800;
}
.empty { font-size: 14px; }

/* 未排座学生宠物卡区 */
.unseat-wrap { margin-top: 24px; }
.unseat-title { font-weight: 800; color: #8a5a34; margin-bottom: 12px; font-size: 15px; }
.pet-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 16px;
}
.pet-card {
  position: relative; border-radius: 20px; padding: 14px 12px 12px; text-align: center;
  background: linear-gradient(150deg, #fff7ec, #ffe9d2);
  border: 3px solid #fff; cursor: pointer; user-select: none;
  box-shadow: 0 6px 16px rgba(230,170,120,.22);
  transition: transform .15s, box-shadow .15s;
}
.pet-card:hover { transform: translateY(-4px); box-shadow: 0 12px 24px rgba(230,170,120,.32); }
.pet-card.picked { border-color: #ff9f5a; box-shadow: 0 0 0 3px rgba(255,159,90,.35); }
.pick-box { position: absolute; top: 10px; left: 10px; z-index: 3; }
.flag { position: absolute; top: 8px; z-index: 2; font-size: 18px; }
.flag-broken { left: 10px; bottom: 46px; }
.flag-grad { right: 10px; background: linear-gradient(135deg,#ffe28a,#f2a93a); color: #fff;
  border-radius: 10px; padding: 1px 7px; font-size: 12px; font-weight: 800; }
.pet-img-wrap { position: relative; height: 118px; display: flex; align-items: center; justify-content: center; }
.pet-img { width: 104px; height: 104px; object-fit: contain; }
.pet-img.alive { animation: pet-breathe 3.2s ease-in-out infinite; filter: drop-shadow(0 6px 8px rgba(220,160,110,.3)); }
.pet-img:not(.alive) { filter: grayscale(.35) opacity(.8); }
.pet-card.frozen { background: linear-gradient(150deg, #eaf6ff, #d4ebff); border-color: #bfe0ff; }
.pet-card.frozen .pet-img { filter: hue-rotate(170deg) saturate(1.3) brightness(1.05) drop-shadow(0 0 8px rgba(120,190,255,.8)); }
.pet-card.broken .pet-img { filter: grayscale(.9) contrast(.92); }
/* 蛋态暖光（高特异性压过 :not(.alive)/frozen/broken 的 img filter；蛋本身不挂这些状态） */
.pet-card .pet-img.egg {
  animation: egg-float 2.4s ease-in-out infinite;
  filter: drop-shadow(0 0 10px rgba(255, 214, 130, .9)) drop-shadow(0 0 22px rgba(255, 190, 90, .5));
}
.pet-card .pet-img.egg-wiggle { animation: egg-click-wiggle .5s ease-in-out; }
.pet-card.broken::after {
  content: ''; position: absolute; inset: 8px; border-radius: 16px; pointer-events: none;
  background: repeating-linear-gradient(45deg, transparent 0 14px, rgba(120,120,140,.12) 14px 15px);
}
.pet-name { font-size: 15px; font-weight: 800; color: #8a5a34; margin-top: 2px; }
.egg-name { color: #b08a66; }
.lv-badge {
  display: inline-block; margin-left: 6px; background: linear-gradient(135deg,#ffb38a,#ff9f5a);
  color: #fff; font-size: 11px; border-radius: 9px; padding: 1px 8px; font-weight: 800; vertical-align: middle;
}
.lv-badge.big { font-size: 13px; padding: 2px 10px; }
.stu-name { font-size: 13px; color: #a07a5c; margin-top: 5px; font-weight: 700; }
.exp-bar { height: 9px; border-radius: 6px; background: #ffe2c8; margin: 7px 10px 3px; overflow: hidden; }
.exp-fill { height: 100%; border-radius: 6px; background: linear-gradient(90deg, #ffd98a, #ff9f5a); transition: width .4s; }
.exp-text { font-size: 11px; color: #b48a68; }

/* 飘字：加分金色、扣分灰红；内含迸溅 emoji（.float-em） */
.float-score {
  position: absolute; left: 50%; top: 20px; font-size: 22px; font-weight: 900; pointer-events: none;
  animation: float-up 1.2s ease-out forwards; z-index: 5; white-space: nowrap;
}
.float-score.good { color: #f2991f; text-shadow: 0 2px 0 #fff, 0 0 12px rgba(255,200,90,.6); }
.float-score.bad { color: #c26a6a; text-shadow: 0 2px 0 #fff; }
.float-em {
  position: absolute; left: 50%; top: 0; font-style: normal; font-size: 16px; pointer-events: none;
  animation: em-burst 1.1s ease-out forwards;
}
/* 兑换金币：抛物线蹦出 */
.float-em.coin { font-size: 20px; animation: coin-arc 1.2s ease-out forwards; }
@keyframes em-burst {
  0% { opacity: 0; transform: translate(0, 0) scale(.4); }
  25% { opacity: 1; }
  100% { opacity: 0; transform: translate(var(--dx), -42px) scale(1.15); }
}
@keyframes coin-arc {
  0% { opacity: 0; transform: translate(0, 12px) scale(.5); }
  20% { opacity: 1; }
  60% { opacity: 1; transform: translate(calc(var(--dx) * .6), -48px) scale(1.1); }
  100% { opacity: 0; transform: translate(var(--dx), -16px) scale(.9); }
}

/* 批量浮动条 */
.batch-bar {
  position: sticky; top: 0; z-index: 20; margin-bottom: 14px;
  background: linear-gradient(135deg, #fff0e0, #ffe3cc); border: 2px solid #ffcf9f;
  border-radius: 16px; padding: 10px 18px; display: flex; align-items: center; gap: 14px;
  box-shadow: 0 6px 16px rgba(230,170,120,.3); font-weight: 700; color: #8a5a34;
}
.pick-num { color: #ff7f32; font-size: 18px; }
.bar-enter-active, .bar-leave-active { transition: all .25s; }
.bar-enter-from, .bar-leave-to { opacity: 0; transform: translateY(-10px); }

/* 领养弹窗 */
.adopt-body { text-align: center; padding: 6px 10px; }
.adopt-tip { color: #a07a5c; font-size: 14px; line-height: 1.7; margin: 8px 0 14px; }

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

/* 评价项卡片 */
.rule-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }
.rule-card {
  border: 2px solid #fff; border-radius: 16px; padding: 12px 8px; cursor: pointer;
  display: flex; flex-direction: column; align-items: center; gap: 4px; transition: transform .12s;
  background: #f6f7fb;
}
.rule-card:hover { transform: scale(1.05); }
.rule-card.plus { background: linear-gradient(150deg, #e9f9f0, #d8f3e4); }
.rule-card.minus { background: linear-gradient(150deg, #fdeeee, #fbdfdf); }
.rule-icon { font-size: 26px; }
.rule-name { font-size: 13px; font-weight: 700; color: #6b5b73; text-align: center; min-height: 36px; display: flex; align-items: center; }
.rule-score { font-size: 14px; font-weight: 900; border-radius: 9px; padding: 0 8px; color: #fff; }
.rule-card.plus .rule-score { background: #5cbb8c; }
.rule-card.minus .rule-score { background: #ef7f7f; }
.cat-empty { text-align: center; color: #b8a8b0; padding: 24px 0; font-size: 13px; }

/* 毕业仪式 */
.graduate-stage { text-align: center; padding: 20px 12px 14px; position: relative; overflow: hidden; }
.graduate-glow {
  position: absolute; top: -60px; left: 50%; transform: translateX(-50%);
  width: 320px; height: 320px; border-radius: 50%; pointer-events: none;
  background: radial-gradient(circle, rgba(255,220,120,.75) 0%, rgba(255,200,90,.25) 45%, transparent 70%);
  animation: glow-pulse 2s ease-in-out infinite;
}
.graduate-emoji { font-size: 64px; position: relative; animation: pet-breathe 2.4s ease-in-out infinite; }
.graduate-title {
  font-size: 26px; font-weight: 900; margin: 8px 0; position: relative;
  background: linear-gradient(135deg, #f2a93a, #e07b2a); -webkit-background-clip: text; background-clip: text; color: transparent;
}
.graduate-text { position: relative; color: #8a6a44; line-height: 1.9; font-size: 14px; }

/* 荣誉榜 */
.rank-list { max-height: 480px; overflow-y: auto; padding-right: 4px; }
.rank-row {
  display: flex; align-items: center; gap: 10px; padding: 9px 12px; border-radius: 14px;
  margin-bottom: 7px; background: #fff7ee; font-size: 14px;
}
.rank-row.top3 { background: linear-gradient(135deg, #fff4d6, #ffe8c2); border: 2px solid #ffd98a; font-weight: 800; }
.rank-row.unranked { opacity: .55; }
.rank-medal { width: 44px; text-align: center; font-size: 17px; font-weight: 900; color: #b08a66; }
.rank-img { width: 38px; height: 38px; object-fit: contain; }
.rank-pet { font-weight: 800; color: #8a5a34; width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.rank-stu { color: #9a7a5c; flex: 1; }
.rank-lv { font-size: 12px; color: #ff7f32; font-weight: 800; }
.rank-badges { font-size: 13px; }
.rank-total { font-weight: 800; color: #d9a52e; width: 64px; text-align: right; }

/* 小商店 */
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
.item-meta.good { color: #4caf7d; }
.item-meta.bad { color: #ef6f6f; }
.ex-stu { font-weight: 800; color: #8a5a34; }
.ex-cost { color: #ef6f6f; font-weight: 800; }
.ex-time { color: #c0a898; font-size: 12px; flex: 1; }

/* 动画 */
@keyframes pet-breathe {
  0%, 100% { transform: translateY(0) rotate(-1.2deg); }
  50% { transform: translateY(-7px) rotate(1.2deg); }
}
/* ===== 天气/状态特效 ===== */
/* 扣分头晕：小乌云漂浮，每 3 秒掉一滴雨 */
.wx-cloud {
  position: absolute; top: 0; right: 4px; font-size: 20px; z-index: 3; pointer-events: none;
  animation: cloud-float 3s ease-in-out infinite;
}
.wx-drop {
  position: absolute; left: 45%; top: 90%; font-style: normal; font-size: 11px;
  animation: rain-drop 3s linear infinite;
}
@keyframes cloud-float { 0%, 100% { transform: translateX(0); } 50% { transform: translateX(-3px); } }
@keyframes rain-drop {
  0% { opacity: 0; transform: translateY(-2px); }
  25% { opacity: 1; }
  75% { opacity: 1; }
  100% { opacity: 0; transform: translateY(18px); }
}
/* 冰冻：3 片雪花飘落（错峰由 snowStyle 内联） */
.wx-snow {
  position: absolute; top: 4px; font-size: 13px; font-style: normal; z-index: 3; pointer-events: none;
  animation: snow-fall 3.2s linear infinite;
}
@keyframes snow-fall {
  0% { opacity: 0; transform: translateY(-4px) rotate(0); }
  20% { opacity: 1; }
  100% { opacity: 0; transform: translateY(30px) rotate(200deg); }
}
/* 今日之星（当日正分合计≥5）：彩虹每 5 秒淡入 2 秒 */
.wx-star {
  position: absolute; top: -8px; left: 4px; font-size: 20px; z-index: 3; pointer-events: none;
  animation: star-rainbow 5s ease-in-out infinite;
}
@keyframes star-rainbow {
  0%, 100% { opacity: 0; transform: scale(.7) rotate(-10deg); }
  40%, 80% { opacity: 1; transform: scale(1.1) rotate(8deg); }
}
/* 今日之星金边呼吸（座位格 / 宠物卡共用） */
.seat.star-today, .pet-card.star-today { animation: star-border 5s ease-in-out infinite; }
@keyframes star-border {
  0%, 100% { box-shadow: 0 0 0 0 rgba(255,205,100,0); }
  50% { box-shadow: 0 0 0 3px rgba(255,205,100,.7), 0 0 22px rgba(255,205,100,.5); }
}
/* Lv4/5 脚下 conic 光环：Lv4 8s 淡，Lv5 5s 亮 */
.wx-aura {
  position: absolute; bottom: 0; left: 50%; width: 106px; height: 28px;
  transform: translateX(-50%); border-radius: 50%; pointer-events: none; z-index: 0;
  opacity: .25; filter: blur(5px);
}
.wx-aura .aura-rot {
  position: absolute; inset: 0; border-radius: 50%;
  background: conic-gradient(from 0deg, transparent 0deg, rgba(255,217,138,.95) 60deg,
    transparent 130deg, transparent 220deg, rgba(255,217,138,.7) 280deg, transparent 340deg);
  animation: aura-spin 8s linear infinite;
}
.wx-aura.lv5 { opacity: .5; width: 114px; }
.wx-aura.lv5 .aura-rot { animation-duration: 5s; }
@keyframes aura-spin { to { transform: rotate(360deg); } }
/* Lv5 周身闪点（::before 承载 ✨，错峰由 sparkStyle 内联） */
.wx-spark {
  position: absolute; font-size: 14px; z-index: 3; pointer-events: none;
  animation: spark-twinkle 2.1s ease-in-out infinite;
}
.wx-spark::before { content: '✨'; }
@keyframes spark-twinkle {
  0%, 100% { opacity: 0; transform: scale(.5) rotate(0); }
  50% { opacity: 1; transform: scale(1.15) rotate(20deg); }
}
/* 评价反馈：加分 Q 弹、扣分缩一下（class 由 popKind 短暂挂载） */
.seat-pet-img.pet-pop, .pet-img.pet-pop { animation: pet-q-pop .43s ease-in-out; }
.seat-pet-img.pet-shrink, .pet-img.pet-shrink { animation: pet-q-shrink .33s ease-in-out; }
@keyframes pet-q-pop {
  0% { transform: scale(1); }
  35% { transform: scale(1.18); }
  70% { transform: scale(.94); }
  100% { transform: scale(1); }
}
@keyframes pet-q-shrink {
  0% { transform: scale(1); }
  40% { transform: scale(.9); }
  100% { transform: scale(1); }
}

/* ===== 领养弹窗：种族横向选择 + 6 阶段预览 ===== */
.skin-pick { display: flex; gap: 10px; overflow-x: auto; padding: 4px 2px 10px; }
.skin-card {
  position: relative; flex: 0 0 86px; border: 2px solid #f0e4d4; border-radius: 14px; background: #fffaf2;
  padding: 8px 4px 6px; text-align: center; cursor: pointer; transition: transform .15s, border-color .15s, box-shadow .15s;
}
.skin-card:hover { transform: translateY(-3px); border-color: #ffc9a3; }
.skin-card.active { border-color: #ff9f5a; background: #fff1e2; box-shadow: 0 0 0 3px rgba(255,159,90,.25); }
.skin-card img { width: 56px; height: 56px; object-fit: contain; }
.skin-card-name { font-size: 12px; font-weight: 800; color: #8a5a34; margin-top: 2px; }
.skin-card-dot {
  position: absolute; top: 6px; right: 8px; width: 10px; height: 10px; border-radius: 50%;
  box-shadow: 0 1px 3px rgba(0,0,0,.25);
}
.skin-card-del { position: absolute; top: 4px; left: 6px; font-size: 13px; }
.skin-add { display: flex; flex-direction: column; align-items: center; justify-content: center; border-style: dashed; color: #c0a898; }
.skin-add-plus { font-size: 30px; line-height: 1; margin: 8px 0 6px; }
.skin-preview {
  display: flex; gap: 6px; justify-content: space-between;
  background: #fff7ee; border-radius: 14px; padding: 10px 8px; margin-bottom: 14px;
}
.skin-preview-item { flex: 1; text-align: center; }
.skin-preview-item img { width: 100%; max-width: 62px; height: 62px; object-fit: contain; }
.skin-preview-item span { display: block; font-size: 11px; color: #b08a66; font-weight: 700; margin-top: 2px; }

@keyframes float-up {
  0% { opacity: 0; transform: translate(-50%, 10px) scale(.8); }
  20% { opacity: 1; }
  100% { opacity: 0; transform: translate(-50%, -46px) scale(1.25); }
}
@keyframes glow-pulse {
  0%, 100% { opacity: .7; transform: translateX(-50%) scale(1); }
  50% { opacity: 1; transform: translateX(-50%) scale(1.12); }
}
</style>
