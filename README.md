# 公交网络灰度实验服务（R1/R2/R3 虚构样例）

用 **Java 21（运行/构建于 JDK 25）+ Spring Boot 3.5 + PostgreSQL** 实现的可复现灰度实验后端与评审控制台。
目标不是比较试点前后平均值，而是在预登记的 **四周、约 50% 合格服务接受新规则** 设计下，估计**乘客每次完整旅程**的端到端候车时间与拥挤暴露变化；旅程包含首乘与换乘，未完成刷卡或中途改线一律保留为 `UNKNOWN`。

系统覆盖题目要求的完整闭环：

1. **实验登记冻结**：合格线路/班次、处理规则、随机种子、分配概率、目标旅程人群、主要/安全指标、试点周期，一次登记并计算指纹。
2. **固定网络图**：连接关系只来自**实验前**刷卡换乘比例（R1↔R2=40%，R3 基本不换乘），用并查集得到冻结簇，不使用试点结果临时拆并。
3. **两套随机方案**：
   - 线路—具体班次（日期级，线路×日期×时段分层，**720 个单元，严格 360/360**）；
   - 高换乘线路成簇后的簇—周方案（2 簇 × 4 周 = **8 个单元，严格 4/4**，每周两簇一中一控）。
4. **暴露账逐段关联**：计划分配 → GPS/AVL 实际运行 → 短线折返/停运/延误偏离 → 每段与完整旅程路径暴露。
5. **共同支持器**：时段 × OD 区间 × 天气 × 服务可达性，检查两臂加权样本；不满足只报覆盖缺口，不外推。
6. **估计器**：乘客人数加权 Hajek **ITT**、按随机化单元聚类的 CR1 区间、CACE/Wald-IV、跨线暴露率 Wilson 区间、簇方案 2⁴ 随机化推断（RI）。
7. **独立护栏**：投诉率、满载班次占比、滞留/未完成旅程占比；任一硬护栏超限即 `STOP_EXPANSION`，扩围结论 `BLOCKED`，服务端同时拒绝人工改判 `GO`。
8. **外部事件按事件时间关联**：暴雨停运、降雨、施工、临时改线均带事件窗口；投诉同时保存 `complaint_time` 与 `service_event_time`，统计只用后者。
9. **界面**：随机分配、暴露路径、共同支持、客流结果、护栏、离线审阅、扩围证据等标签页，另含 Swagger UI / OpenAPI 3。
10. **客流迁移 / 旅程重建（feature-2，独立子系统）**：从 **AFC 刷卡 + AVL 车载定位 + 换乘记录**重建乘客实际旅程，建立**上车观察账 → 车辆轨迹对齐 → 候选下车站集合 → 旅程 OD 流量矩阵 → 抽样核验**闭环，量化**短线折返的额外换乘损失**。提供**确定性时刻表分配**与**概率贝叶斯旅程推断**双引擎，对**同一 OD 站对**各出旅程量与换乘损失区间；**定位中断保留多候选**，**抽样与推断冲突时服务端禁止发布单点迁移量（一律 409）**。前端新增“🚇 客流迁移重建”标签页（共 11 个主标签）。

---

## ★ 客流迁移 / 旅程重建子系统（feature-2）

> 题目要点：客流迁移**不是**父任务的“实验干扰处理”，而是从**刷卡、车载定位、换乘记录**重建乘客实际旅程，
> 量化**短线折返是否制造额外换乘损失**。必须建立：上车观察账、车辆轨迹对齐、候选下车站集合、旅程流量矩阵、抽样核验。
> 确定性时刻表分配与概率旅程推断**两条路线都估计同一 OD 站对的旅程与换乘损失区间**；
> **定位中断保留多候选**；**抽样调查与推断冲突时不得发布单点迁移量**。

### A. 数据流水线与五件产物

原始数据（确定性虚构，日期 `2026-06-02` 早高峰）落 `mig_afc_tap / mig_avl_point / mig_trip_trace /
mig_transfer_record / mig_survey_sample`，重建后产出：

