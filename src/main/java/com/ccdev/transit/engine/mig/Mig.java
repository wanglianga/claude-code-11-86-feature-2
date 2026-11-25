package com.ccdev.transit.engine.mig;

import java.util.List;
import java.util.Map;

/**
 * 客流迁移 / 旅程重建领域的纯数据类型。
 *
 * <p>这是一个与 Spring/DB 无关的确定性计算内核：输入 AFC 刷卡、AVL 轨迹、换乘记录与线网，
 * 输出上车对齐、候选下车站、双引擎（确定性时刻表分配 / 概率贝叶斯推断）旅程与 OD 区间、
 * 抽样核验冲突和发布门控结论。</p>
 */
public final class Mig {

    private Mig() {
    }

    public enum AlignStatus {ALIGNED, NEAREST_FALLBACK, GPS_GAP_FUZZY, UNMATCHED}

    public enum Method {DETERMINISTIC, PROBABILISTIC}

    public enum VerifyStatus {CORROBORATED, CONFLICT, NO_SURVEY, INSUFFICIENT}

    public enum PubStatus {PUBLISHED, INTERVAL_ONLY, BLOCKED}

    // ---------------- 线网与原始观测 ----------------

    public record Station(String code, String name, boolean hub, double attract) {
    }

    public record RouteInfo(String code, String name, List<String> stopSeq,
                            int headwaySec, int transferWalkSec) {
        public int indexOf(String stop) {
            return stopSeq.indexOf(stop);
        }

        public boolean contains(String stop) {
            return stopSeq.contains(stop);
        }
    }

    /** 班次实际轨迹（对齐后的结构化形态，由 AVL 点 + 轨迹头得到）。 */
    public record TripTrace(String tripCode, String routeCode, String vehicleCode,
                            Map<String, Integer> arriveMin,
                            List<String> actualStops,
                            boolean shortTurn, String turnStop, String lastActualStop,
                            boolean outage, String outageFromStop, String outageToStop,
                            int congestionSec) {
    }

    public record Tap(String card, String txnType, int minute, String station,
                      String routeCode, String vehicleCode, String tripCode) {
        public boolean isTapOut() {
            return "TAP_OUT".equals(txnType);
        }
    }

    public record TransferRec(String card, String fromRoute, String fromTrip, String fromStation,
                              String toRoute, String toTrip, String toStation,
                              int minute, Integer waitSec) {
    }

    // ---------------- 重建结果 ----------------

    public record Candidate(String station, double prior, double posterior,
                            boolean detChosen, String reason) {
    }

    /**
     * 一条候选完整路径及其后验。
     * extraTransfer=true 表示该路径相对该 OD 的“无折返直达基线”多出一次换乘，
     * 且该多出来的换乘可归因于短线折返。
     */
    public record PathOption(String dest, String plan, List<String> routes,
                            int transfers, int waitSec, double waitStdSec,
                            double probability, boolean extraTransfer) {
        /** 感知换乘损失：换乘等待 + 每次换乘的惩罚当量。 */
        public double lossSec(double transferPenaltySec) {
            return waitSec + transfers * transferPenaltySec;
        }
    }

    public record CaseResult(
            String boardingCode, String card, int boardMinute, String origin,
            String route, String vehicle, String matchedTrip,
            AlignStatus align, int timeOffsetSec,
            boolean outage, boolean shortTurn, String turnStop,
            boolean tapOut, Integer observedWaitSec,
            List<Candidate> candidates,
            String detDest, String detPlan, int detTransfers, int detWaitSec, int detLossSec,
            double detConfidence, boolean detMismatchRisk,
            boolean detExtraTransfer, double detWaitStdSec,
            List<PathOption> options,
            double expectedLoss, double expectedLossSq, double migProb,
            boolean multiCandidate) {
    }

    /** 单个 OD × 单个引擎的估计块；所有量都带区间。 */
    public record OdBlock(Method method, String origin, String dest, int originBoardings,
                           double journeyPoint, double journeyLow, double journeyHigh,
                           double migPoint, double migLow, double migHigh,
                           double lossMeanSec, double lossLowSec, double lossHighSec,
                           double totalLossHours, double totalLossLowHours, double totalLossHighHours) {
    }

    public record OdRow(String origin, String dest, int originBoardings,
                        OdBlock deterministic, OdBlock probabilistic) {
    }

    public record SurveyInput(String origin, String dest, int sampleN, int extraYes, int population) {
    }

    public record SurveyResult(String origin, String dest, int sampleN, int extraYes,
                               double surveyShare, double surveyLow, double surveyHigh,
                               double surveyVolLow, double surveyVolHigh,
                               double inferLow, double inferHigh,
                               boolean overlap, VerifyStatus status, String note) {
    }

    public record PublishDecision(String scope, String mode, PubStatus status,
                                  boolean singlePointAllowed, Double pointValue,
                                  Double intervalLow, Double intervalHigh,
                                  List<String> conflictOds, String reason) {
    }

    public record EngineResult(
            List<CaseResult> cases,
            List<OdRow> matrix,
            List<SurveyResult> verification,
            PublishDecision reportGate,
            double detValidationAccuracy,
            int detValidationN,
            double transferPenaltySec) {
    }

    /** 路径权重构造期的可变累加器（归一化前使用原始权重）。 */
    public static final class OptionAcc {
        public final String dest;
        public final String plan;
        public final List<String> routes;
        public final int transfers;
        public final int waitSec;
        public final double waitStdSec;
        public double weight;
        public final boolean extraTransfer;

        public OptionAcc(String dest, String plan, List<String> routes, int transfers,
                         int waitSec, double waitStdSec, double weight, boolean extraTransfer) {
            this.dest = dest;
            this.plan = plan;
            this.routes = routes;
            this.transfers = transfers;
            this.waitSec = waitSec;
            this.waitStdSec = waitStdSec;
            this.weight = weight;
            this.extraTransfer = extraTransfer;
        }
    }
}
