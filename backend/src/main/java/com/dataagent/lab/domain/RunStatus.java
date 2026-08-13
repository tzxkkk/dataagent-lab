package com.dataagent.lab.domain;

public enum RunStatus {
    CREATED,
    PLANNING,
    WAITING_FOR_CLARIFICATION,
    WAITING_FOR_APPROVAL,
    RUNNING,
    SUCCEEDED,
    DATA_UNAVAILABLE,
    NOT_IMPLEMENTED,
    FAILED
}
