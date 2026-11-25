package com.ccdev.transit.web;

import com.ccdev.transit.service.MigrationService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客流迁移 / 旅程重建 API。
 *
 * <p>同时暴露顶级 {@code /api/migration/*} 与嵌套
 * {@code /api/experiments/{id}/migration/*} 两套等价路径；id 缺省时使用内置虚构样例实验。
 * 所有 GET 需登录，POST 需 ANALYST/APPROVER（由 SecurityConfig 统一约束）。</p>
 */
@RestController
public class MigrationController {

    private final JdbcTemplate jdbc;
    private final MigrationService migration;

    public MigrationController(JdbcTemplate jdbc, MigrationService migration) {
        this.jdbc = jdbc;
        this.migration = migration;
    }

    // ---------------- 总览 / 工作台 ----------------

    @GetMapping({"/api/migration", "/api/migration/summary", "/api/migration/overview",
            "/api/experiments/{id}/migration/overview"})
    public Map<String, Object> overview(@PathVariable(required = false) Long id) {
        long eid = migration.resolveId(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("experimentId", eid);
        m.put("boardingLedger", count(eid, "mig_boarding_ledger"));
        m.put("rebuiltJourneys", count(eid, "mig_rebuilt_journey"));
        m.put("odFlowRows", count(eid, "mig_od_flow"));
        m.put("candidateRows", count(eid, "mig_alight_candidate"));
        m.put("afcTaps", count(eid, "mig_afc_tap"));
        m.put("avlPoints", count(eid, "mig_avl_point"));
        m.put("transferRecords", count(eid, "mig_transfer_record"));
        m.put("surveySamples", count(eid, "mig_survey_sample"));
        m.put("multiCandidateJourneys",
                countWhere(eid, "mig_rebuilt_journey", "multi_candidate=TRUE"));
        m.put("locationOutageCases",
                countWhere(eid, "mig_rebuilt_journey", "location_outage=TRUE"));
        m.put("shortTurnCases", countWhere(eid, "mig_rebuilt_journey", "short_turn=TRUE"));
        m.put("extraTransferJourneys",
                countWhere(eid, "mig_rebuilt_journey", "extra_transfer=TRUE"));
        m.put("conflictOds", jdbc.queryForList(
                "SELECT origin_stop,dest_stop,status FROM mig_verification "
                        + "WHERE experiment_id=? AND status='CONFLICT'", eid));
        m.put("gate", migration.currentGate(eid));
        m.put("diagnostics", migration.diagnostics(eid));
        return m;
    }

    /** 一次取齐：总览 + OD 矩阵 + 核验 + 门控，便于评审/冒烟。 */
    @GetMapping({"/api/migration/workbench", "/api/experiments/{id}/migration/workbench"})
    public Map<String, Object> workbench(@PathVariable(required = false) Long id) {
        long eid = migration.resolveId(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overview", overview(id));
        m.put("matrix", migration.assembledMatrix(eid));
        m.put("flows", jdbc.queryForList(
                "SELECT * FROM mig_od_flow WHERE experiment_id=? "
                        + "ORDER BY origin_stop,dest_stop,method", eid));
        m.put("verification", jdbc.queryForList(
                "SELECT * FROM mig_verification WHERE experiment_id=? "
                        + "ORDER BY status DESC, origin_stop,dest_stop", eid));
        m.put("gate", migration.currentGate(eid));
        m.put("diagnostics", migration.diagnostics(eid));
        return m;
    }

    @GetMapping({"/api/migration/network", "/api/experiments/{id}/migration/network"})
    public Map<String, Object> network(@PathVariable(required = false) Long id) {
        long eid = migration.resolveId(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stations", jdbc.queryForList(
                "SELECT code,name,is_hub,attract FROM mig_station WHERE experiment_id=? ORDER BY id", eid));
        m.put("routes", jdbc.queryForList(
                "SELECT code,name,stop_seq,headway_sec,transfer_walk_sec FROM mig_route "
                        + "WHERE experiment_id=? ORDER BY id", eid));
        // 先验校准核对：各站 TAP_OUT 计数与经验占比，用于检查 attract 先验是否与刷下车样本一致
        List<Map<String, Object>> tapOut = jdbc.queryForList("""
                SELECT station_code, count(*) AS tap_out_count,
                       round(count(*)::numeric / NULLIF(sum(count(*)) OVER (),0), 3) AS empirical_share
                FROM mig_afc_tap WHERE experiment_id=? AND txn_type='TAP_OUT'
                GROUP BY station_code ORDER BY tap_out_count DESC
                """, eid);
        m.put("tapOutCalibration", tapOut);
        m.put("calibrationNote", "attract 为按历史刷下车/土地利用标定的下车先验；"
                + "概率引擎对每卡刷下车证据施加强似然，确定性规则准确率见 /api/migration/overview -> diagnostics。");
        return m;
    }

    @PostMapping({"/api/migration/rebuild", "/api/experiments/{id}/migration/rebuild"})
    public Map<String, Object> rebuild(@PathVariable(required = false) Long id) {
        return migration.rebuild(id);
    }

    // ---------------- ① 上车观察账 ----------------

    @GetMapping({"/api/migration/boardings", "/api/migration/boarding-ledger",
            "/api/experiments/{id}/migration/boardings"})
    public Map<String, Object> boardings(@PathVariable(required = false) Long id,
                                         @RequestParam(required = false) String alignStatus,
                                         @RequestParam(required = false) Boolean outage,
                                         @RequestParam(required = false) String q,
                                         @RequestParam(defaultValue = "200") int limit) {
        long eid = migration.resolveId(id);
        StringBuilder where = new StringBuilder(" WHERE b.experiment_id=? ");
        List<Object> args = new ArrayList<>();
        args.add(eid);
        if (alignStatus != null && !alignStatus.isBlank() && !"ALL".equals(alignStatus)) {
            where.append(" AND b.align_status=? ");
            args.add(alignStatus);
        }
        if (Boolean.TRUE.equals(outage)) {
            where.append(" AND b.location_outage=TRUE ");
        }
        if (q != null && !q.isBlank()) {
            where.append(" AND (b.boarding_code ILIKE ? OR b.card_code ILIKE ? "
                    + "OR b.board_station ILIKE ? OR b.matched_trip ILIKE ?) ");
            String like = "%" + q + "%";
            for (int i = 0; i < 4; i++) args.add(like);
        }
        args.add(Math.min(500, Math.max(1, limit)));
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT b.*, j.det_dest, j.mig_prob, j.multi_candidate, j.extra_transfer
                FROM mig_boarding_ledger b
                LEFT JOIN mig_rebuilt_journey j ON j.boarding_code=b.boarding_code
                """ + where + " ORDER BY b.id LIMIT ?", args.toArray());

        List<Map<String, Object>> statusCounts = jdbc.queryForList(
                "SELECT align_status, count(*) n FROM mig_boarding_ledger "
                        + "WHERE experiment_id=? GROUP BY align_status ORDER BY align_status", eid);
        return Map.of("rows", rows, "statusCounts", statusCounts);
    }

    @GetMapping({"/api/migration/boardings/{code}",
            "/api/experiments/{id}/migration/boardings/{code}"})
    public Map<String, Object> boardingDetail(@PathVariable(required = false) Long id,
                                               @PathVariable String code) {
        long eid = migration.resolveId(id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM mig_boarding_ledger WHERE experiment_id=? "
                        + "AND (boarding_code=? OR card_code=?)", eid, code, code);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到上车观察账: " + code);
        }
        Map<String, Object> boarding = rows.get(0);
        String boardingCode = (String) boarding.get("boarding_code");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("boarding", boarding);
        m.put("candidates", jdbc.queryForList(
                "SELECT station_code,rank_no,prior_weight,posterior_prob,det_selected,retain_reason "
                        + "FROM mig_alight_candidate WHERE experiment_id=? AND boarding_code=? "
                        + "ORDER BY rank_no", eid, boardingCode));
        List<Map<String, Object>> journeys = jdbc.queryForList(
                "SELECT * FROM mig_rebuilt_journey WHERE experiment_id=? AND boarding_code=?",
                eid, boardingCode);
        m.put("journey", journeys.isEmpty() ? null : journeys.get(0));
        return m;
    }

    // ---------------- ② 车辆轨迹对齐 ----------------

    @GetMapping({"/api/migration/trajectories", "/api/migration/alignment",
            "/api/experiments/{id}/migration/trajectories"})
    public List<Map<String, Object>> trajectories(@PathVariable(required = false) Long id) {
        long eid = migration.resolveId(id);
        return jdbc.queryForList("""
                SELECT t.*,
                  (SELECT count(*) FROM mig_avl_point p
                     WHERE p.experiment_id=t.experiment_id AND p.trip_code=t.trip_code
                       AND p.fix_ok=FALSE) AS missing_fix_points,
                  (SELECT count(*) FROM mig_boarding_ledger b
                     WHERE b.experiment_id=t.experiment_id AND b.matched_trip=t.trip_code)
                     AS aligned_boardings
                FROM mig_trip_trace t WHERE t.experiment_id=? ORDER BY t.trip_code
                """, eid);
    }

    @GetMapping({"/api/migration/trajectories/{tripCode}",
            "/api/experiments/{id}/migration/trajectories/{tripCode}"})
    public Map<String, Object> trajectoryDetail(@PathVariable(required = false) Long id,
                                                @PathVariable String tripCode) {
        long eid = migration.resolveId(id);
        List<Map<String, Object>> head = jdbc.queryForList(
                "SELECT * FROM mig_trip_trace WHERE experiment_id=? AND trip_code=?", eid, tripCode);
        if (head.isEmpty()) {
            throw new IllegalArgumentException("未找到轨迹: " + tripCode);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trace", head.get(0));
        m.put("points", jdbc.queryForList(
                "SELECT seq_index,station_code,minute_of_day,progress_km,fix_ok "
                        + "FROM mig_avl_point WHERE experiment_id=? AND trip_code=? ORDER BY seq_index",
                eid, tripCode));
        m.put("boardings", jdbc.queryForList(
                "SELECT boarding_code,card_code,board_minute,board_station,align_status "
                        + "FROM mig_boarding_ledger WHERE experiment_id=? AND matched_trip=? "
                        + "ORDER BY board_minute", eid, tripCode));
        return m;
    }

    // ---------------- ③ 候选下车站 ----------------

    @GetMapping({"/api/migration/candidates", "/api/migration/alighting-candidates",
            "/api/migration/candidate-stops", "/api/migration/alighting",
            "/api/experiments/{id}/migration/candidates"})
    public List<Map<String, Object>> candidates(@PathVariable(required = false) Long id,
                                                @RequestParam(required = false) String boardingCode) {
        long eid = migration.resolveId(id);
        if (boardingCode == null || boardingCode.isBlank()) {
            return jdbc.queryForList("SELECT * FROM mig_alight_candidate "
                    + "WHERE experiment_id=? ORDER BY boarding_code,rank_no LIMIT 1000", eid);
        }
        return jdbc.queryForList("""
                SELECT c.* FROM mig_alight_candidate c
                WHERE c.experiment_id=? AND (c.boarding_code=?
                   OR c.boarding_code=('BL-'||?) )
                ORDER BY c.rank_no
                """, eid, boardingCode, boardingCode);
    }

    // ---------------- 重建旅程 ----------------

    @GetMapping({"/api/migration/journeys", "/api/experiments/{id}/migration/journeys"})
    public Map<String, Object> journeys(@PathVariable(required = false) Long id,
                                        @RequestParam(required = false) Boolean multiCandidate,
                                        @RequestParam(required = false) Boolean shortTurn,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(defaultValue = "200") int limit) {
        long eid = migration.resolveId(id);
        StringBuilder where = new StringBuilder(" WHERE experiment_id=? ");
        List<Object> args = new ArrayList<>();
        args.add(eid);
        if (Boolean.TRUE.equals(multiCandidate)) where.append(" AND multi_candidate=TRUE ");
        if (Boolean.TRUE.equals(shortTurn)) where.append(" AND short_turn=TRUE ");
        if (q != null && !q.isBlank()) {
            where.append(" AND (journey_code ILIKE ? OR card_code ILIKE ? OR origin_stop ILIKE ? "
                    + "OR det_dest ILIKE ?) ");
            String like = "%" + q + "%";
            for (int i = 0; i < 4; i++) args.add(like);
        }
        args.add(Math.min(500, Math.max(1, limit)));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM mig_rebuilt_journey" + where + " ORDER BY id LIMIT ?", args.toArray());
        return Map.of("rows", rows);
    }

    @GetMapping({"/api/migration/journeys/{key}",
            "/api/experiments/{id}/migration/journeys/{key}"})
    public Map<String, Object> journeyDetail(@PathVariable(required = false) Long id,
                                             @PathVariable String key) {
        long eid = migration.resolveId(id);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM mig_rebuilt_journey WHERE experiment_id=? "
                        + "AND (journey_code=? OR card_code=? OR boarding_code=?)",
                eid, key, key, key);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("未找到重建旅程: " + key);
        }
        Map<String, Object> j = rows.get(0);
        String boardingCode = (String) j.get("boarding_code");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("journey", j);
        m.put("candidates", jdbc.queryForList(
                "SELECT station_code,rank_no,posterior_prob,det_selected,retain_reason "
                        + "FROM mig_alight_candidate WHERE experiment_id=? AND boarding_code=? "
                        + "ORDER BY rank_no", eid, boardingCode));
        return m;
    }

    // ---------------- ④ OD 流量矩阵（双引擎区间） ----------------

    @GetMapping({"/api/migration/od-matrix", "/api/migration/flow-matrix", "/api/migration/matrix",
            "/api/migration/flows", "/api/migration/od-flows", "/api/migration/journey-flow",
            "/api/experiments/{id}/migration/od-matrix"})
    public Map<String, Object> odMatrix(@PathVariable(required = false) Long id) {
        long eid = migration.resolveId(id);
        return Map.of(
                "matrix", migration.assembledMatrix(eid),
                "flows", jdbc.queryForList(
                        "SELECT * FROM mig_od_flow WHERE experiment_id=? "
                                + "ORDER BY origin_stop,dest_stop,method", eid),
                "policy", "确定性与概率两引擎估计同一 OD 站对；区间口径不同但成对可比。");
    }

    // ---------------- ⑤ 抽样核验 ----------------

    @GetMapping({"/api/migration/surveys", "/api/migration/samples",
            "/api/migration/sampling", "/api/migration/sample-checks",
            "/api/migration/sample-verification",
            "/api/experiments/{id}/migration/surveys"})
    public Map<String, Object> surveys(@PathVariable(required = false) Long id) {
        long eid = migration.resolveId(id);
        return Map.of(
                "samples", jdbc.queryForList(
                        "SELECT * FROM mig_survey_sample WHERE experiment_id=? ORDER BY id", eid),
                "verification", jdbc.queryForList(
                        "SELECT * FROM mig_verification WHERE experiment_id=? "
                                + "ORDER BY status DESC, origin_stop,dest_stop", eid));
    }

    @GetMapping({"/api/migration/verification",
            "/api/experiments/{id}/migration/verification"})
    public Map<String, Object> verification(@PathVariable(required = false) Long id) {
        long eid = migration.resolveId(id);
        return Map.of("rows", jdbc.queryForList(
                "SELECT * FROM mig_verification WHERE experiment_id=? "
                        + "ORDER BY status DESC, origin_stop,dest_stop", eid));
    }

    // ---------------- 发布门控 ----------------

    @GetMapping({"/api/migration/publication",
            "/api/experiments/{id}/migration/publication"})
    public Map<String, Object> publication(@PathVariable(required = false) Long id) {
        long eid = migration.resolveId(id);
        return Map.of("gate", migration.currentGate(eid),
                "history", migration.publicationHistory(eid));
    }

    @PostMapping({"/api/migration/publish",
            "/api/experiments/{id}/migration/publish"})
    public Map<String, Object> publish(@PathVariable(required = false) Long id,
                                       @RequestBody(required = false) Map<String, String> body,
                                       Authentication auth) {
        Map<String, String> b = body == null ? Map.of() : body;
        return migration.publish(id, b.get("scope"), b.get("origin"), b.get("dest"),
                b.get("mode"), auth == null ? "unknown" : auth.getName());
    }

    // ---------------- 小工具 ----------------

    private long count(long eid, String table) {
        Long v = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE experiment_id=?", Long.class, eid);
        return v == null ? 0 : v;
    }

    private long countWhere(long eid, String table, String where) {
        Long v = jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE experiment_id=? AND " + where,
                Long.class, eid);
        return v == null ? 0 : v;
    }
}
