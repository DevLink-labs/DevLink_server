package com.devlink.server.domain.gitrepo;

/** working_files.files JSON 배열의 항목. 경로 규칙과 중복 검증은 서비스에서 처리한다. */
public record WorkingFileEntry(String relativePath, WorkingFileChangeType changeType) {
}
