package com.devlink.server.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.dto.gitcollection.WorkingFileRequest;
import jakarta.validation.Validator;

@Component
public class WorkingFileValidator {

	private static final int MAX_FILES = 2000;
	private static final Pattern REPO_KEY_PATTERN = Pattern.compile("[0-9a-f]{64}");
	private static final Pattern IF_MATCH_PATTERN = Pattern.compile(
	        "\"([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
	        + "[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\"");
	private static final Pattern BRANCH_PATTERN = Pattern.compile("[\\p{L}\\p{N}._/-]+");
	private final Validator validator;
	
	public UUID parseIfMatch(String ifMatch) {
		if(ifMatch == null) {
			return null;
		}
		Matcher matcher = IF_MATCH_PATTERN.matcher(ifMatch);
		
		if(!matcher.matches()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		
		return UUID.fromString(matcher.group(1));
	}
	
	public WorkingFileValidator(Validator validator) {
		this.validator = validator;
	}
	
	public void validateRepoKey(String repoKey) {
		if(repoKey == null
				|| !REPO_KEY_PATTERN.matcher(repoKey).matches()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}
	
	public void validate(String repoKey, WorkingFileRequest request) {
		validateRepoKey(repoKey);
		
		if(request == null) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		
		if(request.files() != null
				&& request.files().size()> MAX_FILES) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		
		if(!validator.validate(request).isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		validateBranch(request.branch());
		
		Set<String> paths = new HashSet<>();
		
		for (WorkingFileRequest.FileInput file : request.files()) {
			validatePath(file.relativePath());
			
			if(!paths.add(file.relativePath())) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
		}
	}
	
	private void validateBranch(String branch) {
		if(branch == null) {
			return;
		}
		if(!validText(branch,255)
				||branch.startsWith("-")
				||branch.contains("..")
				||!BRANCH_PATTERN.matcher(branch).matches()) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
	}
	
	private void validatePath(String path) {
		if(!validText(path,500)
				||path.getBytes(StandardCharsets.UTF_8).length > 1000
				||path.indexOf('\\') >= 0
				||path.matches("^[a-zA-Z]:.*")) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		for(String segment : path.split("/",-1)) {
			if(segment.isEmpty()
					|| segment.equals(".")
					|| segment.equals("..")) {
				throw new BusinessException(ErrorCode.VALIDATION_FAILED);
			}
		}
	}
	
	private boolean validText(String value, int maxLength) {
		return value != null
				&& !value.isBlank()
				&& value.codePointCount(0, value.length())<=maxLength
				&& value.codePoints().noneMatch(Character::isISOControl);
	}
}
