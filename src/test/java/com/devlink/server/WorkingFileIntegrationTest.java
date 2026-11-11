package com.devlink.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.gitrepo.WorkingFile;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import com.devlink.server.domain.gitrepo.WorkingFileId;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.gitcollection.WorkingFileRequest;
import com.devlink.server.dto.gitcollection.WorkingFileRequest.FileInput;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TeamGitRepositoryRepository;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import com.devlink.server.repository.WorkingFileRepository;
import com.devlink.server.service.WorkingFileService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses real service transactions and PostgreSQL; no enclosing test transaction masks commits or rollback. */
@TestPropertySource(properties = "spring.datasource.hikari.connection-init-sql=SET lock_timeout TO '10s'")
class WorkingFileIntegrationTest extends AbstractIntegrationTest {
    private static final String REPO_KEY = "a".repeat(64);
    @Autowired private WorkingFileService service;
    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private TeamMemberRepository members;
    @Autowired private AgentRepository agents;
    @Autowired private TeamGitRepositoryRepository repositories;
    @Autowired private WorkingFileRepository workingFiles;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    private Long teamId;
    private AgentPrincipal owner;
    private AgentPrincipal sameOwner;
    private AgentPrincipal teammate;

    @BeforeEach
    void prepareCommittedFixtures() {
        tx().executeWithoutResult(status -> {
            String unique = UUID.randomUUID().toString();
            User first = users.save(User.create(unique + "@working.test", "작업 사용자", null, "test-hash"));
            User second = users.save(User.create(unique + "@teammate.test", "다른 사용자", null, "test-hash"));
            Team team = teams.save(Team.create("작업 파일 검증", unique));
            teamId = team.getId();
            members.save(TeamMember.create(teamId, first.getId(), MemberRole.OWNER));
            members.save(TeamMember.create(teamId, second.getId(), MemberRole.MEMBER));
            owner = agent(teamId, first.getId(), "첫 Agent");
            sameOwner = agent(teamId, first.getId(), "같은 사용자 Agent");
            teammate = agent(teamId, second.getId(), "팀원 Agent");
        });
    }

    @Test
    void storesEveryChangeTypeUnicodePathsAndServerTimestamps() {
        List<FileInput> files = new ArrayList<>();
        for (WorkingFileChangeType type : WorkingFileChangeType.values()) {
            files.add(new FileInput("src/한글😀-" + type + ".tsx", type));
        }
        Instant before = Instant.now().minusSeconds(1);
        replace(teamId, REPO_KEY, owner, new WorkingFileRequest("feature/작업", files));
        Instant after = Instant.now().plusSeconds(1);

        WorkingFile saved = stored(REPO_KEY, owner);
        assertThat(saved.getFiles()).containsExactlyElementsOf(files.stream()
                .map(file -> new WorkingFileEntry(file.relativePath(), file.changeType())).toList());
        assertThat(saved.getBranchName()).isEqualTo("feature/작업");
        assertThat(saved.getObservedAt()).isBetween(before, after);
        assertThat(Duration.between(saved.getObservedAt(), saved.getExpiresAt())).isEqualTo(Duration.ofSeconds(90));
        assertThat(saved.getUserId()).isEqualTo(owner.ownerUserId());
        assertRepositoryCount(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2000})
    void createsRepositoryAndObservationForEmptyAndMaximumFileLists(int count) {
        List<FileInput> files = IntStream.range(0, count).mapToObj(i -> file("src/F" + i + ".java")).toList();
        replace(teamId, REPO_KEY, owner, new WorkingFileRequest(null, files));
        WorkingFile saved = stored(REPO_KEY, owner);
        assertThat(saved.getBranchName()).isNull();
        assertThat(saved.getFiles()).hasSize(count);
        assertRepositoryCount(1);
    }

