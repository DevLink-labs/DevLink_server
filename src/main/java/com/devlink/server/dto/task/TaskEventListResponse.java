package com.devlink.server.dto.task;

import java.util.List;

public record TaskEventListResponse(
		Long taskId,
		List<TaskEventResponse> items,
		String nextBeforeSequence,
		boolean hasNext) {

}
