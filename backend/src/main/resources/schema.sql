-- 班主任工作平台 建库建表脚本（幂等）
CREATE DATABASE IF NOT EXISTS teacher_workspace DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE teacher_workspace;

-- 用户（多老师账号；数据按 user_id 隔离）
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE COMMENT '登录账号',
  password_hash VARCHAR(128) NOT NULL,
  display_name VARCHAR(64) COMMENT '显示名/教师名',
  email VARCHAR(128) COMMENT '邮箱',
  school VARCHAR(128) COMMENT '学校名称',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 学校名录（全校共享的输入记忆，跨账号可见）
CREATE TABLE IF NOT EXISTS school_dict (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(128) NOT NULL UNIQUE,
  use_count INT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 班级（per-user）
CREATE TABLE IF NOT EXISTS class_info (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1 COMMENT '归属账号 sys_user.id',
  name VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL COMMENT 'HOMEROOM 班主任班 / SUBJECT 任课班',
  term VARCHAR(32),
  KEY idx_class_user (user_id)
);

-- 学生（per-user；同一账号内学号唯一）
CREATE TABLE IF NOT EXISTS student (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  stu_no VARCHAR(32) NOT NULL COMMENT '学号',
  name VARCHAR(64) NOT NULL,
  gender VARCHAR(8) DEFAULT '男',
  class_id BIGINT,
  address VARCHAR(255),
  UNIQUE KEY uk_user_stuno (user_id, stu_no),
  KEY idx_stu_user (user_id)
);

-- 家长联系方式（per-user）
CREATE TABLE IF NOT EXISTS parent_contact (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  relation VARCHAR(16) COMMENT '父亲/母亲/其他',
  name VARCHAR(64),
  phone VARCHAR(32),
  KEY idx_pc_user (user_id)
);

-- 科目（per-user）
CREATE TABLE IF NOT EXISTS subject (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(32) NOT NULL,
  teacher VARCHAR(64),
  color VARCHAR(16),
  is_mine TINYINT NOT NULL DEFAULT 0 COMMENT '1=我任教的科目（工作台我的课/作业科目按此判定，不靠 teacher 文本猜）',
  KEY idx_subject_user (user_id)
);

-- 节次（全局共享模板，所有账号通用）
CREATE TABLE IF NOT EXISTS period (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(32) NOT NULL,
  start_time VARCHAR(8),
  end_time VARCHAR(8),
  sort INT DEFAULT 0,
  is_break TINYINT DEFAULT 0 COMMENT '跑操/午休等不可排课'
);

-- 课表（per-user，经 class_id 归属）
CREATE TABLE IF NOT EXISTS timetable (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  class_id BIGINT NOT NULL,
  day_of_week INT NOT NULL COMMENT '1-7 周一到周日',
  period_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  UNIQUE KEY uk_slot (class_id, day_of_week, period_id),
  KEY idx_tt_user (user_id)
);

-- 座次配置（per-user）
CREATE TABLE IF NOT EXISTS seat_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  class_id BIGINT NOT NULL UNIQUE,
  `rows` INT DEFAULT 7,
  cols INT DEFAULT 8,
  aisles VARCHAR(64) COMMENT '过道列，JSON 数组如 [2,4,6]'
);

-- 座位分配（per-user）
CREATE TABLE IF NOT EXISTS seat_assignment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  class_id BIGINT NOT NULL,
  row_no INT NOT NULL,
  col_no INT NOT NULL,
  student_id BIGINT,
  UNIQUE KEY uk_seat (class_id, row_no, col_no),
  KEY idx_sa_user (user_id)
);

-- 值日事项（per-user）
CREATE TABLE IF NOT EXISTS duty_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(64) NOT NULL,
  KEY idx_dt_user (user_id)
);

-- 每日值日人数（per-user；同一账号每天一条）
CREATE TABLE IF NOT EXISTS duty_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  day_of_week INT NOT NULL COMMENT '1-5 周一到周五',
  person_count INT DEFAULT 4,
  UNIQUE KEY uk_user_day (user_id, day_of_week)
);

-- 值日名单（task_id 为该学生当天分配的值日事项，任务到人）
CREATE TABLE IF NOT EXISTS duty_roster (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  day_of_week INT NOT NULL,
  student_id BIGINT NOT NULL,
  task_id BIGINT NULL COMMENT '分配的值日事项 duty_task.id',
  round INT DEFAULT 1,
  KEY idx_dr_user (user_id)
);