    @Test
    void replacesEntireSnapshotThenClearsFilesWithoutDeletingObservationOrOtherUser() {
        replace(teamId, REPO_KEY, owner, request("main", "src/Old.java", "src/Removed.java"));
        replace(teamId, REPO_KEY, teammate, request("teammate", "src/Team.java"));
        WorkingFile otherBefore = stored(REPO_KEY, teammate);
        replace(teamId, REPO_KEY, owner, request("feature/new", "src/New.java"));
        WorkingFile replaced = stored(REPO_KEY, owner);
        assertThat(replaced.getFiles()).containsExactly(new WorkingFileEntry("src/New.java", WorkingFileChangeType.MODIFIED));
        assertThat(replaced.getBranchName()).isEqualTo("feature/new");

        replace(teamId, REPO_KEY, owner, new WorkingFileRequest(null, List.of()));

        WorkingFile cleared = stored(REPO_KEY, owner);
        assertThat(cleared.getFiles()).isEmpty();
        assertThat(cleared.getBranchName()).isNull();
        assertThat(cleared.getObservedAt()).isAfterOrEqualTo(replaced.getObservedAt());
        assertThat(Duration.between(cleared.getObservedAt(), cleared.getExpiresAt())).isEqualTo(Duration.ofSeconds(90));
        assertThat(stored(REPO_KEY, teammate).getFiles()).isEqualTo(otherBefore.getFiles());
        assertThat(stored(REPO_KEY, teammate).getObservedAt()).isEqualTo(otherBefore.getObservedAt());
        assertThat(observationCount()).isEqualTo(2);
    }

    @Test
    void agentsOwnedBySameUserShareOneObservationAndRefreshIdenticalPayload() {
        var input = request("main", "src/App.java");
        replace(teamId, REPO_KEY, owner, input);
        Instant previous = stored(REPO_KEY, owner).getObservedAt();
        replace(teamId, REPO_KEY, sameOwner, input);
        assertThat(observationCount()).isEqualTo(1);
        assertRepositoryCount(1);
        assertThat(stored(REPO_KEY, owner).getObservedAt()).isAfterOrEqualTo(previous);
    }

    @Test
    void separatesRepositoriesAndTeamsWithTheSameHash() {
        String otherKey = "b".repeat(64);
        replace(teamId, REPO_KEY, owner, request("main", "first.java"));
        replace(teamId, otherKey, owner, request("main", "second.java"));
        AgentPrincipal otherTeamAgent = tx().execute(status -> {
            Team team = teams.save(Team.create("다른 팀", UUID.randomUUID().toString()));
            members.save(TeamMember.create(team.getId(), owner.ownerUserId(), MemberRole.OWNER));
            return agent(team.getId(), owner.ownerUserId(), "다른 팀 Agent");
        });
        replace(otherTeamAgent.teamId(), REPO_KEY, otherTeamAgent, request("main", "other-team.java"));

        assertRepositoryCount(2);
        assertThat(stored(REPO_KEY, owner).getFiles().getFirst().relativePath()).isEqualTo("first.java");
        assertThat(stored(otherKey, owner).getFiles().getFirst().relativePath()).isEqualTo("second.java");
        assertThat(stored(REPO_KEY, otherTeamAgent).getFiles().getFirst().relativePath()).isEqualTo("other-team.java");
        assertThat(repoId(REPO_KEY, owner)).isNotEqualTo(repoId(REPO_KEY, otherTeamAgent));
    }

    @Test
    void revisionRejectsOlderRequestsAndKeepsTheLatestSnapshotAndExpiry() {
        replace(teamId, REPO_KEY, owner, request("main", "initial.java"));
        long repoId = repoId(REPO_KEY, owner);
        UUID revision = service.getRevision(teamId, REPO_KEY, owner).revision();
        UUID latest = service.replace(teamId, REPO_KEY, owner, request("newer", "newer.java"),
                "\"" + revision + "\"").revision();
        Map<String, Object> before = row(repoId, owner.ownerUserId());
        assertError(ErrorCode.WORKING_FILES_REVISION_MISMATCH, () -> service.replace(
                teamId, REPO_KEY, owner, request("old", "old.java"), "\"" + revision + "\""));
        assertThat(row(repoId, owner.ownerUserId())).isEqualTo(before);
        assertThat(service.getRevision(teamId, REPO_KEY, owner).revision()).isEqualTo(latest);
    }

