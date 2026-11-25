package com.ccdev.transit.service;

import com.ccdev.transit.engine.mig.Mig;
import com.ccdev.transit.engine.mig.MigSampleData;
import com.ccdev.transit.engine.mig.MigrationEngine;
import com.ccdev.transit.util.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客流迁移重建编排：从库中原始 AFC 刷卡 / AVL 轨迹 / 换乘记录装载输入，
 * 调用无状态双引擎 {@link MigrationEngine}，并把上车观察账、候选下车站、
 * 重建旅程、OD 流量矩阵、抽样核验落库；同时负责冲突感知的发布门控。
 */
@Service
public class MigrationService {

    private static final Logger log = LoggerFactory.getLogger(MigrationService.class);

    private final JdbcTemplate jdbc;

    public MigrationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 解析目标实验：显式 id 优先，否则用虚构样例编号，再退化为最早的实验。 */
    public long resolveId(Long id) {
        if (id != null) {
            return id;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM experiment WHERE code=?", Long.class, SeedService.SAMPLE_CODE);
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        Long any = jdbc.queryForObject("SELECT min(id) FROM experiment", Long.class);
        if (any == null) {
            throw new IllegalStateException("尚无实验，无法进行客流迁移重建");
        }
        return any;
    }

    // ============================ 重建 ============================

    @Transactional
    public Map<String, Object> rebuild(Long idOrNull) {
        long id = resolveId(idOrNull);

        Mig.EngineResult result = compute(id);

        // 清空派生产物（保留 mig_publication 发布历史与原始数据表）
        jdbc.update("DELETE FROM mig_alight_candidate WHERE experiment_id=?", id);
        jdbc.update("DELETE FROM mig_rebuilt_journey WHERE experiment_id=?", id);
        jdbc.update("DELETE FROM mig_od_flow WHERE experiment_id=?", id);
        jdbc.update("DELETE FROM mig_verification WHERE experiment_id=?", id);
        jdbc.update("DELETE FROM mig_boarding_ledger WHERE experiment_id=?", id);

        for (Mig.CaseResult c : result.cases()) {
            insertBoarding(id, c);
            insertCandidates(id, c);
            insertJourney(id, c);
        }
        for (Mig.OdRow row : result.matrix()) {
            insertOdBlock(id, row.deterministic());
            insertOdBlock(id, row.probabilistic());
        }
        for (Mig.SurveyResult s : result.verification()) {
            insertVerification(id, s);
        }

        log.info("客流迁移重建完成 exp={} cases={} odRows={} conflicts={}",
                id, result.cases().size(), result.matrix().size() * 2,
                result.reportGate().conflictOds().size());

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("ok", true);
        summary.put("experimentId", id);
        summary.put("boardingCases", result.cases().size());
        summary.put("odPairs", result.matrix().size());
        summary.put("odFlowRows", result.matrix().size() * 2);
        summary.put("verificationRows", result.verification().size());
        summary.put("detValidationAccuracy", Math.round(result.detValidationAccuracy() * 1000) / 1000.0);
        summary.put("detValidationN", result.detValidationN());
        summary.put("reportGate", result.reportGate());
        return summary;
    }

    private Mig.EngineResult compute(long id) {
        MigrationEngine.Network net = loadNetwork(id);
        List<Mig.Tap> taps = loadTaps(id);
        List<Mig.TransferRec> xfers = loadTransfers(id);
        List<Mig.SurveyInput> surveys = loadSurveys(id);
        return MigrationEngine.rebuild(net, taps, xfers, surveys);
    }

    /** 不落库的引擎诊断：确定性留出准确率、校准错误带与报告门控。 */
    public Map<String, Object> diagnostics(Long idOrNull) {
        long id = resolveId(idOrNull);
        Mig.EngineResult r = compute(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("detValidationAccuracy", Math.round(r.detValidationAccuracy() * 1000) / 1000.0);
        m.put("detValidationN", r.detValidationN());
        m.put("detCalibratedErrorBand",
                MigrationEngine.calibratedDetErrorBand(r.detValidationAccuracy()));
        m.put("transferPenaltySec", r.transferPenaltySec());
        m.put("reportGate", r.reportGate());
        return m;
    }

    private MigrationEngine.Network loadNetwork(long id) {
        List<Mig.Station> stations = jdbc.query(
                "SELECT code,name,is_hub,attract FROM mig_station WHERE experiment_id=? ORDER BY id",
                (rs, i) -> new Mig.Station(rs.getString("code"), rs.getString("name"),
                        rs.getBoolean("is_hub"), rs.getDouble("attract")), id);

        List<Mig.RouteInfo> routes = jdbc.query(
                "SELECT code,name,stop_seq,headway_sec,transfer_walk_sec FROM mig_route "
                        + "WHERE experiment_id=? ORDER BY id",
                (rs, i) -> new Mig.RouteInfo(rs.getString("code"), rs.getString("name"),
                        List.of(rs.getString("stop_seq").split(",")),
                        rs.getInt("headway_sec"), rs.getInt("transfer_walk_sec")), id);

        List<Map<String, Object>> headers = jdbc.queryForList(
                "SELECT * FROM mig_trip_trace WHERE experiment_id=?", id);
        List<Mig.TripTrace> traces = new ArrayList<>();
        for (Map<String, Object> h : headers) {
            String trip = (String) h.get("trip_code");
            Map<String, Integer> arrive = new LinkedHashMap<>();
            List<String> actual = new ArrayList<>();
            jdbc.query("SELECT station_code,minute_of_day FROM mig_avl_point "
                            + "WHERE experiment_id=? AND trip_code=? AND fix_ok=TRUE ORDER BY seq_index",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        String st = rs.getString(1);
                        int m = rs.getInt(2);
                        if (st != null) {
                            arrive.put(st, m);
                            actual.add(st);
                        }
                    }, id, trip);
            traces.add(new Mig.TripTrace(
                    trip, (String) h.get("route_code"), (String) h.get("vehicle_code"),
                    arrive, actual,
                    Boolean.TRUE.equals(h.get("short_turn")),
                    (String) h.get("turn_stop"), (String) h.get("last_actual_stop"),
                    Boolean.TRUE.equals(h.get("outage")),
                    (String) h.get("outage_from_stop"), (String) h.get("outage_to_stop"),
                    ((Number) h.getOrDefault("congestion_sec", 0)).intValue()));
        }
        return new MigrationEngine.Network(stations, routes, traces);
    }

    private List<Mig.Tap> loadTaps(long id) {
        return jdbc.query("SELECT card_code,txn_type,minute_of_day,station_code,route_code,"
                        + "vehicle_code,trip_code FROM mig_afc_tap WHERE experiment_id=? ORDER BY id",
                (rs, i) -> new Mig.Tap(rs.getString("card_code"), rs.getString("txn_type"),
                        rs.getInt("minute_of_day"), rs.getString("station_code"),
                        rs.getString("route_code"), rs.getString("vehicle_code"),
                        rs.getString("trip_code")), id);
    }

    private List<Mig.TransferRec> loadTransfers(long id) {
        return jdbc.query("SELECT card_code,from_route,from_trip,from_station,to_route,to_trip,"
                        + "to_station,minute_of_day,wait_sec FROM mig_transfer_record "
                        + "WHERE experiment_id=? ORDER BY id",
                (rs, i) -> new Mig.TransferRec(rs.getString("card_code"),
                        rs.getString("from_route"), rs.getString("from_trip"),
                        rs.getString("from_station"), rs.getString("to_route"),
                        rs.getString("to_trip"), rs.getString("to_station"),
                        rs.getInt("minute_of_day"),
                        rs.getObject("wait_sec") == null ? null : rs.getInt("wait_sec")), id);
    }

    private List<Mig.SurveyInput> loadSurveys(long id) {
        return jdbc.query("SELECT origin_stop,dest_stop,sample_n,extra_yes,od_population "
                        + "FROM mig_survey_sample WHERE experiment_id=? ORDER BY id",
                (rs, i) -> new Mig.SurveyInput(rs.getString("origin_stop"),
                        rs.getString("dest_stop"), rs.getInt("sample_n"),
                        rs.getInt("extra_yes"), rs.getInt("od_population")), id);
    }

    // ---------------- 派生写入 ----------------

    private void insertBoarding(long id, Mig.CaseResult c) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("expectedLoss", Math.round(c.expectedLoss()));
        detail.put("migProbability", c.migProb());
        detail.put("candidateDestCount", c.candidates().size());
        jdbc.update("""
                INSERT INTO mig_boarding_ledger(experiment_id,boarding_code,card_code,service_date,
                  board_minute,board_station,route_code,vehicle_code,matched_trip,align_status,
                  time_offset_sec,location_outage,short_turn,turn_stop,has_tap_out,
                  observed_transfer,detail)
                VALUES (?,?,?,?::date,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, c.boardingCode(), c.card(), MigSampleData.DATE,
                c.boardMinute(), c.origin(), c.route(), c.vehicle(), c.matchedTrip(),
                c.align().name(), c.timeOffsetSec(), c.outage(), c.shortTurn(), c.turnStop(),
                c.tapOut(), c.observedWaitSec() != null, Json.write(detail));
    }

    private void insertCandidates(long id, Mig.CaseResult c) {
        int rank = 0;
        for (Mig.Candidate cand : c.candidates()) {
            jdbc.update("""
                    INSERT INTO mig_alight_candidate(experiment_id,boarding_code,station_code,rank_no,
                      prior_weight,posterior_prob,det_selected,retain_reason)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, id, c.boardingCode(), cand.station(), ++rank,
                    cand.prior(), cand.posterior(), cand.detChosen(), cand.reason());
        }
    }

    private void insertJourney(long id, Mig.CaseResult c) {
        double var = Math.max(0, c.expectedLossSq() - c.expectedLoss() * c.expectedLoss());
        Map<String, Double> destMap = new LinkedHashMap<>();
        for (Mig.Candidate cand : c.candidates()) {
            destMap.put(cand.station(), cand.posterior());
        }
        double expTransfers = c.options().stream()
                .mapToDouble(o -> o.probability() * o.transfers()).sum();
        boolean extra = c.detExtraTransfer() || c.migProb() >= 0.5;
        jdbc.update("""
                INSERT INTO mig_rebuilt_journey(experiment_id,journey_code,boarding_code,card_code,
                  origin_stop,det_dest,det_path,det_transfers,det_transfer_wait,det_loss_sec,
                  det_confidence,det_mismatch_risk,prob_dest_map,prob_option_map,
                  prob_transfers_exp,prob_loss_mean,prob_loss_var,mig_prob,multi_candidate,
                  location_outage,short_turn,extra_transfer)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?::text,?::text,?,?,?,?,?,?,?,?)
                """,
                id, "MJ-" + c.card(), c.boardingCode(), c.card(), c.origin(),
                c.detDest(), c.detPlan(), c.detTransfers(), c.detWaitSec(), c.detLossSec(),
                c.detConfidence(), c.detMismatchRisk(),
                Json.write(destMap), Json.write(c.options()),
                Math.round(expTransfers * 100) / 100.0,
                Math.round(c.expectedLoss() * 10) / 10.0,
                Math.round(var * 10) / 10.0,
                Math.round(c.migProb() * 1000) / 1000.0,
                c.multiCandidate(), c.outage(), c.shortTurn(), extra);
    }

    private void insertOdBlock(long id, Mig.OdBlock b) {
        jdbc.update("""
                INSERT INTO mig_od_flow(experiment_id,origin_stop,dest_stop,method,origin_boardings,
                  journey_point,journey_low,journey_high,mig_point,mig_low,mig_high,
                  loss_mean_sec,loss_low_sec,loss_high_sec,total_loss_hours,total_loss_low,
                  total_loss_high)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, b.origin(), b.dest(), b.method().name(), b.originBoardings(),
                b.journeyPoint(), b.journeyLow(), b.journeyHigh(),
                b.migPoint(), b.migLow(), b.migHigh(),
                b.lossMeanSec(), b.lossLowSec(), b.lossHighSec(),
                b.totalLossHours(), b.totalLossLowHours(), b.totalLossHighHours());
    }

    private void insertVerification(long id, Mig.SurveyResult s) {
        jdbc.update("""
                INSERT INTO mig_verification(experiment_id,origin_stop,dest_stop,sample_n,extra_yes,
                  survey_share,survey_low,survey_high,survey_vol_low,survey_vol_high,
                  infer_low,infer_high,intervals_overlap,status,note)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                id, s.origin(), s.dest(), s.sampleN(), s.extraYes(),
                s.surveyShare(), s.surveyLow(), s.surveyHigh(), s.surveyVolLow(), s.surveyVolHigh(),
                s.inferLow(), s.inferHigh(), s.overlap(), s.status().name(), s.note());
    }

    // ============================ OD 矩阵组装 ============================

    public List<Map<String, Object>> assembledMatrix(long id) {
        List<Map<String, Object>> flows = jdbc.queryForList(
                "SELECT * FROM mig_od_flow WHERE experiment_id=? ORDER BY origin_stop,dest_stop,method", id);
        Map<String, Map<String, Object>> byOd = new LinkedHashMap<>();
        for (Map<String, Object> f : flows) {
            String key = f.get("origin_stop") + "|" + f.get("dest_stop");
            Map<String, Object> row = byOd.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("origin", f.get("origin_stop"));
                m.put("dest", f.get("dest_stop"));
                m.put("originBoardings", f.get("origin_boardings"));
                return m;
            });
            row.put(((String) f.get("method")).equals("DETERMINISTIC") ? "deterministic" : "probabilistic", f);
        }
        return new ArrayList<>(byOd.values());
    }

    // ============================ 发布门控 ============================

    public Map<String, Object> currentGate(long id) {
        List<Map<String, Object>> conflicts = jdbc.queryForList(
                "SELECT origin_stop,dest_stop,status FROM mig_verification "
                        + "WHERE experiment_id=? AND status='CONFLICT'", id);
        List<String> ods = conflicts.stream()
                .map(c -> c.get("origin_stop") + "→" + c.get("dest_stop")).toList();
        Map<String, Object> gate = new LinkedHashMap<>();
        gate.put("conflictOds", ods);
        gate.put("singlePointAllowed", ods.isEmpty());
        gate.put("status", ods.isEmpty() ? "PUBLISHED" : "INTERVAL_ONLY");
        gate.put("reason", ods.isEmpty()
                ? "抽样核验与推断相容，可发布单点迁移量。"
                : "OD " + String.join("、", ods) + " 抽样与推断冲突：禁止单点迁移量，只能发布包络区间或暂缓。");
        return gate;
    }

    /**
     * 发布迁移量。冲突纪律在服务端强制执行，任何角色都不能绕过：
     * POINT 模式命中冲突（报告级或该 OD）一律 409；INTERVAL 模式发布跨来源包络区间。
     */
    @Transactional
    public Map<String, Object> publish(Long idOrNull, String scope, String origin, String dest,
                                       String mode, String user) {
        long id = resolveId(idOrNull);
        String useMode = (mode == null || mode.isBlank()) ? "INTERVAL" : mode.toUpperCase();
        boolean odScope = "OD".equalsIgnoreCase(scope) || (origin != null && dest != null);
        String scopeKey = odScope ? "OD:" + origin + ">" + dest : "REPORT";

        boolean odConflict = false;
        if (odScope) {
            Integer c = jdbc.queryForObject(
                    "SELECT count(*) FROM mig_verification WHERE experiment_id=? "
                            + "AND origin_stop=? AND dest_stop=? AND status='CONFLICT'",
                    Integer.class, id, origin, dest);
            odConflict = c != null && c > 0;
        }
        Map<String, Object> gate = currentGate(id);
        @SuppressWarnings("unchecked")
        List<String> conflictOds = (List<String>) gate.get("conflictOds");

        if ("POINT".equals(useMode)) {
            if (odScope && odConflict) {
                throw new IllegalStateException(
                        "该 OD 抽样调查与旅程推断区间冲突，服务端拒绝发布单点迁移量；请改用 INTERVAL 发布包络区间。");
            }
            if (!odScope && !conflictOds.isEmpty()) {
                throw new IllegalStateException(
                        "存在 " + conflictOds.size() + " 个抽样冲突 OD，报告级单点迁移量禁止发布；"
                                + "冲突清单：" + String.join("、", conflictOds)
                                + "。可对相容 OD 单独发点，或发布区间。");
            }
            Double point = odScope ? odMigPoint(id, origin, dest) : totalMigPoint(id);
            jdbc.update("""
                    INSERT INTO mig_publication(experiment_id,scope,publish_mode,status,point_value,
                      interval_low,interval_high,payload,reason,published_by)
                    VALUES (?,?,?,?,?,NULL,NULL,?::text,?,?)
                    """, id, scopeKey, "POINT", "PUBLISHED", point,
                    Json.write(Map.of("point", point == null ? 0 : point)),
                    "单点迁移量已发布（无未解决冲突）", user);
            return publicationResult(scopeKey, "POINT", "PUBLISHED", true, point, null, null,
                    "单点迁移量已发布", user);
        }

        // INTERVAL：计算跨 确定性/概率/抽样 的包络区间
        double[] env = odScope ? odEnvelope(id, origin, dest) : reportEnvelope(id);
        String status = conflictOds.isEmpty() ? "PUBLISHED" : "INTERVAL_ONLY";
        String reason = conflictOds.isEmpty()
                ? "已发布区间（双引擎 + 抽样包络）。"
                : "存在抽样冲突，按纪律仅发布跨来源宽包络区间，不发布单点。";
        jdbc.update("""
                INSERT INTO mig_publication(experiment_id,scope,publish_mode,status,point_value,
                  interval_low,interval_high,payload,reason,published_by)
                VALUES (?,?,?,?,NULL,?,?,?::text,?,?)
                """, id, scopeKey, "INTERVAL", status, env[0], env[1],
                Json.write(Map.of("low", env[0], "high", env[1])), reason, user);
        return publicationResult(scopeKey, "INTERVAL", status, false, null,
                env[0], env[1], reason, user);
    }

    private Map<String, Object> publicationResult(String scope, String mode, String status,
                                                  boolean pointAllowed, Double point,
                                                  Double low, Double high, String reason, String user) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scope", scope);
        m.put("mode", mode);
        m.put("status", status);
        m.put("singlePointAllowed", pointAllowed);
        m.put("pointValue", point);
        m.put("intervalLow", low);
        m.put("intervalHigh", high);
        m.put("reason", reason);
        m.put("publishedBy", user);
        return m;
    }

    private Double odMigPoint(long id, String o, String d) {
        List<Double> v = jdbc.queryForList(
                "SELECT mig_point FROM mig_od_flow WHERE experiment_id=? AND origin_stop=? "
                        + "AND dest_stop=? AND method='PROBABILISTIC'", Double.class, id, o, d);
        return v.isEmpty() ? 0.0 : v.get(0);
    }

    private Double totalMigPoint(long id) {
        Double v = jdbc.queryForObject(
                "SELECT coalesce(sum(mig_point),0) FROM mig_od_flow WHERE experiment_id=? "
                        + "AND method='PROBABILISTIC'", Double.class, id);
        return v == null ? 0.0 : v;
    }

    /** 单 OD 包络：两引擎迁移区间 + 抽样体积区间的并集上下界。 */
    private double[] odEnvelope(long id, String o, String d) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        List<Map<String, Object>> flows = jdbc.queryForList(
                "SELECT method,mig_low,mig_high FROM mig_od_flow WHERE experiment_id=? "
                        + "AND origin_stop=? AND dest_stop=?", id, o, d);
        for (Map<String, Object> f : flows) {
            low = Math.min(low, ((Number) f.get("mig_low")).doubleValue());
            high = Math.max(high, ((Number) f.get("mig_high")).doubleValue());
        }
        List<Map<String, Object>> vs = jdbc.queryForList(
                "SELECT survey_vol_low,survey_vol_high FROM mig_verification "
                        + "WHERE experiment_id=? AND origin_stop=? AND dest_stop=?", id, o, d);
        for (Map<String, Object> v : vs) {
            low = Math.min(low, ((Number) v.get("survey_vol_low")).doubleValue());
            high = Math.max(high, ((Number) v.get("survey_vol_high")).doubleValue());
        }
        if (!Double.isFinite(low)) {
            low = 0;
            high = 0;
        }
        return new double[]{round1(low), round1(Math.max(low, high))};
    }

    /** 报告级包络：逐 OD 包络下界/上界分别求和（保守宽区间）。 */
    private double[] reportEnvelope(long id) {
        List<Map<String, Object>> ods = jdbc.queryForList(
                "SELECT DISTINCT origin_stop o, dest_stop d FROM mig_od_flow WHERE experiment_id=?", id);
        double lo = 0, hi = 0;
        for (Map<String, Object> row : ods) {
            double[] e = odEnvelope(id, (String) row.get("o"), (String) row.get("d"));
            lo += e[0];
            hi += e[1];
        }
        return new double[]{round1(lo), round1(hi)};
    }

    public List<Map<String, Object>> publicationHistory(long id) {
        return jdbc.queryForList(
                "SELECT id,scope,publish_mode,status,point_value,interval_low,interval_high,"
                        + "reason,published_by,created_at FROM mig_publication "
                        + "WHERE experiment_id=? ORDER BY id DESC LIMIT 50", id);
    }

    private static double round1(double x) {
        return Math.round(x * 10.0) / 10.0;
    }
}
