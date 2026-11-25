package com.ccdev.transit.engine.mig;

import com.ccdev.transit.engine.Stats;
import com.ccdev.transit.engine.mig.Mig.AlignStatus;
import com.ccdev.transit.engine.mig.Mig.Candidate;
import com.ccdev.transit.engine.mig.Mig.CaseResult;
import com.ccdev.transit.engine.mig.Mig.EngineResult;
import com.ccdev.transit.engine.mig.Mig.OdBlock;
import com.ccdev.transit.engine.mig.Mig.OdRow;
import com.ccdev.transit.engine.mig.Mig.OptionAcc;
import com.ccdev.transit.engine.mig.Mig.PathOption;
import com.ccdev.transit.engine.mig.Mig.PublishDecision;
import com.ccdev.transit.engine.mig.Mig.RouteInfo;
import com.ccdev.transit.engine.mig.Mig.Station;
import com.ccdev.transit.engine.mig.Mig.SurveyInput;
import com.ccdev.transit.engine.mig.Mig.SurveyResult;
import com.ccdev.transit.engine.mig.Mig.Tap;
import com.ccdev.transit.engine.mig.Mig.TransferRec;
import com.ccdev.transit.engine.mig.Mig.TripTrace;
import com.ccdev.transit.engine.mig.Mig.VerifyStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客流迁移双引擎（纯逻辑、确定性、无 Spring/DB 依赖）。
 *
 * <h3>两条估计路线，输出同一 OD 站对的旅程量与换乘损失区间</h3>
 * <ul>
 *   <li><b>确定性时刻表路径分配</b>：对齐 AVL 实际轨迹后按规则选唯一最可能下车站/路径，
 *       连接等待取时刻表值。快、可解释，但在短线折返无换乘刷卡、拥堵、定位中断时会错配或过度自信。</li>
 *   <li><b>概率旅程推断</b>：枚举候选下车站与候选路线，把下车吸引先验（由刷下车样本校准）、
 *       广义费用、换乘等待分布、刷下车/换乘链证据纳入后验，输出目的地图分布、迁移概率与损失方差，
 *       从而给出解析区间；定位中断时保留多候选，不提前收敛。</li>
 * </ul>
 */
public final class MigrationEngine {

    public static final double Z_95 = 1.96;
    public static final double TRANSFER_PENALTY_SEC = 300.0; // 每次换乘的感知惩罚当量（5 分钟）
    private static final double WAIT_VALUE_MIN = 2.0;        // 候车时间广义费用权重
    private static final double TRANSFER_PENALTY_MIN = 8.0;
    private static final double BETA = 0.045;                // Logit 费用敏感度（较小，吸引先验主导）
    private static final double MISSED_XFER_MASS = 0.28;     // 无换乘记录时“其实换乘了”的校准先验总质量
    private static final int ALIGN_WINDOW_MIN = 30;

    private MigrationEngine() {
    }

    /** 一次重建的全部输入。 */
    public record Network(List<Station> stations, List<RouteInfo> routes, List<TripTrace> traces) {
    }

    // OD 累加器（按方法分别累积一/二阶矩）
    private static final class Acc {
        double journey, jvar;
        double mig, mvar;
        double loss, lossSq; // 额外换乘损失的一阶和二阶总和（跨个案求和）
        double detLossLow, detLossHigh;
    }