-- 班委（per-user）
CREATE TABLE IF NOT EXISTS committee (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  role VARCHAR(32),
  KEY idx_comm_user (user_id)
);

-- 考试（per-user）
CREATE TABLE IF NOT EXISTS exam (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(64) NOT NULL,
  exam_date DATE,
  source_file VARCHAR(255),
  KEY idx_exam_user (user_id)
);

-- 成绩（per-user）
CREATE TABLE IF NOT EXISTS score (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  exam_id BIGINT NOT NULL,
  student_id BIGINT,
  subject VARCHAR(32) NOT NULL,
  score DECIMAL(6,2),
  stu_no VARCHAR(32),
  stu_name VARCHAR(64),
  KEY idx_score_user (user_id)
);

-- 家校沟通记录（per-user）
CREATE TABLE IF NOT EXISTS comm_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT,
  parent_id BIGINT,
  contact_date DATE,
  method VARCHAR(16),
  content VARCHAR(1000),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_cl_user (user_id)
);

-- 待办（per-user）
CREATE TABLE IF NOT EXISTS todo (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  title VARCHAR(255) NOT NULL,
  category VARCHAR(16) DEFAULT '其他' COMMENT '教学/班级/家校/其他',
  due_date DATE,
  done TINYINT DEFAULT 0,
  source VARCHAR(16) DEFAULT 'MANUAL' COMMENT 'MANUAL/QUICK',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_todo_user (user_id)
);

-- 考勤（per-user）
CREATE TABLE IF NOT EXISTS attendance (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  record_date DATE NOT NULL,
  student_id BIGINT NOT NULL,
  status VARCHAR(8) DEFAULT '出勤' COMMENT '出勤/迟到/请假/缺勤',
  leave_id BIGINT NULL COMMENT '联动生成的请假单 id（删单时据此回滚，手动标记为空）',
  UNIQUE KEY uk_att (record_date, student_id),
  KEY idx_att_user (user_id)
);

-- 课堂纪律（per-user）
CREATE TABLE IF NOT EXISTS discipline_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  record_date DATE NOT NULL,
  student_id BIGINT NOT NULL,
  content VARCHAR(1000),
  KEY idx_dl_user (user_id)
);

-- 作业收缴（per-user）
CREATE TABLE IF NOT EXISTS homework_check (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  record_date DATE NOT NULL,
  subject VARCHAR(32),
  student_id BIGINT NOT NULL,
  status VARCHAR(8) DEFAULT '交' COMMENT '交/未交',
  UNIQUE KEY uk_hw (record_date, subject, student_id),
  KEY idx_hw_user (user_id)
);

-- 速记原文（per-user）
CREATE TABLE IF NOT EXISTS quick_note (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  content TEXT,
  parsed_json TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_qn_user (user_id)
);

-- 速记规则（全局共享关键词模板）
CREATE TABLE IF NOT EXISTS quick_note_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  module_key VARCHAR(16) NOT NULL,
  keywords TEXT COMMENT '逗号分隔关键词',
  enabled TINYINT DEFAULT 1
);

-- 节假日（per-user）
CREATE TABLE IF NOT EXISTS holiday (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(32) NOT NULL,
  holiday_date DATE NOT NULL,
  KEY idx_hol_user (user_id)
);

-- 工作台卡片顺序（per-user）
CREATE TABLE IF NOT EXISTS card_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  card_key VARCHAR(32) NOT NULL,
  sort_order INT DEFAULT 0,
  visible TINYINT DEFAULT 1,
  UNIQUE KEY uk_user_card (user_id, card_key)
);

-- 请假记录（per-user；病假/事假/公假台账，销假闭环）
CREATE TABLE IF NOT EXISTS leave_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  leave_type VARCHAR(8) NOT NULL COMMENT '病假/事假/公假',
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  days DECIMAL(4,1) DEFAULT 1 COMMENT '请假天数，支持 0.5 半天',
  reason VARCHAR(500),
  notify_method VARCHAR(16) COMMENT '家长告知方式：微信/电话/书面/口头',
  status VARCHAR(8) DEFAULT 'ACTIVE' COMMENT 'ACTIVE 请假中 / DONE 已销假',
  return_date DATE COMMENT '实际返校销假日',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_lr_user_stu (user_id, student_id),
  KEY idx_lr_user_status (user_id, status, end_date)
);