| 步骤 | 产物表 / API | 说明 |
| --- | --- | --- |
| ① 上车观察账 | `mig_boarding_ledger` · `GET /api/migration/boardings` | 每张首乘 AFC 刷卡按 线路+车辆+站点时刻 对齐到 AVL 班次，状态 `ALIGNED / NEAREST_FALLBACK / GPS_GAP_FUZZY / UNMATCHED` |
| ② 车辆轨迹对齐 | `mig_trip_trace`、`mig_avl_point` · `GET /api/migration/trajectories[/{trip}]` | 逐站到离时刻与 `fix_ok`；记录短线折返、折返站、定位中断区间、拥堵延误 |
| ③ 候选下车站集合 | `mig_alight_candidate` · `GET /api/migration/candidates?boardingCode=` | 每个上车账保留排序候选及先验/后验；中断或后验分散时**不收敛为单一站** |
| ④ 旅程流量矩阵 | `mig_rebuilt_journey`、`mig_od_flow` · `GET /api/migration/od-matrix` | 每 OD × `DETERMINISTIC/PROBABILISTIC` 两行：旅程量、迁移量、人均/总换乘损失，均带 `[low,high]` |
| ⑤ 抽样核验 | `mig_verification` · `GET /api/migration/verification`、`/surveys` | Wilson 抽样区间 × 推断区间，结论 `CORROBORATED / CONFLICT / INSUFFICIENT` |
| 发布门控 | `mig_publication` · `GET/POST /api/migration/publication|publish` | 冲突 OD/报告级**单点 409**；只允许 `INTERVAL` 包络区间 |

一键总览：`GET /api/migration/workbench`（总览+矩阵+核验+门控+校准）。所有接口同时提供等价嵌套路径
`/api/experiments/{id}/migration/...`，`id` 缺省自动取 `PILOT-R123-202606`；并提供 `/flows`、`/flow-matrix`、
`/samples`、`/sampling`、`/candidate-stops`、`/boarding-ledger` 等常见别名。

### B. 双引擎口径（纯逻辑内核 `engine/mig/MigrationEngine.java`，无 Spring/DB 依赖）

- **确定性时刻表路径分配**：对齐 AVL 后按固定“吸引权重 + 广义费用”规则选**唯一**最可能下车站/路径，
  换乘等待取时刻表名义值（步行 + 半班）。快、可解释；但在**短线折返无换乘刷卡、拥堵、定位中断**时会错配或过度自信。
  其区间用“刷下车留出验证准确率”校准的分配错误带 `η=clip(1−acc, 8%,40%)` + 时刻表等待敏感包络构造。
- **概率旅程推断（贝叶斯后验）**：枚举候选下车站与候选路线（直达 / 枢纽换乘 / 等下一班 / 漏刷换乘），
  后验权重 ∝ 下车吸引先验（由 `TAP_OUT` 样本校准）× Logit 广义费用 × 刷下车/换乘链似然，
  换乘等待作为带方差的随机量进入后验；定位中断时再与均匀候选做 30% “传感器无知”混合。
  OD 区间用伯努利/分类矩 `Σq(1−q)` 与损失一/二阶矩解析得到（95%），歧义越大区间越宽。
- **额外换乘损失（秒）** = 实际换乘等待 + 换乘次数 × `300s` 感知惩罚；相对“该 OD 无折返直达基线”，
  仅把**可归因于短线折返的新增换乘**计入迁移损失。

### C. 两条硬纪律（服务端强制，有测试与 e2e 断言）

1. **定位中断保留多候选**：`R2-GAP-0812` 在 `HUB-CENTRAL→W-STATION` 间中断、`W-CULTURE` 无 fix。
   样例卡 `C-DEMO-GAP-01` 对齐为 `ALIGNED` 但 `location_outage=true`，保留
   `W-STATION≈0.55 / W-CULTURE≈0.25 / N-NORTH≈0.20` 三个 ≥0.1 的候选；
   上车站正落在中断区间的 `C-DEMO-FUZZY-01` 走插值模糊对齐，状态 `GPS_GAP_FUZZY`。
2. **抽样冲突禁发单点**：OD `E2-EXPO → W-STATION` 推断迁移量约 **45.6 [38.4, 52.8]**，
   而刻意构造的抽样 `52` 人中仅 `1` 人报告额外换乘（代表总体 54），Wilson 体积区间约 **[0.2, 5.5]**，
   两区间不重叠 → `CONFLICT`。此后：
   - `POST /api/migration/publish {"scope":"REPORT","mode":"POINT"}` → **409**；
   - 对该 OD `mode:"POINT"` → **409**，**任何角色（含 approver）都不能绕过**；
   - `mode:"INTERVAL"` 始终允许，落库为 `INTERVAL_ONLY` 的跨“两引擎+抽样”包络区间。
   - 对照：`E-LAKE → W-STATION` 抽样与推断重叠（`CORROBORATED`），其单点可正常发布；
     `HUB-CENTRAL → W-STATION` 样本 `n=6<10` 判 `INSUFFICIENT`，不据此判冲突也不支持单点。

