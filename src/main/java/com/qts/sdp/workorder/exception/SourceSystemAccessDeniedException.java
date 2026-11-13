package com.qts.sdp.workorder.exception;

import com.qts.sdp.workorder.model.SourceSystem;
import com.qts.sdp.workorder.security.Permission;

/** The caller is authenticated but holds no grant for this action on this source system. */
public class SourceSystemAccessDeniedException extends RuntimeException {

    private final Permission.Action action;
    private final SourceSystem sourceSystem;

    public SourceSystemAccessDeniedException(Permission.Action action, SourceSystem sourceSystem) {
        super("No " + action + " access to source system " + sourceSystem);
        this.action = action;
        this.sourceSystem = sourceSystem;
    }

    public Permission.Action getAction() {
        return action;
    }

    public SourceSystem getSourceSystem() {
        return sourceSystem;
    }
}
