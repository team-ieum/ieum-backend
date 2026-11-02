package com.ieum.workflowcore.deployment;

/** Selects the only instance allowed to run non-clustered maintenance jobs. */
public interface BackgroundOwnership {
    boolean isOwner();
}