### D. 可复现样例规模与数字（固定确定性构造，重算结果稳定）

启动后播种 **235** 个上车观察账/重建旅程、**23** 个 OD（`mig_od_flow` 46 行）、**18** 个定位中断个案。
关键 OD（概率引擎，2026-06-02 早高峰）：

| OD | 迁移量（人）95% 区间 | 人均额外换乘损失（秒）区间 | 总损失（小时） |
| --- | --- | --- | --- |
| `E2-EXPO → W-STATION`（冲突 OD） | **45.6 [38.4, 52.8]** | **606 [507, 705]** | 7.68 |
| `E-LAKE → W-STATION`（抽样相容） | **28.5 [22.8, 34.2]** | **595 [472, 719]** | 4.72 |

确定性规则在 **76** 张“有刷下车”卡上的隐藏目的地留出准确率约 **0.553**（系统性偏向最大吸引站 `W-STATION`，
恰好示范其在多目的站/无下车场景的错配），校准错误带取上限 `η=0.40`；概率引擎则如实把这种不确定展开为宽区间。
命名核验卡：`C-DEMO-SHORT-01`（折返+观察到换乘）、`C-DEMO-GAP-01`（中断三候选）、
`C-DEMO-FUZZY-01`（模糊对齐）、`C-DEMO-MISS-01`（无换乘刷卡：确定性错配到折返站、概率保留换乘后验）、
`C-DEMO-TAPOUT-01`（刷下车锚点）、`C-DEMO-CONG-01`（拥堵长等待）。

前端“🚇 客流迁移重建”页含 6 个子视图：OD 双引擎区间矩阵、上车观察账（筛选/搜索/弹窗候选与路径后验）、
轨迹对齐（逐站 fix 时间线）、候选下车/重建旅程、抽样核验、发布门控（冲突单点按钮会收到 409 行内提示，附发布历史）。

---

## 一、最快启动：Docker Compose（一键）

```bash
docker compose up --build
```

- 应用：<http://localhost:8080>
- Swagger UI：<http://localhost:8080/swagger-ui.html>
- Compose 内 PostgreSQL 18：库/用户/密码均为 `ccdev`；为避免与宿主机已装 PostgreSQL 的 5432 冲突，宿主机映射为 **5433**（容器内仍是 5432）。
- 首次启动会自动执行 `schema.sql`（幂等建表）并写入确定性虚构样例。

> 说明：本交付环境内没有 `docker` 可执行程序，因此 Dockerfile/Compose 已按标准多阶段构建编写，但**镜像构建/Compose 启动未在本环境实际执行**；下列本地方式已被实际构建、运行与验证。

---

## 二、本地开发与启动（本容器已预装运行时）

默认 PATH 已指向预装版本；如需显式固定：

```bash
export JAVA_HOME=/opt/jdk-25
export PATH=$JAVA_HOME/bin:/opt/maven/bin:/opt/postgresql-18/bin:$PATH
```

### 1. 准备 PostgreSQL（已运行可跳过）

本容器自带 PostgreSQL 18，且已存在可连账号：

```bash
psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" -c 'select 1;'
```

若在全新机器上：用 PostgreSQL 18 建库建用户即可，或直接使用上面的 Docker Compose。连接参数可用环境变量覆盖：

| 变量 | 默认值 |
| --- | --- |
| `DB_URL` | `jdbc:postgresql://127.0.0.1:5432/ccdev` |
| `DB_USER` / `DB_PASSWORD` | `ccdev` / `ccdev` |
| `SERVER_PORT` | `8080` |

> `ccdev/ccdev` 仅为隔离开发库，不是生产配置。

### 2. 开发模式启动

```bash
mvn spring-boot:run
```

或打包后运行：

```bash
mvn -DskipTests package
java -jar target/transit-pilot.jar
```

启动后访问 <http://127.0.0.1:8080>，会自动建表并播种样例（幂等：检测到 `PILOT-R123-202606` 已存在则跳过）。

### 3. 完全重置样例

```bash
psql "postgresql://ccdev:ccdev@127.0.0.1:5432/ccdev" \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
# 然后重启应用；Docker 方式可用 docker compose down -v && docker compose up --build
```

