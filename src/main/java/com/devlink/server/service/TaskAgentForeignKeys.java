package com.devlink.server.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

final class TaskAgentForeignKeys {
    private TaskAgentForeignKeys() {
    }

    static boolean isViolatedBy(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                String constraintName = violation.getConstraintName();
                if ("fk_tasks_requester_agent".equals(constraintName)
                        || "fk_tasks_receiver_agent".equals(constraintName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
