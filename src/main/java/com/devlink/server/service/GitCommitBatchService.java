package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.devlink.server.domain.gitrepo.TeamGitRepository;
import com.devlink.server.repository.TeamGitRepositoryRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.CommitInput;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.FileInput;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse.Conflict;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse.ConflictReason;
import java.util.Collection;
import com.devlink.server.domain.gitrepo.RepositoryCommit;
import com.devlink.server.domain.gitrepo.CommitFile;
import com.devlink.server.repository.RepositoryCommitRepository;
import com.devlink.server.repository.CommitFileRepository;
import java.util.Comparator;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse;

@Service
public class GitCommitBatchService {
	
	private final RepositoryCommitRepository commits;
	private final CommitFileRepository commitFiles;
	private final GitCommitBatchValidator validator;
	private final TeamAuthorizationService teams;
	private final TeamGitRepositoryRepository repositories;
	
	private record PreparedBatch(int uniqueCount,
			List<CommitInput> commits,
			List<Conflict> conflicts) {
		
	}
	
	private PreparedBatch prepareBatch(GitCommitBatchRequest request) {
		Map<String, List<CommitInput>> grouped = groupBySha(request);
		
		List<CommitInput> prepared = new ArrayList<>();
		List<Conflict> conflicts = new ArrayList<>();
		
		for(Map.Entry<String, List<CommitInput>> entry : grouped.entrySet()) {
			List<CommitInput> group = entry.getValue();
			CommitInput first = group.getFirst();
			
			boolean mismatch = group.stream()
					.anyMatch(candidate -> !sameContent(first,candidate));
			
			if(mismatch) {
				conflicts.add(new Conflict(
						entry.getKey(),
						ConflictReason.BATCH_CONTENT_MISMATCH));
				continue;
			}
			
			Long authorUserId = group.stream()
					.map(CommitInput::authorUserId)
					.filter(id -> id != null)
					.findFirst()
					.orElse(null);
			
			prepared.add(new CommitInput(
					first.commitSha(),
					first.committedAt(),
					authorUserId,
					List.copyOf(first.files())));
		}
		return new PreparedBatch(
				grouped.size(),
				List.copyOf(prepared),
				List.copyOf(conflicts));
	}
	

	
	public GitCommitBatchService(
			GitCommitBatchValidator validator,
			TeamAuthorizationService teams,
			TeamGitRepositoryRepository repositories,
			RepositoryCommitRepository commits,
			CommitFileRepository commitFiles) {
		this.validator = validator;
		this.teams = teams;
		this.repositories = repositories;
		this.commits = commits;
		this.commitFiles = commitFiles;
	}
	
	private boolean matchesExisting(
	        RepositoryCommit existing,
	        Map<String, CommitChangeType> existingFiles,
	        CommitInput input) {

	    boolean sameTime = existing.getCommittedAt()
	            .equals(
	                    OffsetDateTime.parse(input.committedAt()).toInstant()
	            );

	    return sameTime
	            && existingFiles.equals(toFileMap(input.files()));
	}
	
	@Transactional
	public GitCommitBatchResponse upload(
	        Long teamId,
	        AgentPrincipal principal,
	        GitCommitBatchRequest request) {

	    validateRequest(teamId, principal, request);

	    PreparedBatch batch = prepareBatch(request);

	    TeamGitRepository repository =
	            findOrCreateRepository(teamId, request.repoKey());

	    Map<String, RepositoryCommit> existingBySha =
	            findExistingCommits(
	                    teamId,
	                    repository.getId(),
	                    batch.commits());

	    Map<Long, Map<String, CommitChangeType>> existingFiles =
	            findExistingFiles(existingBySha.values());

	    List<Conflict> conflicts = new ArrayList<>(batch.conflicts());

	    int insertedCount = 0;
	    int updatedCount = 0;
	    int duplicateCount = 0;

	    for (CommitInput input : batch.commits()) {
	        RepositoryCommit existing =
	                existingBySha.get(input.commitSha());

	        if (existing == null) {
	            saveNewCommit(teamId, repository.getId(), input);
	            insertedCount++;
	            continue;
	        }

	        if (!matchesExisting(
	                existing,
	                existingFiles.get(existing.getId()),
	                input)) {

	            conflicts.add(new Conflict(
	                    input.commitSha(),
	                    ConflictReason.COMMIT_CONTENT_MISMATCH));
	            continue;
	        }

	        Long storedAuthor = existing.getAuthorUserId();
	        Long incomingAuthor = input.authorUserId();

	        if (storedAuthor != null
	                && incomingAuthor != null
	                && !storedAuthor.equals(incomingAuthor)) {

	            conflicts.add(new Conflict(
	                    input.commitSha(),
	                    ConflictReason.AUTHOR_MISMATCH));
	            continue;
	        }

	        if (storedAuthor == null && incomingAuthor != null) {
	            existing.fillAuthor(incomingAuthor);
	            updatedCount++;
	        } else {
	            duplicateCount++;
	        }
	    }

	    conflicts.sort(Comparator.comparing(Conflict::commitSha));

	    return new GitCommitBatchResponse(
	            repository.getId(),
	            request.repoKey(),
	            request.commits().size(),
	            batch.uniqueCount(),
	            insertedCount,
	            updatedCount,
	            duplicateCount,
	            conflicts.size(),
	            List.copyOf(conflicts));
	}
	
