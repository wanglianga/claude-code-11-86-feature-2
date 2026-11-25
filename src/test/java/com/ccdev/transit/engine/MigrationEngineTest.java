package com.ccdev.transit.engine;

import com.ccdev.transit.engine.mig.Mig;
import com.ccdev.transit.engine.mig.MigSampleData;
import com.ccdev.transit.engine.mig.MigrationEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MigrationEngineTest {

    private Mig.EngineResult run() {
        MigSampleData.Data data = MigSampleData.build();
        return MigrationEngine.rebuild(data.network(), data.taps(), data.transfers(), data.surveys());
    }

    @Test
    void printDiagnostics() {
        Mig.EngineResult r = run();
        System.out.println("detAcc=" + r.detValidationAccuracy() + " n=" + r.detValidationN());
        System.out.println("---- OD MATRIX (prob mig [lo,hi], loss mean[lo,hi]) ----");
        for (Mig.OdRow row : r.matrix()) {
            Mig.OdBlock p = row.probabilistic();
            Mig.OdBlock d = row.deterministic();
            if (p.migPoint() > 0.3 || d.migPoint() > 0.3) {
                System.out.printf(
                        "%s->%s ob=%d | PROB j=%.1f[%.1f,%.1f] mig=%.1f[%.1f,%.1f] loss=%.0f[%.0f,%.0f] totH=%.2f | DET mig=%.1f loss=%.0f%n",
                        row.origin(), row.dest(), row.originBoardings(),
                        p.journeyPoint(), p.journeyLow(), p.journeyHigh(),
                        p.migPoint(), p.migLow(), p.migHigh(),
                        p.lossMeanSec(), p.lossLowSec(), p.lossHighSec(), p.totalLossHours(),
                        d.migPoint(), d.lossMeanSec());
            }
        }
        System.out.println("---- VERIFICATION ----");
        for (Mig.SurveyResult s : r.verification()) {
            System.out.printf("%s->%s %s surveyVol[%.1f,%.1f] infer[%.1f,%.1f] overlap=%s%n",
                    s.origin(), s.dest(), s.status(), s.surveyVolLow(), s.surveyVolHigh(),
                    s.inferLow(), s.inferHigh(), s.overlap());
        }
        System.out.println("GATE " + r.reportGate().status() + " conflicts=" + r.reportGate().conflictOds());
        for (Mig.CaseResult c : r.cases()) {
            if (c.card().contains("DEMO-GAP") || c.card().contains("DEMO-FUZZY")
                    || c.card().contains("DEMO-MISS") || c.card().contains("DEMO-SHORT-01")) {
                System.out.printf("CASE %s align=%s outage=%s multi=%s det=%s/%d xf extra=%s migP=%.2f cands=%s%n",
                        c.card(), c.align(), c.outage(), c.multiCandidate(),
                        c.detDest(), c.detTransfers(), c.detExtraTransfer(), c.migProb(),
                        c.candidates().stream().map(x -> x.station() + ":" + x.posterior()).toList());
            }
        }
    }

    @Test
    void outageKeepsMultipleCandidates() {
        Mig.EngineResult r = run();
        Mig.CaseResult gap = r.cases().stream()
                .filter(c -> c.card().equals("C-DEMO-GAP-01")).findFirst().orElseThrow();
        assertTrue(gap.outage());
        assertTrue(gap.multiCandidate());
        assertTrue(gap.candidates().size() >= 2, "定位中断必须保留至少 2 个候选下车站");
        long retained = gap.candidates().stream().filter(c -> c.posterior() >= 0.1).count();
        assertTrue(retained >= 2);

        Mig.CaseResult fuzzy = r.cases().stream()
                .filter(c -> c.card().equals("C-DEMO-FUZZY-01")).findFirst().orElseThrow();
        assertEquals(Mig.AlignStatus.GPS_GAP_FUZZY, fuzzy.align());
    }

    @Test
    void shortTurnProducesPositiveTransferLossInterval() {
        Mig.EngineResult r = run();
        Mig.OdRow row = r.matrix().stream()
                .filter(x -> x.origin().equals(MigSampleData.E1) && x.dest().equals(MigSampleData.W2))
                .findFirst().orElseThrow();
        Mig.OdBlock p = row.probabilistic();
        assertTrue(p.migPoint() > 5);
        assertTrue(p.migLow() > 0, "强换乘证据下迁移区间下界应 >0");
        assertTrue(p.lossMeanSec() > 300);
        assertTrue(p.lossLowSec() > 0, "额外换乘损失区间下界应 >0");
        assertTrue(p.lossHighSec() >= p.lossMeanSec());
        // 两引擎都必须给出同一 OD
        assertTrue(row.deterministic().journeyPoint() >= 1);
    }

    @Test
    void conflictSurveyForbidsSinglePointGate() {
        Mig.EngineResult r = run();
        assertEquals(Mig.PubStatus.BLOCKED, r.reportGate().status());
        assertFalse(r.reportGate().singlePointAllowed());
        assertTrue(r.reportGate().conflictOds().stream().anyMatch(s -> s.contains(MigSampleData.E2)));
        Mig.SurveyResult conflict = r.verification().stream()
                .filter(s -> s.origin().equals(MigSampleData.E2) && s.dest().equals(MigSampleData.W2))
                .findFirst().orElseThrow();
        assertEquals(Mig.VerifyStatus.CONFLICT, conflict.status());
        assertFalse(conflict.overlap());
    }

    @Test
    void corroboratedAndInsufficientExist() {
        Mig.EngineResult r = run();
        assertTrue(r.verification().stream()
                .anyMatch(s -> s.status() == Mig.VerifyStatus.CORROBORATED));
        assertTrue(r.verification().stream()
                .anyMatch(s -> s.status() == Mig.VerifyStatus.INSUFFICIENT));
    }

    @Test
    void deterministicValidationReported() {
        Mig.EngineResult r = run();
        assertTrue(r.detValidationN() >= 10);
        assertTrue(r.detValidationAccuracy() > 0.3 && r.detValidationAccuracy() <= 1.0);
    }
}
