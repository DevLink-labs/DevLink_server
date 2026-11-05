package com.devlink.server.domain.gitrepo;

/** commit_files.change_type. DB CHECK 제약과 동일한 값을 유지한다. */
public enum CommitChangeType {
    ADDED, MODIFIED, DELETED, RENAMED
}