    public static EngineResult rebuild(Network net, List<Tap> taps, List<TransferRec> transfers,
                                       List<SurveyInput> surveys) {
        Map<String, Station> stationMap = new LinkedHashMap<>();
        for (Station s : net.stations()) {
            stationMap.put(s.code(), s);
        }
        Map<String, RouteInfo> routeMap = new LinkedHashMap<>();
        for (RouteInfo r : net.routes()) {
            routeMap.put(r.code(), r);
        }
        Map<String, TripTrace> traceByTrip = new LinkedHashMap<>();
        List<TripTrace> traceList = new ArrayList<>();
        for (TripTrace t : net.traces()) {
            traceByTrip.put(t.tripCode(), t);
            traceList.add(t);
        }

        // 按卡分组原始记录
        Map<String, List<Tap>> tapsByCard = new LinkedHashMap<>();
        for (Tap t : taps) {
            tapsByCard.computeIfAbsent(t.card(), k -> new ArrayList<>()).add(t);
        }
        Map<String, TransferRec> xferByCard = new LinkedHashMap<>();
        for (TransferRec x : transfers) {
            xferByCard.putIfAbsent(x.card(), x);
        }

        List<CaseResult> cases = new ArrayList<>();
        // origin -> 该站上账号数（硬上车计数）
        Map<String, Integer> originCount = new LinkedHashMap<>();
        int validationN = 0;
        int validationCorrect = 0;

        for (Map.Entry<String, List<Tap>> e : tapsByCard.entrySet()) {
            String card = e.getKey();
            List<Tap> cardTaps = e.getValue();
            cardTaps.sort(Comparator.comparingInt(Tap::minute));
            List<Tap> ins = cardTaps.stream().filter(t -> !t.isTapOut()).toList();
            if (ins.isEmpty()) {
                continue;
            }
            Tap board = ins.get(0); // 旅程首乘
            Tap tapOut = cardTaps.stream().filter(Tap::isTapOut)
                    .max(Comparator.comparingInt(Tap::minute)).orElse(null);
            TransferRec xfer = xferByCard.get(card);

            Aligned al = align(board, routeMap.get(board.routeCode()), traceByTrip, traceList);
            TripTrace trace = al.trace;
            RouteInfo route = routeMap.get(board.routeCode());

            boolean outage = trace != null && trace.outage();
            boolean shortTurn = trace != null && trace.shortTurn();

            List<PathOption> options = buildOptions(board, route, trace, tapOut, xfer,
                    routeMap, stationMap);

            // 定位中断：AVL 无知带来额外熵，把后验与候选路径均匀分布做一次混合，
            // 主动压平单一目的站概率、放宽区间，并在产物中保留全部候选而不收敛。
            if (outage && !options.isEmpty()) {
                options = temperWithOutageIgnorance(options);
            }

            // 汇总目的地图分布与多候选标志
            Map<String, Double> destProb = new LinkedHashMap<>();
            for (PathOption o : options) {
                destProb.merge(o.dest(), o.probability(), Double::sum);
            }
            int meaningfulDests = 0;
            double topDestProb = 0;
            for (double p : destProb.values()) {
                if (p >= 0.12) {
                    meaningfulDests++;
                }
                topDestProb = Math.max(topDestProb, p);
            }
            boolean multiCandidate = outage || meaningfulDests >= 2 || topDestProb < 0.8;

            double expectedLoss = 0, expectedLossSq = 0, migProb = 0;
            for (PathOption o : options) {
                double l = o.lossSec(TRANSFER_PENALTY_SEC);
                expectedLoss += o.probability() * l;
                expectedLossSq += o.probability() * (l * l + o.waitStdSec() * o.waitStdSec());
                if (o.extraTransfer()) {
                    migProb += o.probability();
                }
            }

            // 确定性结论
            DetChoice det = deterministicChoice(board, route, trace, tapOut, xfer,
                    routeMap, stationMap, options);

            boolean tapOutFlag = tapOut != null;
            Integer observedWait = xfer != null && xfer.waitSec() != null ? xfer.waitSec() : null;

            String boardingCode = "BL-" + card;
            List<Candidate> candidates = toCandidates(destProb, det.dest, stationMap);

            cases.add(new CaseResult(
                    boardingCode, card, board.minute(), board.station(),
                    board.routeCode(), board.vehicleCode(),
                    trace == null ? null : trace.tripCode(),
                    al.status, al.offsetSec,
                    outage, shortTurn, trace == null ? null : trace.turnStop(),
                    tapOutFlag, observedWait,
                    candidates,
                    det.dest, det.plan, det.transfers, det.waitSec, det.lossSec,
                    det.confidence, det.mismatchRisk, det.extraTransfer, det.waitStdSec,
                    options,
                    expectedLoss, expectedLossSq, migProb, multiCandidate));

            originCount.merge(board.station(), 1, Integer::sum);

            // 确定性留出验证：对有刷下车的卡，隐藏其刷下车后预测并比对
            if (tapOut != null && route != null) {
                validationN++;
                String hidden = deterministicPredictHidden(board, route, trace, xfer, routeMap, stationMap);
                if (tapOut.station().equals(hidden)) {
                    validationCorrect++;
                }
            }
        }

        double detAccuracy = validationN == 0 ? Double.NaN
                : (double) validationCorrect / validationN;
        double detEta = calibratedDetErrorBand(detAccuracy);

        // ---------------- OD 聚合 ----------------
        Map<String, Acc> detAccs = new LinkedHashMap<>();
        Map<String, Acc> probAccs = new LinkedHashMap<>();
        for (CaseResult c : cases) {
            aggregateDeterministic(c, detAccs);
            aggregateProbabilistic(c, probAccs);
        }

        // 固定 OD 并集，按 origin 然后重点 OD 排序
        java.util.Set<String> keys = new java.util.TreeSet<>();
        keys.addAll(detAccs.keySet());
        keys.addAll(probAccs.keySet());
        List<OdRow> matrix = new ArrayList<>();
        for (String key : keys) {
            String[] oc = key.split("\\|", 2);
            String o = oc[0], d = oc[1];
            int ob = originCount.getOrDefault(o, 0);
            OdBlock dB = buildBlock(Mig.Method.DETERMINISTIC, o, d, ob, detAccs.get(key), detEta);
            OdBlock pB = buildBlock(Mig.Method.PROBABILISTIC, o, d, ob, probAccs.get(key), detEta);
            matrix.add(new OdRow(o, d, ob, dB, pB));
        }
        matrix.sort(Comparator.comparing(OdRow::origin).thenComparing(OdRow::dest));

        // ---------------- 抽样核验 ----------------
        List<SurveyResult> verification = new ArrayList<>();
        List<String> conflictOds = new ArrayList<>();
        for (SurveyInput s : surveys) {
            String key = s.origin() + "|" + s.dest();
            Acc pa = probAccs.get(key);
            double inferLow = 0, inferHigh = 0;
            if (pa != null) {
                double migPoint = pa.mig;
                double half = Z_95 * Math.sqrt(Math.max(0, pa.mvar));
                inferLow = Math.max(0, migPoint - half);
                inferHigh = migPoint + half;
            }
            SurveyResult sr = evaluateSurvey(s, inferLow, inferHigh);
            verification.add(sr);
            if (sr.status() == VerifyStatus.CONFLICT) {
                conflictOds.add(s.origin() + "→" + s.dest());
            }
        }

        // 报告级发布门控：存在冲突 => 单点迁移量一律不得发布
        double totalMigPoint = 0, totalLow = 0, totalHigh = 0;
        for (OdRow row : matrix) {
            OdBlock p = row.probabilistic();
            if (p != null) {
                totalMigPoint += p.migPoint();
                totalLow += p.migLow();
                totalHigh += p.migHigh();
            }
        }
        boolean anyConflict = !conflictOds.isEmpty();
        PublishDecision gate = new PublishDecision(
                "REPORT", "POINT",
                anyConflict ? Mig.PubStatus.BLOCKED : Mig.PubStatus.PUBLISHED,
                !anyConflict,
                anyConflict ? null : round1(totalMigPoint),
                round1(totalLow), round1(totalHigh),
                conflictOds,
                anyConflict
                        ? "抽样调查与概率推断在 OD " + String.join("、", conflictOds)
                          + " 上区间不重叠：禁止发布任何单点迁移量，只能发布跨来源包络区间或暂缓。"
                        : "各抽样 OD 与推断区间相容，可发布点估计与区间。");

        return new EngineResult(cases, matrix, verification, gate,
                detAccuracy, validationN, TRANSFER_PENALTY_SEC);
    }

