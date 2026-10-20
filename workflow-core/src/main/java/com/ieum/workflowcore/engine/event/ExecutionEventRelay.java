package com.ieum.workflowcore.engine.event;

import java.util.UUID;

/** Cross-instance transport; local-only deployments do not need an implementation. */
public interface ExecutionEventRelay {
    void publish(UUID executionId, ExecutionEvent event);
    void complete(UUID executionId);
}
