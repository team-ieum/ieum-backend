package com.ieum.workflowcore.engine;

import java.util.UUID;

/** Optional deployment port. Checks ownership before new side effects and state writes. */
public interface ExecutionOwnershipGuard {
    void assertOwned(UUID executionId);

    /** Infrastructure failure, never a permanent workflow failure. Keep the queue delivery pending. */
    final class OwnershipLostException extends RuntimeException {
        public OwnershipLostException(String message, Throwable cause) { super(message, cause); }
        public OwnershipLostException(String message) { super(message); }
    }
}