    // ============================ 对齐 ============================

    private record Aligned(TripTrace trace, AlignStatus status, int offsetSec) {
    }

    private static Aligned align(Tap board, RouteInfo route,
                                 Map<String, TripTrace> byTrip, List<TripTrace> all) {
        TripTrace best = null;
        int bestAbs = Integer.MAX_VALUE;
        boolean bestFuzzy = false;

        List<TripTrace> candidates = new ArrayList<>();
        if (board.tripCode() != null && byTrip.containsKey(board.tripCode())) {
            candidates.add(byTrip.get(board.tripCode()));
        } else {
            for (TripTrace t : all) {
                if (board.routeCode() != null && !board.routeCode().equals(t.routeCode())) {
                    continue;
                }
                if (board.vehicleCode() != null && !board.vehicleCode().equals(t.vehicleCode())) {
                    continue;
                }
                candidates.add(t);
            }
        }

        for (TripTrace t : candidates) {
            int[] score = offsetAt(t, route, board.station(), board.minute());
            if (score == null) {
                continue;
            }
            int off = score[0]; // 分钟差（带符号近似）
            boolean fuzzy = score[1] == 1;
            if (Math.abs(off) < bestAbs) {
                bestAbs = Math.abs(off);
                best = t;
                bestFuzzy = fuzzy;
            }
        }

        if (best == null || bestAbs > ALIGN_WINDOW_MIN) {
            return new Aligned(best, AlignStatus.UNMATCHED, 0);
        }
        int offSec = bestAbs * 60;
        AlignStatus st;
        if (bestFuzzy) {
            st = AlignStatus.GPS_GAP_FUZZY;
        } else if (bestAbs <= 2) {
            st = AlignStatus.ALIGNED;
        } else {
            st = AlignStatus.NEAREST_FALLBACK;
        }
        return new Aligned(best, st, offSec);
    }

