package com.devlink.server.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.devlink.server.domain.gitrepo.CommitFile;
import com.devlink.server.domain.gitrepo.RepositoryCommit;
import com.devlink.server.domain.gitrepo.TeamGitRepository;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.CommitInput;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.FileInput;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse.Conflict;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse.ConflictReason;
import com.devlink.server.repository.CommitFileRepository;
import com.devlink.server.repository.RepositoryCommitRepository;
import com.devlink.server.repository.TeamGitRepositoryRepository;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests use real validation/entities; SQL, dirty checking and rollback need DB integration tests. */
class GitCommitBatchServiceTest {
    private static final Long TEAM_ID = 31L;
    private static final Long REPO_ID = 12L;
    private static final Long OWNER_ID = 2L;
    private static final Long OTHER_OWNER = 99L;
    private static final String REPO_KEY = "a".repeat(64);
    private static final String TIME = "2026-11-09T01:00:00Z";
    private static final String SAME_TIME_OFFSET = "2026-11-09T10:00:00+09:00";
    private static final String DIFFERENT_TIME = "2026-11-09T01:00:01Z";
    private static final Instant FIRST_SEEN = Instant.parse("2026-11-08T00:00:00Z");
    private static final AgentPrincipal ACTOR = new AgentPrincipal(7L, OWNER_ID, TEAM_ID);
    private static ValidatorFactory factory;

