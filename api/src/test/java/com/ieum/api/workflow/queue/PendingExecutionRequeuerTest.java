package com.ieum.api.workflow.queue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ieum.workflowcore.repository.WorkflowExecutionRepository;
import com.ieum.api.deployment.DeploymentControl;
import com.ieum.api.deployment.PendingRecoveryStatus;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.ieum.workflowcore.repository.WorkflowExecutionRepository.PendingObservation;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.slf4j.LoggerFactory;

class PendingExecutionRequeuerTest {
    private WorkflowExecutionRepository executions;
    private ExecutionJobQueue queue;
    private DeploymentControl control;
    private PendingExecutionRequeuer requeuer;
    private SimpleMeterRegistry metrics;

    @BeforeEach
    void setUp() {
        executions = mock(WorkflowExecutionRepository.class);
        queue = mock(ExecutionJobQueue.class);
        control = mock(DeploymentControl.class);
        when(control.isOwner()).thenReturn(true);
        when(executions.findPendingIdsBetween(any(), any(), any())).thenReturn(List.of());
        requeuer = new PendingExecutionRequeuer(executions, queue, control);
        metrics = new SimpleMeterRegistry();
        ReflectionTestUtils.setField(requeuer, "metrics", metrics);
        ReflectionTestUtils.setField(requeuer, "expectedZoneName", ZoneId.systemDefault().getId());
        ReflectionTestUtils.setField(requeuer, "notBefore", Instant.now().minusSeconds(86400).toString());
        requeuer.validatePolicy();
    }

    @Test
    void missingOrUnzonedCutoffFailsStartupRatherThanSilentlyReplayingHistoricalRows() {
        ReflectionTestUtils.setField(requeuer, "notBefore", "");
        assertThatThrownBy(requeuer::validatePolicy).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(requeuer, "notBefore", "2026-10-20T12:00:00");
        assertThatThrownBy(requeuer::validatePolicy).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(queue);
    }

    @Test
    void candidateAndRetainedInstancesNeverAutomaticallyRequeue() {
        when(control.isOwner()).thenReturn(false);
        requeuer.recoverPending();
        verify(executions, never()).findPendingIdsBetween(any(), any(), any());
        verifyNoInteractions(queue);
    }