    /**
     * 返回 [带符号分钟偏移, fuzzy]；无法对齐返回 null。
     * 若该站无精确到离点但落在定位中断区间内，按中断两端时刻线性插值并标记 fuzzy。
     */
    private static int[] offsetAt(TripTrace t, RouteInfo route, String station, int minute) {
        Integer arr = t.arriveMin().get(station);
        if (arr != null) {
            return new int[]{minute - arr, 0};
        }
        if (route == null || !route.contains(station) || !t.outage()) {
            return null;
        }
        int idx = route.indexOf(station);
        // 找中断区间两侧最近的有时刻站
        Integer beforeMin = null, afterMin = null;
        int beforeIdx = -1, afterIdx = -1;
        for (Map.Entry<String, Integer> en : t.arriveMin().entrySet()) {
            int k = route.indexOf(en.getKey());
            if (k < 0) continue;
            if (k < idx && k > beforeIdx) {
                beforeIdx = k;
                beforeMin = en.getValue();
            }
            if (k > idx && (afterIdx == -1 || k < afterIdx)) {
                afterIdx = k;
                afterMin = en.getValue();
            }
        }
        if (beforeMin == null || afterMin == null || afterIdx == beforeIdx) {
            return null;
        }
        double frac = (double) (idx - beforeIdx) / (afterIdx - beforeIdx);
        int est = (int) Math.round(beforeMin + frac * (afterMin - beforeMin));
        return new int[]{minute - est, 1};
    }

    // ============================ 候选路径构造（概率） ============================

