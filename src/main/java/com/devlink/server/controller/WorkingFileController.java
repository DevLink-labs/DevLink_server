package com.devlink.server.controller;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.devlink.server.common.ApiResponse;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.gitcollection.WorkingFileRequest;
import com.devlink.server.service.WorkingFileService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/git")
@RequiredArgsConstructor
public class WorkingFileController {
	
	private final WorkingFileService service;
	
	@PutMapping(
			value = {"/working-files/{repoKey}",
					"/dependency-graph/working-files/{repoKey}"},
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<Void> replace(
			@PathVariable Long teamId,
			@PathVariable String repoKey,
			@AuthenticationPrincipal AgentPrincipal principal,
			@RequestBody WorkingFileRequest request){
		service.replace(teamId, repoKey, principal, request);
		
		return ApiResponse.success(null);
	}
}
