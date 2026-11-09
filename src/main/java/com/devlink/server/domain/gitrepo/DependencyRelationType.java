package com.devlink.server.domain.gitrepo;

/** 파일 경로로 표현할 수 있는 의존 관계. */
public enum DependencyRelationType {
    IMPORT, RENDERS, CALLS
}
