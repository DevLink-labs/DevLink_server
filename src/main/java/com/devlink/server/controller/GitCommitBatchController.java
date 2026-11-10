package com.devlink.server.controller;

import com.devlink.server.common.ApiResponse;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse;
import com.devlink.server.service.GitCommitBatchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/git")
@RequiredArgsConstructor
public class GitCommitBatchController {

	private final GitCommitBatchService service;
	
	@PostMapping(
			value = "/commits:batch",
			consumes = MediaType.APPLICATION_JSON_VALUE,
			produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<GitCommitBatchResponse>> upload(
			@PathVariable Long teamId,
			@AuthenticationPrincipal AgentPrincipal principal,
			@RequestBody GitCommitBatchRequest request){
		return ResponseEntity.ok(ApiResponse.success(service.upload(teamId, principal, request)));
	}
}