---

## 三、测试账号

表单登录页 <http://127.0.0.1:8080/login.html>，也可直接对 API 使用 HTTP Basic：

| 账号 | 密码 | 角色 | 权限 |
| --- | --- | --- | --- |
| `viewer` | `viewer123` | VIEWER | 全部 GET / 离线审阅 |
| `analyst` | `analyst123` | ANALYST | 额外可：登记实验、重算流水线、评估护栏 |
| `approver` | `approver123` | APPROVER | 额外可：形成/签署扩围结论（GO/HOLD/BLOCKED） |

例：

```bash
curl -u analyst:analyst123 http://127.0.0.1:8080/api/experiments
```

---

## 四、本题验证方式（含我实际执行过的结论）

### 1. 单元测试（实际执行 ✅）

```bash
mvn test
```

结果（最后一次实际运行）：

```
Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

覆盖：50% 平衡分配与确定性哈希、R1/R2 聚类/R3 独立、全部命名路径分类
（分流/同车普通对照/对照转乘处理/全程 T/全程 C/不执行/两类 UNKNOWN）、
聚类 ITT 公式、Wilson 区间与 RI 枚举；feature-2 另加 **6 个**迁移引擎测试
（中断多候选与 `GPS_GAP_FUZZY`、折返损失区间下界为正、冲突门控、相容/证据不足三态、确定性留出准确率）。

### 1b. 客流迁移不变量脚本（feature-2，实际执行 ✅）

```bash
./verify-migration.sh          # 默认 http://127.0.0.1:8080，可传 BASE_URL
```

同样仅依赖 `bash/curl/python3` 标准库，实际结果：

```
通过 49 项，失败 0 项
ALL_MIGRATION_CHECKS_PASSED
```

核验：五类产物数量、中断样例 ≥2 候选且后验和约为 1、`GPS_GAP_FUZZY` 模糊对齐、
折返/中断轨迹头、双引擎同一 OD 且区间包含点估计/损失下界为正、抽样三态、
冲突 OD 与报告级单点均 409、相容 OD 单点 200、区间发布 `INTERVAL_ONLY`、
viewer 403 / 未认证 401、重算幂等、7 个路径别名与前端标签资源。

### 1c. 全新空库初始化（实际执行 ✅）

除在开发库反复重算外，我还新建了一个空 PostgreSQL 库并以
`DB_URL=jdbc:postgresql://127.0.0.1:5432/<fresh_db> SERVER_PORT=8081 java -jar target/transit-pilot.jar`
启动，验证 `schema.sql` 在空库一次性建表、父样例与迁移样例自动播种成功；
对该实例分别运行 `./verify-migration.sh http://127.0.0.1:8081`（49/0）与
`./verify.sh http://127.0.0.1:8081`（34/0）均通过。这等价覆盖了 Docker Compose 中
“全新 PG volume 首次启动 + 自动建表播种”的应用侧路径。

### 2. 端到端不变量脚本（实际执行 ✅）

先启动应用，再运行：

```bash
./verify.sh                 # 默认 http://127.0.0.1:8080
```

脚本仅依赖 `bash/curl/python3`（标准库），实际结果：

```
通过 34 项，失败 0 项
ALL_VERIFY_CHECKS_PASSED
```

核验内容包括：

- 两套分配总数与严格 50%（720=360/360；8=4/4）；
- 9 个 `J-DEMO-*` 命名旅程的完整路径、分配臂、cross/diverted 标记；
- `FULL_CONTROL` 中不存在任何 `cross_line=true`（交叉暴露不混入未暴露对照）；
- 两设计 ITT/CACE/RI/跨线率区间存在，且簇方案区间更宽；
- 共同支持缺口存在；投诉、滞留两条护栏超限；
- 投诉确有滞后提交且按 `service_event_time` 关联；
- analyst 不能调用扩围评估（403）；approver 得到 `BLOCKED`；护栏超限时人工 `GO` 被拒（409）；
- `/v3/api-docs`、`/swagger-ui/index.html`、前端页面与 `app.js` 均可访问。

### 3. 前端渲染冒烟（实际执行 ✅）

