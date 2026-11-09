package com.devlink.server.dto.gitcollection;

import java.util.List;

import com.devlink.server.common.StrictStringDeserializer;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record GitCommitBatchRequest(@NotBlank
		@Pattern(regexp = "[0-9a-f]{64}")
		@JsonDeserialize(using = StrictStringDeserializer.class)
		String repoKey,
		
		@NotEmpty
		@Valid
		List<@NotNull CommitInput> commits) {
	
	public record CommitInput(
			@NotBlank
			@Pattern(regexp = "(?:[0-9a-f]{40}|[0-9a-f]{64})")
			@JsonDeserialize(using = StrictStringDeserializer.class)
			String commitSha,
			
			@NotBlank
			@JsonDeserialize(using = StrictStringDeserializer.class)
			String committedAt,
			
			@JsonProperty(value = "authorUserId", required = true)
			@Positive
			Long authorUserId,
			
			@NotNull
			@Valid
			List<@NotNull FileInput> files
			
			
			
			) {
		
	}
	public record FileInput(
			@NotBlank
			@Size(max = 500)
			@JsonDeserialize(using = StrictStringDeserializer.class)
			String relativePath,
			
			@NotNull
			CommitChangeType changeType
			) {
		
	}
	
	
	
	
}
