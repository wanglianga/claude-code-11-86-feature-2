-- 公交灰度实验服务 PostgreSQL schema（幂等，可重复启动）

CREATE TABLE IF NOT EXISTS app_user (
    id            BIGSERIAL PRIMARY KEY,
    username      TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    display_name  TEXT,
    role          TEXT NOT NULL CHECK (role IN ('VIEWER','ANALYST','APPROVER'))
);

CREATE TABLE IF NOT EXISTS experiment (
    id                  BIGSERIAL PRIMARY KEY,
    code                TEXT UNIQUE NOT NULL,
    name                TEXT NOT NULL,
    status              TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT/REGISTERED/RUNNING/PAUSED/STOPPED/COMPLETED
    active_design       TEXT NOT NULL DEFAULT 'TRIP_RANDOMIZED',
    seed                TEXT NOT NULL,
    assignment_prob     DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    saturation          DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    pilot_weeks         INT NOT NULL DEFAULT 4,
    start_date          DATE,
    end_date            DATE,
    target_population   TEXT,
    treatment_rules     TEXT,   -- JSON：处理规则（缩短间隔/短线折返）
    primary_metrics     TEXT,   -- JSON 数组
    safety_metrics      TEXT,   -- JSON 数组
    network_config      TEXT,   -- JSON：换乘边阈值等
    analysis_config     TEXT,   -- JSON：共同支持最小样本等
    registration_hash   TEXT,
    registered_at       TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS route (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    UNIQUE (experiment_id, code)
);

CREATE TABLE IF NOT EXISTS stop_ (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    kind          TEXT NOT NULL DEFAULT 'NORMAL', -- NORMAL/HUB
    UNIQUE (experiment_id, code)
);

-- 实验前刷卡换乘比例（冻结，不允许用试点结果重算）
CREATE TABLE IF NOT EXISTS pre_transfer_edge (
    id                 BIGSERIAL PRIMARY KEY,
    experiment_id      BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    from_route         TEXT NOT NULL,
    to_route           TEXT NOT NULL,
    transfer_journeys  INTEGER NOT NULL,
    total_journeys     INTEGER NOT NULL,
    ratio              DOUBLE PRECISION NOT NULL,
    frozen             BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, from_route, to_route)
);

-- 依据实验前网络一次性冻结的线路簇
CREATE TABLE IF NOT EXISTS route_cluster (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    cluster_code  TEXT NOT NULL,
    route_codes   TEXT NOT NULL, -- 逗号分隔，排序
    frozen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (experiment_id, cluster_code)
);

-- 随机化单元与分配结果（同一实验保存两套设计的分配）
CREATE TABLE IF NOT EXISTS assignment_unit (
    id                BIGSERIAL PRIMARY KEY,
    experiment_id     BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    design_type       TEXT NOT NULL, -- TRIP_RANDOMIZED / CLUSTER_WEEK
    unit_key          TEXT NOT NULL,
    stratum           TEXT NOT NULL,
    label             TEXT,
    route_codes       TEXT NOT NULL,
    cluster_code      TEXT,
    week_index        INTEGER,
    hash_value        TEXT NOT NULL,
    arm               TEXT NOT NULL, -- TREATMENT / CONTROL
    inclusion_prob    DOUBLE PRECISION NOT NULL,
    UNIQUE (experiment_id, design_type, unit_key)
);
CREATE INDEX IF NOT EXISTS idx_assignment_exp ON assignment_unit(experiment_id, design_type);

-- 计划班次（日期级实例，保留计划分配）
CREATE TABLE IF NOT EXISTS service_trip (
    id                BIGSERIAL PRIMARY KEY,
    experiment_id     BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    trip_code         TEXT NOT NULL,
    template_key      TEXT NOT NULL, -- TRIP:R1:T02
    cluster_week_key  TEXT NOT NULL, -- CW:C12:W1
    route_code        TEXT NOT NULL,
    service_date      DATE NOT NULL,
    week_index        INTEGER NOT NULL,
    period_band       TEXT NOT NULL,
    seq_in_band       INTEGER NOT NULL DEFAULT 1,
    planned_departure TIMESTAMPTZ NOT NULL,
    od_band           TEXT,
    capacity          INTEGER NOT NULL DEFAULT 80,
    eligible          BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, trip_code)
);
CREATE INDEX IF NOT EXISTS idx_trip_lookup ON service_trip(experiment_id, route_code, service_date, period_band);

