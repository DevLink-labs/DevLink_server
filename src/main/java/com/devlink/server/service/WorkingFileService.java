package com.devlink.server.service;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import com.devlink.server.dto.gitcollection.WorkingFileRequest;
import com.devlink.server.repository.TeamGitRepositoryRepository;
import com.devlink.server.repository.WorkingFileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import java.util.UUID;
import com.devlink.server.domain.gitrepo.WorkingFileId;
import com.devlink.server.dto.gitcollection.WorkingFileRevisionResponse;
import com.devlink.server.repository.WorkingFileRevisionRepository;

@Service
@RequiredArgsConstructor
public class WorkingFileService {
	private static final long EXPIRY_SECONDS = 90;
	
	private final WorkingFileValidator validator;
	private final TeamAuthorizationService teams;
	private final TeamGitRepositoryRepository repositories;
	private final WorkingFileRepository workingFiles;
	private final ObjectMapper objectMapper;
	private final WorkingFileRevisionRepository revisions;
	
	@Transactional
	public WorkingFileRevisionResponse getRevision(
			Long teamId,
			String repoKey,
			AgentPrincipal principal) {
		
		validateAccess(teamId, principal);
		validator.validateRepoKey(repoKey);
		teams.requireTeamMember(principal.ownerUserId(), teamId);
		
		repositories.insertIfAbsent(teamId, repoKey);
		
		var repository = repositories
				.findByTeamIdAndOriginUrlSha256(teamId, repoKey)
				.orElseThrow(() ->
					new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
		
		Long repoId = repository.getId();
		Long userId = principal.ownerUserId();
		
		revisions.insertIfAbsent(repoId, userId, UUID.randomUUID());
		
		var current = revisions
				.findById(new WorkingFileId(repoId, userId))
				.orElseThrow(() ->
				new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
		
		return new WorkingFileRevisionResponse(
				2,
				current.getRevision());
	}
	
	@Transactional
	public void replace(
			Long teamId,
			String repoKey,
			AgentPrincipal principal,
			WorkingFileRequest request) {
		
		validateAccess(teamId, principal);
		validator.validate(repoKey, request);
		teams.requireTeamMember(principal.ownerUserId(), teamId);
		
		List<WorkingFileEntry> files = request.files().stream()
				.map(file -> new WorkingFileEntry(
						file.relativePath(),
						file.changeType()))
				.toList();
		
		String filesJson = serializeFiles(files);
		
		repositories.insertIfAbsent(teamId, repoKey);
		
		var repository = repositories
				.findByTeamIdAndOriginUrlSha256(teamId, repoKey)
				.orElseThrow(() ->
				new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
		
		Instant observedAt = Instant.now();
		Instant expiresAt = observedAt.plusSeconds(EXPIRY_SECONDS);
		
		workingFiles.upsert(
				repository.getId(),
				principal.ownerUserId(),
				request.branch(),
				filesJson,
				observedAt,
				expiresAt);
	}
	
	private void validateAccess(Long teamId, AgentPrincipal principal) {
		if(principal == null
				|| principal.agentId() <= 0
				|| principal.ownerUserId() <= 0
				|| principal.teamId() <= 0) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
		if(teamId == null || teamId <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if(principal.teamId() != teamId.longValue()) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		
	}
	
	private String serializeFiles(List<WorkingFileEntry> files) {
		try {
			return objectMapper.writeValueAsString(files);
		}catch(JsonProcessingException exception) {
			throw new IllegalStateException("작업 파일 목록을 JSON으로 변환하지 못했습니다.",exception);
		}
	}
}
