package com.devlink.server.dto.task;

import com.devlink.server.domain.task.TaskStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record WebTaskListRequest(
		TaskStatus status,
		@Min(0)
		Integer page,
		
		@Min(1)
		@Max(100)
		Integer size) {

	public WebTaskListRequest{
		page = page == null ? 0 : page;
		size = size == null ? 20 : size;
	}
	
}