    private static List<PathOption> buildOptions(Tap board, RouteInfo route, TripTrace trace,
                                                 Tap tapOut, TransferRec xfer,
                                                 Map<String, RouteInfo> routes,
                                                 Map<String, Station> stations) {
        List<OptionAcc> accs = new ArrayList<>();
        if (route == null) {
            return List.of();
        }
        int oIdx = route.indexOf(board.station());

        // 1) 观察到换乘链：第二程线路已知，目的地沿第二程下游推断
        if (xfer != null) {
            RouteInfo toRoute = routes.get(xfer.toRoute());
            String xStation = xfer.fromStation() != null ? xfer.fromStation() : xfer.toStation();
            if (toRoute != null) {
                int xIdx = toRoute.indexOf(xStation);
                if (xIdx < 0) {
                    xIdx = toRoute.indexOf(xfer.toStation());
                }
                boolean extra = trace != null && trace.shortTurn()
                        && trace.turnStop() != null
                        && trace.turnStop().equals(xStation);
                int wait = xfer.waitSec() != null ? xfer.waitSec()
                        : toRoute.transferWalkSec() + toRoute.headwaySec() / 2;
                double waitStd = xfer.waitSec() != null ? 60.0
                        : toRoute.headwaySec() / Math.sqrt(12.0);
                for (int i = xIdx + 1; i >= 0 && i < toRoute.stopSeq().size(); i++) {
                    if (i <= xIdx) continue;
                    String d = toRoute.stopSeq().get(i);
                    double rideMin = Math.abs(i - Math.max(xIdx, 0)) * 2.5;
                    double w = destWeight(d, rideMin, wait / 60.0, 1, stations);
                    boolean extraToD = extra && route.contains(d) && route.indexOf(d) > oIdx;
                    accs.add(new OptionAcc(d, "XFER:" + route.code() + ">" + toRoute.code(),
                            List.of(route.code(), toRoute.code()), 1, wait, waitStd, w, extraToD));
                }
                // 短线折返后等下一班同线 R1 去折返点以远（如 W3）
                if (trace != null && trace.shortTurn() && trace.turnStop() != null
                        && route.contains(trace.turnStop())) {
                    int tIdx = route.indexOf(trace.turnStop());
                    int nominalWait = route.transferWalkSec() + route.headwaySec() / 2
                            + trace.congestionSec();
                    for (int i = tIdx + 1; i < route.stopSeq().size(); i++) {
                        String d = route.stopSeq().get(i);
                        double rideMin = (i - tIdx) * 2.5;
                        double w = 0.55 * destWeight(d, rideMin, nominalWait / 60.0, 1, stations);
                        accs.add(new OptionAcc(d, "NEXT:" + route.code(),
                                List.of(route.code(), route.code()), 1, nominalWait,
                                route.headwaySec() / Math.sqrt(12.0), w, true));
                    }
                }
            }
        } else {
            // 2) 无换乘记录：本车直达候选
            int endIdx = route.stopSeq().size() - 1;
            if (trace != null && trace.shortTurn() && trace.turnStop() != null
                    && route.contains(trace.turnStop())) {
                endIdx = route.indexOf(trace.turnStop());
            }
            double outageFlatten = trace != null && trace.outage() ? 1.6 : 1.0;
            double directRawSum = 0;
            for (int i = oIdx + 1; i <= endIdx && i < route.stopSeq().size(); i++) {
                String d = route.stopSeq().get(i);
                double rideMin = (i - oIdx) * 2.5;
                double base = destWeight(d, rideMin, 0, 0, stations);
                // 定位中断：弱化距离费用、抬升区间候选权重，避免错误收敛到单一站
                double w = trace != null && trace.outage() ? base * outageFlatten : base;
                accs.add(new OptionAcc(d, "DIRECT:" + route.code(),
                        List.of(route.code()), 0, 0, 0, w, false));
                directRawSum += w;
            }

            // 3) 短线折返且无换乘刷卡：概率上保留“其实在折返站换乘/等下一班”的质量。
            //    令 missedRaw 缩放后总权重 = directRawSum * odds，使归一后错过换乘的后验质量
            //    约为 MISSED_XFER_MASS。
            if (trace != null && trace.shortTurn() && trace.turnStop() != null
                    && route.contains(trace.turnStop()) && directRawSum > 0) {
                String xStation = trace.turnStop();
                int tIdx = route.indexOf(xStation);
                List<OptionAcc> missed = new ArrayList<>();
                for (RouteInfo xr : routes.values()) {
                    if (xr.code().equals(route.code()) || !xr.contains(xStation)) {
                        continue;
                    }
                    int xIdx = xr.indexOf(xStation);
                    int waitMean = xr.transferWalkSec() + xr.headwaySec() / 2
                            + trace.congestionSec();
                    double waitStd = xr.headwaySec() / Math.sqrt(12.0);
                    for (int i = xIdx + 1; i < xr.stopSeq().size(); i++) {
                        String d = xr.stopSeq().get(i);
                        // 只对“原本可由本线直达、折返后被迫换乘”的站计额外换乘
                        if (!route.contains(d) || route.indexOf(d) <= tIdx) {
                            continue;
                        }
                        double rideMin = (i - xIdx) * 2.5;
                        double w = destWeight(d, rideMin, waitMean / 60.0, 1, stations);
                        missed.add(new OptionAcc(d, "MISSED-XFER:" + xr.code(),
                                List.of(route.code(), xr.code()), 1, waitMean, waitStd, w, true));
                    }
                }
                // 等下一班同线
                int waitMean = route.transferWalkSec() + route.headwaySec() / 2
                        + trace.congestionSec();
                for (int i = tIdx + 1; i < route.stopSeq().size(); i++) {
                    String d = route.stopSeq().get(i);
                    double rideMin = (i - tIdx) * 2.5;
                    double w = 0.8 * destWeight(d, rideMin, waitMean / 60.0, 1, stations);
                    missed.add(new OptionAcc(d, "MISSED-NEXT:" + route.code(),
                            List.of(route.code(), route.code()), 1, waitMean,
                            route.headwaySec() / Math.sqrt(12.0), w, true));
                }
                double missedRawSum = missed.stream().mapToDouble(a -> a.weight).sum();
                if (missedRawSum > 0) {
                    double odds = MISSED_XFER_MASS / (1 - MISSED_XFER_MASS);
                    double scale = directRawSum * odds / missedRawSum;
                    for (OptionAcc a : missed) {
                        accs.add(new OptionAcc(a.dest, a.plan, a.routes, a.transfers, a.waitSec,
                                a.waitStdSec, a.weight * scale, a.extraTransfer));
                    }
                }
            }
        }

        // 刷下车证据：有 TAP_OUT 时强似然收敛到真实下车站
        if (tapOut != null) {
            for (int i = 0; i < accs.size(); i++) {
                OptionAcc a = accs.get(i);
                double factor = a.dest.equals(tapOut.station()) ? 60.0 : 0.05;
                accs.set(i, new OptionAcc(a.dest, a.plan, a.routes, a.transfers, a.waitSec,
                        a.waitStdSec, a.weight * factor, a.extraTransfer));
            }
        }

        double sum = accs.stream().mapToDouble(a -> a.weight).sum();
        List<PathOption> out = new ArrayList<>();
        if (sum <= 0) {
            return out;
        }
        for (OptionAcc a : accs) {
            out.add(new PathOption(a.dest, a.plan, a.routes, a.transfers, a.waitSec,
                    a.waitStdSec, a.weight / sum, a.extraTransfer));
        }
        return out;
    }

    private static double destWeight(String dest, double rideMin, double waitMin,
                                     int transfers, Map<String, Station> stations) {
        Station s = stations.get(dest);
        double attract = s == null ? 1.0 : Math.max(0.2, s.attract());
        double gcost = rideMin + WAIT_VALUE_MIN * waitMin + TRANSFER_PENALTY_MIN * transfers;
        return attract * Math.exp(-BETA * gcost);
    }

    // ============================ 确定性选择 ============================