-- 期末评语（per-user；每生每学期一条，唯一键约束；AI/模板生成后老师可编辑保存）
CREATE TABLE IF NOT EXISTS `comment` (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  term VARCHAR(40) NOT NULL COMMENT '学期标识：term_start~term_end',
  content TEXT COMMENT '评语正文',
  source VARCHAR(16) DEFAULT 'manual' COMMENT 'ai/template/manual 来源',
  updated_at DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_comment (user_id, student_id, term),
  KEY idx_cmt_user (user_id)
);

-- 成就定义（user_id=0 为系统内置模板全局共享 UNION 可见；>0 为老师自定义）
CREATE TABLE IF NOT EXISTS achievement_def (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 0 COMMENT '0=系统内置；>0=老师自定义',
  name VARCHAR(64) NOT NULL COMMENT '成就名称',
  description VARCHAR(500) COMMENT '如何获得（灰色徽章悬停展示）',
  icon VARCHAR(32) COMMENT '图标名（frontend/public/badges 下 PNG 文件名，不含扩展名）',
  tier VARCHAR(16) NOT NULL COMMENT 'BRONZE铜/SILVER银/GOLD金/PLATINUM白金；可进阶成就此列为入门档（BRONZE），学生当前档在 student_achievement.tier',
  tiered TINYINT NOT NULL DEFAULT 0 COMMENT '1=可进阶（铜银金三档，阈值在 rule_params.tiers），0=固定档',
  kind VARCHAR(8) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL 手动 / AUTO 自动',
  rule_type VARCHAR(32) COMMENT 'AUTO 规则类型：PERFECT_ATTENDANCE/PERFECT_HOMEWORK/PERFECT_DISCIPLINE/RANK_UP/ALL_EXCELLENT/SUBJECT_TOP/SCORE_FULL/DUTY_COUNT/COMMITTEE/CONTINUOUS_ATTENDANCE/PET_ADOPTED/PET_LEVEL/PET_GRADUATE/PET_SHOP/RANK_TOP/COLLECTOR',
  rule_params VARCHAR(500) COMMENT '规则参数 JSON，如 {"tiers":[{"tier":"BRONZE","min":10},{"tier":"SILVER","min":20},{"tier":"GOLD","min":30}]}',
  enabled TINYINT DEFAULT 1 COMMENT '启用状态（内置可停用不可删）',
  builtin TINYINT DEFAULT 0 COMMENT '1=系统内置',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_ad_user (user_id)
);

-- 学生成就获得记录（per-user；同一成就每学期每生最多一枚，幂等）
CREATE TABLE IF NOT EXISTS student_achievement (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  def_id BIGINT NOT NULL,
  term VARCHAR(40) NOT NULL COMMENT '学期标识：term_start~term_end',
  source VARCHAR(8) DEFAULT 'AUTO' COMMENT 'AUTO 扫描颁发 / MANUAL 老师授予',
  tier VARCHAR(16) NULL COMMENT '当前持有档 BRONZE/SILVER/GOLD/PLATINUM；NULL=老数据，展示/计点回退 achievement_def.tier',
  remark VARCHAR(255) COMMENT '手动授予备注，如"校运会跳远第一"',
  awarded_at DATE COMMENT '获得日期（进阶升级时刷新为当前档达成日期）',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sa (user_id, student_id, def_id, term),
  KEY idx_sa_user (user_id),
  KEY idx_sa_student (user_id, student_id)
);

