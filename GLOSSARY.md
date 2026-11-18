# 领域词汇表 (GLOSSARY.md)

本文件维护中学班主任工作平台的领域术语与架构概念统一定义。

---

### 一、环境与多租户拓扑

- **正式环境 (Prod Environment)**：供真实教师与学校管理员日常教学办公的生产环境，绑定正式域名与正式数据库，数据具备持久性与神圣性，不参与任何自动清空或重置。
- **测试/演示环境 (Dev/Demo Environment)**：供潜在用户与访客自由体验系统全部功能的独立沙箱环境，绑定演示域名与独立演示数据库。
- **正式库 (Master Database)**：数据库名为 `teacher_workspace`，仅服务于正式环境，存储生产真实业务数据。
- **测试库 (Demo Database)**：数据库名为 `teacher_workspace_demo`，仅服务于测试/演示环境，物理上与正式库隔离，数据随定时调度或手动指令定期全量重置。

---

### 二、切库与数据源架构

- **动态切库 (Dynamic Datasource Routing)**：同一个后端 Spring Boot 进程根据请求入口特征（HTTP 请求头 `X-Datasource` 或访问域名 Host）在运行时透明切换数据源连接的技术机制。
- **数据源路由器 (RoutingDataSource)**：继承自 Spring 原生 `AbstractRoutingDataSource` 的数据源管理中枢，依据 `ThreadLocal` 上下文中的路由 Key 动态决定当前 SQL 连接哪个物理库。
- **数据源上下文 (DataSourceContext)**：基于 `ThreadLocal<String>` 维护当前线程数据源标识的轻量组件，在拦截器 `preHandle` 注入并在 `afterCompletion` 及时清理以防连接池污染。

---

### 三、体验沙箱与重置保障

- **自由体验沙箱 (Unrestricted Demo Sandbox)**：在测试环境中运行的无限制体验模式。因物理分库彻底杜绝了污染正式数据的可能，测试区不对用户权限、改密或功能做任何人工防御拦截，访客可全功能自由操作。
- **高保真演示数据集 (High-Fidelity Seed Data)**：测试库初始化与重置时自动灌装的全套仿真数据，包含 50 名学生名册、周课表、男女交错座位表、值日轮值、两次考试对比成绩、待办事项与班级宠物。
- **重置互斥锁 (ResetLock)**：防止测试区并发重置冲突的本地线程安全锁（`ReentrantLock`），确保全库清空与高保真数据灌装具有原子性与排他性。
- **重置冷却机制 (Reset Cooldown)**：防止测试区一键恢复接口被恶意刷频的限频防护，限制连续手动重置间隔（5 分钟）。
