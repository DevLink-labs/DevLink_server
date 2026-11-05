package com.devlink.server.dto.task;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record TaskEventListRequest(
		@Pattern(regexp = "[1-9][0-9]{0,18}")
		@DecimalMax("9223372036854775807")
		String beforeSequence,
		
		@Min(1)
		@Max(100)
		Integer limit) {
	public TaskEventListRequest{
		limit = limit ==null?50:limit;
	}
}