	private void saveNewCommit(
	        Long teamId,
	        Long repoId,
	        CommitInput input) {

	    RepositoryCommit saved = commits.save(
	            RepositoryCommit.of(
	                    teamId,
	                    repoId,
	                    input.commitSha(),
	                    OffsetDateTime.parse(input.committedAt()).toInstant(),
	                    input.authorUserId()));

	    List<CommitFile> files = input.files().stream()
	            .map(file -> CommitFile.of(
	                    saved.getId(),
	                    file.relativePath(),
	                    file.changeType()))
	            .toList();

	    if (!files.isEmpty()) {
	        commitFiles.saveAll(files);
	    }
	}
	
	private Map<Long, Map<String, CommitChangeType>> findExistingFiles(
	        Collection<RepositoryCommit> existing) {

	    if (existing.isEmpty()) {
	        return Map.of();
	    }

	    List<Long> commitIds = existing.stream()
	            .map(RepositoryCommit::getId)
	            .toList();

	    Map<Long, Map<String, CommitChangeType>> result = new TreeMap<>();

	    for (RepositoryCommit commit : existing) {
	        result.put(commit.getId(), new TreeMap<>());
	    }

	    for (CommitFile file : commitFiles.findByIdCommitIdIn(commitIds)) {
	        result.get(file.getCommitId())
	                .put(file.getRelativePath(), file.getChangeType());
	    }

	    return result;
	}
	
	private Map<String, RepositoryCommit> findExistingCommits(
	        Long teamId,
	        Long repoId,
	        List<CommitInput> inputs) {

	    if (inputs.isEmpty()) {
	        return Map.of();
	    }

	    List<String> shas = inputs.stream()
	            .map(CommitInput::commitSha)
	            .toList();

	    List<RepositoryCommit> stored =
	            commits.findByTeamIdAndRepoIdAndCommitShaIn(
	                    teamId, repoId, shas);

	    Map<String, RepositoryCommit> result = new TreeMap<>();

	    for (RepositoryCommit commit : stored) {
	        result.put(commit.getCommitSha(), commit);
	    }

	    return result;
	}
	
	private Map<String, List<CommitInput>> groupBySha(GitCommitBatchRequest request){
		
		Map<String, List<CommitInput>> grouped = new TreeMap<>();
		
		for(CommitInput commit: request.commits()) {
			grouped.computeIfAbsent(commit.commitSha(), key -> new ArrayList<>()).add(commit);
		}
		return grouped;
	}
	
	private Map<String, CommitChangeType> toFileMap(
	        List<FileInput> files) {

	    Map<String, CommitChangeType> result = new TreeMap<>();

	    for (FileInput file : files) {
	        result.put(file.relativePath(), file.changeType());
	    }

	    return result;
	}
	
	private boolean sameContent(CommitInput left, CommitInput right) {
	    boolean sameTime = OffsetDateTime.parse(left.committedAt())
	            .toInstant()
	            .equals(
	                    OffsetDateTime.parse(right.committedAt()).toInstant()
	            );

	    return sameTime
	            && toFileMap(left.files()).equals(toFileMap(right.files()));
	}
	
	private TeamGitRepository findOrCreateRepository(
			Long teamId,
			String repoKey) {
		repositories.insertIfAbsent(teamId,repoKey);
		
		return repositories.findForUpdate(teamId, repoKey)
				.orElseThrow(() ->
				new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
	}
	
	@Transactional(readOnly = true)
	public void validateRequest(
			Long teamId,
			AgentPrincipal principal,
			GitCommitBatchRequest request) {
		
		if(principal == null
				|| principal.agentId() <=0
				|| principal.ownerUserId() <= 0
				|| principal.teamId() <= 0) {
			throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
		}
		if(teamId == null || teamId <= 0) {
			throw new BusinessException(ErrorCode.VALIDATION_FAILED);
		}
		if(teamId.longValue() != principal.teamId()) {
			throw new BusinessException(ErrorCode.ACCESS_DENIED);
		}
		validator.validate(request);
		
		teams.requireTeamMember(principal.ownerUserId(), teamId);
		
		for(GitCommitBatchRequest.CommitInput commit : request.commits()) {
			Long authorUserId = commit.authorUserId();
			
			if(authorUserId != null && authorUserId.longValue() != principal.ownerUserId()) {
				throw new BusinessException(ErrorCode.ACCESS_DENIED);
			}
		}
	}
}