-- 班级宠物园：学生宠物（per-user；一个学生一只，形态由 level 推导 1~5；hatched=0 为孵化中蛋）
CREATE TABLE IF NOT EXISTS pet (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  pet_key VARCHAR(16) NOT NULL DEFAULT 'dragon' COMMENT '进化链标识，v1 只有 dragon 云小龙',
  pet_name VARCHAR(32) COMMENT '宠物中文名（领养时起名）',
  level INT NOT NULL DEFAULT 1 COMMENT '等级 1~5',
  exp INT NOT NULL DEFAULT 0 COMMENT '累计成长值（满级封顶 280）',
  adopted_at DATETIME COMMENT '领养时间',
  graduated_count INT NOT NULL DEFAULT 0 COMMENT '毕业次数（荣誉榜徽章数）',
  evolve_pending TINYINT NOT NULL DEFAULT 0 COMMENT '跨页面/自动联动升级待演标记：1=下次入座次表页补播进化演出',
  hatched TINYINT NOT NULL DEFAULT 1 COMMENT '是否已破壳：0=孵化中蛋（领养后到次日破壳前），1=已出生；DEFAULT 1 保证存量宠物不回退成蛋',
  UNIQUE KEY uk_pet_student (user_id, student_id),
  KEY idx_pet_user (user_id)
);

-- 宠物皮肤（内置皮肤 user_id=NULL 全局共享；自定义皮肤按账号隔离，图片走 /uploads 上传）
CREATE TABLE IF NOT EXISTS pet_skin (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NULL COMMENT 'NULL=内置全局皮肤',
  skin_key VARCHAR(32) NOT NULL COMMENT '进化链标识：内置 dragon；自定义 u+id',
  name VARCHAR(32) NOT NULL COMMENT '皮肤/种族名',
  built_in TINYINT NOT NULL DEFAULT 0,
  fx_theme VARCHAR(16) NOT NULL DEFAULT 'gold' COMMENT '进化演出主题：gold/fire/ice/thunder/forest/candy',
  img_egg VARCHAR(255) COMMENT '蛋形态图 URL',
  img_1 VARCHAR(255),
  img_2 VARCHAR(255),
  img_3 VARCHAR(255),
  img_4 VARCHAR(255),
  img_5 VARCHAR(255) COMMENT 'Lv5 形态图 URL',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_skin_key (skin_key),
  KEY idx_ps_user (user_id)
);

-- 宠物评价项（per-user；预置 ~35 项可改分/停用，可自定义增删）
CREATE TABLE IF NOT EXISTS pet_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  category VARCHAR(8) NOT NULL COMMENT '学习/行为/健康/其他',
  name VARCHAR(32) NOT NULL,
  score INT NOT NULL COMMENT '成长值变动（正=加分，负=扣分）',
  icon VARCHAR(8) COMMENT 'emoji 图标',
  enabled TINYINT DEFAULT 1,
  auto_source VARCHAR(32) DEFAULT NULL COMMENT '自动联动来源标识；NULL=纯手动项',
  KEY idx_pr_user (user_id)
);

-- 宠物成长值流水（per-user；biz_ref 幂等防自动联动重复扣分）
CREATE TABLE IF NOT EXISTS pet_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  rule_id BIGINT NULL COMMENT '手动评价关联 pet_rule.id；自动联动为空',
  score INT NOT NULL COMMENT '本次变动分值（带正负）',
  source VARCHAR(24) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/AUTO_ATTENDANCE/AUTO_HOMEWORK/AUTO_DISCIPLINE/AUTO_SCORE',
  biz_ref VARCHAR(64) NULL COMMENT '业务记录标识（如 attendance:123），自动联动幂等用',
  remark VARCHAR(128) COMMENT '事项名/备注（如"迟到""兑换xxx"）',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pl_user_stu (user_id, student_id),
  KEY idx_pl_biz (user_id, source, biz_ref)
);

-- 宠物毕业徽章墙（per-user；每次毕业一枚）
CREATE TABLE IF NOT EXISTS pet_badge (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  pet_key VARCHAR(16) NOT NULL,
  pet_name VARCHAR(32),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pb_user_stu (user_id, student_id)
);

-- 小商店商品（per-user；stock=-1 表示不限库存）
CREATE TABLE IF NOT EXISTS pet_shop_item (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(32) NOT NULL,
  icon VARCHAR(8) COMMENT 'emoji 图标',
  price INT NOT NULL COMMENT '兑换所需成长值',
  stock INT NOT NULL DEFAULT -1 COMMENT '库存，-1=不限',
  KEY idx_psi_user (user_id)
);

-- 小商店兑换记录（per-user；待兑现→已兑现，线下履约记账）
CREATE TABLE IF NOT EXISTS pet_exchange (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  student_id BIGINT NOT NULL,
  item_id BIGINT NOT NULL,
  cost INT NOT NULL COMMENT '兑换时扣减的成长值',
  status VARCHAR(8) NOT NULL DEFAULT '待兑现' COMMENT '待兑现/已兑现',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_pe_user (user_id)
);

