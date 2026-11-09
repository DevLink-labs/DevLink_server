package com.devlink.server;

import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.devlink.server.domain.gitrepo.CommitFile;
import com.devlink.server.domain.gitrepo.RepositoryCommit;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.CommitInput;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.FileInput;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse.Conflict;
import com.devlink.server.dto.gitcollection.GitCommitBatchResponse.ConflictReason;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.CommitFileRepository;
import com.devlink.server.repository.RepositoryCommitRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.service.GitCommitBatchService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** No test-level transaction: inspect real service commits, rollback and concurrent PostgreSQL writes. */
@TestPropertySource(properties = "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '10s'")
class GitCommitBatchIntegrationTest extends AbstractIntegrationTest {
    private static final String REPO_KEY = "a".repeat(64);
    private static final String TIME = "2026-11-09T01:00:00.123456Z";
    private static final String SAME_TIME_OFFSET = "2026-11-09T10:00:00.123456+09:00";
    private static final String DIFFERENT_TIME = "2026-11-09T01:00:01.123456Z";

    @Autowired private GitCommitBatchService service;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private RepositoryCommitRepository commits;
    @MockitoSpyBean private CommitFileRepository files;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;

    private Long teamId;
    private AgentPrincipal owner;
    private AgentPrincipal secondOwner;
    private AgentPrincipal other;

    @BeforeEach
    void prepareCommittedFixture() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            String unique = UUID.randomUUID().toString();
            User first = users.save(User.create(unique + "@batch.test", "업로드 사용자", null, "test-hash"));
            User second = users.save(User.create(unique + "@other.test", "다른 사용자", null, "test-hash"));
            Team team = teams.save(Team.create("커밋 업로드 테스트", unique));
            teamId = team.getId();
            members.save(TeamMember.create(teamId, first.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(teamId, second.getId(), MemberRole.MEMBER));
            Agent firstAgent = agents.save(Agent.create(teamId, first.getId(),
                    "첫 Agent", null, ToolType.CODEX, "batch-test"));
            Agent secondAgent = agents.save(Agent.create(teamId, first.getId(),
                    "두 번째 Agent", null, ToolType.CODEX, "batch-test"));
            Agent otherAgent = agents.save(Agent.create(teamId, second.getId(),
                    "다른 사용자 Agent", null, ToolType.CODEX, "batch-test"));
            owner = new AgentPrincipal(firstAgent.getId(), first.getId(), teamId);
            secondOwner = new AgentPrincipal(secondAgent.getId(), first.getId(), teamId);
            other = new AgentPrincipal(otherAgent.getId(), second.getId(), teamId);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {40, 64})
    void commitsRepositoryCommitAndFilesToPostgres(int shaLength) {
        String sha = "f".repeat(shaLength);
        var input = new CommitInput(sha, SAME_TIME_OFFSET, owner.ownerUserId(),
                List.of(file("src/한글.java"), new FileInput("README.md", CommitChangeType.ADDED)));

        var response = service.upload(teamId, owner, request(input));

        assertCounts(response, 1, 1, 1, 0, 0, 0);
        assertStoredCounts(1, 1, 2);
        RepositoryCommit saved = stored(response.repoId(), sha);
        assertThat(saved.getCommittedAt()).isEqualTo(Instant.parse(TIME));
        assertThat(saved.getAuthorUserId()).isEqualTo(owner.ownerUserId());
        assertThat(saved.getFirstSeenAt()).isNotNull();
        assertThat(fileMap(saved.getId())).containsExactlyInAnyOrderEntriesOf(
                Map.of("src/한글.java", CommitChangeType.MODIFIED, "README.md", CommitChangeType.ADDED));
        assertThat(jdbc.queryForObject(
                "select origin_url_sha256 from team_repositories where id = ?", String.class, response.repoId()))
                .isEqualTo(REPO_KEY);
    }