本环境没有浏览器，我在临时目录用 `jsdom@24` 对**真实运行的后端**加载磁盘上最新的
`index.html + app.js`（脚本 `/tmp/miguismoke/smoke.mjs`，临时目录，不属于交付物）：
依次切换全部 **11** 个主标签确认无“加载失败”/无未捕获 JS 错误，再对“客流迁移重建”页做深度交互——
OD 矩阵默认过滤与“显示全部”、`上车账/轨迹/旅程/核验/发布` 五个子视图、
中断个案候选弹窗（≥2 条概率条）、轨迹逐站弹窗、报告级单点收到 409 行内提示、相容 OD 单点成功，
实际结果：

```
UI_SMOKE 27/27 passed
UI_SMOKE_OK
```

> 说明：jsdom 不做真实布局，只验证 DOM 渲染与交互逻辑无运行时错误；未做像素级视觉校验。

### 4. 我没有执行的验证

- 没有实际运行 `docker compose up --build`（该容器内无 `docker` 命令）；
  Docker 路径仅完成了文件编写与 YAML 自检，未声称已实际构建镜像。

---

## 五、虚构样例剧本与复现数字

固定种子 `TSP-PILOT-2026-06-01`，数据由 `SeedService` 用固定 `Random(20260601)` 与
`SHA-256(seed::unitKey)` 确定性生成；任何人重置库后重跑应得到一致数字。

- 网络：R1/R2 共享 `HUB-CENTRAL`，实验前边 R1→R2=0.40、R2→R1=0.38；R3 边比例 ≤0.02。
  冻结簇：`C-R1R2`、`C-R3`。
- 规模：**2477** 条旅程（含 9 个命名样例、22 条批量分流、46 条暴雨、约 2400 条普通合成）、
  **720** 个日期级计划班次及 GPS 实际运行、**45** 条投诉、**4** 个外部事件。
- 关键事件：
  - `2026-06-02`：一个 R1 处理 AM 班次实际**短线折返**，22+1 条分流旅程改乘 R2 对照 AM1 班次（LF=1.17）；
    同车普通 R2 乘客 `J-DEMO-PURER2-01` 仍是纯净对照。
  - R1/PM3 的处理实例上司机多次不执行新规则（ITT 保留其处理臂，实际路径落为 `FULL_CONTROL`）。
  - `2026-06-15` 暴雨红色预警，R1/R2 早高峰停运：18 未完成刷卡 + 12 中途改线 + 16 可达性受损完成旅程。
  - 另有 `06-08` 降雨、`06-11` R3 施工、`06-19` R2 临时改线。投诉提交时间刻意滞后服务 1–6 天。

最近一次确定性流水线的主要结果（共同支持域，候车秒，负=改善）：

| 设计 | 估计量 | 估计 | 95% 区间 / p | 旅程数/单元 |
| --- | --- | --- | --- | --- |
| 班次随机 | ITT 候车 | **−66.7** | [−90.7, −42.8] | 2255 / 584 |
| 班次随机 | CACE/Wald | −86.1 | [−114.1, −58.1] | 2255 |
| 班次随机 | ITT 拥挤暴露 | −0.1 | [−1.4, 1.2]（不显著） | 2255 / 584 |
| 簇—周随机 | ITT 候车 | **−194.3** | [−235.1, −153.5]（CR1, df=7，偏乐观） | 2067 / 8 |
| 簇—周随机 | RI 置换 p | — | **p=0.177**（2⁴=16 置换；双侧最小可达 (1+2)/17≈0.176） | 2067 |
| 跨线暴露 | Wilson 率 | **18.3%** | [16.8%, 19.9%] | 2391 |

解读：簇内统一处理内部化了 R1/R2 换乘溢出，点估计更大；但只有 2 个簇、8 个簇—周单元，
渐近 CR1 区间偏窄且在少簇下反保守性不足，**随机化推断 p=0.177** 才是更诚实的不确定性证据——
这正是题目要求的“簇方案污染更小但可用随机化单元少、置信/分辨更弱”。

护栏最新值（独立于候车改善）：

| 护栏 | 观测 | 冻结阈值 | 结论 |
| --- | --- | --- | --- |
| 投诉率（/万乘客人次，按服务时间） | ≈145.9 | 45 | **BREACHED** |
| 滞留/未完成或候车 >1200s 占比 | 4.12% | 3% | **BREACHED** |
| LF≥1.0 满载班次占比 | 3.23% | 5% | OK（分流 R2 班次进入 Top 满载明细） |

因此扩围证据页与全局横幅均为 **BLOCKED**：即使平均候车显著改善，投诉与安全护栏超限仍独立阻止扩围。

---

## 六、主要 HTTP API