-- 实际 GPS/AVL 运行与偏离（实际执行状态）
CREATE TABLE IF NOT EXISTS actual_service_run (
    id                  BIGSERIAL PRIMARY KEY,
    trip_id             BIGINT NOT NULL UNIQUE REFERENCES service_trip(id) ON DELETE CASCADE,
    gps_run_code        TEXT NOT NULL,
    actual_departure    TIMESTAMPTZ,
    actual_arrival      TIMESTAMPTZ,
    headway_seconds     INTEGER,
    rule_executed       BOOLEAN NOT NULL DEFAULT TRUE,
    deviation           TEXT NOT NULL DEFAULT 'NONE', -- NONE/SHORT_TURN/SUSPENDED/OVERTIME/DELAY
    short_turn_at_stop  TEXT,
    diverted_to_route   TEXT,
    delay_seconds       INTEGER NOT NULL DEFAULT 0,
    observed_lf         DOUBLE PRECISION,
    source              TEXT NOT NULL DEFAULT 'GPS_AVL'
);

-- 天气/施工/临时改线事件，按事件发生时间关联服务
CREATE TABLE IF NOT EXISTS external_event (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    event_type     TEXT NOT NULL, -- WEATHER_RAINSTORM/WEATHER_RAIN/CONSTRUCTION/ROUTE_CHANGE
    severity       TEXT,
    title          TEXT NOT NULL,
    start_ts       TIMESTAMPTZ NOT NULL,
    end_ts         TIMESTAMPTZ NOT NULL,
    affected_routes TEXT NOT NULL DEFAULT '*',
    suspension     BOOLEAN NOT NULL DEFAULT FALSE,
    source         TEXT
);

-- 刷卡完整旅程（未完成刷卡/中途改线保留为非 COMPLETE 状态）
CREATE TABLE IF NOT EXISTS card_journey (
    id                        BIGSERIAL PRIMARY KEY,
    experiment_id             BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code              TEXT NOT NULL UNIQUE,
    week_index                INTEGER NOT NULL,
    service_date              DATE NOT NULL,
    service_ts                TIMESTAMPTZ NOT NULL,
    period_band               TEXT NOT NULL,
    origin_stop               TEXT,
    hub_stop                  TEXT,
    dest_stop                 TEXT,
    od_band                   TEXT NOT NULL,
    route_pattern             TEXT NOT NULL, -- R1ONLY/R2ONLY/R1R2/R3ONLY/...
    weather_class             TEXT NOT NULL DEFAULT 'NORMAL',
    accessibility             BOOLEAN NOT NULL DEFAULT TRUE,
    status                    TEXT NOT NULL, -- COMPLETE/INCOMPLETE_TAP/MID_ROUTE_CHANGE
    passenger_count           INTEGER NOT NULL DEFAULT 1,
    anchor_unit_key           TEXT,
    anchor_arm_trip           TEXT,
    cluster_week_key          TEXT,
    cluster_code              TEXT,
    anchor_arm_cluster        TEXT,
    wait_seconds              DOUBLE PRECISION,
    first_wait_seconds        DOUBLE PRECISION,
    transfer_wait_seconds     DOUBLE PRECISION NOT NULL DEFAULT 0,
    crowd_exposure            DOUBLE PRECISION,
    severe_crowd_minutes      DOUBLE PRECISION,
    max_load_factor           DOUBLE PRECISION,
    -- 同一目标总体在“簇—周”方案下按同一 DGP 构造的可复现实现结果
    wait_cluster_seconds      DOUBLE PRECISION,
    crowd_cluster_exposure    DOUBLE PRECISION,
    severe_crowd_cluster_min  DOUBLE PRECISION,
    complaint_count           INTEGER NOT NULL DEFAULT 0,
    is_demo                   BOOLEAN NOT NULL DEFAULT FALSE,
    note                      TEXT
);
CREATE INDEX IF NOT EXISTS idx_journey_exp ON card_journey(experiment_id);
CREATE INDEX IF NOT EXISTS idx_journey_stratum ON card_journey(experiment_id, period_band, od_band, weather_class, accessibility);

