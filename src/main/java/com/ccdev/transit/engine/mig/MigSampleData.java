package com.ccdev.transit.engine.mig;

import com.ccdev.transit.engine.mig.Mig.RouteInfo;
import com.ccdev.transit.engine.mig.Mig.Station;
import com.ccdev.transit.engine.mig.Mig.SurveyInput;
import com.ccdev.transit.engine.mig.Mig.Tap;
import com.ccdev.transit.engine.mig.Mig.TransferRec;
import com.ccdev.transit.engine.mig.Mig.TripTrace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客流迁移子系统的确定性虚构样例（2026-06-02 早高峰，R1/R2 走廊）。
 *
 * <p>剧本要点：</p>
 * <ul>
 *   <li>R1 班次 R1-ST-0810 在 {@code HUB-CENTRAL} 短线折返，E 片乘客被迫在枢纽换 R2 —— 额外换乘损失来源；</li>
 *   <li>R2 班次 R2-GAP-0812 在 HUB→W2 之间定位中断（W1 无 fix），候选下车站必须保留多个；</li>
 *   <li>存在“无换乘刷卡”的折返乘客：确定性规则把折返站当终点（错配），概率引擎保留换乘后验；</li>
 *   <li>一个拥堵换乘班次：确定性用时刻表等待，概率引擎采纳更长的实际换乘等待；</li>
 *   <li>OD {@code E2-EXPO → W-STATION} 的抽样与推断被刻意构造成区间不重叠（冲突），发布门控禁止单点。</li>
 * </ul>
 */
public final class MigSampleData {

    public static final String DATE = "2026-06-02";

    // 站码常量，供服务/控制器/核验引用
    public static final String E0 = "E-EASTGATE";
    public static final String E1 = "E-LAKE";
    public static final String E2 = "E2-EXPO";
    public static final String HUB = "HUB-CENTRAL";
    public static final String W1 = "W-CULTURE";
    public static final String W2 = "W-STATION";
    public static final String W3 = "W-UNIV";
    public static final String S0 = "S-GATE";
    public static final String N1 = "N-NORTH";

    public record AvlSeed(String tripCode, String routeCode, String vehicleCode,
                          int seq, String station, int minute, double progressKm, boolean fixOk) {
    }

    public record Data(MigrationEngine.Network network,
                       List<Tap> taps,
                       List<TransferRec> transfers,
                       List<SurveyInput> surveys,
                       List<AvlSeed> avlPoints) {
    }

    private final List<Station> stations = new ArrayList<>();
    private final List<RouteInfo> routes = new ArrayList<>();
    private final List<TripTrace> traces = new ArrayList<>();
    private final List<Tap> taps = new ArrayList<>();
    private final List<TransferRec> xfers = new ArrayList<>();
    private final List<SurveyInput> surveys = new ArrayList<>();

    public static Data build() {
        MigSampleData d = new MigSampleData();
        d.network();
        d.traces();
        d.namedCases();
        d.shortTurnCohorts();
        d.normalCohorts();
        d.gapCohort();
        d.missedCohort();
        d.congestionCohort();
        d.surveys();
        return d.toData();
    }

    private Data toData() {
        MigrationEngine.Network net = new MigrationEngine.Network(stations, routes, traces);
        return new Data(net, taps, xfers, surveys, buildAvlPoints());
    }

    // ---------------- 线网 ----------------

    private void network() {
        stations.add(new Station(E0, "城东枢纽", true, 4));
        stations.add(new Station(E1, "东湖", false, 6));
        stations.add(new Station(E2, "会展中心", false, 8));
        stations.add(new Station(HUB, "中心枢纽", true, 15));
        stations.add(new Station(W1, "文化宫", false, 9));
        stations.add(new Station(W2, "铁路西站", true, 30));
        stations.add(new Station(W3, "大学城", false, 9));
        stations.add(new Station(S0, "南门", false, 4));
        stations.add(new Station(N1, "北苑", false, 7));

        routes.add(new RouteInfo("R1", "一路",
                List.of(E0, E1, E2, HUB, W1, W2, W3), 300, 180));
        routes.add(new RouteInfo("R2", "二路",
                List.of(S0, HUB, W1, W2, N1), 360, 180));
    }

    // ---------------- 班次轨迹 ----------------

    private void traces() {
        traces.add(trace("R1-FULL-0730", "R1", "V-R1-01", false, null, W3,
                false, null, null, 0,
                E0, 450, E1, 453, E2, 456, HUB, 460, W1, 464, W2, 468, W3, 472));

        traces.add(trace("R1-ST-0810", "R1", "V-R1-07", true, HUB, HUB,
                false, null, null, 0,
                E0, 486, E1, 489, E2, 492, HUB, 496));

        traces.add(trace("R2-CONN-0820", "R2", "V-R2-04", false, null, N1,
                false, null, null, 0,
                S0, 495, HUB, 501, W1, 505, W2, 509, N1, 514));

        // HUB -> W2 之间定位中断，W1 无 fix
        traces.add(trace("R2-GAP-0812", "R2", "V-R2-09", false, null, N1,
                true, HUB, W2, 0,
                S0, 484, HUB, 490, W2, 500, N1, 505));

        traces.add(trace("R1-CONG-0750", "R1", "V-R1-03", false, null, W3,
                false, null, null, 420,
                E0, 462, E1, 466, E2, 470, HUB, 476, W1, 481, W2, 486, W3, 491));
    }

