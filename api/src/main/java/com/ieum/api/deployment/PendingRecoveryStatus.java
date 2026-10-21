package com.ieum.api.deployment;

import java.time.Instant;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Shared observation only; no queue, database or endpoint dependencies. */
@Component
@ConditionalOnProperty(name = "ieum.deployment.distributed.enabled", havingValue = "true")
public class PendingRecoveryStatus {
    public record Snapshot(boolean initialized, boolean valid, String reason,
                           String expectedZone, String jvmZone, String clockZone,
                           long checkedAtEpochSeconds, long blockedSinceEpochSeconds,
                           long lastBlockedAtEpochSeconds, long blockedChecks) {
        public boolean blocked() { return !valid; }
        public Map<String, Object> asMap() {
            return Map.ofEntries(Map.entry("initialized", initialized), Map.entry("valid", valid),
                Map.entry("blocked", blocked()), Map.entry("reason", reason),
                Map.entry("expectedZone", expectedZone), Map.entry("jvmZone", jvmZone),
                Map.entry("clockZone", clockZone), Map.entry("checkedAtEpochSeconds", checkedAtEpochSeconds),
                Map.entry("blockedSinceEpochSeconds", blockedSinceEpochSeconds),
                Map.entry("lastBlockedAtEpochSeconds", lastBlockedAtEpochSeconds),
                Map.entry("blockedChecks", blockedChecks));
        }
    }

    private volatile Snapshot snapshot = new Snapshot(false, false, "not_validated", "", "", "", 0, 0, 0, 0);
    public Snapshot snapshot() { return snapshot; }

    public synchronized void valid(String expected, String jvm, String clock, Instant now) {
        var prior = snapshot;
        snapshot = new Snapshot(true, true, "", expected, jvm, clock, now.getEpochSecond(),
            0, prior.lastBlockedAtEpochSeconds(), prior.blockedChecks());
    }

    public synchronized void blocked(String expected, String jvm, String clock, String reason, Instant now) {
        var prior = snapshot;
        long since = prior.initialized() && prior.blocked() ? prior.blockedSinceEpochSeconds() : now.getEpochSecond();
        snapshot = new Snapshot(true, false, reason, expected, jvm, clock, now.getEpochSecond(),
            since, now.getEpochSecond(), prior.blockedChecks() + 1);
    }
}
