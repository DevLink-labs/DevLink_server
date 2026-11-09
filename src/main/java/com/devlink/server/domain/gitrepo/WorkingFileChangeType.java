package com.devlink.server.domain.gitrepo;

/** working_files.files 배열 항목의 changeType. DB 제약은 없으며 서비스에서 검증한다. */
public enum WorkingFileChangeType {
    ADDED, MODIFIED, DELETED, TYPE_CHANGED, UNTRACKED, UNMERGED
}
