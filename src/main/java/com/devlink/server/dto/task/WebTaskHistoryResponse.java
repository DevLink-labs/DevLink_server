package com.devlink.server.dto.task;

import java.util.List;

public record WebTaskHistoryResponse(	
		Long taskId,
		String prompt,
		String resultText,
		String failureReason,
		String rejectionReason,
		boolean partiallyModified,
		List<TaskFileResponse> taskFiles,
		List<TaskEventResponse> items,
		String nextBeforeSequence,
		boolean hasNext,
        String nextAfterSequence) {
    public WebTaskHistoryResponse(Long taskId, String prompt, String resultText, String failureReason,
            String rejectionReason, boolean partiallyModified, List<TaskFileResponse> taskFiles,
            List<TaskEventResponse> items, String nextBeforeSequence, boolean hasNext) {
        this(taskId, prompt, resultText, failureReason, rejectionReason, partiallyModified, taskFiles,
                items, nextBeforeSequence, hasNext, null);
    }


}