    @Test
    void rowsAreRestrictedToRecentHourEvenWithAnOldCutoff() {
        LocalDateTime before = LocalDateTime.now();
        requeuer.recoverPending();
        var lower = ArgumentCaptor.forClass(LocalDateTime.class);
        var upper = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(executions).findPendingIdsBetween(lower.capture(), upper.capture(), any());
        assertThat(lower.getValue()).isBetween(before.minusHours(1), LocalDateTime.now().minusHours(1));
        assertThat(upper.getValue()).isBetween(before.minusMinutes(1), LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void aMoreRecentOperatorCutoffIsAnExactLowerBoundInJvmDatabaseTime() {
        Instant cutoff = Instant.now().minusSeconds(600);
        ReflectionTestUtils.setField(requeuer, "notBefore", cutoff.toString());
        requeuer.validatePolicy();
        requeuer.recoverPending();
        verify(executions).findPendingIdsBetween(
            eq(LocalDateTime.ofInstant(cutoff, ZoneId.systemDefault())), any(), any());
    }

    @Test
    void durablePendingRowsAreRetriedAndScanProgressesPastBlockedRows() {
        var first = java.util.stream.IntStream.range(0, 100).mapToObj(i -> UUID.randomUUID()).toList();
        UUID later = UUID.randomUUID();
        when(executions.findPendingIdsBetween(any(), any(), any()))
            .thenReturn(first, List.of(later), List.of());
        requeuer.recoverPending();
        requeuer.recoverPending();
        requeuer.recoverPending();
        verify(queue).requeuePending(later, 3);
        var pages = ArgumentCaptor.forClass(Pageable.class);
        verify(executions, times(3)).findPendingIdsBetween(any(), any(), pages.capture());
        assertThat(pages.getAllValues().stream().map(Pageable::getPageNumber)).containsExactly(0, 1, 0);
    }

    @Test
    void ownershipLossDuringBatchStopsFurtherPublication() {
        ReflectionTestUtils.setField(requeuer, "nextObservation", Instant.MAX);
        when(executions.findPendingIdsBetween(any(), any(), any()))
            .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(control.isOwner()).thenReturn(true, true, false);
        requeuer.recoverPending();
        verify(queue, times(1)).requeuePending(any(), eq(3));
    }

    @Test
    void pendingAt61MinutesWarnsAndSetsSampleMetricWithoutReplayingOrFailingIt() {
        Instant now = Instant.parse("2026-10-21T03:00:00Z");
        setNow(now);
        LocalDateTime started = LocalDateTime.ofInstant(now.minusSeconds(61 * 60), ZoneId.systemDefault());
        var row = observation(started);
        when(executions.findPendingObservationsBetween(any(), any(), any())).thenReturn(List.of(row));
        Logger logger = (Logger) LoggerFactory.getLogger(PendingExecutionRequeuer.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            requeuer.recoverPending();
            requeuer.recoverPending(); // Observation is rate limited independently of the 20-second scan.
        } finally { logger.detachAppender(logs); }
        assertThat(gauge("excluded.sampled", "reason", "age")).isEqualTo(1);
        assertThat(gauge("excluded.oldest_age_seconds", "reason", "age")).isEqualTo(3660);
        assertThat(logs.list.stream().filter(log -> log.getLevel().equals(ch.qos.logback.classic.Level.WARN))
            .map(ILoggingEvent::getFormattedMessage)).singleElement()
            .asString().contains("운영자 검토 필요", "reason: age", row.getId().toString(), "PENDING 유지");
        verify(executions, times(1)).findPendingObservationsBetween(any(), any(), any());
        verifyNoInteractions(queue);
        verify(executions, never()).save(any());
        verify(executions, never()).finishIfNotTerminal(any(), any(), anyBoolean(), any());
    }

    @Test
    void excludedObservationIsOwnerOnlyAndResetsGaugesOnLossOfOwnership() {
        var row = observation(LocalDateTime.now().minusDays(2));
        when(executions.findPendingObservationsBefore(any(), any()))
            .thenReturn(List.of(row));
        requeuer.recoverPending();
        assertThat(gauge("excluded.sampled", "reason", "before_cutoff")).isEqualTo(1);
        clearInvocations(executions);
        when(control.isOwner()).thenReturn(false);
        requeuer.recoverPending();
        assertThat(gauge("excluded.sampled", "reason", "before_cutoff")).isZero();
        assertThat(metrics.get("ieum.workflow.pending.recovery.observed_at_epoch_seconds").gauge().value()).isZero();
        verifyNoInteractions(executions);
    }

    @Test
    void observationUses101RowSentinelAndResetsAfterBacklogClears() {
        Instant now = Instant.now(); setNow(now);
        var rows = java.util.stream.IntStream.range(0, 101)
            .mapToObj(i -> observation(LocalDateTime.now().minusHours(2).plusSeconds(i))).toList();
        when(executions.findPendingObservationsBetween(any(), any(), any())).thenReturn(rows, List.of());
        requeuer.recoverPending();
        assertThat(gauge("excluded.sampled", "reason", "age")).isEqualTo(100);
        assertThat(gauge("excluded.limit_reached", "reason", "age")).isEqualTo(1);
        var page = ArgumentCaptor.forClass(Pageable.class);
        verify(executions).findPendingObservationsBetween(any(), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(101);
        ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(now.plusSeconds(61), ZoneId.systemDefault()));
        requeuer.recoverPending();
        assertThat(gauge("excluded.sampled", "reason", "age")).isZero();
        assertThat(gauge("excluded.limit_reached", "reason", "age")).isZero();
        assertThat(gauge("excluded.oldest_age_seconds", "reason", "age")).isZero();
    }

    @Test
    void exhaustedAndDeferredAreCountedAndLastBatchGaugesRecover() {
        when(executions.findPendingIdsBetween(any(), any(), any()))
            .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID()), List.of());
        when(queue.requeuePending(any(), eq(3))).thenReturn(ExecutionJobQueue.RecoveryResult.ATTEMPTS_EXHAUSTED,
            ExecutionJobQueue.RecoveryResult.DEFERRED);
        requeuer.recoverPending();
        assertThat(gauge("batch", "result", "attempts_exhausted")).isEqualTo(1);
        assertThat(gauge("batch", "result", "deferred")).isEqualTo(1);
        assertThat(metrics.get("ieum.workflow.pending.recovery.results").tag("result", "attempts_exhausted")
            .counter().count()).isEqualTo(1);
        requeuer.recoverPending();
        assertThat(gauge("batch", "result", "attempts_exhausted")).isZero();
        assertThat(gauge("batch", "result", "deferred")).isZero();
    }

