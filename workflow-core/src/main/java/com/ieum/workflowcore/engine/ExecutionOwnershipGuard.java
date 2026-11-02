package com.ieum.workflowcore.engine;

import java.util.UUID;

/** Optional deployment port. Checks ownership before new side effects and state writes. */
public interface ExecutionOwnershipGuard {
    void assertOwned(UUID executionId);

    /** Infrastructure failure, never a permanent workflow failure. Keep the queue delivery pending. */
    class OwnershipLostException extends RuntimeException {
        public OwnershipLostException(String message, Throwable cause) { super(message, cause); }
        public OwnershipLostException(String message) { super(message); }
    }

    /** No new effects may start, but a timeout is not affirmative evidence of lease loss. */
    final class OwnershipUncertainException extends OwnershipLostException {
        public OwnershipUncertainException(String message, Throwable cause) { super(message, cause); }
    }
}