    @Test
    void expiresExactlyAtNinetySecondsButRetainsTheStoredRow() {
        replace(teamId, REPO_KEY, owner, request("main", "src/App.java"));
        WorkingFile saved = stored(REPO_KEY, owner);
        assertThat(workingFiles.findByIdRepoIdAndExpiresAtAfter(saved.getRepoId(),
                saved.getExpiresAt().minusNanos(1000))).hasSize(1);
        assertThat(workingFiles.findByIdRepoIdAndExpiresAtAfter(saved.getRepoId(), saved.getExpiresAt())).isEmpty();
        assertThat(workingFiles.existsById(saved.getId())).isTrue();
    }

    @Test
    void preservesPathThatExceedsJavaLengthButMeetsUnicodeAndByteLimits() {
        String path = "😀".repeat(166) + "a".repeat(332) + "é".repeat(2);
        replace(teamId, REPO_KEY, owner, request(null, path));
        assertThat(stored(REPO_KEY, owner).getFiles().getFirst().relativePath()).isEqualTo(path);
    }

    @Test
    void rejectsInvalidIdentityTeamAndPayloadBeforeAnyRepositoryWrite() {
        var input = request("main", "src/App.java");
        assertError(ErrorCode.AUTHENTICATION_REQUIRED, () -> replace(teamId, REPO_KEY, null, input));
        assertError(ErrorCode.AUTHENTICATION_REQUIRED, () -> replace(teamId, REPO_KEY,
                new AgentPrincipal(0, owner.ownerUserId(), teamId), input));
        assertError(ErrorCode.VALIDATION_FAILED, () -> replace(0L, REPO_KEY, owner, input));
        assertError(ErrorCode.ACCESS_DENIED, () -> replace(teamId + 1, REPO_KEY, owner, input));
        assertError(ErrorCode.VALIDATION_FAILED, () -> replace(teamId, REPO_KEY, owner, request("main", "../bad")));
        jdbc.update("delete from team_members where team_id = ? and user_id = ?", teamId, owner.ownerUserId());
        assertError(ErrorCode.ACCESS_DENIED, () -> replace(teamId, REPO_KEY, owner, input));
        assertRepositoryCount(0);
        assertThat(observationCount()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void actualDatabaseFailureRollsBackRepositoryCreationSnapshotAndRevision(boolean existing, boolean conditional) {
        if (existing) replace(teamId, REPO_KEY, owner, request("main", "original.java"));
        Map<String, Object> before = existing ? row(repoId(REPO_KEY, owner), owner.ownerUserId()) : null;
        UUID beforeRevision = existing || conditional
                ? service.getRevision(teamId, REPO_KEY, owner).revision() : null;

        // Fail in PostgreSQL after the real repository statement writes the test user's row.
        // The function/trigger exist only in this disposable Testcontainers database.
        String name = "working_test_failure_" + owner.ownerUserId();
        jdbc.execute("""
                CREATE FUNCTION %s() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.user_id = %d AND NEW.branch_name = 'feature/failure' THEN
                        RAISE EXCEPTION 'test failure after working_files write' USING ERRCODE = '23514';
                    END IF;
                    RETURN NEW;
                END;
                $$
                """.formatted(name, owner.ownerUserId()));
        try {
            jdbc.execute("CREATE TRIGGER " + name
                    + " AFTER INSERT OR UPDATE ON working_files FOR EACH ROW EXECUTE FUNCTION " + name + "()");
            try {
                RuntimeException failure = assertThrows(RuntimeException.class, () ->
                        service.replace(teamId, REPO_KEY, owner, request("feature/failure", "new.java"),
                                conditional ? "\"" + beforeRevision + "\"" : null));
                Throwable current = failure;
                String sqlState = null;
                while (current != null) {
                    if (current instanceof SQLException sql) { sqlState = sql.getSQLState(); break; }
                    current = current.getCause();
                }
                assertThat(sqlState).as("database failure: %s", failure).isEqualTo("23514");
                assertRepositoryCount(existing || conditional ? 1 : 0);
                assertThat(observationCount()).isEqualTo(existing ? 1 : 0);
                if (existing) assertThat(row(repoId(REPO_KEY, owner), owner.ownerUserId())).isEqualTo(before);
                if (beforeRevision != null) {
                    assertThat(service.getRevision(teamId, REPO_KEY, owner).revision()).isEqualTo(beforeRevision);
                } else {
                    assertThat(jdbc.queryForObject("""
                            select count(*) from working_file_revisions v
                            join team_repositories r on r.id = v.repo_id where r.team_id = ?
                            """, Long.class, teamId)).isZero();
                }
            } finally {
                jdbc.execute("DROP TRIGGER " + name + " ON working_files");
            }
        } finally {
            jdbc.execute("DROP FUNCTION " + name + "()");
        }
    }

    @Test
    void concurrentFirstUploadsKeepOneRepositoryAndOneCompleteSnapshot() throws Exception {
        CountDownLatch ready = new CountDownLatch(4);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                int index = i;
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Start timeout");
                    replace(teamId, REPO_KEY, index % 2 == 0 ? owner : sameOwner,
                            request("feature/" + index, "src/F" + index + ".java"));
                    return null;
                }));
            }
            try { assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        assertRepositoryCount(1);
        assertThat(observationCount()).isEqualTo(1);
        WorkingFile saved = stored(REPO_KEY, owner);
        int index = Integer.parseInt(saved.getBranchName().substring("feature/".length()));
        assertThat(saved.getFiles()).containsExactly(
                new WorkingFileEntry("src/F" + index + ".java", WorkingFileChangeType.MODIFIED));
    }

    private AgentPrincipal agent(Long targetTeam, Long userId, String name) {
        Agent saved = agents.saveAndFlush(Agent.create(targetTeam, userId, name, null, ToolType.CODEX, "working-test"));
        return new AgentPrincipal(saved.getId(), userId, targetTeam);
    }

    private void replace(Long targetTeam, String key, AgentPrincipal actor, WorkingFileRequest input) {
        service.replace(targetTeam, key, actor, input, null);
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private long repoId(String key, AgentPrincipal actor) {
        return repositories.findByTeamIdAndOriginUrlSha256(actor.teamId(), key).orElseThrow().getId();
    }
    private WorkingFile stored(String key, AgentPrincipal actor) {
        return workingFiles.findById(new WorkingFileId(repoId(key, actor), actor.ownerUserId())).orElseThrow();
    }
    private Map<String, Object> row(long repoId, long userId) {
        return jdbc.queryForMap("select * from working_files where repo_id = ? and user_id = ?", repoId, userId);
    }
    private void assertRepositoryCount(int count) {
        assertThat(jdbc.queryForObject("select count(*) from team_repositories where team_id = ?", Long.class, teamId))
                .isEqualTo(count);
    }
    private long observationCount() {
        return jdbc.queryForObject("""
                select count(*) from working_files w join team_repositories r on r.id = w.repo_id where r.team_id = ?
                """, Long.class, teamId);
    }
    private static WorkingFileRequest request(String branch, String... paths) {
        return new WorkingFileRequest(branch, java.util.Arrays.stream(paths).map(WorkingFileIntegrationTest::file).toList());
    }
    private static FileInput file(String path) { return new FileInput(path, WorkingFileChangeType.MODIFIED); }
    private static void assertError(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertThat(assertThrows(BusinessException.class, action).getErrorCode()).isEqualTo(code);
    }
}