所有 `/api/**` 需登录（表单 Cookie 或 Basic）。完整交互文档：`/swagger-ui.html`。

| 方法/路径 | 作用 |
| --- | --- |
| `GET /api/me` | 当前账号与角色 |
| `GET/POST /api/experiments`、`GET /api/experiments/{id}` | 列表/登记/详情 |
| `POST /api/experiments/{id}/register` | 冻结簇并生成两套分配与指纹 |
| `GET /api/experiments/{id}/network` | 实验前边、冻结簇、两设计有效单元与选择依据 |
| `GET /api/experiments/{id}/assignments?design=...` | `TRIP_RANDOMIZED` / `CLUSTER_WEEK` 分配清单 |
| `POST /api/experiments/{id}/pipeline/run` | 用冻结数据重算暴露账→支持层→估计→护栏 |
| `GET /api/experiments/{id}/exposure` | 暴露台账（可按 pathLabel/q 过滤） |
| `GET /api/experiments/{id}/journeys/{code}` | 单旅程：卡片 + 逐段 GPS/臂/偏离 + 判定追踪 |
| `GET /api/experiments/{id}/support` | 共同支持分层与覆盖缺口 |
| `GET /api/experiments/{id}/analysis` | 全部估计结果 |
| `GET/POST /api/experiments/{id}/guardrails[/evaluate]` | 护栏规则/最新评估/重新评估 |
| `GET /api/experiments/{id}/expansion`、`POST .../expansion/evaluate|decide` | 扩围证据与结论（APPROVER） |
| `GET /api/experiments/{id}/review-bundle` | 离线审阅包（冻结配置+分配哈希+追踪+结果） |
| `GET /api/migration/workbench`、`/od-matrix`、`/boardings`、`/trajectories`、`/candidates`、`/journeys`、`/surveys`、`/verification`、`/publication` | **feature-2 客流迁移重建**：五产物 + 双引擎 OD 区间 + 抽样核验（详见上文 ★ 章节；均有 `/api/experiments/{id}/migration/*` 等价路径） |
| `POST /api/migration/rebuild`、`POST /api/migration/publish` | 从原始 AFC/AVL/换乘重算；冲突 OD 单点发布服务端 409（ANALYST+） |
| `GET /api/experiments/{id}/events`、`/complaints` | 外部事件时间线、投诉时间关联核验 |

---

## 七、目录与实现说明

```
src/main/java/com/ccdev/transit/
  engine/            # 纯逻辑、无 Spring：确定性哈希、平衡分配、网络聚类、路径分类、统计/RI
  engine/mig/        # feature-2：MigrationEngine 双引擎、Mig 类型、MigSampleData 确定性样例
  service/           # 登记冻结、确定性播种器、分析流水线、扩围决策、Migration(Seed)Service
  web/               # REST 控制器与 DTO（含 MigrationController）
  config/ security/  # Spring Security 三角色、OpenAPI、jsonb 响应处理
src/main/resources/
  schema.sql         # 幂等 PostgreSQL 建表（父实验表 + mig_* 迁移子系统表）
  static/            # 原生 JS 单页控制台（login/index/app/styles，含“客流迁移重建”页）
src/test/java/...    # 22 个纯 JUnit 测试（不依赖数据库/Docker，其中 6 个迁移引擎测试）
verify.sh            # 父任务 34 项端到端不变量
verify-migration.sh  # feature-2 49 项端到端不变量
Dockerfile docker-compose.yml docker-settings.xml .gitattributes
```

关键口径：

- **分配臂 vs 实际暴露是两条轴**：ITT 永远按随机分配臂；司机不执行/短线折返不改变分配臂，只改变实际路径标签。
- **CROSS_MIXED 单独成类**：枢纽异臂搭乘、短线折返分流都进该类，绝不并入 `FULL_CONTROL`。
- **不把执行者子集叫随机效应**：实际路径/执行者均值以 `AS_TREATED_ASSOCIATION` 明确标注为观察性关联；因果口径只有 ITT 与带假设的 CACE。
- **未知保留**：`INCOMPLETE_TAP/MID_ROUTE_CHANGE` 不插补、不归臂、不进效应估计，只计数并进入安全护栏。
- **共同支持**：无支持层旅程不产生外推结论，仅在支持页与 `gaps` 中报告覆盖缺口。
- **时间基准**：天气/施工/改线与 `service_ts` 按窗口重叠关联；护栏投诉率按 `complaint.service_event_time` 归集。
