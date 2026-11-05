package com.devlink.server;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.devlink.server.domain.gitrepo.CommitFile;
import com.devlink.server.domain.gitrepo.CommitFileId;
import com.devlink.server.domain.gitrepo.DependencyRelationType;
import com.devlink.server.domain.gitrepo.FileDependency;
import com.devlink.server.domain.gitrepo.RepositoryCommit;
import com.devlink.server.domain.gitrepo.TeamGitRepository;
import com.devlink.server.domain.gitrepo.WorkingFile;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.devlink.server.domain.gitrepo.WorkingFileEntry;
import com.devlink.server.domain.gitrepo.WorkingFileId;
import com.devlink.server.domain.gitsetting.UserGitSetting;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.user.User;
import com.devlink.server.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;

/** V10 스키마와 3차 Git 데이터 엔티티 매핑을 실제 PostgreSQL로 검증한다. */
@Transactional
class GitDataPersistenceIntegrationTest extends AbstractIntegrationTest {
    private static final String SHA256 = "a".repeat(64);

    @Autowired private UserRepository users;
    @Autowired private TeamRepository teams;
    @Autowired private AgentRepository agents;
    @Autowired private UserGitSettingRepository gitSettings;
    @Autowired private TeamGitRepositoryRepository repositories;
    @Autowired private RepositoryCommitRepository commits;
    @Autowired private CommitFileRepository commitFiles;
    @Autowired private WorkingFileRepository workingFiles;
    @Autowired private FileDependencyRepository fileDependencies;
    @Autowired private EntityManager em;

    @Test
    void agentPartAndRolesRoundTripThroughJsonb() {
        User user = users.save(User.create("part@example.com", "Tester", null, "password-hash"));
        Team team = teams.save(Team.create("Routing", "routing-invite"));
        Agent legacy = agents.save(Agent.create(team.getId(), user.getId(), "Legacy", null, ToolType.CODEX, "server"));
        Agent tagged = agents.save(Agent.create(team.getId(), user.getId(), "Tagged", null, ToolType.CODEX, "server",
                AgentPart.BACKEND, List.of("인증", "로그인")));
        em.flush();
        em.clear();

        Agent savedLegacy = agents.findById(legacy.getId()).orElseThrow();
        assertThat(savedLegacy.getPart()).isEqualTo(AgentPart.OTHER);
        assertThat(savedLegacy.getRoles()).isEmpty();

        Agent savedTagged = agents.findById(tagged.getId()).orElseThrow();
        assertThat(savedTagged.getPart()).isEqualTo(AgentPart.BACKEND);
        assertThat(savedTagged.getRoles()).containsExactly("인증", "로그인");

        savedTagged.updateRoutingMetadata(null, List.of());
        em.flush();
        em.clear();
        Agent cleared = agents.findById(tagged.getId()).orElseThrow();
        assertThat(cleared.getPart()).isEqualTo(AgentPart.BACKEND);
        assertThat(cleared.getRoles()).isEmpty();
    }

    @Test
    void gitTablesPersistWithCompositeKeysAndJsonb() {
        User user = users.save(User.create("git@example.com", "Tester", null, "password-hash"));
        Team team = teams.save(Team.create("Git", "git-invite"));
        Instant observedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        gitSettings.save(UserGitSetting.register(user.getId(), "git@example.com"));
        TeamGitRepository repo = repositories.save(TeamGitRepository.create(team.getId(), SHA256));
        RepositoryCommit commit = commits.save(RepositoryCommit.of(team.getId(), repo.getId(),
                "0123456789abcdef0123456789abcdef01234567", observedAt, user.getId()));
        commitFiles.save(CommitFile.of(commit.getId(), "src/main/App.java", CommitChangeType.MODIFIED));
        workingFiles.save(WorkingFile.observe(repo.getId(), user.getId(), "feature/x",
                List.of(new WorkingFileEntry("src/main/App.java", WorkingFileChangeType.MODIFIED),
                        new WorkingFileEntry("README.md", WorkingFileChangeType.UNTRACKED)),
                observedAt, observedAt.plusSeconds(90)));
        fileDependencies.save(FileDependency.of(repo.getId(), "0123456789abcdef0123456789abcdef01234567",
                "src/main/App.java", "src/main/Util.java", DependencyRelationType.IMPORT, observedAt));
        em.flush();
        em.clear();

        assertThat(gitSettings.findByGitEmail("git@example.com")).map(UserGitSetting::getUserId).contains(user.getId());
        assertThat(gitSettings.existsByGitEmailAndUserIdNot("git@example.com", user.getId())).isFalse();
        assertThat(repositories.findByTeamIdAndOriginUrlSha256(team.getId(), SHA256)).map(TeamGitRepository::getId).contains(repo.getId());

        RepositoryCommit savedCommit = commits.findById(commit.getId()).orElseThrow();
        assertThat(savedCommit.getCommittedAt()).isEqualTo(observedAt);
        assertThat(savedCommit.getAuthorUserId()).isEqualTo(user.getId());
        assertThat(savedCommit.getFirstSeenAt()).isNotNull();

        List<CommitFile> savedFiles = commitFiles.findByIdCommitIdIn(List.of(commit.getId()));
        assertThat(savedFiles).hasSize(1);
        assertThat(savedFiles.get(0).getRelativePath()).isEqualTo("src/main/App.java");
        assertThat(savedFiles.get(0).getChangeType()).isEqualTo(CommitChangeType.MODIFIED);
        assertThat(commitFiles.findById(new CommitFileId(commit.getId(), "src/main/App.java"))).isPresent();

        WorkingFile savedWorking = workingFiles.findById(new WorkingFileId(repo.getId(), user.getId())).orElseThrow();
        assertThat(savedWorking.getBranchName()).isEqualTo("feature/x");
        assertThat(savedWorking.getFiles()).containsExactly(
                new WorkingFileEntry("src/main/App.java", WorkingFileChangeType.MODIFIED),
                new WorkingFileEntry("README.md", WorkingFileChangeType.UNTRACKED));
        assertThat(workingFiles.findByIdRepoIdInAndExpiresAtAfter(List.of(repo.getId()), observedAt)).hasSize(1);
        assertThat(workingFiles.findByIdRepoIdInAndExpiresAtAfter(List.of(repo.getId()), observedAt.plusSeconds(90))).isEmpty();

        assertThat(fileDependencies.count()).isEqualTo(1);
    }

    @Test
    void emptyWorkingTreeObservationIsPreserved() {
        User user = users.save(User.create("empty@example.com", "Tester", null, "password-hash"));
        Team team = teams.save(Team.create("Empty", "empty-invite"));
        TeamGitRepository repo = repositories.save(TeamGitRepository.create(team.getId(), SHA256));
        Instant now = Instant.now();
        workingFiles.save(WorkingFile.observe(repo.getId(), user.getId(), "main", null, now, now.plusSeconds(90)));
        em.flush();
        em.clear();

        WorkingFile saved = workingFiles.findById(new WorkingFileId(repo.getId(), user.getId())).orElseThrow();
        assertThat(saved.getFiles()).isEmpty();
    }
}
