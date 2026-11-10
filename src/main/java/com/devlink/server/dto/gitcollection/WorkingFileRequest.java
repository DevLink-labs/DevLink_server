package com.devlink.server.dto.gitcollection;

import java.util.List;
import com.devlink.server.common.StrictStringDeserializer;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.devlink.server.common.WorkingFileChangeTypeDeserializer;

public record WorkingFileRequest(
		@JsonDeserialize(using = StrictStringDeserializer.class)
		String branch,
		@NotNull
		@Valid
		List<@NotNull FileInput> files) {
	
	@JsonAnySetter
	public void rejectUnknownField(String name, Object value) {
		throw new IllegalArgumentException("정의되지 않은 필드입니다: "+name);
	}
	
	public record FileInput(
			@NotBlank
			@JsonDeserialize(using = StrictStringDeserializer.class)
			String relativePath,
			
			@NotNull
			@JsonDeserialize(using = WorkingFileChangeTypeDeserializer.class)
			WorkingFileChangeType changeType) {
		@JsonAnySetter
		public void rejectUnknownField(String name, Object value) {
			throw new IllegalArgumentException("정의되지 않은 필드입니다: "+name);
		}
	}
}