    private TripTrace trace(String trip, String route, String vehicle,
                            boolean shortTurn, String turnStop, String lastStop,
                            boolean outage, String outFrom, String outTo, int congestion,
                            Object... kv) {
        Map<String, Integer> arrive = new LinkedHashMap<>();
        List<String> actual = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            String s = (String) kv[i];
            int m = (Integer) kv[i + 1];
            arrive.put(s, m);
            actual.add(s);
        }
        return new TripTrace(trip, route, vehicle, arrive, actual, shortTurn, turnStop,
                lastStop, outage, outFrom, outTo, congestion);
    }

    /** 由轨迹结构生成逐站 AVL 点：到达表内 fix=TRUE；中断区间缺测站 fix=FALSE 并线性补时刻。 */
    private List<AvlSeed> buildAvlPoints() {
        List<AvlSeed> out = new ArrayList<>();
        for (TripTrace t : traces) {
            RouteInfo r = routes.stream().filter(x -> x.code().equals(t.routeCode())).findFirst().orElseThrow();
            List<String> seq = r.stopSeq();
            for (int i = 0; i < seq.size(); i++) {
                String s = seq.get(i);
                Integer m = t.arriveMin().get(s);
                if (m != null) {
                    out.add(new AvlSeed(t.tripCode(), t.routeCode(), t.vehicleCode(),
                            i, s, m, i * 0.8, true));
                } else if (t.outage() && inOutageIndex(r, t, i)) {
                    int est = interpolate(r, t, i);
                    out.add(new AvlSeed(t.tripCode(), t.routeCode(), t.vehicleCode(),
                            i, s, est, i * 0.8, false));
                }
            }
        }
        return out;
    }

    private boolean inOutageIndex(RouteInfo r, TripTrace t, int idx) {
        int a = r.indexOf(t.outageFromStop());
        int b = r.indexOf(t.outageToStop());
        return a >= 0 && b >= 0 && idx > a && idx < b;
    }

    private int interpolate(RouteInfo r, TripTrace t, int idx) {
        int lo = idx - 1, hi = idx + 1;
        while (lo >= 0 && !t.arriveMin().containsKey(r.stopSeq().get(lo))) lo--;
        while (hi < r.stopSeq().size() && !t.arriveMin().containsKey(r.stopSeq().get(hi))) hi++;
        int m1 = t.arriveMin().get(r.stopSeq().get(lo));
        int m2 = t.arriveMin().get(r.stopSeq().get(hi));
        return (int) Math.round(m1 + (double) (idx - lo) / (hi - lo) * (m2 - m1));
    }

    // ---------------- 原始刷卡 / 换乘构造助手 ----------------

    private void in(String card, int minute, String station, String route, String vehicle) {
        taps.add(new Tap(card, "TAP_IN", minute, station, route, vehicle, null));
    }

    private void out(String card, int minute, String station) {
        taps.add(new Tap(card, "TAP_OUT", minute, station, null, null, null));
    }

    private void xfer(String card, String fr, String ft, String fs,
                      String tr, String tt, String ts, int minute, int wait) {
        xfers.add(new TransferRec(card, fr, ft, fs, tr, tt, ts, minute, wait));
    }

    /** 短线折返 + 枢纽换 R2 的一批卡。 */
    private void shortXfer(String prefix, int count, String origin, int boardMin,
                           int pinnedW2, int pinnedW1) {
        for (int i = 1; i <= count; i++) {
            String card = String.format("C-%s-%03d", prefix, i);
            in(card, boardMin, origin, "R1", "V-R1-07");
            int wait = 220 + (i % 5) * 30; // 220..340 确定性变化
            xfer(card, "R1", "R1-ST-0810", HUB, "R2", "R2-CONN-0820", HUB, 501, wait);
            if (i <= pinnedW2) {
                out(card, 512 + (i % 3), W2);
            } else if (i <= pinnedW2 + pinnedW1) {
                out(card, 508 + (i % 2), W1);
            }
        }
    }

    /** 正常直达班次上的一批卡（部分刷下车用于校准/留出核验）。 */
    private void direct(String prefix, int count, String trip, String vehicle,
                        String origin, int boardMin, String dest, int destMin, int pinned) {
        for (int i = 1; i <= count; i++) {
            String card = String.format("C-%s-%03d", prefix, i);
            in(card, boardMin + (i % 3), origin, "R1", vehicle);
            if (i <= pinned) {
                out(card, destMin + (i % 3), dest);
            }
        }
    }

    // ---------------- 命名核验个案 ----------------

    private void namedCases() {
        // 折返分流：观察到换乘，额外换乘损失明确
        in("C-DEMO-SHORT-01", 489, E1, "R1", "V-R1-07");
        xfer("C-DEMO-SHORT-01", "R1", "R1-ST-0810", HUB, "R2", "R2-CONN-0820", HUB, 501, 270);

        // 定位中断：HUB 上 R2-GAP，W1 缺测 -> W1/W2/N1 多候选
        in("C-DEMO-GAP-01", 490, HUB, "R2", "V-R2-09");

        // 上车站本身落在中断区间 W1：对齐应为 GPS_GAP_FUZZY
        in("C-DEMO-FUZZY-01", 495, W1, "R2", "V-R2-09");

        // 无换乘刷卡的折返乘客：确定性错配到 HUB，概率保留换乘后验
        in("C-DEMO-MISS-01", 492, E2, "R1", "V-R1-07");

        // 有刷下车的正常直达：两引擎都应高置信命中 W3
        in("C-DEMO-TAPOUT-01", 450, E0, "R1", "V-R1-01");
        out("C-DEMO-TAPOUT-01", 474, W3);

        // 拥堵 + 换乘：实际等待更长
        in("C-DEMO-CONG-01", 466, E1, "R1", "V-R1-03");
        xfer("C-DEMO-CONG-01", "R1", "R1-CONG-0750", HUB, "R2", "R2-CONN-0820", HUB, 500, 470);
        out("C-DEMO-CONG-01", 510, W2);
    }

    // ---------------- 各队列 ----------------

    private void shortTurnCohorts() {
        // 冲突 OD E2->W2：54 张折返换 R2 卡，10 张刷 W2 下车
        shortXfer("E2W2S", 54, E2, 492, 10, 2);
        // 核验一致 OD E1->W2：34 张，6 张刷 W2、3 张刷 W1
        shortXfer("E1W2S", 34, E1, 489, 6, 3);
        // 其余受影响 OD
        shortXfer("E1W1S", 12, E1, 489, 0, 4);
        shortXfer("E0W2S", 16, E0, 486, 4, 1);
        shortXfer("E2W1S", 10, E2, 492, 0, 3);
        // W3 只能等下一班 R1（同线换乘）
        for (int i = 1; i <= 8; i++) {
            String card = String.format("C-E2W3N-%03d", i);
            in(card, 492, E2, "R1", "V-R1-07");
            xfer(card, "R1", "R1-ST-0810", HUB, "R1", "R1-NEXT-0830", HUB, 520, 420 + (i % 4) * 40);
            if (i <= 3) out(card, 540, W3);
        }
    }

    private void normalCohorts() {
        direct("E0W3D", 18, "R1-FULL-0730", "V-R1-01", E0, 450, W3, 472, 8);
        direct("E2W2D", 20, "R1-FULL-0730", "V-R1-01", E2, 456, W2, 468, 9);
        direct("E1W1D", 14, "R1-FULL-0730", "V-R1-01", E1, 453, W1, 464, 6);
        direct("E0W2D", 12, "R1-FULL-0730", "V-R1-01", E0, 450, W2, 468, 5);
    }

    private void gapCohort() {
        // HUB 上中断班次、无下车：候选 W1/W2/N1
        for (int i = 1; i <= 8; i++) {
            String card = String.format("C-HUBGAP-%03d", i);
            in(card, 490 + (i % 2), HUB, "R2", "V-R2-09");
        }
        // 少量刷下车锚点，校准 R2 下游吸引
        for (int i = 1; i <= 5; i++) {
            String card = String.format("C-GAPW2O-%03d", i);
            in(card, 490, HUB, "R2", "V-R2-09");
            out(card, 502, W2);
        }
        for (int i = 1; i <= 3; i++) {
            String card = String.format("C-GAPN1O-%03d", i);
            in(card, 490, HUB, "R2", "V-R2-09");
            out(card, 507, N1);
        }
    }

    private void missedCohort() {
        // 无换乘记录的折返卡：确定性错配 HUB，概率保留错过换乘质量
        for (int i = 1; i <= 9; i++) {
            String card = String.format("C-MISS-%03d", i);
            String origin = (i % 2 == 0) ? E1 : E2;
            int bm = E1.equals(origin) ? 489 : 492;
            in(card, bm, origin, "R1", "V-R1-07");
        }
    }

    private void congestionCohort() {
        for (int i = 1; i <= 6; i++) {
            String card = String.format("C-CONG-%03d", i);
            in(card, 466 + (i % 3), E1, "R1", "V-R1-03");
            xfer(card, "R1", "R1-CONG-0750", HUB, "R2", "R2-CONN-0820", HUB, 500, 430 + (i % 4) * 30);
            if (i <= 2) out(card, 510, W2);
        }
    }

    private void surveys() {
        // 刻意冲突：大样本几乎无人报告额外换乘，与推断的高迁移区间不重叠
        surveys.add(new SurveyInput(E2, W2, 52, 1, 54));
        // 相容：抽样高比例额外换乘，区间与推断重叠
        surveys.add(new SurveyInput(E1, W2, 30, 26, 34));
        // 证据不足（小样本，不据此判定冲突）
        surveys.add(new SurveyInput(HUB, W2, 6, 1, 8));
    }
}
