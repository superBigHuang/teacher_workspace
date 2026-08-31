-- 班主任工作平台 建库建表脚本（幂等）
CREATE DATABASE IF NOT EXISTS teacher_workspace DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE teacher_workspace;

-- 用户（预留多用户）
CREATE TABLE IF NOT EXISTS sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(64) NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  display_name VARCHAR(64),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 班级
CREATE TABLE IF NOT EXISTS class_info (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  type VARCHAR(16) NOT NULL COMMENT 'HOMEROOM 班主任班 / SUBJECT 任课班',
  term VARCHAR(32)
);

-- 学生
CREATE TABLE IF NOT EXISTS student (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  stu_no VARCHAR(32) NOT NULL COMMENT '学号',
  name VARCHAR(64) NOT NULL,
  gender VARCHAR(8) DEFAULT '男',
  class_id BIGINT,
  address VARCHAR(255),
  UNIQUE KEY uk_stuno (stu_no)
);

-- 家长联系方式
CREATE TABLE IF NOT EXISTS parent_contact (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT NOT NULL,
  relation VARCHAR(16) COMMENT '父亲/母亲/其他',
  name VARCHAR(64),
  phone VARCHAR(32)
);

-- 科目
CREATE TABLE IF NOT EXISTS subject (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(32) NOT NULL,
  teacher VARCHAR(64),
  color VARCHAR(16)
);

-- 节次
CREATE TABLE IF NOT EXISTS period (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(32) NOT NULL,
  start_time VARCHAR(8),
  end_time VARCHAR(8),
  sort INT DEFAULT 0,
  is_break TINYINT DEFAULT 0 COMMENT '跑操/午休等不可排课'
);

-- 课表
CREATE TABLE IF NOT EXISTS timetable (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  day_of_week INT NOT NULL COMMENT '1-7 周一到周日',
  period_id BIGINT NOT NULL,
  subject_id BIGINT NOT NULL,
  UNIQUE KEY uk_slot (class_id, day_of_week, period_id)
);

-- 座次配置
CREATE TABLE IF NOT EXISTS seat_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL UNIQUE,
  rows INT DEFAULT 7,
  cols INT DEFAULT 8,
  aisles VARCHAR(64) COMMENT '过道列，JSON 数组如 [2,4,6]'
);

-- 座位分配
CREATE TABLE IF NOT EXISTS seat_assignment (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  class_id BIGINT NOT NULL,
  row_no INT NOT NULL,
  col_no INT NOT NULL,
  student_id BIGINT,
  UNIQUE KEY uk_seat (class_id, row_no, col_no)
);

-- 值日事项
CREATE TABLE IF NOT EXISTS duty_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL
);

-- 每日值日人数
CREATE TABLE IF NOT EXISTS duty_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  day_of_week INT NOT NULL UNIQUE COMMENT '1-5 周一到周五',
  person_count INT DEFAULT 4
);

-- 值日名单（task_id 为该学生当天分配的值日事项，任务到人）
CREATE TABLE IF NOT EXISTS duty_roster (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  day_of_week INT NOT NULL,
  student_id BIGINT NOT NULL,
  task_id BIGINT NULL COMMENT '分配的值日事项 duty_task.id',
  round INT DEFAULT 1
);

-- 班委
CREATE TABLE IF NOT EXISTS committee (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT NOT NULL,
  role VARCHAR(32)
);

-- 考试
CREATE TABLE IF NOT EXISTS exam (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(64) NOT NULL,
  exam_date DATE,
  source_file VARCHAR(255)
);

-- 成绩
CREATE TABLE IF NOT EXISTS score (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  exam_id BIGINT NOT NULL,
  student_id BIGINT,
  subject VARCHAR(32) NOT NULL,
  score DECIMAL(6,2),
  stu_no VARCHAR(32),
  stu_name VARCHAR(64)
);

-- 家校沟通记录
CREATE TABLE IF NOT EXISTS comm_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  student_id BIGINT,
  parent_id BIGINT,
  contact_date DATE,
  method VARCHAR(16),
  content VARCHAR(1000),
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 待办
CREATE TABLE IF NOT EXISTS todo (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  title VARCHAR(255) NOT NULL,
  category VARCHAR(16) DEFAULT '其他' COMMENT '教学/班级/家校/其他',
  due_date DATE,
  done TINYINT DEFAULT 0,
  source VARCHAR(16) DEFAULT 'MANUAL' COMMENT 'MANUAL/QUICK',
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 考勤
CREATE TABLE IF NOT EXISTS attendance (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  record_date DATE NOT NULL,
  student_id BIGINT NOT NULL,
  status VARCHAR(8) DEFAULT '出勤' COMMENT '出勤/迟到/请假/缺勤',
  UNIQUE KEY uk_att (record_date, student_id)
);

-- 课堂纪律
CREATE TABLE IF NOT EXISTS discipline_log (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  record_date DATE NOT NULL,
  student_id BIGINT NOT NULL,
  content VARCHAR(1000)
);

-- 作业收缴
CREATE TABLE IF NOT EXISTS homework_check (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  record_date DATE NOT NULL,
  subject VARCHAR(32),
  student_id BIGINT NOT NULL,
  status VARCHAR(8) DEFAULT '交' COMMENT '交/未交',
  UNIQUE KEY uk_hw (record_date, subject, student_id)
);

-- 速记原文
CREATE TABLE IF NOT EXISTS quick_note (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  content TEXT,
  parsed_json TEXT,
  created_at DATETIME DEFAULT CURRENT_TIMESTAMP
);

-- 速记规则
CREATE TABLE IF NOT EXISTS quick_note_rule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  module_key VARCHAR(16) NOT NULL,
  keywords TEXT COMMENT '逗号分隔关键词',
  enabled TINYINT DEFAULT 1
);

-- 节假日
CREATE TABLE IF NOT EXISTS holiday (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(32) NOT NULL,
  holiday_date DATE NOT NULL
);

-- 工作台卡片顺序
CREATE TABLE IF NOT EXISTS card_config (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  card_key VARCHAR(32) NOT NULL UNIQUE,
  sort_order INT DEFAULT 0,
  visible TINYINT DEFAULT 1
);

-- 全局设置
CREATE TABLE IF NOT EXISTS setting (
  k VARCHAR(64) PRIMARY KEY,
  v VARCHAR(500)
);
