package com.devlink.server.controller;

import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.devlink.server.common.ApiResponse;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.gitcollection.WorkingFileRequest;
import com.devlink.server.service.WorkingFileService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import com.devlink.server.dto.gitcollection.WorkingFileRevisionResponse;

@RestController
@RequestMapping("/api/v1/teams/{teamId}/git")
@RequiredArgsConstructor
public class WorkingFileController {
	
	private final WorkingFileService service;
	
	@GetMapping(
	        value = "/working-files/{repoKey}",
	        produces = MediaType.APPLICATION_JSON_VALUE)
	public ResponseEntity<ApiResponse<WorkingFileRevisionResponse>> getRevision(
	        @PathVariable Long teamId,
	        @PathVariable String repoKey,
	        @AuthenticationPrincipal AgentPrincipal principal) {

	    WorkingFileRevisionResponse response =
	            service.getRevision(teamId, repoKey, principal);

	    return ResponseEntity.ok()
	            .cacheControl(CacheControl.noStore())
	            .body(ApiResponse.success(response));
	}
	
	@PutMapping(
	        value = {
	                "/working-files/{repoKey}",
	                "/dependency-graph/working-files/{repoKey}"
	        },
	        consumes = MediaType.APPLICATION_JSON_VALUE,
	        produces = MediaType.APPLICATION_JSON_VALUE)
	public ApiResponse<WorkingFileRevisionResponse> replace(
	        @PathVariable Long teamId,
	        @PathVariable String repoKey,
	        @AuthenticationPrincipal AgentPrincipal principal,
	        @RequestBody WorkingFileRequest request,
	        @RequestHeader(
	                value = HttpHeaders.IF_MATCH,
	                required = false) String ifMatch) {

	    WorkingFileRevisionResponse response =
	            service.replace(teamId, repoKey, principal, request, ifMatch);

	    return ApiResponse.success(ifMatch == null ? null : response);
	}
}