    private TeamAuthorizationService teams;
    private TeamGitRepositoryRepository repositories;
    private RepositoryCommitRepository commits;
    private CommitFileRepository commitFiles;
    private GitCommitBatchService service;
    private final List<RepositoryCommit> inserted = new ArrayList<>();
    private final List<CommitFile> writtenFiles = new ArrayList<>();
    private long nextId = 1_000L;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
    }

    @AfterAll
    static void closeValidator() {
        if (factory != null) {
            factory.close();
        }
    }

    @BeforeEach
    void setUp() {
        teams = mock(TeamAuthorizationService.class);
        repositories = mock(TeamGitRepositoryRepository.class);
        commits = mock(RepositoryCommitRepository.class);
        commitFiles = mock(CommitFileRepository.class);
        service = new GitCommitBatchService(new GitCommitBatchValidator(factory.getValidator()),
                teams, repositories, commits, commitFiles);

        TeamGitRepository repository = TeamGitRepository.create(TEAM_ID, REPO_KEY);
        ReflectionTestUtils.setField(repository, "id", REPO_ID);
        when(repositories.findForUpdate(TEAM_ID, REPO_KEY)).thenReturn(Optional.of(repository));
        givenStored(List.of(), List.of());
        // Simulate generated IDs; persistence lifecycle and transactions are outside this unit test.
        when(commits.save(any(RepositoryCommit.class))).thenAnswer(call -> {
            RepositoryCommit entity = call.getArgument(0);
            if (entity.getId() == null) {
                ReflectionTestUtils.setField(entity, "id", nextId++);
                inserted.add(entity);
            }
            return entity;
        });
        when(commitFiles.saveAll(any())).thenAnswer(call -> {
            Iterable<CommitFile> values = call.getArgument(0);
            List<CommitFile> batch = new ArrayList<>();
            values.forEach(batch::add);
            writtenFiles.addAll(batch);
            return batch;
        });
    }

    @ParameterizedTest
    @MethodSource("invalidPrincipals")
    void invalidAgentIdentityCannotAccessStorage(AgentPrincipal principal) {
        assertUploadError(TEAM_ID, principal, request(input(sha(1), OWNER_ID)),
                ErrorCode.AUTHENTICATION_REQUIRED);
        verifyNoInteractions(teams, repositories, commits, commitFiles);
    }

    static Stream<AgentPrincipal> invalidPrincipals() {
        return Stream.of(null,
                new AgentPrincipal(0, OWNER_ID, TEAM_ID),
                new AgentPrincipal(-1, OWNER_ID, TEAM_ID),
                new AgentPrincipal(7, 0, TEAM_ID),
                new AgentPrincipal(7, -1, TEAM_ID),
                new AgentPrincipal(7, OWNER_ID, 0),
                new AgentPrincipal(7, OWNER_ID, -1));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1})
    void invalidTeamIdCannotAccessStorage(Long teamId) {
        assertUploadError(teamId, ACTOR, request(input(sha(1), OWNER_ID)),
                ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(teams, repositories, commits, commitFiles);
    }

    @Test
    void rejectsOtherTeamBeforeLookingUpItsResources() {
        assertUploadError(TEAM_ID + 1, ACTOR, request(input(sha(1), OWNER_ID)),
                ErrorCode.ACCESS_DENIED);
        verifyNoInteractions(teams, repositories, commits, commitFiles);
    }

    @ParameterizedTest
    @EnumSource(value = ErrorCode.class, names = {"ACCESS_DENIED", "RESOURCE_NOT_FOUND"})
    void respectsCurrentMembershipCheckBeforeStartingUpload(ErrorCode error) {
        doThrow(new BusinessException(error)).when(teams).requireTeamMember(OWNER_ID, TEAM_ID);

        assertUploadError(TEAM_ID, ACTOR, request(input(sha(1), OWNER_ID)), error);

        verify(teams).requireTeamMember(OWNER_ID, TEAM_ID);
        verifyNoInteractions(repositories, commits, commitFiles);
    }

    @Test
    void anyForeignAuthorRejectsTheWholeBatchBeforeStorage() {
        var request = request(input(sha(1), OWNER_ID), input(sha(2), OTHER_OWNER));

        assertUploadError(TEAM_ID, ACTOR, request, ErrorCode.ACCESS_DENIED);

        verifyNoInteractions(repositories, commits, commitFiles);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void appliesRealInputValidationBeforeDatabaseAccess(GitCommitBatchRequest request) {
        assertUploadError(TEAM_ID, ACTOR, request, ErrorCode.VALIDATION_FAILED);
        verifyNoInteractions(teams, repositories, commits, commitFiles);
    }

    static Stream<GitCommitBatchRequest> invalidRequests() {
        return Stream.of(null, request(),
                new GitCommitBatchRequest("bad", List.of(input(sha(1), OWNER_ID))),
                request(input(sha(1), OWNER_ID, file("../Auth.java"))));
    }

    @Test
    void preserves413ForMoreThan100RawItems() {
        var request = new GitCommitBatchRequest(REPO_KEY,
                Collections.nCopies(101, input(sha(1), OWNER_ID)));

        assertUploadError(TEAM_ID, ACTOR, request, ErrorCode.PAYLOAD_TOO_LARGE);
        verifyNoInteractions(teams, repositories, commits, commitFiles);
    }

    @Test
    void insertsCommitAndFilesWithVerifiedTeamAndRepository() {
        var response = service.upload(TEAM_ID, ACTOR, request(input(sha(1), OWNER_ID,
                file("src/A.java"), new FileInput("src/B.java", CommitChangeType.ADDED))));

        assertCounts(response, 1, 1, 1, 0, 0, 0);
        assertThat(response.repoId()).isEqualTo(REPO_ID);
        assertThat(response.repoKey()).isEqualTo(REPO_KEY);
        assertThat(inserted).hasSize(1);
        RepositoryCommit saved = inserted.getFirst();
        assertThat(saved.getTeamId()).isEqualTo(TEAM_ID);
        assertThat(saved.getRepoId()).isEqualTo(REPO_ID);
        assertThat(saved.getCommitSha()).isEqualTo(sha(1));
        assertThat(saved.getCommittedAt()).isEqualTo(Instant.parse(TIME));
        assertThat(saved.getAuthorUserId()).isEqualTo(OWNER_ID);
        assertThat(writtenFiles).extracting(CommitFile::getRelativePath, CommitFile::getChangeType)
                .containsExactlyInAnyOrder(tuple("src/A.java", CommitChangeType.MODIFIED),
                        tuple("src/B.java", CommitChangeType.ADDED));
        assertThat(writtenFiles).allSatisfy(file -> assertThat(file.getCommitId()).isEqualTo(saved.getId()));
        verify(teams).requireTeamMember(OWNER_ID, TEAM_ID);
        var order = inOrder(repositories, commits);
        order.verify(repositories).insertIfAbsent(TEAM_ID, REPO_KEY);
        order.verify(repositories).findForUpdate(TEAM_ID, REPO_KEY);
        order.verify(commits).findByTeamIdAndRepoIdAndCommitShaIn(TEAM_ID, REPO_ID, List.of(sha(1)));
    }

    @Test
    void insertsUnidentifiedAuthorWithEmptyFileList() {
        var response = service.upload(TEAM_ID, ACTOR, request(input(sha(1), null)));

        assertCounts(response, 1, 1, 1, 0, 0, 0);
        assertThat(inserted).hasSize(1);
        assertThat(inserted.getFirst().getAuthorUserId()).isNull();
        assertThat(writtenFiles).isEmpty();
        verifyNoInteractions(commitFiles);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mergesSameShaWithDifferentFileOrderOffsetsAndNullableAuthor(boolean knownFirst) {
        var first = input(sha(1), knownFirst ? OWNER_ID : null,
                file("src/A.java"), file("src/B.java"));
        var second = new CommitInput(sha(1), SAME_TIME_OFFSET, knownFirst ? null : OWNER_ID,
                List.of(file("src/B.java"), file("src/A.java")));

        var response = service.upload(TEAM_ID, ACTOR, request(first, second));

        assertCounts(response, 2, 1, 1, 0, 0, 0);
        assertThat(inserted).hasSize(1);
        assertThat(inserted.getFirst().getAuthorUserId()).isEqualTo(OWNER_ID);
        assertThat(inserted.getFirst().getCommittedAt()).isEqualTo(Instant.parse(TIME));
        assertThat(writtenFiles).hasSize(2);
    }

    @Test
    void mergingUnidentifiedAuthorsDoesNotInventAnAuthorOrCountDatabaseDuplicates() {
        var response = service.upload(TEAM_ID, ACTOR,
                request(input(sha(1), null), input(sha(1), null)));

        assertCounts(response, 2, 1, 1, 0, 0, 0);
        assertThat(inserted.getFirst().getAuthorUserId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"time", "path", "changeType"})
    void anyBatchContentMismatchSkipsTheWholeShaAndStillRegistersRepository(String difference) {
        CommitInput original = input(sha(1), OWNER_ID, file("src/A.java"));
        CommitInput changed = changedInput(sha(1), OWNER_ID, difference);

        var response = service.upload(TEAM_ID, ACTOR, request(original, changed, original));

        assertCounts(response, 3, 1, 0, 0, 0, 1);
        assertThat(response.conflicts()).containsExactly(
                new Conflict(sha(1), ConflictReason.BATCH_CONTENT_MISMATCH));
        verify(repositories).insertIfAbsent(TEAM_ID, REPO_KEY);
        verify(repositories).findForUpdate(TEAM_ID, REPO_KEY);
        verifyNoInteractions(commits, commitFiles);
    }

    @Test
    void recognizesStoredContentDespiteDifferentOffsetAndFileOrder() {
        RepositoryCommit stored = stored(10, sha(1), OWNER_ID);
        givenStored(List.of(stored), List.of(
                CommitFile.of(10L, "src/A.java", CommitChangeType.MODIFIED),
                CommitFile.of(10L, "src/B.java", CommitChangeType.ADDED)));
        var input = new CommitInput(sha(1), SAME_TIME_OFFSET, OWNER_ID,
                List.of(new FileInput("src/B.java", CommitChangeType.ADDED), file("src/A.java")));

        var response = service.upload(TEAM_ID, ACTOR, request(input));

        assertCounts(response, 1, 1, 0, 0, 1, 0);
        assertThat(inserted).isEmpty();
        assertThat(writtenFiles).isEmpty();
        assertThat(stored.getFirstSeenAt()).isEqualTo(FIRST_SEEN);
    }

    @ParameterizedTest
    @MethodSource("compatibleAuthors")
    void handlesAuthorEnrichmentWithoutChangingCommitMetadata(
            Long storedAuthor, Long incomingAuthor, int updated, int duplicates) {
        RepositoryCommit stored = stored(10, sha(1), storedAuthor);
        givenStored(List.of(stored), List.of());

        var response = service.upload(TEAM_ID, ACTOR, request(input(sha(1), incomingAuthor)));

        assertCounts(response, 1, 1, 0, updated, duplicates, 0);
        assertThat(stored.getAuthorUserId()).isEqualTo(updated == 1 ? OWNER_ID : storedAuthor);
        assertThat(stored.getCommitSha()).isEqualTo(sha(1));
        assertThat(stored.getCommittedAt()).isEqualTo(Instant.parse(TIME));
        assertThat(stored.getFirstSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(inserted).isEmpty();
        assertThat(writtenFiles).isEmpty();
    }

    static Stream<Arguments> compatibleAuthors() {
        return Stream.of(
                Arguments.of(null, null, 0, 1),
                Arguments.of(OWNER_ID, OWNER_ID, 0, 1),
                Arguments.of(OWNER_ID, null, 0, 1),
                Arguments.of(null, OWNER_ID, 1, 0),
                Arguments.of(OTHER_OWNER, null, 0, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"time", "path", "changeType"})
    void storedBodyMismatchTakesPriorityOverAuthorMismatch(String difference) {
        RepositoryCommit stored = stored(10, sha(1), OTHER_OWNER);
        givenStored(List.of(stored),
                List.of(CommitFile.of(10L, "src/A.java", CommitChangeType.MODIFIED)));

        var response = service.upload(TEAM_ID, ACTOR, request(changedInput(sha(1), OWNER_ID, difference)));

        assertCounts(response, 1, 1, 0, 0, 0, 1);
        assertThat(response.conflicts()).containsExactly(
                new Conflict(sha(1), ConflictReason.COMMIT_CONTENT_MISMATCH));
        assertThat(stored.getAuthorUserId()).isEqualTo(OTHER_OWNER);
        assertThat(stored.getFirstSeenAt()).isEqualTo(FIRST_SEEN);
        assertThat(inserted).isEmpty();
        assertThat(writtenFiles).isEmpty();
    }

    @Test
    void rejectsReassignmentOfKnownAuthorWithoutModifyingStoredCommit() {
        RepositoryCommit stored = stored(10, sha(1), OTHER_OWNER);
        givenStored(List.of(stored), List.of());

        var response = service.upload(TEAM_ID, ACTOR, request(input(sha(1), OWNER_ID)));

        assertCounts(response, 1, 1, 0, 0, 0, 1);
        assertThat(response.conflicts()).containsExactly(
                new Conflict(sha(1), ConflictReason.AUTHOR_MISMATCH));
        assertThat(stored.getAuthorUserId()).isEqualTo(OTHER_OWNER);
        assertThat(inserted).isEmpty();
    }

    @Test
    void aggregatesMixedOutcomesAndSortsConflictsAcrossAllSources() {
        RepositoryCommit upgrade = stored(50, sha(5), null);
        RepositoryCommit duplicate = stored(40, sha(4), OWNER_ID);
        RepositoryCommit authorConflict = stored(30, sha(3), OTHER_OWNER);
        RepositoryCommit bodyConflict = stored(20, sha(2), OWNER_ID);
        givenStored(List.of(upgrade, duplicate, authorConflict, bodyConflict), List.of());
        var changed = new CommitInput(sha(1), DIFFERENT_TIME, OWNER_ID, List.of());
        var request = request(input(sha(6), OWNER_ID), input(sha(5), OWNER_ID),
                input(sha(4), OWNER_ID), input(sha(3), OWNER_ID),
                input(sha(2), OWNER_ID, file("src/new.java")),
                changed, input(sha(1), OWNER_ID));

        var response = service.upload(TEAM_ID, ACTOR, request);

        assertCounts(response, 7, 6, 1, 1, 1, 3);
        assertThat(response.conflicts()).containsExactly(
                new Conflict(sha(1), ConflictReason.BATCH_CONTENT_MISMATCH),
                new Conflict(sha(2), ConflictReason.COMMIT_CONTENT_MISMATCH),
                new Conflict(sha(3), ConflictReason.AUTHOR_MISMATCH));
        assertThat(upgrade.getAuthorUserId()).isEqualTo(OWNER_ID);
        assertThat(authorConflict.getAuthorUserId()).isEqualTo(OTHER_OWNER);
        assertThat(inserted).extracting(RepositoryCommit::getCommitSha).containsExactly(sha(6));
        verify(commits).findByTeamIdAndRepoIdAndCommitShaIn(
                TEAM_ID, REPO_ID, List.of(sha(2), sha(3), sha(4), sha(5), sha(6)));
        verify(commitFiles).findByIdCommitIdIn(anyCollection());
    }

    @Test
    void replayFindsStoredCommitAndDoesNotInsertItAgain() {
        var request = request(input(sha(1), OWNER_ID, file("src/A.java")));
        var first = service.upload(TEAM_ID, ACTOR, request);
        givenStored(List.copyOf(inserted), List.copyOf(writtenFiles));
        when(repositories.insertIfAbsent(TEAM_ID, REPO_KEY)).thenReturn(0);

        var replay = service.upload(TEAM_ID, ACTOR, request);

        assertCounts(first, 1, 1, 1, 0, 0, 0);
        assertCounts(replay, 1, 1, 0, 0, 1, 0);
        assertThat(inserted).hasSize(1);
        assertThat(writtenFiles).hasSize(1);
    }

    @Test
    void propagatesUnexpectedFileStorageFailureInsteadOfReturningSuccess() {
        var failure = new DataAccessResourceFailureException("test storage failure");
        doThrow(failure).when(commitFiles).saveAll(any());

        var thrown = assertThrows(DataAccessResourceFailureException.class,
                () -> service.upload(TEAM_ID, ACTOR, request(input(sha(1), OWNER_ID, file("src/A.java")))));

        assertThat(thrown).isSameAs(failure);
    }

    private void givenStored(List<RepositoryCommit> stored, List<CommitFile> files) {
        when(commits.findByTeamIdAndRepoIdAndCommitShaIn(eq(TEAM_ID), eq(REPO_ID), anyCollection()))
                .thenReturn(stored);
        when(commitFiles.findByIdCommitIdIn(anyCollection())).thenReturn(files);
    }

    private void assertUploadError(
            Long teamId, AgentPrincipal principal, GitCommitBatchRequest request, ErrorCode error) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(teamId, principal, request));
        assertThat(exception.getErrorCode()).isEqualTo(error);
    }

    private static void assertCounts(GitCommitBatchResponse response,
            int received, int unique, int inserted, int updated, int duplicates, int conflicts) {
        assertThat(response.receivedCount()).isEqualTo(received);
        assertThat(response.uniqueCount()).isEqualTo(unique);
        assertThat(response.insertedCount()).isEqualTo(inserted);
        assertThat(response.updatedCount()).isEqualTo(updated);
        assertThat(response.duplicateCount()).isEqualTo(duplicates);
        assertThat(response.conflictCount()).isEqualTo(conflicts);
        assertThat(response.conflicts()).hasSize(conflicts);
        assertThat(response.uniqueCount()).isEqualTo(response.insertedCount() + response.updatedCount()
                + response.duplicateCount() + response.conflictCount());
    }

    private static RepositoryCommit stored(long id, String sha, Long author) {
        RepositoryCommit entity = RepositoryCommit.of(TEAM_ID, REPO_ID, sha, Instant.parse(TIME), author);
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "firstSeenAt", FIRST_SEEN);
        return entity;
    }

    private static CommitInput changedInput(String sha, Long author, String difference) {
        return switch (difference) {
            case "time" -> new CommitInput(sha, DIFFERENT_TIME, author, List.of(file("src/A.java")));
            case "path" -> input(sha, author, file("src/B.java"));
            case "changeType" -> input(sha, author, new FileInput("src/A.java", CommitChangeType.ADDED));
            default -> throw new IllegalArgumentException(difference);
        };
    }

    private static GitCommitBatchRequest request(CommitInput... inputs) {
        return new GitCommitBatchRequest(REPO_KEY, List.of(inputs));
    }

    private static CommitInput input(String sha, Long author, FileInput... files) {
        return new CommitInput(sha, TIME, author, List.of(files));
    }

    private static FileInput file(String path) {
        return new FileInput(path, CommitChangeType.MODIFIED);
    }

    private static String sha(int digit) {
        return String.valueOf(digit).repeat(40);
    }
}