    @Test
    void observationFailurePreservesLastSnapshotAndDoesNotBlockInWindowRecovery() {
        Instant now = Instant.now(); setNow(now);
        var row = observation(LocalDateTime.now().minusHours(2));
        when(executions.findPendingObservationsBetween(any(), any(), any())).thenReturn(List.of(row))
            .thenThrow(new IllegalStateException("observation timed out"));
        requeuer.recoverPending();
        double successfulAt = metrics.get("ieum.workflow.pending.recovery.observed_at_epoch_seconds").gauge().value();
        ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(now.plusSeconds(61), ZoneId.systemDefault()));
        when(executions.findPendingIdsBetween(any(), any(), any())).thenReturn(List.of(UUID.randomUUID()));
        requeuer.recoverPending();
        assertThat(gauge("excluded.sampled", "reason", "age")).isEqualTo(1);
        assertThat(metrics.get("ieum.workflow.pending.recovery.observed_at_epoch_seconds").gauge().value())
            .isEqualTo(successfulAt);
        assertThat(metrics.get("ieum.workflow.pending.recovery.observation.failures").counter().count()).isEqualTo(1);
        verify(queue).requeuePending(any(), eq(3));
    }

    @Test
    void historicalWarningIsOncePerProcessAcrossRepeatedObservationAndOwnershipChanges() {
        Instant now = Instant.now(); setNow(now);
        var historical = observation(LocalDateTime.now().minusDays(2));
        var age = observation(LocalDateTime.now().minusHours(2));
        when(executions.findPendingObservationsBefore(any(), any())).thenReturn(List.of(historical));
        when(executions.findPendingObservationsBetween(any(), any(), any())).thenReturn(List.of(age));
        Logger logger = (Logger) LoggerFactory.getLogger(PendingExecutionRequeuer.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            requeuer.recoverPending();
            ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(now.plusSeconds(61), ZoneId.systemDefault()));
            requeuer.recoverPending();
            when(control.isOwner()).thenReturn(false);
            requeuer.recoverPending();
            assertThat(gauge("excluded.sampled", "reason", "before_cutoff")).isZero();
            when(control.isOwner()).thenReturn(true);
            ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(now.plusSeconds(122), ZoneId.systemDefault()));
            requeuer.recoverPending();
        } finally { logger.detachAppender(logs); }
        var warnings = logs.list.stream().filter(log -> log.getLevel().equals(ch.qos.logback.classic.Level.WARN))
            .map(ILoggingEvent::getFormattedMessage).toList();
        assertThat(warnings.stream().filter(log -> log.contains("reason: before_cutoff"))).hasSize(1);
        assertThat(warnings.stream().filter(log -> log.contains("reason: age"))).hasSize(3);
        assertThat(gauge("excluded.sampled", "reason", "before_cutoff")).isEqualTo(1);
        verify(executions, times(3)).findPendingObservationsBefore(any(), any());
    }

    @Test
    void anEmptyHistoricalFirstObservationDoesNotConsumeTheOneTimeSummary() {
        Instant now = Instant.now(); setNow(now);
        requeuer.recoverPending();
        var historical = observation(LocalDateTime.now().minusDays(2));
        when(executions.findPendingObservationsBefore(any(), any())).thenReturn(List.of(historical));
        Logger logger = (Logger) LoggerFactory.getLogger(PendingExecutionRequeuer.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(now.plusSeconds(61), ZoneId.systemDefault()));
            requeuer.recoverPending();
        } finally { logger.detachAppender(logs); }
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
            .filter(log -> log.contains("reason: before_cutoff"))).hasSize(1);
    }

    @Test
    void missingInvalidOrDifferentlyCasedExpectedZoneFailsBeforeAnyRecovery() {
        for (String invalid : List.of("", "asia/seoul", "not-a-time-zone")) {
            ReflectionTestUtils.setField(requeuer, "expectedZoneName", invalid);
            assertThatThrownBy(requeuer::validatePolicy).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pending-recovery-expected-zone");
        }
        org.assertj.core.api.Assertions.assertThatCode(requeuer::recoverPending).doesNotThrowAnyException();
        assertThat(gauge("timezone.blocked", "", "")).isEqualTo(1);
        verifyNoInteractions(executions, queue);
    }

    @Test
    void expectedZoneMismatchRejectsJvmOverrideInsteadOfReinterpretingStoredTimestamps() {
        ZoneId different = PendingExecutionRequeuer.sameTimestampZone(ZoneId.systemDefault(), ZoneId.of("UTC"))
            ? ZoneId.of("Asia/Seoul") : ZoneId.of("UTC");
        ReflectionTestUtils.setField(requeuer, "expectedZoneName", different.getId());
        assertThatThrownBy(requeuer::validatePolicy).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("timezone differs").hasMessageContaining("JVM=")
            .hasMessageContaining("existing timestamps are not converted");
        org.assertj.core.api.Assertions.assertThatCode(requeuer::recoverPending).doesNotThrowAnyException();
        assertThat(status().snapshot().reason()).isEqualTo("zone_mismatch");
        verifyNoInteractions(executions, queue);
    }

    @Test
    void fullTimezoneRulesAcceptAliasesButRejectMatchingCurrentOffsets() {
        assertThat(PendingExecutionRequeuer.sameTimestampZone(ZoneId.of("UTC"), ZoneId.of("Etc/UTC"))).isTrue();
        assertThat(PendingExecutionRequeuer.sameTimestampZone(ZoneId.of("Asia/Seoul"), ZoneId.of("+09:00"))).isFalse();
    }

    @Test
    void runtimeClockZoneDriftStopsBeforeDatabaseQueriesOrPublication() {
        ZoneId different = PendingExecutionRequeuer.sameTimestampZone(ZoneId.systemDefault(), ZoneId.of("UTC"))
            ? ZoneId.of("Asia/Seoul") : ZoneId.of("UTC");
        ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(Instant.now(), different));
        org.assertj.core.api.Assertions.assertThatCode(requeuer::recoverPending).doesNotThrowAnyException();
        assertThat(metrics.get("ieum.workflow.pending.recovery.timezone.validation_failures").counter().count())
            .isEqualTo(1);
        assertThat(status().snapshot().blocked()).isTrue();
        verifyNoInteractions(executions, queue);
    }

    @Test
    void runtimeDriftRecordsFailureThrottlesWarningsAndAutomaticallyResumesWhenCorrected() {
        Instant now = Instant.now(); setNow(now);
        ZoneId different = PendingExecutionRequeuer.sameTimestampZone(ZoneId.systemDefault(), ZoneId.of("UTC"))
            ? ZoneId.of("Asia/Seoul") : ZoneId.of("UTC");
        Logger logger = (Logger) LoggerFactory.getLogger(PendingExecutionRequeuer.class);
        var logs = new ListAppender<ILoggingEvent>(); logs.start(); logger.addAppender(logs);
        try {
            for (long elapsed : new long[]{0, 20, 300}) {
                ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(now.plusSeconds(elapsed), different));
                org.assertj.core.api.Assertions.assertThatCode(requeuer::recoverPending).doesNotThrowAnyException();
            }
            verifyNoInteractions(executions, queue);
            assertThat(gauge("timezone.valid", "", "")).isZero();
            assertThat(gauge("timezone.blocked", "", "")).isEqualTo(1);
            assertThat(status().snapshot().blockedChecks()).isEqualTo(3);
            assertThat(status().snapshot().blockedSinceEpochSeconds()).isEqualTo(now.getEpochSecond());
            assertThat(status().snapshot().lastBlockedAtEpochSeconds()).isEqualTo(now.plusSeconds(300).getEpochSecond());
            ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(now.plusSeconds(301), ZoneId.systemDefault()));
            requeuer.recoverPending();
            requeuer.recoverPending();
        } finally { logger.detachAppender(logs); }
        assertThat(logs.list.stream().filter(log -> log.getLevel().equals(ch.qos.logback.classic.Level.WARN)))
            .hasSize(2).allSatisfy(log -> assertThat(log.getThrowableProxy()).isNull());
        assertThat(logs.list.stream().map(ILoggingEvent::getFormattedMessage)
            .filter(log -> log.contains("시간대 검증 정상화"))).hasSize(1);
        assertThat(metrics.get("ieum.workflow.pending.recovery.timezone.validation_failures").counter().count())
            .isEqualTo(3);
        assertThat(metrics.get("ieum.workflow.pending.recovery.failures").counter().count()).isEqualTo(3);
        assertThat(gauge("timezone.valid", "", "")).isEqualTo(1);
        assertThat(gauge("timezone.blocked", "", "")).isZero();
        assertThat(status().snapshot().blockedSinceEpochSeconds()).isZero();
        assertThat(status().snapshot().blockedChecks()).isEqualTo(3);
        verify(executions, times(2)).findPendingIdsBetween(any(), any(), any());
    }

    private PendingRecoveryStatus status() {
        return (PendingRecoveryStatus) ReflectionTestUtils.getField(requeuer, "timezoneStatus");
    }

    private void setNow(Instant now) {
        ReflectionTestUtils.setField(requeuer, "clock", Clock.fixed(now, ZoneId.systemDefault()));
        ReflectionTestUtils.setField(requeuer, "notBefore", now.minusSeconds(86400).toString());
        requeuer.validatePolicy();
    }

    private PendingObservation observation(LocalDateTime started) {
        var row = mock(PendingObservation.class);
        when(row.getId()).thenReturn(UUID.randomUUID());
        when(row.getStartedAt()).thenReturn(started);
        return row;
    }

    private double gauge(String suffix, String key, String value) {
        if (key.isEmpty()) return metrics.get("ieum.workflow.pending.recovery." + suffix).gauge().value();
        return metrics.get("ieum.workflow.pending.recovery." + suffix).tag(key, value).gauge().value();
    }
}
