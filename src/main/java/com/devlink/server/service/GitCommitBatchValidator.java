package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest;
import jakarta.validation.Validator;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import java.util.HashSet;
import java.util.Set;

@Component
public class GitCommitBatchValidator {
	
	private static final int MAX_COMMITS = 100;
	private static final Pattern COMMITTED_AT_PATTERN = Pattern.compile(
	        "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}"
	        + "(?:\\.\\d{1,6})?(?:Z|[+-]\\d{2}:\\d{2})");
	private final Validator validator;
	private static final Pattern DRIVE_PATH_PATTERN = Pattern.compile("^[A-Za-z]:");	
	public GitCommitBatchValidator(Validator validator) {
		this.validator = validator;
	}
	
	private void validateCommittedAt(String value) {
		if (value == null || !COMMITTED_AT_PATTERN.matcher(value).matches()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		try {
			OffsetDateTime.parse(value);
		}catch(DateTimeParseException exception) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}
	
	private void validateRelativePath(String path) {
		if(path == null
				|| path.isBlank()
				|| path.length()>500
				|| path.startsWith("/")
				|| path.indexOf('\\')>= 0
				|| path.indexOf('\0') >= 0
				|| DRIVE_PATH_PATTERN.matcher(path).find()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		for(String part:path.split("/",-1)) {
			if(part.isEmpty()||part.equals(".")||part.equals("..")) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
		}
	}
	
	public void validate(GitCommitBatchRequest request) {
		if(request == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		
		if(request.commits() != null && request.commits().size() > MAX_COMMITS) {
			throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		if(!validator.validate(request).isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		
		for(GitCommitBatchRequest.CommitInput commit : request.commits()) {
			validateCommittedAt(commit.committedAt());
			
			Set<String> paths = new HashSet<>();
			
			for(GitCommitBatchRequest.FileInput file:commit.files()) {
				validateRelativePath(file.relativePath());
				
				if(!paths.add(file.relativePath())) {
					throw new BusinessException(ErrorCode.VALIDATION_FAILED);
				}
			}
		}
	}	
}