-- 题库分类（per-user；语文背诵/英语单词/数学口算…，老师自建，供打怪兽/PK 抽题）
CREATE TABLE IF NOT EXISTS quiz_category (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  name VARCHAR(32) NOT NULL,
  icon VARCHAR(8),
  enabled TINYINT DEFAULT 1,
  sort INT DEFAULT 0,
  KEY idx_qc_user (user_id)
);

-- 题库（per-user；answer 只给老师端，30 天内用过的抽题降权）
CREATE TABLE IF NOT EXISTS quiz_bank (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  category_id BIGINT NOT NULL,
  question VARCHAR(500) NOT NULL,
  answer VARCHAR(500),
  difficulty INT DEFAULT 1 COMMENT '1易 2中 3难',
  enabled TINYINT DEFAULT 1,
  use_count INT DEFAULT 0,
  last_used_at DATETIME NULL,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_qb_user_cat (user_id, category_id, enabled)
);

-- 全局设置（per-user，键值按账号隔离）
CREATE TABLE IF NOT EXISTS setting (
  user_id BIGINT NOT NULL DEFAULT 1,
  k VARCHAR(64) NOT NULL,
  v VARCHAR(500),
  PRIMARY KEY (user_id, k)
);

-- 消息推送发送记录（per-user；按 渠道+业务键 幂等去重，如 todo-daily:2026-09-03 / ach:123:GOLD）
CREATE TABLE IF NOT EXISTS push_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  channel VARCHAR(16) NOT NULL COMMENT 'wecom / dingtalk / bark',
  biz_ref VARCHAR(64) COMMENT '幂等键：todo-daily:日期 / ach-daily:日期 / ach:saId:tier',
  ok TINYINT DEFAULT 1,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_push_user (user_id),
  KEY idx_push_biz (user_id, channel, biz_ref)
);

-- 打怪兽场次（per-user；一天可多场，故去掉 (user_id,boss_date) 唯一键改普通索引；
-- mode=RANDOM/CLASS/GROUP/MANUAL 四模式；category_id 为 NULL 表示无题库抽背；
-- round_no 当前轮次、target_rounds 目标轮次（血量=target_rounds×hit_per_pass，答错不扣血故打不满一轮不会被秒）；
-- status=ACTIVE 进行中 / KILLED 已击杀 / ESCAPED 跨天未结束（懒判不落库）；ended_at 结束时间）
CREATE TABLE IF NOT EXISTS daily_boss (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  boss_date DATE NOT NULL,
  boss_name VARCHAR(32),
  boss_emoji VARCHAR(8),
  mode VARCHAR(16) DEFAULT 'RANDOM' COMMENT 'RANDOM/CLASS/GROUP/MANUAL',
  title VARCHAR(64),
  category_id BIGINT NULL COMMENT '题库分类；NULL=无题库抽背',
  difficulty INT NULL,
  hp_total INT DEFAULT 100,
  hp_left INT DEFAULT 100,
  hit_per_pass INT DEFAULT 10,
  round_no INT DEFAULT 1,
  target_rounds INT DEFAULT 20,
  kill_student_id BIGINT NULL,
  killed_at DATETIME NULL,
  ended_at DATETIME NULL,
  status VARCHAR(8) DEFAULT 'ACTIVE' COMMENT 'ACTIVE/KILLED/ESCAPED',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  KEY idx_boss_user_date (user_id, boss_date)
);

-- 打怪兽命中明细（per-user；一次判定一行，biz_ref 唯一键防止重复发奖；
-- round_no 该判定所在轮次、group_col 小组模式的座位列号（小组=座位表的列）；quiz_id 可空=抽背无题）
CREATE TABLE IF NOT EXISTS boss_hit (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL DEFAULT 1,
  boss_id BIGINT NOT NULL,
  student_id BIGINT NOT NULL,
  quiz_id BIGINT NULL,
  round_no INT DEFAULT 1,
  group_col INT NULL,
  result VARCHAR(8) COMMENT 'PASS/FAIL',
  damage INT DEFAULT 0,
  biz_ref VARCHAR(64),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_biz (user_id, biz_ref),
  KEY idx_bh_boss (boss_id)
);