    @Test
    void replayKeepsStoredIdsAndFirstSeenTime() {
        var first = service.upload(teamId, owner, request(input(1, owner.ownerUserId(),
                file("src/A.java"), file("src/B.java"))));
        RepositoryCommit before = stored(first.repoId(), sha(1));
        var reordered = new CommitInput(sha(1), SAME_TIME_OFFSET, owner.ownerUserId(),
                List.of(file("src/B.java"), file("src/A.java")));

        var replay = service.upload(teamId, secondOwner, request(reordered));

        assertCounts(replay, 1, 1, 0, 0, 1, 0);
        assertThat(replay.repoId()).isEqualTo(first.repoId());
        RepositoryCommit after = stored(first.repoId(), sha(1));
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getFirstSeenAt()).isEqualTo(before.getFirstSeenAt());
        assertThat(after.getCommittedAt()).isEqualTo(before.getCommittedAt());
        assertStoredCounts(1, 1, 2);
    }

    @Test
    void dirtyCheckingPersistsAuthorEnrichmentAndKeepsOtherMetadata() {
        var first = service.upload(teamId, owner, request(input(1, null, file("src/A.java"))));
        RepositoryCommit before = stored(first.repoId(), sha(1));
        Map<String, CommitChangeType> beforeFiles = fileMap(before.getId());

        var enriched = service.upload(teamId, secondOwner,
                request(input(1, owner.ownerUserId(), file("src/A.java"))));

        assertCounts(enriched, 1, 1, 0, 1, 0, 0);
        RepositoryCommit after = stored(first.repoId(), sha(1));
        assertThat(after.getAuthorUserId()).isEqualTo(owner.ownerUserId());
        assertThat(after.getId()).isEqualTo(before.getId());
        assertThat(after.getCommitSha()).isEqualTo(before.getCommitSha());
        assertThat(after.getCommittedAt()).isEqualTo(before.getCommittedAt());
        assertThat(after.getFirstSeenAt()).isEqualTo(before.getFirstSeenAt());
        assertThat(fileMap(after.getId())).isEqualTo(beforeFiles);
        assertStoredCounts(1, 1, 1);
    }

    @Test
    void batchConflictsStillRegisterRepositoryWithoutSavingAnyCommit() {
        var changed = new CommitInput(sha(1), DIFFERENT_TIME, owner.ownerUserId(), List.of());

        var response = service.upload(teamId, owner, request(input(1, null), changed, input(1, null)));

        assertCounts(response, 3, 1, 0, 0, 0, 1);
        assertThat(response.conflicts()).containsExactly(
                new Conflict(sha(1), ConflictReason.BATCH_CONTENT_MISMATCH));
        assertStoredCounts(1, 0, 0);
    }

    @Test
    void logicalConflictsDoNotRollBackOtherValidCommits() {
        service.upload(teamId, other, request(input(2, other.ownerUserId())));
        var seeded = service.upload(teamId, owner, request(input(4, owner.ownerUserId())));
        RepositoryCommit previousAuthor = stored(seeded.repoId(), sha(2));
        RepositoryCommit previousBody = stored(seeded.repoId(), sha(4));
        var changed = new CommitInput(sha(3), DIFFERENT_TIME, owner.ownerUserId(), List.of());

        var response = service.upload(teamId, owner, request(
                input(4, owner.ownerUserId(), file("src/changed.java")),
                changed, input(3, owner.ownerUserId()),
                input(2, owner.ownerUserId()), input(1, owner.ownerUserId(), file("src/new.java"))));

        assertCounts(response, 5, 4, 1, 0, 0, 3);
        assertThat(response.conflicts()).containsExactly(
                new Conflict(sha(2), ConflictReason.AUTHOR_MISMATCH),
                new Conflict(sha(3), ConflictReason.BATCH_CONTENT_MISMATCH),
                new Conflict(sha(4), ConflictReason.COMMIT_CONTENT_MISMATCH));
        assertThat(stored(response.repoId(), sha(2)).getAuthorUserId()).isEqualTo(other.ownerUserId());
        assertThat(stored(response.repoId(), sha(2)).getFirstSeenAt()).isEqualTo(previousAuthor.getFirstSeenAt());
        assertThat(stored(response.repoId(), sha(4)).getFirstSeenAt()).isEqualTo(previousBody.getFirstSeenAt());
        assertThat(fileMap(previousBody.getId())).isEmpty();
        assertStoredCounts(1, 3, 1);
    }

    @Test
    void identicalShaIsIndependentAcrossTeamsAndRepositories() {
        var first = service.upload(teamId, owner, request(input(1, owner.ownerUserId(), file("src/A.java"))));
        var second = service.upload(teamId, owner, requestFor("b".repeat(64),
                input(1, owner.ownerUserId(), file("src/B.java"))));
        AgentPrincipal anotherTeam = new TransactionTemplate(transactionManager).execute(tx -> {
            Team team = teams.save(Team.create("다른 팀", UUID.randomUUID().toString()));
            members.save(TeamMember.create(team.getId(), owner.ownerUserId(), MemberRole.OWNER));
            Agent agent = agents.save(Agent.create(team.getId(), owner.ownerUserId(),
                    "다른 팀 Agent", null, ToolType.CODEX, "batch-test"));
            return new AgentPrincipal(agent.getId(), owner.ownerUserId(), team.getId());
        });

        var third = service.upload(anotherTeam.teamId(), anotherTeam,
                request(input(1, owner.ownerUserId(), file("src/C.java"))));

        assertCounts(first, 1, 1, 1, 0, 0, 0);
        assertCounts(second, 1, 1, 1, 0, 0, 0);
        assertCounts(third, 1, 1, 1, 0, 0, 0);
        assertThat(List.of(first.repoId(), second.repoId(), third.repoId())).doesNotHaveDuplicates();
        assertStoredCounts(2, 2, 2);
        assertThat(jdbc.queryForObject("select count(*) from repository_commits where team_id = ?",
                Long.class, anotherTeam.teamId())).isEqualTo(1);
    }

    @Test
    void unauthorizedAuthorCannotCreateRepositoryOrPartialBatch() {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(teamId, owner, request(
                        input(1, owner.ownerUserId()), input(2, other.ownerUserId()))));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertStoredCounts(0, 0, 0);
    }

    @Test
    void rechecksActualMembershipBeforeWriting() {
        jdbc.update("delete from team_members where team_id = ? and user_id = ?", teamId, owner.ownerUserId());

        BusinessException exception = assertThrows(BusinessException.class,
                () -> service.upload(teamId, owner, request(input(1, owner.ownerUserId()))));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.ACCESS_DENIED);
        assertStoredCounts(0, 0, 0);
    }

    @Test
    void constraintFailureRollsBackNewRepositoryCommitAndAlreadyFlushedFile() {
        AtomicBoolean partialWriteObserved = new AtomicBoolean();
        failAfterFirstFile(() -> {
            assertStoredCounts(1, 1, 1);
            partialWriteObserved.set(true);
        });

        assertSqlConstraintFailure(() -> service.upload(teamId, owner,
                request(input(1, owner.ownerUserId(), file("src/first.java"), file("src/second.java")))));

        assertThat(partialWriteObserved).isTrue();
        assertStoredCounts(0, 0, 0);
    }

    @Test
    void constraintFailureAlsoRollsBackAlreadyFlushedAuthorEnrichment() {
        var initial = service.upload(teamId, owner, request(input(1, null, file("src/original.java"))));
        RepositoryCommit before = stored(initial.repoId(), sha(1));
        Map<String, CommitChangeType> originalFiles = fileMap(before.getId());
        AtomicBoolean authorUpdateObserved = new AtomicBoolean();
        failAfterFirstFile(() -> {
            assertStoredCounts(1, 2, 2);
            assertThat(jdbc.queryForObject("select author_user_id from repository_commits where id = ?",
                    Long.class, before.getId())).isEqualTo(owner.ownerUserId());
            authorUpdateObserved.set(true);
        });

        assertSqlConstraintFailure(() -> service.upload(teamId, owner, request(
                input(1, owner.ownerUserId(), file("src/original.java")),
                input(2, owner.ownerUserId(), file("src/first.java"), file("src/second.java")))));

        assertThat(authorUpdateObserved).isTrue();
        assertStoredCounts(1, 1, 1);
        RepositoryCommit after = stored(initial.repoId(), sha(1));
        assertThat(after.getAuthorUserId()).isNull();
        assertThat(after.getFirstSeenAt()).isEqualTo(before.getFirstSeenAt());
        assertThat(after.getCommittedAt()).isEqualTo(before.getCommittedAt());
        assertThat(fileMap(after.getId())).isEqualTo(originalFiles);
    }

    @Test
    void concurrentFirstUploadsCreateOneRepositoryAndOneCommit() throws Exception {
        var input = request(input(1, owner.ownerUserId(), file("src/A.java"), file("src/B.java")));

        List<GitCommitBatchResponse> responses = concurrently(List.of(input, input, input, input));

        assertThat(responses).allSatisfy(response -> {
            assertThat(response.receivedCount()).isEqualTo(1);
            assertThat(response.uniqueCount()).isEqualTo(1);
            assertThat(response.updatedCount()).isZero();
            assertThat(response.conflictCount()).isZero();
        });
        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::insertedCount).sum()).isEqualTo(1);
        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::duplicateCount).sum()).isEqualTo(3);
        assertThat(responses.stream().map(GitCommitBatchResponse::repoId).distinct().count()).isEqualTo(1);
        assertStoredCounts(1, 1, 2);
    }

    @Test
    void concurrentAuthorEnrichmentIsCountedOnlyOnce() throws Exception {
        var initial = service.upload(teamId, owner, request(input(1, null)));
        RepositoryCommit before = stored(initial.repoId(), sha(1));
        var input = request(input(1, owner.ownerUserId()));

        var responses = concurrently(List.of(input, input));

        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::insertedCount).sum()).isZero();
        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::updatedCount).sum()).isEqualTo(1);
        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::duplicateCount).sum()).isEqualTo(1);
        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::conflictCount).sum()).isZero();
        RepositoryCommit after = stored(initial.repoId(), sha(1));
        assertThat(after.getAuthorUserId()).isEqualTo(owner.ownerUserId());
        assertThat(after.getFirstSeenAt()).isEqualTo(before.getFirstSeenAt());
        assertStoredCounts(1, 1, 0);
    }

    @Test
    void concurrentDifferentBodiesProduceOneInsertAndOneContentConflict() throws Exception {
        var responses = concurrently(List.of(
                request(input(1, owner.ownerUserId(), file("src/A.java"))),
                request(input(1, owner.ownerUserId(), file("src/B.java")))));

        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::insertedCount).sum()).isEqualTo(1);
        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::conflictCount).sum()).isEqualTo(1);
        assertThat(responses.stream().mapToInt(GitCommitBatchResponse::duplicateCount).sum()).isZero();
        assertThat(responses.stream().flatMap(response -> response.conflicts().stream()).toList())
                .containsExactly(new Conflict(sha(1), ConflictReason.COMMIT_CONTENT_MISMATCH));
        String winnerPath = responses.getFirst().insertedCount() == 1 ? "src/A.java" : "src/B.java";
        RepositoryCommit saved = stored(responses.getFirst().repoId(), sha(1));
        assertThat(fileMap(saved.getId())).containsExactlyInAnyOrderEntriesOf(
                Map.of(winnerPath, CommitChangeType.MODIFIED));
        assertStoredCounts(1, 1, 1);
    }

    private List<GitCommitBatchResponse> concurrently(List<GitCommitBatchRequest> requests) throws Exception {
        CountDownLatch ready = new CountDownLatch(requests.size());
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(requests.size())) {
            var futures = IntStream.range(0, requests.size()).mapToObj(index -> executor.submit(() -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent upload");
                }
                return service.upload(teamId, index % 2 == 0 ? owner : secondOwner, requests.get(index));
            })).toList();
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            List<GitCommitBatchResponse> responses = new ArrayList<>();
            for (var future : futures) {
                responses.add(future.get(30, TimeUnit.SECONDS));
            }
            return responses;
        }
    }

    private void failAfterFirstFile(Runnable verifyFlushedState) {
        doAnswer(call -> {
            Iterable<CommitFile> values = call.getArgument(0);
            List<CommitFile> candidates = new ArrayList<>();
            values.forEach(candidates::add);
            assertThat(candidates).hasSize(2);
            em.persist(candidates.getFirst());
            em.flush();
            verifyFlushedState.run();
            // A real PostgreSQL NOT NULL violation after partial data has reached the DB.
            em.createNativeQuery("""
                    insert into commit_files (commit_id, relative_path, change_type)
                    values (:commitId, NULL, 'MODIFIED')
                    """).setParameter("commitId", candidates.getFirst().getCommitId()).executeUpdate();
            throw new AssertionError("Expected PostgreSQL to reject a null file path");
        }).when(files).saveAll(any());
    }

    private void assertSqlConstraintFailure(Executable action) {
        RuntimeException exception = assertThrows(RuntimeException.class, action);
        String sqlState = null;
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql) {
                sqlState = sql.getSQLState();
                break;
            }
        }
        assertThat(sqlState).isEqualTo("23502");
    }

    private RepositoryCommit stored(Long repoId, String sha) {
        return commits.findByTeamIdAndRepoIdAndCommitShaIn(teamId, repoId, List.of(sha)).getFirst();
    }

    private Map<String, CommitChangeType> fileMap(Long commitId) {
        Map<String, CommitChangeType> result = new TreeMap<>();
        for (CommitFile file : files.findByIdCommitIdIn(List.of(commitId))) {
            result.put(file.getRelativePath(), file.getChangeType());
        }
        return result;
    }

    private void assertStoredCounts(int expectedRepos, int expectedCommits, int expectedFiles) {
        assertThat(jdbc.queryForObject("select count(*) from team_repositories where team_id = ?",
                Long.class, teamId)).isEqualTo(expectedRepos);
        assertThat(jdbc.queryForObject("select count(*) from repository_commits where team_id = ?",
                Long.class, teamId)).isEqualTo(expectedCommits);
        assertThat(jdbc.queryForObject("""
                select count(*) from commit_files f
                join repository_commits c on c.id = f.commit_id
                where c.team_id = ?
                """, Long.class, teamId)).isEqualTo(expectedFiles);
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
        assertThat(unique).isEqualTo(inserted + updated + duplicates + conflicts);
    }

    private static GitCommitBatchRequest request(CommitInput... inputs) {
        return requestFor(REPO_KEY, inputs);
    }

    private static GitCommitBatchRequest requestFor(String repoKey, CommitInput... inputs) {
        return new GitCommitBatchRequest(repoKey, List.of(inputs));
    }

    private static CommitInput input(int shaNumber, Long author, FileInput... files) {
        return new CommitInput(sha(shaNumber), TIME, author, List.of(files));
    }

    private static FileInput file(String path) {
        return new FileInput(path, CommitChangeType.MODIFIED);
    }

    private static String sha(int value) {
        return "%040x".formatted(value);
    }
}
