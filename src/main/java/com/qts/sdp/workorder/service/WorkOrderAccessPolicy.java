package com.qts.sdp.workorder.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.qts.sdp.workorder.exception.SourceSystemAccessDeniedException;
import com.qts.sdp.workorder.model.SourceSystem;
import com.qts.sdp.workorder.security.Caller;
import com.qts.sdp.workorder.security.Permission;

/**
 * Source-system level authorization. The decision uses only the caller's validated token
 * permissions and the source system of the data being touched: for a new work order that is
 * the requested owner, for an existing one it is the value stored in the database.
 */
@Component
public class WorkOrderAccessPolicy {

    private static final Logger log = LoggerFactory.getLogger(WorkOrderAccessPolicy.class);

    public void requireRead(Caller caller, SourceSystem sourceSystem) {
        if (!caller.canRead(sourceSystem)) {
            deny(caller, Permission.Action.READ, sourceSystem);
        }
    }

    public void requireWrite(Caller caller, SourceSystem sourceSystem) {
        if (!caller.canWrite(sourceSystem)) {
            deny(caller, Permission.Action.WRITE, sourceSystem);
        }
    }

    private static void deny(Caller caller, Permission.Action action, SourceSystem sourceSystem) {
        log.warn("Authorization denied: client={} action={} sourceSystem={}",
                caller.clientId(), action, sourceSystem);
        throw new SourceSystemAccessDeniedException(action, sourceSystem);
    }
}