    /** 定位中断时把 30% 质量与候选路径均匀分布混合，表达轨迹无知。 */
    private static List<PathOption> temperWithOutageIgnorance(List<PathOption> options) {
        int m = options.size();
        double eps = 0.30;
        List<PathOption> out = new ArrayList<>(m);
        for (PathOption o : options) {
            double p = (1 - eps) * o.probability() + eps / m;
            out.add(new PathOption(o.dest(), o.plan(), o.routes(), o.transfers(), o.waitSec(),
                    o.waitStdSec(), p, o.extraTransfer()));
        }
        return out;
    }

    private record DetChoice(String dest, String plan, int transfers, int waitSec, int lossSec,
                             double confidence, boolean mismatchRisk,
                             boolean extraTransfer, double waitStdSec) {
    }

    private static DetChoice deterministicChoice(Tap board, RouteInfo route, TripTrace trace,
                                                 Tap tapOut, TransferRec xfer,
                                                 Map<String, RouteInfo> routes,
                                                 Map<String, Station> stations,
                                                 List<PathOption> probOptions) {
        if (route == null) {
            return new DetChoice(board.station(), "UNMATCHED", 0, 0, 0, 0.1, true, false, 0);
        }
        // 有刷下车：确定性结果直接采用观测
        if (tapOut != null) {
            int tr = xfer != null ? 1 : 0;
            int nominalWait = nominalConnectionWait(xfer, routes);
            boolean extra = xfer != null && trace != null && trace.shortTurn()
                    && xfer.fromStation() != null
                    && xfer.fromStation().equals(trace.turnStop())
                    && route.contains(tapOut.station());
            int loss = nominalWait + tr * (int) TRANSFER_PENALTY_SEC;
            return new DetChoice(tapOut.station(), "TAP_OUT_OBSERVED", tr, nominalWait, loss,
                    0.98, false, extra, headwayStd(xfer, routes));
        }

        boolean risk = (trace != null && (trace.shortTurn() || trace.congestionSec() > 0
                || trace.outage())) || xfer == null && trace != null && trace.shortTurn();

        if (xfer != null) {
            RouteInfo toRoute = routes.get(xfer.toRoute());
            String xStation = xfer.fromStation() != null ? xfer.fromStation() : xfer.toStation();
            String dest = argmaxDownstream(toRoute, xStation, stations);
            int nominalWait = nominalConnectionWait(xfer, routes);
            boolean extra = trace != null && trace.shortTurn()
                    && xStation.equals(trace.turnStop())
                    && route.contains(dest) && route.indexOf(dest) > route.indexOf(board.station());
            int loss = nominalWait + (int) TRANSFER_PENALTY_SEC;
            return new DetChoice(dest, "TIMETABLE:XFER:" + route.code() + ">" + xfer.toRoute(),
                    1, nominalWait, loss, 0.9, risk || trace != null && trace.congestionSec() > 0,
                    extra, headwayStd(xfer, routes));
        }

        // 无换乘记录：确定性只在本车可达站中按固定吸引规则选唯一站（不保留“其实换乘”的可能）
        int endIdx = route.stopSeq().size() - 1;
        if (trace != null && trace.shortTurn() && trace.turnStop() != null
                && route.contains(trace.turnStop())) {
            endIdx = route.indexOf(trace.turnStop());
        }
        String dest = null;
        double best = -1;
        for (int i = route.indexOf(board.station()) + 1; i <= endIdx
                && i < route.stopSeq().size(); i++) {
            String d = route.stopSeq().get(i);
            double w = destWeight(d, (i - route.indexOf(board.station())) * 2.5, 0, 0, stations);
            if (w > best) {
                best = w;
                dest = d;
            }
        }
        if (dest == null) {
            dest = trace != null && trace.lastActualStop() != null
                    ? trace.lastActualStop() : board.station();
        }
        return new DetChoice(dest, "TIMETABLE:DIRECT:" + route.code(), 0, 0, 0,
                trace != null && trace.outage() ? 0.5 : 0.82, risk, false, 0);
    }

    /** 隐藏刷下车后的确定性预测，用于留出核验确定性规则准确率。 */
    private static String deterministicPredictHidden(Tap board, RouteInfo route, TripTrace trace,
                                                     TransferRec xfer,
                                                     Map<String, RouteInfo> routes,
                                                     Map<String, Station> stations) {
        if (xfer != null) {
            RouteInfo toRoute = routes.get(xfer.toRoute());
            String xStation = xfer.fromStation() != null ? xfer.fromStation() : xfer.toStation();
            String d = argmaxDownstream(toRoute, xStation, stations);
            if (d != null) {
                return d;
            }
        }
        int endIdx = route.stopSeq().size() - 1;
        if (trace != null && trace.shortTurn() && trace.turnStop() != null
                && route.contains(trace.turnStop())) {
            endIdx = route.indexOf(trace.turnStop());
        }
        int oIdx = route.indexOf(board.station());
        String dest = null;
        double best = -1;
        for (int i = oIdx + 1; i <= endIdx && i < route.stopSeq().size(); i++) {
            String d = route.stopSeq().get(i);
            double w = destWeight(d, (i - oIdx) * 2.5, 0, 0, stations);
            if (w > best) {
                best = w;
                dest = d;
            }
        }
        return dest;
    }

