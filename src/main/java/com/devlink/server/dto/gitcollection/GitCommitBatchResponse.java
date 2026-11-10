package com.devlink.server.dto.gitcollection;

import java.util.List;

public record GitCommitBatchResponse(
		Long repoId,
		String repoKey,
		int receivedCount,
		int uniqueCount,
		int insertedCount,
		int updatedCount,
		int duplicateCount,
		int conflictCount,
		List<Conflict> conflicts) {

	public record Conflict(
		String commitSha,
		ConflictReason reason) {
	}
	public enum ConflictReason{
		BATCH_CONTENT_MISMATCH,
		COMMIT_CONTENT_MISMATCH,
		AUTHOR_MISMATCH
	}
	
}