CREATE TABLE IF NOT EXISTS journey_leg (
    id             BIGSERIAL PRIMARY KEY,
    journey_id     BIGINT NOT NULL REFERENCES card_journey(id) ON DELETE CASCADE,
    leg_index      INTEGER NOT NULL,
    route_code     TEXT NOT NULL,
    trip_id        BIGINT REFERENCES service_trip(id),
    actual_run_id  BIGINT REFERENCES actual_service_run(id),
    board_stop     TEXT,
    alight_stop    TEXT,
    is_transfer    BOOLEAN NOT NULL DEFAULT FALSE,
    unit_key       TEXT,
    planned_arm    TEXT,
    rule_effective BOOLEAN,
    deviation      TEXT,
    leg_exposure   TEXT NOT NULL, -- TREATMENT/CONTROL/CROSS/UNKNOWN
    wait_seconds   DOUBLE PRECISION NOT NULL DEFAULT 0,
    ride_minutes   DOUBLE PRECISION NOT NULL DEFAULT 0,
    load_factor    DOUBLE PRECISION,
    note           TEXT
);
CREATE INDEX IF NOT EXISTS idx_leg_journey ON journey_leg(journey_id);

-- 暴露账：每旅程完整路径一行
CREATE TABLE IF NOT EXISTS exposure_ledger (
    journey_id            BIGINT PRIMARY KEY REFERENCES card_journey(id) ON DELETE CASCADE,
    experiment_id         BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code          TEXT NOT NULL,
    status                TEXT NOT NULL,
    path_label            TEXT NOT NULL, -- FULL_TREATMENT/FULL_CONTROL/CROSS_MIXED/UNKNOWN
    cluster_path_label    TEXT NOT NULL, -- CLUSTER_TREATMENT/CLUSTER_CONTROL/CLUSTER_INTERNAL_MIX/UNKNOWN
    treatment_legs        INTEGER NOT NULL DEFAULT 0,
    control_legs          INTEGER NOT NULL DEFAULT 0,
    cross_legs            INTEGER NOT NULL DEFAULT 0,
    unknown_legs          INTEGER NOT NULL DEFAULT 0,
    assigned_arm_trip     TEXT,
    assigned_arm_cluster  TEXT,
    effective_treatment   BOOLEAN NOT NULL DEFAULT FALSE,
    cross_line            BOOLEAN NOT NULL DEFAULT FALSE,
    diverted              BOOLEAN NOT NULL DEFAULT FALSE,
    path_probability      DOUBLE PRECISION,
    path_detail           TEXT,
    rebuilt_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ledger_path ON exposure_ledger(experiment_id, path_label);

CREATE TABLE IF NOT EXISTS support_stratum (
    id               BIGSERIAL PRIMARY KEY,
    experiment_id    BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    stratum_key      TEXT NOT NULL,
    period_band      TEXT,
    od_band          TEXT,
    weather_class    TEXT,
    accessibility    BOOLEAN,
    n_total          INTEGER,
    weighted_total   DOUBLE PRECISION,
    n_t_trip         INTEGER,
    n_c_trip         INTEGER,
    w_t_trip         DOUBLE PRECISION,
    w_c_trip         DOUBLE PRECISION,
    n_t_cluster      INTEGER,
    n_c_cluster      INTEGER,
    w_t_cluster      DOUBLE PRECISION,
    w_c_cluster      DOUBLE PRECISION,
    supported_trip   BOOLEAN,
    supported_cluster BOOLEAN,
    gap_reason       TEXT,
    UNIQUE (experiment_id, stratum_key)
);

CREATE TABLE IF NOT EXISTS analysis_result (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    design_type   TEXT NOT NULL,
    estimator_code TEXT NOT NULL, -- ITT / CACE_WALD / PATH_MEAN / CROSS_RATE / RI ...
    metric_code   TEXT NOT NULL,  -- wait_seconds / crowd_exposure ...
    scope         TEXT NOT NULL,  -- COMMON_SUPPORT / ALL_ELIGIBLE / PATH_*
    estimate      DOUBLE PRECISION,
    se            DOUBLE PRECISION,
    ci_low        DOUBLE PRECISION,
    ci_high       DOUBLE PRECISION,
    p_value       DOUBLE PRECISION,
    n_journeys    INTEGER,
    n_units       INTEGER,
    detail        TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_result_exp ON analysis_result(experiment_id);

CREATE TABLE IF NOT EXISTS guardrail_rule (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    metric_source TEXT NOT NULL,
    comparator    TEXT NOT NULL DEFAULT 'GT', -- GT/LT
    threshold     DOUBLE PRECISION NOT NULL,
    min_sample    INTEGER NOT NULL DEFAULT 30,
    enabled       BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE (experiment_id, code)
);

CREATE TABLE IF NOT EXISTS guardrail_evaluation (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    rule_code     TEXT NOT NULL,
    observed      DOUBLE PRECISION,
    threshold     DOUBLE PRECISION,
    sample_n      INTEGER,
    breached      BOOLEAN NOT NULL,
    action        TEXT NOT NULL, -- OK / WARN / STOP_EXPANSION / INSUFFICIENT_EVIDENCE
    detail        TEXT,
    evaluated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_ge_exp ON guardrail_evaluation(experiment_id);

CREATE TABLE IF NOT EXISTS expansion_decision (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    decision      TEXT NOT NULL, -- GO / HOLD / BLOCKED
    reason        TEXT,
    evidence      TEXT,
    decided_by    TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS complaint (
    id                   BIGSERIAL PRIMARY KEY,
    experiment_id        BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    complaint_code       TEXT,
    journey_id           BIGINT REFERENCES card_journey(id) ON DELETE SET NULL,
    route_code           TEXT,
    category             TEXT,
    severity             TEXT,
    complaint_time       TIMESTAMPTZ NOT NULL,  -- 投诉提交时间（可滞后服务数日）
    service_event_time   TIMESTAMPTZ NOT NULL,  -- 实际服务发生时间（统计关联基准）
    channel              TEXT
);
CREATE INDEX IF NOT EXISTS idx_complaint_service_time ON complaint(experiment_id, service_event_time);

CREATE TABLE IF NOT EXISTS load_count (
    id             BIGSERIAL PRIMARY KEY,
    actual_run_id BIGINT REFERENCES actual_service_run(id) ON DELETE CASCADE,
    trip_id        BIGINT REFERENCES service_trip(id) ON DELETE CASCADE,
    observed_at    TIMESTAMPTZ,
    passengers     INTEGER,
    capacity       INTEGER,
    load_factor    DOUBLE PRECISION
);

-- =====================================================================
-- 客流迁移 / 旅程重建子系统（feature-2）
-- 从 AFC 刷卡、AVL 车载定位、换乘记录重建实际旅程，量化短线折返的额外换乘损失。
-- 与父任务“实验干扰处理”相互独立：这里是 OD 站对客流迁移与双引擎区间。
-- =====================================================================

-- 迁移子系统自己的车站维表（使用与父样例一致的 HUB-CENTRAL 命名）
CREATE TABLE IF NOT EXISTS mig_station (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    name          TEXT NOT NULL,
    is_hub        BOOLEAN NOT NULL DEFAULT FALSE,
    attract       DOUBLE PRECISION NOT NULL DEFAULT 1.0, -- 下车吸引先验（由有刷下车样本校准）
    UNIQUE (experiment_id, code)
);

-- 线路站序与运营参数（时刻表基线）
CREATE TABLE IF NOT EXISTS mig_route (
    id             BIGSERIAL PRIMARY KEY,
    experiment_id  BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    code           TEXT NOT NULL,
    name           TEXT NOT NULL,
    stop_seq       TEXT NOT NULL,   -- 逗号分隔站码（有序）
    headway_sec    INTEGER NOT NULL DEFAULT 300,
    transfer_walk_sec INTEGER NOT NULL DEFAULT 180,
    UNIQUE (experiment_id, code)
);

-- AFC 刷卡原始交易（绝大多数只有上车 TAP_IN，少量 TAP_OUT 用于校准/核验）
CREATE TABLE IF NOT EXISTS mig_afc_tap (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    card_code     TEXT NOT NULL,
    txn_type      TEXT NOT NULL, -- TAP_IN / TAP_OUT
    service_date  DATE NOT NULL,
    minute_of_day INTEGER NOT NULL, -- 当日分钟，确定性计算用
    event_ts      TIMESTAMPTZ,
    station_code  TEXT NOT NULL,
    route_code    TEXT,
    vehicle_code  TEXT,
    trip_code     TEXT,          -- AFC 可能只给车不给班次；为空时由轨迹对齐补
    passenger_count INTEGER NOT NULL DEFAULT 1
);
CREATE INDEX IF NOT EXISTS idx_afc_card ON mig_afc_tap(experiment_id, card_code);

-- AVL 车载定位点（逐站到离时刻 + 定位质量；fix_ok=FALSE 区间即定位中断）
CREATE TABLE IF NOT EXISTS mig_avl_point (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    trip_code     TEXT NOT NULL,
    route_code    TEXT NOT NULL,
    vehicle_code  TEXT NOT NULL,
    service_date  DATE NOT NULL,
    minute_of_day INTEGER NOT NULL,
    event_ts      TIMESTAMPTZ,
    station_code  TEXT,
    seq_index     INTEGER NOT NULL,
    progress_km   DOUBLE PRECISION NOT NULL DEFAULT 0,
    fix_ok        BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE INDEX IF NOT EXISTS idx_avl_trip ON mig_avl_point(experiment_id, trip_code);

-- 班次轨迹头：实际是否短线折返、折返站、定位中断区间
CREATE TABLE IF NOT EXISTS mig_trip_trace (
    id               BIGSERIAL PRIMARY KEY,
    experiment_id    BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    trip_code        TEXT NOT NULL,
    route_code       TEXT NOT NULL,
    vehicle_code     TEXT NOT NULL,
    service_date     DATE NOT NULL,
    short_turn       BOOLEAN NOT NULL DEFAULT FALSE,
    turn_stop        TEXT,
    last_actual_stop TEXT,
    outage           BOOLEAN NOT NULL DEFAULT FALSE,
    outage_from_stop TEXT,
    outage_to_stop   TEXT,
    congestion_sec  INTEGER NOT NULL DEFAULT 0, -- AVL 相对时刻表的累计延误（拥堵错配来源）
    UNIQUE (experiment_id, trip_code)
);

-- 换乘记录（同一卡短时间内第二次上车，或 fare 换乘标记）
CREATE TABLE IF NOT EXISTS mig_transfer_record (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    card_code     TEXT NOT NULL,
    service_date  DATE NOT NULL,
    from_route    TEXT,
    from_trip     TEXT,
    from_station  TEXT,
    to_route      TEXT NOT NULL,
    to_trip       TEXT,
    to_station    TEXT NOT NULL,
    minute_of_day INTEGER NOT NULL,
    wait_sec      INTEGER,
    source        TEXT NOT NULL DEFAULT 'CARD_CHAIN' -- CARD_CHAIN / FARE_FLAG / SURVEY
);
CREATE INDEX IF NOT EXISTS idx_xfer_card ON mig_transfer_record(experiment_id, card_code);

-- ① 上车观察账：每次首乘刷卡与 AVL/班次对齐后的观察
CREATE TABLE IF NOT EXISTS mig_boarding_ledger (
    id               BIGSERIAL PRIMARY KEY,
    experiment_id    BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    boarding_code    TEXT NOT NULL UNIQUE,
    card_code        TEXT NOT NULL,
    service_date     DATE NOT NULL,
    board_minute     INTEGER NOT NULL,
    board_station    TEXT NOT NULL,
    route_code       TEXT NOT NULL,
    vehicle_code     TEXT,
    matched_trip     TEXT,
    align_status     TEXT NOT NULL, -- ALIGNED / NEAREST_FALLBACK / GPS_GAP_FUZZY / UNMATCHED
    time_offset_sec  INTEGER NOT NULL DEFAULT 0,
    location_outage  BOOLEAN NOT NULL DEFAULT FALSE,
    short_turn       BOOLEAN NOT NULL DEFAULT FALSE,
    turn_stop        TEXT,
    has_tap_out      BOOLEAN NOT NULL DEFAULT FALSE,
    observed_transfer BOOLEAN NOT NULL DEFAULT FALSE,
    detail           TEXT
);
CREATE INDEX IF NOT EXISTS idx_board_exp ON mig_boarding_ledger(experiment_id);

-- ③ 候选下车站集合（定位中断/无刷下车时保留多候选，不提前收敛）
CREATE TABLE IF NOT EXISTS mig_alight_candidate (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    boarding_code   TEXT NOT NULL,
    station_code    TEXT NOT NULL,
    rank_no         INTEGER NOT NULL,
    prior_weight    DOUBLE PRECISION NOT NULL,
    posterior_prob  DOUBLE PRECISION NOT NULL,
    det_selected    BOOLEAN NOT NULL DEFAULT FALSE,
    retain_reason   TEXT,
    UNIQUE (experiment_id, boarding_code, station_code)
);
CREATE INDEX IF NOT EXISTS idx_cand_board ON mig_alight_candidate(boarding_code);

-- 重建出的乘客旅程（同时保存确定性与概率两套结论）
CREATE TABLE IF NOT EXISTS mig_rebuilt_journey (
    id                  BIGSERIAL PRIMARY KEY,
    experiment_id       BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    journey_code        TEXT NOT NULL UNIQUE,
    boarding_code       TEXT NOT NULL,
    card_code           TEXT NOT NULL,
    origin_stop         TEXT NOT NULL,
    -- 确定性时刻表分配
    det_dest            TEXT,
    det_path            TEXT,
    det_transfers       INTEGER NOT NULL DEFAULT 0,
    det_transfer_wait   INTEGER NOT NULL DEFAULT 0,
    det_loss_sec        INTEGER NOT NULL DEFAULT 0,
    det_confidence      DOUBLE PRECISION NOT NULL DEFAULT 1,
    det_mismatch_risk   BOOLEAN NOT NULL DEFAULT FALSE,
    -- 概率旅程推断
    prob_dest_map       TEXT,    -- JSON {dest: prob}
    prob_option_map     TEXT,    -- JSON 全候选路径后验
    prob_transfers_exp  DOUBLE PRECISION NOT NULL DEFAULT 0,
    prob_loss_mean      DOUBLE PRECISION NOT NULL DEFAULT 0,
    prob_loss_var       DOUBLE PRECISION NOT NULL DEFAULT 0,
    mig_prob            DOUBLE PRECISION NOT NULL DEFAULT 0, -- 折返导致额外换乘的后验概率
    multi_candidate     BOOLEAN NOT NULL DEFAULT FALSE,
    location_outage     BOOLEAN NOT NULL DEFAULT FALSE,
    short_turn          BOOLEAN NOT NULL DEFAULT FALSE,
    extra_transfer      BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE INDEX IF NOT EXISTS idx_rj_exp ON mig_rebuilt_journey(experiment_id);
CREATE INDEX IF NOT EXISTS idx_rj_od ON mig_rebuilt_journey(experiment_id, origin_stop, det_dest);

-- ④ 旅程流量矩阵：同一 OD 站对 × 两引擎的旅程量/迁移量/换乘损失区间
CREATE TABLE IF NOT EXISTS mig_od_flow (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    origin_stop     TEXT NOT NULL,
    dest_stop       TEXT NOT NULL,
    method          TEXT NOT NULL, -- DETERMINISTIC / PROBABILISTIC
    origin_boardings INTEGER NOT NULL DEFAULT 0,
    journey_point   DOUBLE PRECISION NOT NULL,
    journey_low     DOUBLE PRECISION NOT NULL,
    journey_high    DOUBLE PRECISION NOT NULL,
    mig_point       DOUBLE PRECISION NOT NULL,
    mig_low         DOUBLE PRECISION NOT NULL,
    mig_high        DOUBLE PRECISION NOT NULL,
    loss_mean_sec   DOUBLE PRECISION NOT NULL,
    loss_low_sec    DOUBLE PRECISION NOT NULL,
    loss_high_sec   DOUBLE PRECISION NOT NULL,
    total_loss_hours DOUBLE PRECISION NOT NULL,
    total_loss_low   DOUBLE PRECISION NOT NULL,
    total_loss_high  DOUBLE PRECISION NOT NULL,
    UNIQUE (experiment_id, origin_stop, dest_stop, method)
);
CREATE INDEX IF NOT EXISTS idx_od_exp ON mig_od_flow(experiment_id);

-- ⑤ 抽样核验：人工/问卷抽样的 OD 与“是否因折返额外换乘”
CREATE TABLE IF NOT EXISTS mig_survey_sample (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    sample_code   TEXT NOT NULL UNIQUE,
    origin_stop   TEXT NOT NULL,
    dest_stop     TEXT NOT NULL,
    sample_n      INTEGER NOT NULL DEFAULT 1,  -- 聚合记录时的样本数
    extra_yes     INTEGER NOT NULL DEFAULT 0,
    od_population INTEGER NOT NULL DEFAULT 0,   -- 该 OD 可代表的上车总体（硬计数）
    surveyed_at   DATE,
    source        TEXT NOT NULL DEFAULT 'FIELD_SURVEY',
    note          TEXT
);

-- OD 级核验结论（抽样区间 vs 推断区间）
CREATE TABLE IF NOT EXISTS mig_verification (
    id              BIGSERIAL PRIMARY KEY,
    experiment_id   BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    origin_stop     TEXT NOT NULL,
    dest_stop       TEXT NOT NULL,
    sample_n        INTEGER NOT NULL,
    extra_yes       INTEGER NOT NULL,
    survey_share    DOUBLE PRECISION,
    survey_low      DOUBLE PRECISION,
    survey_high     DOUBLE PRECISION,
    survey_vol_low  DOUBLE PRECISION,
    survey_vol_high DOUBLE PRECISION,
    infer_low       DOUBLE PRECISION,
    infer_high      DOUBLE PRECISION,
    intervals_overlap BOOLEAN NOT NULL DEFAULT TRUE,
    status          TEXT NOT NULL, -- CORROBORATED / CONFLICT / NO_SURVEY / INSUFFICIENT
    note            TEXT,
    UNIQUE (experiment_id, origin_stop, dest_stop)
);

-- 发布门控：冲突 OD 禁止发布单点迁移量，只能发区间/暂缓
CREATE TABLE IF NOT EXISTS mig_publication (
    id            BIGSERIAL PRIMARY KEY,
    experiment_id BIGINT NOT NULL REFERENCES experiment(id) ON DELETE CASCADE,
    scope         TEXT NOT NULL, -- REPORT 或 OD:E2>W2
    publish_mode  TEXT NOT NULL, -- POINT / INTERVAL
    status        TEXT NOT NULL, -- PUBLISHED / INTERVAL_ONLY / BLOCKED
    point_value   DOUBLE PRECISION,
    interval_low  DOUBLE PRECISION,
    interval_high DOUBLE PRECISION,
    payload       TEXT,
    reason        TEXT,
    published_by  TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pub_exp ON mig_publication(experiment_id);