    private static String argmaxDownstream(RouteInfo r, String xStation,
                                           Map<String, Station> stations) {
        if (r == null) {
            return null;
        }
        int xIdx = r.indexOf(xStation);
        String dest = null;
        double best = -1;
        for (int i = Math.max(0, xIdx) + 1; i < r.stopSeq().size(); i++) {
            String d = r.stopSeq().get(i);
            double w = destWeight(d, (i - Math.max(0, xIdx)) * 2.5, 0, 0, stations);
            if (w > best) {
                best = w;
                dest = d;
            }
        }
        return dest;
    }

    private static int nominalConnectionWait(TransferRec xfer, Map<String, RouteInfo> routes) {
        RouteInfo r = xfer == null ? null : routes.get(xfer.toRoute());
        int headway = r == null ? 300 : r.headwaySec();
        int walk = r == null ? 180 : r.transferWalkSec();
        // 基于时刻表的名义连接等待：步行 + 平均半班
        return walk + headway / 2;
    }

    private static double headwayStd(TransferRec xfer, Map<String, RouteInfo> routes) {
        RouteInfo r = xfer == null ? null : routes.get(xfer.toRoute());
        int headway = r == null ? 300 : r.headwaySec();
        return headway / Math.sqrt(12.0);
    }

    private static List<Candidate> toCandidates(Map<String, Double> destProb, String detDest,
                                                Map<String, Station> stations) {
        List<Candidate> list = new ArrayList<>();
        int rank = 0;
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(destProb.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        for (Map.Entry<String, Double> en : sorted) {
            String s = en.getKey();
            double p = en.getValue();
            String reason = p >= 0.5 ? "POSTERIOR_PRIMARY"
                    : p >= 0.12 ? "RETAINED_ALTERNATIVE" : "LOW_MASS_TAIL";
            Station st = stations.get(s);
            list.add(new Candidate(s, round3(p), round3(p), detDest != null && detDest.equals(s),
                    reason + (st != null && st.hub() ? ":HUB" : "")));
            if (++rank >= 6) {
                break;
            }
        }
        return list;
    }

    // ============================ OD 聚合 ============================

    private static String key(String o, String d) {
        return o + "|" + d;
    }

    private static void aggregateDeterministic(CaseResult c, Map<String, Acc> accs) {
        if (c.detDest() == null) {
            return;
        }
        Acc a = accs.computeIfAbsent(key(c.origin(), c.detDest()), k -> new Acc());
        a.journey += 1;
        // 确定性 q=1，统计方差为 0；区间改用校准错误带，在 buildBlock 处理
        if (c.detExtraTransfer()) {
            a.mig += 1;
            double l = c.detLossSec();
            a.loss += l;
            double halfSensitivity = c.detWaitStdSec() == 0 ? 0 : c.detWaitStdSec() * Z_95;
            a.detLossLow += Math.max(0, l - halfSensitivity);
            a.detLossHigh += l + halfSensitivity;
        }
    }

    private static void aggregateProbabilistic(CaseResult c, Map<String, Acc> accs) {
        // 先按 OD 折叠同一目的站的多条路径
        Map<String, double[]> byDest = new LinkedHashMap<>();
        // 每项: q, qa(额外换乘概率), loss1, loss2
        for (PathOption o : c.options()) {
            double[] v = byDest.computeIfAbsent(o.dest(), k -> new double[4]);
            double l = o.lossSec(TRANSFER_PENALTY_SEC);
            v[0] += o.probability();
            if (o.extraTransfer()) {
                v[1] += o.probability();
                v[2] += o.probability() * l;
                v[3] += o.probability() * (l * l + o.waitStdSec() * o.waitStdSec());
            }
        }
        for (Map.Entry<String, double[]> en : byDest.entrySet()) {
            double q = en.getValue()[0];
            double qa = en.getValue()[1];
            double loss1 = en.getValue()[2];
            double loss2 = en.getValue()[3];
            Acc a = accs.computeIfAbsent(key(c.origin(), en.getKey()), k -> new Acc());
            a.journey += q;
            a.jvar += q * (1 - q);
            a.mig += qa;
            a.mvar += qa * (1 - qa);
            a.loss += loss1;
            // Var(个案损失) = E[Y^2]-E[Y]^2；跨个案独立求和
            double caseVar = Math.max(0, loss2 - loss1 * loss1);
            a.lossSq += caseVar; // 这里借用 lossSq 字段存“方差之和”
        }
    }

    private static OdBlock buildBlock(Mig.Method method, String o, String d, int originBoardings,
                                      Acc a, double detEta) {
        if (a == null) {
            return new OdBlock(method, o, d, originBoardings, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0);
        }
        if (method == Mig.Method.PROBABILISTIC) {
            double jHalf = Z_95 * Math.sqrt(Math.max(0, a.jvar));
            double mHalf = Z_95 * Math.sqrt(Math.max(0, a.mvar));
            double lossSe = Math.sqrt(Math.max(0, a.lossSq));
            double lossHalf = Z_95 * lossSe;
            double meanLoss = a.mig > 0 ? a.loss / a.mig : 0;
            return new OdBlock(method, o, d, originBoardings,
                    round1(a.journey), round1(Math.max(0, a.journey - jHalf)),
                    round1(a.journey + jHalf),
                    round1(a.mig), round1(Math.max(0, a.mig - mHalf)), round1(a.mig + mHalf),
                    round1(meanLoss), round1(Math.max(0, meanLoss - lossHalf / Math.max(a.mig, 1e-9))),
                    round1(meanLoss + lossHalf / Math.max(a.mig, 1e-9)),
                    round3(a.loss / 3600.0),
                    round3(Math.max(0, a.loss - lossHalf) / 3600.0),
                    round3((a.loss + lossHalf) / 3600.0));
        } else {
            // 确定性 q=1 无统计方差；用留出核验错误率 eta 构造分配错误带（夹在 8%~40%）
            double eta = detEta;
            double jLow = Math.max(0, a.journey * (1 - eta));
            double jHigh = a.journey * (1 + eta);
            double mLow = Math.max(0, a.mig * (1 - eta));
            double mHigh = a.mig * (1 + eta);
            double meanLoss = a.mig > 0 ? a.loss / a.mig : 0;
            double meanLow = a.mig > 0 ? a.detLossLow / a.mig : 0;
            double meanHigh = a.mig > 0 ? a.detLossHigh / a.mig : 0;
            return new OdBlock(method, o, d, originBoardings,
                    round1(a.journey), round1(jLow), round1(jHigh),
                    round1(a.mig), round1(mLow), round1(mHigh),
                    round1(meanLoss), round1(meanLow), round1(meanHigh),
                    round3(a.loss / 3600.0), round3(a.detLossLow / 3600.0),
                    round3(a.detLossHigh / 3600.0));
        }
    }

    /** 用确定性留出准确率收紧/设定错误带（1-accuracy，夹在 [0.08,0.4]）。 */
    public static double calibratedDetErrorBand(double accuracy) {
        if (Double.isNaN(accuracy)) {
            return 0.15;
        }
        return Math.min(0.4, Math.max(0.08, 1 - accuracy));
    }

    // ============================ 抽样核验 ============================

    private static SurveyResult evaluateSurvey(SurveyInput s, double inferLow, double inferHigh) {
        int n = s.sampleN();
        double[] w = Stats.wilson(s.extraYes(), Math.max(n, 1), Z_95);
        double pop = s.population();
        double svLow = w[1] * pop;
        double svHigh = w[2] * pop;
        if (n < 10) {
            return new SurveyResult(s.origin(), s.dest(), n, s.extraYes(),
                    round3(w[0]), round3(w[1]), round3(w[2]),
                    round1(svLow), round1(svHigh),
                    round1(inferLow), round1(inferHigh), true,
                    VerifyStatus.INSUFFICIENT, "抽样 n<10，证据不足：不据此判定冲突，也不支持单点发布。");
        }
        boolean overlap = svLow <= inferHigh + 1e-9 && inferLow <= svHigh + 1e-9;
        VerifyStatus st = overlap ? VerifyStatus.CORROBORATED : VerifyStatus.CONFLICT;
        String note = overlap
                ? "抽样 Wilson 区间与概率推断区间重叠。"
                : "抽样区间与推断区间不重叠：按纪律不得发布该 OD 单点迁移量。";
        return new SurveyResult(s.origin(), s.dest(), n, s.extraYes(),
                round3(w[0]), round3(w[1]), round3(w[2]),
                round1(svLow), round1(svHigh),
                round1(inferLow), round1(inferHigh), overlap, st, note);
    }

    private static double round1(double x) {
        return Math.round(x * 10.0) / 10.0;
    }

    private static double round3(double x) {
        return Math.round(x * 1000.0) / 1000.0;
    }
}
