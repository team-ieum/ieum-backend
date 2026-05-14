package com.ieum.workflowcore.scheduler;

import java.util.UUID;
import org.quartz.JobKey;
import org.quartz.TriggerKey;

/**
 * Quartz Job/Trigger 키 생성 유틸리티.
 *
 * <p>키 형식:
 * <ul>
 *   <li>JobKey    : {@code workflow-{workflowId}} / group: {@code workflow-schedules}
 *   <li>TriggerKey: {@code trigger-{workflowId}}  / group: {@code workflow-schedules}
 * </ul>
 */
public final class JobKeyGenerator {

    public static final String JOB_GROUP = "workflow-schedules";

    private JobKeyGenerator() {}

    public static JobKey jobKey(UUID workflowId) {
        return JobKey.jobKey("workflow-" + workflowId, JOB_GROUP);
    }

    public static TriggerKey triggerKey(UUID workflowId) {
        return TriggerKey.triggerKey("trigger-" + workflowId, JOB_GROUP);
    }
}
