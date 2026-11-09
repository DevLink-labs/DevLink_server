package com.devlink.server.dto.recommendation;

/** 결과 전체에는 두 값 모두, 0점 후보에는 NO_MATCHING_EVIDENCE만 사용한다. */
public enum InsufficientReason {
    NO_ELIGIBLE_AGENTS, NO_MATCHING_EVIDENCE
}
