package com.ccdev.transit.service;

import com.ccdev.transit.engine.mig.Mig;
import com.ccdev.transit.engine.mig.MigSampleData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 写入客流迁移子系统的确定性虚构原始数据（AFC 刷卡 / AVL 定位 / 轨迹头 / 换乘 / 抽样），
 * 随后调用 {@link MigrationService#rebuild(Long)} 生成五类派生产物。
 *
 * <p>原始数据只在缺失时写入（幂等）；派生产物每次启动都从原始表重算，便于引擎迭代。</p>
 */
@Service
@Order(30)
public class MigrationSeedService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(MigrationSeedService.class);

    private final JdbcTemplate jdbc;
    private final MigrationService migrationService;

    public MigrationSeedService(JdbcTemplate jdbc, MigrationService migrationService) {
        this.jdbc = jdbc;
        this.migrationService = migrationService;
    }

    @Override
    public void run(String... args) {
        Long expId;
        try {
            expId = migrationService.resolveId(null);
        } catch (Exception e) {
            log.warn("客流迁移播种跳过：{}", e.getMessage());
            return;
        }
        final long id = expId;

        Long taps = jdbc.queryForObject(
                "SELECT count(*) FROM mig_afc_tap WHERE experiment_id=?", Long.class, id);
        if (taps == null || taps == 0) {
            seedRaw(id);
            log.info("客流迁移原始样例已写入 experimentId={}", id);
        } else {
            log.info("客流迁移原始样例已存在（{} 条刷卡），仅重算派生产物", taps);
        }
        migrationService.rebuild(id);
    }

    private void seedRaw(long id) {
        MigSampleData.Data data = MigSampleData.build();
        LocalDate date = LocalDate.parse(MigSampleData.DATE);

        for (Mig.Station s : data.network().stations()) {
            jdbc.update("INSERT INTO mig_station(experiment_id,code,name,is_hub,attract) "
                    + "VALUES (?,?,?,?,?)", id, s.code(), s.name(), s.hub(), s.attract());
        }
        for (Mig.RouteInfo r : data.network().routes()) {
            jdbc.update("INSERT INTO mig_route(experiment_id,code,name,stop_seq,headway_sec,"
                            + "transfer_walk_sec) VALUES (?,?,?,?,?,?)",
                    id, r.code(), r.name(), String.join(",", r.stopSeq()),
                    r.headwaySec(), r.transferWalkSec());
        }
        for (Mig.TripTrace t : data.network().traces()) {
            jdbc.update("""
                    INSERT INTO mig_trip_trace(experiment_id,trip_code,route_code,vehicle_code,
                      service_date,short_turn,turn_stop,last_actual_stop,outage,outage_from_stop,
                      outage_to_stop,congestion_sec)
                    VALUES (?,?,?,?,(?::date),?,?,?,?,?,?,?)
                    """,
                    id, t.tripCode(), t.routeCode(), t.vehicleCode(), MigSampleData.DATE,
                    t.shortTurn(), t.turnStop(), t.lastActualStop(), t.outage(),
                    t.outageFromStop(), t.outageToStop(), t.congestionSec());
        }
        for (MigSampleData.AvlSeed p : data.avlPoints()) {
            jdbc.update("""
                    INSERT INTO mig_avl_point(experiment_id,trip_code,route_code,vehicle_code,
                      service_date,minute_of_day,event_ts,station_code,seq_index,progress_km,fix_ok)
                    VALUES (?,?,?,?,(?::date),?,?,?,?,?,?)
                    """,
                    id, p.tripCode(), p.routeCode(), p.vehicleCode(), MigSampleData.DATE,
                    p.minute(), ts(date, p.minute()), p.station(), p.seq(), p.progressKm(), p.fixOk());
        }
        for (Mig.Tap t : data.taps()) {
            jdbc.update("""
                    INSERT INTO mig_afc_tap(experiment_id,card_code,txn_type,service_date,
                      minute_of_day,event_ts,station_code,route_code,vehicle_code,trip_code)
                    VALUES (?,?,?,(?::date),?,?,?,?,?,?)
                    """,
                    id, t.card(), t.txnType(), MigSampleData.DATE, t.minute(),
                    ts(date, t.minute()), t.station(), t.routeCode(), t.vehicleCode(), t.tripCode());
        }
        for (Mig.TransferRec x : data.transfers()) {
            jdbc.update("""
                    INSERT INTO mig_transfer_record(experiment_id,card_code,service_date,
                      from_route,from_trip,from_station,to_route,to_trip,to_station,
                      minute_of_day,wait_sec,source)
                    VALUES (?,?,(?::date),?,?,?,?,?,?,?,?,'CARD_CHAIN')
                    """,
                    id, x.card(), MigSampleData.DATE, x.fromRoute(), x.fromTrip(),
                    x.fromStation(), x.toRoute(), x.toTrip(), x.toStation(),
                    x.minute(), x.waitSec());
        }
        for (Mig.SurveyInput s : data.surveys()) {
            jdbc.update("""
                    INSERT INTO mig_survey_sample(experiment_id,sample_code,origin_stop,dest_stop,
                      sample_n,extra_yes,od_population,surveyed_at,source,note)
                    VALUES (?,?,?,?,?,?,?,(?::date),'FIELD_SURVEY','确定性虚构抽样')
                    """,
                    id, "SV-" + s.origin() + "-" + s.dest(), s.origin(), s.dest(),
                    s.sampleN(), s.extraYes(), s.population(), MigSampleData.DATE);
        }
    }

    private static Timestamp ts(LocalDate date, int minuteOfDay) {
        return Timestamp.valueOf(LocalDateTime.of(
                date, java.time.LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)));
    }
}
