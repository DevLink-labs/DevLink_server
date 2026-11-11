package com.devlink.server.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.Agent;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.agent.ToolType;
import com.devlink.server.domain.gitrepo.*;
import com.devlink.server.dto.recommendation.AgentRecommendationRequest;
import com.devlink.server.dto.recommendation.RecommendationCandidateResponse;
import com.devlink.server.repository.*;
import com.devlink.server.repository.CommitFileRepository.CommitEvidenceView;
import com.devlink.server.repository.CommitFileRepository.CommitPathView;
import com.devlink.server.repository.FileDependencyRepository.ImportingFileView;
import com.devlink.server.service.RecommendationGitEvidence.RepoPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import static com.devlink.server.service.RecommendationScorePolicy.MAX_IMPORTERS_PER_DIRECT_FILE;
import static com.devlink.server.service.RecommendationScorePolicy.MAX_INDIRECT_COMMIT_WEIGHT;
import static com.devlink.server.service.RecommendationScorePolicy.PATH_QUERY_CHUNK_SIZE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RecommendationGitEvidenceServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-11T00:00:00Z");
    private static final String DIRECT = "src/orders/OrderService.java";
    private static final String INDIRECT = "src/web/CheckoutController.java";
    private final TeamGitRepositoryRepository repos = mock(TeamGitRepositoryRepository.class);
    private final RepositoryCommitRepository commits = mock(RepositoryCommitRepository.class);
    private final CommitFileRepository files = mock(CommitFileRepository.class);
    private final WorkingFileRepository working = mock(WorkingFileRepository.class);
    private final FileDependencyRepository dependencies = mock(FileDependencyRepository.class);
    private final RecommendationGitEvidenceService service =
            new RecommendationGitEvidenceService(repos, commits, files, working, dependencies);

    @BeforeEach
    void setup() {
        TeamGitRepository repo = TeamGitRepository.create(7L, "a".repeat(64));
        ReflectionTestUtils.setField(repo, "id", 12L);
        when(repos.findByTeamId(7L)).thenReturn(List.of(repo));
        when(commits.existsByTeamId(7L)).thenReturn(true);
        directPaths(DIRECT);
    }

    @Test
    void expandsOneStepAndAppliesHalfWeightToIndirectWorkAndCommits() {
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT)))
                .thenReturn(List.of(edge(INDIRECT, DIRECT), edge(INDIRECT, DIRECT)));
        observation(INDIRECT, NOW);
        evidence(commit(12L, INDIRECT, "sha", NOW));

        var result = collect();
        var user = result.forUser(200L);
        assertThat(result.relatedPaths()).containsExactlyInAnyOrder(
                new RepoPath(12L, DIRECT), new RepoPath(12L, INDIRECT));
        assertThat(RecommendationScorePolicy.working(user.workingWeight())).isEqualTo(7.5);
        assertThat(RecommendationScorePolicy.frequency(user.weightedCommitCount())).isEqualTo(1.5);
        assertThat(user.strongestCommitWeight()
                * RecommendationScorePolicy.recency(user.strongestCommitAt(), NOW)).isEqualTo(5.0);
        assertThat(user.relatedFiles().get(0).indirect()).isTrue();
        verify(dependencies).findImportingFiles(7L, 12L, List.of(DIRECT));
        verifyNoMoreInteractions(dependencies);
    }

    @Test
    void directMatchWinsAndSharedCommitIsCountedOnceAtFullWeight() {
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT)))
                .thenReturn(List.of(edge(DIRECT, DIRECT), edge(INDIRECT, DIRECT)));
        evidence(commit(12L, INDIRECT, "same", NOW), commit(12L, DIRECT, "same", NOW));
        observation(DIRECT, NOW);

        var user = collect().forUser(200L);
        assertThat(user.distinctCommitCount()).isEqualTo(1);
        assertThat(user.weightedCommitCount()).isEqualTo(1.0);
        assertThat(user.workingWeight()).isEqualTo(1.0);
        assertThat(user.strongestCommitWeight()).isEqualTo(1.0);
        assertThat(user.relatedFiles().stream().filter(f -> f.path().relativePath().equals(DIRECT))
                .findFirst().orElseThrow().indirect()).isFalse();
    }

    @Test
    void recencyUsesStrongestWeightedCommitRatherThanLatestTimestamp() {
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT)))
                .thenReturn(List.of(edge(INDIRECT, DIRECT)));
        evidence(commit(12L, INDIRECT, "recent", NOW),
                commit(12L, DIRECT, "older", NOW.minus(Duration.ofDays(2))));
        var user = collect().forUser(200L);
        assertThat(user.latestCommitAt()).isEqualTo(NOW);
        assertThat(user.strongestCommitAt()).isEqualTo(NOW.minus(Duration.ofDays(2)));
        assertThat(user.strongestCommitWeight()).isEqualTo(1.0);
        assertThat(user.weightedCommitCount()).isEqualTo(1.5);
    }

    @Test
    void missingGraphKeepsDirectEvidenceAndRejectsSamePathFromOtherRepository() {
        evidence(commit(12L, DIRECT, "direct", NOW), commit(99L, DIRECT, "foreign", NOW));
        observation(DIRECT, NOW.minusSeconds(90));
        var user = collect().forUser(200L);
        assertThat(user.distinctCommitCount()).isEqualTo(1);
        assertThat(user.weightedCommitCount()).isEqualTo(1.0);
        assertThat(user.workingWeight()).isZero();
        assertThat(user.relatedFiles()).hasSize(1);
        assertThat(user.relatedFiles().get(0).weight()).isEqualTo(1.0);
    }

    @Test
    void noDirectMatchDoesNotExpandGraph() {
        when(files.findTeamPathsInWindow(eq(7L), any(), any())).thenReturn(List.of());
        assertThat(collect().relatedPaths()).isEmpty();
        verifyNoInteractions(dependencies);
    }

    @Test
    void excludesHubImportersFromCommitAndWorkingEvidenceButKeepsDirectEvidence() {
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT)))
                .thenReturn(importers(DIRECT, MAX_IMPORTERS_PER_DIRECT_FILE + 1));
        observation(INDIRECT, NOW);
        evidence(commit(12L, INDIRECT, "importer", NOW), commit(12L, DIRECT, "direct", NOW));

        var result = collect();
        var user = result.forUser(200L);
        assertThat(result.relatedPaths()).containsExactly(new RepoPath(12L, DIRECT));
        assertThat(user.workingWeight()).isZero();
        assertThat(user.distinctCommitCount()).isEqualTo(1);
        assertThat(user.weightedCommitCount()).isEqualTo(1.0);
        assertThat(user.relatedFiles()).extracting(file -> file.path().relativePath()).containsExactly(DIRECT);
    }

    @Test
    void expandsAtTheImporterLimitCountingDistinctFiles() {
        List<ImportingFileView> edges = new ArrayList<>(importers(DIRECT, MAX_IMPORTERS_PER_DIRECT_FILE));
        edges.add(edge(INDIRECT, DIRECT));
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT))).thenReturn(edges);
        evidence(commit(12L, INDIRECT, "importer", NOW));

        var result = collect();
        assertThat(result.relatedPaths()).hasSize(MAX_IMPORTERS_PER_DIRECT_FILE + 1);
        assertThat(result.forUser(200L).weightedCommitCount()).isEqualTo(RecommendationScorePolicy.INDIRECT_WEIGHT);
    }

    @Test
    void importerOfHubAndNonHubDirectFilesIsAddedAsIndirect() {
        String firstNonHub = "src/orders/OrderValidator.java";
        String secondNonHub = "src/orders/OrderWorkflow.java";
        directPaths(DIRECT, firstNonHub, secondNonHub);
        List<ImportingFileView> edges = new ArrayList<>(importers(DIRECT, MAX_IMPORTERS_PER_DIRECT_FILE + 1));
        edges.add(edge(INDIRECT, secondNonHub));
        edges.add(edge(INDIRECT, firstNonHub));
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT, firstNonHub, secondNonHub))).thenReturn(edges);
        evidence(commit(12L, INDIRECT, "importer", NOW));

        var result = collect();
        assertThat(result.relatedPaths()).contains(new RepoPath(12L, INDIRECT));
        assertThat(result.forUser(200L).relatedFiles().getFirst().indirect()).isTrue();
    }

    @Test
    void capsFrequencyContributionOfTenIndirectCommits() {
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT)))
                .thenReturn(List.of(edge(INDIRECT, DIRECT)));
        evidence(tenIndirectCommits(200L).toArray(CommitEvidenceView[]::new));

        var user = collect().forUser(200L);
        assertThat(user.distinctCommitCount()).isEqualTo(10);
        assertThat(user.weightedCommitCount()).isEqualTo(MAX_INDIRECT_COMMIT_WEIGHT);
        assertThat(RecommendationScorePolicy.frequency(user.weightedCommitCount()))
                .isEqualTo(RecommendationScorePolicy.FREQUENCY_POINTS_PER_COMMIT * MAX_INDIRECT_COMMIT_WEIGHT);
    }

    @Test
    void addsThreeDirectCommitsToCappedIndirectContribution() {
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT)))
                .thenReturn(List.of(edge(INDIRECT, DIRECT)));
        List<CommitEvidenceView> rows = new ArrayList<>(tenIndirectCommits(200L));
        int directCommitCount = 3;
        for (int index = 0; index < directCommitCount; index++) {
            rows.add(commit(12L, DIRECT, "direct-" + index, NOW));
        }
        evidence(rows.toArray(CommitEvidenceView[]::new));

        assertThat(collect().forUser(200L).weightedCommitCount())
                .isEqualTo(directCommitCount + MAX_INDIRECT_COMMIT_WEIGHT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void directOwnerRemainsRecommendedDespiteFrequentWorkOnHubImporter() {
        String userPath = "src/domain/User.java";
        directPaths(userPath);
        when(dependencies.findImportingFiles(7L, 12L, List.of(userPath)))
                .thenReturn(importers(userPath, MAX_IMPORTERS_PER_DIRECT_FILE + 1));
        observation(INDIRECT, NOW);
        List<CommitEvidenceView> rows = new ArrayList<>(tenIndirectCommits(200L));
        rows.add(commit(12L, userPath, "direct-owner", NOW.minus(Duration.ofDays(14)), 300L));
        evidence(rows.toArray(CommitEvidenceView[]::new));
        AgentRepository agents = mock(AgentRepository.class);
        Agent owner = candidate(30L, 300L);
        when(agents.findRecommendationCandidates(7L)).thenReturn(List.of(candidate(20L, 200L), owner));
        ObjectProvider<Clock> clock = mock(ObjectProvider.class);
        when(clock.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        var recommendation = new AgentRecommendationService(agents, mock(TeamAuthorizationService.class),
                service, mock(AgentPresenceService.class), clock);

        var response = recommendation.recommend(7L, new AgentPrincipal(10L, 100L, 7L),
                new AgentRecommendationRequest("User.java", null));

        assertThat(response.candidates()).extracting(RecommendationCandidateResponse::agentId)
                .containsExactly(owner.getId());
        assertThat(response.candidates().getFirst().scores().git()).isEqualTo(8.0);
    }

    @Test
    void queriesImportersInTwoSortedChunksForOnePathOverTheLimit() {
        List<String> paths = matchedOrderPaths(PATH_QUERY_CHUNK_SIZE + 1);

        collect();

        var calls = inOrder(dependencies);
        calls.verify(dependencies).findImportingFiles(7L, 12L, paths.subList(0, PATH_QUERY_CHUNK_SIZE));
        calls.verify(dependencies).findImportingFiles(7L, 12L, List.of(paths.getLast()));
        verifyNoMoreInteractions(dependencies);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mergesCommitEvidenceAcrossChunksAndCountsSharedCommitsOnce(boolean sharedCommit) {
        List<String> direct = matchedOrderPaths(PATH_QUERY_CHUNK_SIZE);
        List<String> indirect = List.of(INDIRECT);
        when(dependencies.findImportingFiles(7L, 12L, direct))
                .thenReturn(List.of(edge(INDIRECT, direct.getFirst())));
        var first = commit(12L, direct.getFirst(), "direct", NOW);
        var second = commit(12L, INDIRECT, sharedCommit ? "direct" : "indirect", NOW);
        when(files.findEvidenceInWindow(eq(7L), any(), any(), eq(NOW), eq(direct))).thenReturn(List.of(first));
        when(files.findEvidenceInWindow(eq(7L), any(), any(), eq(NOW), eq(indirect))).thenReturn(List.of(second));

        var user = collect().forUser(200L);

        var calls = inOrder(files);
        calls.verify(files).findEvidenceInWindow(eq(7L), any(), any(), eq(NOW), eq(direct));
        calls.verify(files).findEvidenceInWindow(eq(7L), any(), any(), eq(NOW), eq(indirect));
        verify(files, times(2)).findEvidenceInWindow(eq(7L), any(), any(), eq(NOW), any());
        assertThat(user.relatedFiles()).extracting(file -> file.path().relativePath())
                .containsExactlyInAnyOrder(direct.getFirst(), INDIRECT);
        assertThat(user.distinctCommitCount()).isEqualTo(sharedCommit ? 1 : 2);
        assertThat(user.weightedCommitCount())
                .isEqualTo(sharedCommit ? 1.0 : 1.0 + RecommendationScorePolicy.INDIRECT_WEIGHT);
    }

    @Test
    void queriesEachRepositoryMethodOnceAtTheChunkLimit() {
        List<String> paths = matchedOrderPaths(PATH_QUERY_CHUNK_SIZE);

        collect();

        verify(dependencies).findImportingFiles(7L, 12L, paths);
        verifyNoMoreInteractions(dependencies);
        verify(files).findEvidenceInWindow(eq(7L), any(), any(), eq(NOW), eq(paths));
        verify(files).findEvidenceInWindow(eq(7L), any(), any(), eq(NOW), any());
    }

    @Test
    void sameImporterPathInAnotherRepositoryDoesNotContributeEvidence() {
        TeamGitRepository firstRepo = repos.findByTeamId(7L).getFirst();
        TeamGitRepository secondRepo = TeamGitRepository.create(7L, "b".repeat(64));
        ReflectionTestUtils.setField(secondRepo, "id", 13L);
        when(repos.findByTeamId(7L)).thenReturn(List.of(firstRepo, secondRepo));
        List<CommitPathView> directRows = List.of(12L, 13L).stream().<CommitPathView>map(repoId ->
                new CommitPathView() {
                    public Long getRepoId() { return repoId; }
                    public String getRelativePath() { return DIRECT; }
                }).toList();
        when(files.findTeamPathsInWindow(eq(7L), any(), any())).thenReturn(directRows);
        when(dependencies.findImportingFiles(7L, 12L, List.of(DIRECT)))
                .thenReturn(List.of(edge(INDIRECT, DIRECT)));
        evidence(commit(12L, INDIRECT, "first-repo", NOW), commit(13L, INDIRECT, "second-repo", NOW));

        var result = collect();
        var user = result.forUser(200L);

        verify(dependencies).findImportingFiles(7L, 12L, List.of(DIRECT));
        verify(dependencies).findImportingFiles(7L, 13L, List.of(DIRECT));
        verifyNoMoreInteractions(dependencies);
        assertThat(result.relatedPaths()).containsExactlyInAnyOrder(
                new RepoPath(12L, DIRECT), new RepoPath(13L, DIRECT), new RepoPath(12L, INDIRECT));
        assertThat(user.distinctCommitCount()).isEqualTo(1);
        assertThat(user.weightedCommitCount()).isEqualTo(RecommendationScorePolicy.INDIRECT_WEIGHT);
        assertThat(user.relatedFiles()).hasSize(1);
        assertThat(user.relatedFiles().getFirst().path()).isEqualTo(new RepoPath(12L, INDIRECT));
        assertThat(user.relatedFiles().getFirst().indirect()).isTrue();
    }

    private List<String> matchedOrderPaths(int count) {
        List<String> paths = IntStream.range(0, count).mapToObj(i -> "src/order/Service-" + i + ".java")
                .sorted().toList();
        directPaths(paths.reversed().toArray(String[]::new));
        return paths;
    }

    private void directPaths(String... paths) {
        List<CommitPathView> rows = Arrays.stream(paths).<CommitPathView>map(path -> new CommitPathView() {
            public Long getRepoId() { return 12L; }
            public String getRelativePath() { return path; }
        }).toList();
        when(files.findTeamPathsInWindow(eq(7L), any(), any())).thenReturn(rows);
    }

    private static List<ImportingFileView> importers(String target, int count) {
        return IntStream.range(0, count)
                .mapToObj(index -> edge(index == 0 ? INDIRECT : "src/web/Importer" + index + ".java", target))
                .toList();
    }

    private static List<CommitEvidenceView> tenIndirectCommits(long author) {
        return IntStream.range(0, 10)
                .mapToObj(index -> commit(12L, INDIRECT, "indirect-" + index, NOW.minusSeconds(index), author))
                .toList();
    }

    private static Agent candidate(long id, long author) {
        Agent agent = Agent.create(7L, author, "Agent " + id, null, ToolType.CODEX, "project",
                AgentPart.BACKEND, List.of());
        ReflectionTestUtils.setField(agent, "id", id);
        return agent;
    }

    private RecommendationGitEvidence collect() {
        return service.collect(7L, List.of(200L), List.of("order"), NOW);
    }

    private void observation(String path, Instant at) {
        when(working.findByIdRepoIdInAndIdUserIdIn(any(), any())).thenReturn(List.of(
                WorkingFile.observe(12L, 200L, "main", List.of(
                        new WorkingFileEntry(path, WorkingFileChangeType.MODIFIED)), at, at.plusSeconds(90))));
    }

    private void evidence(CommitEvidenceView... rows) {
        when(files.findEvidenceInWindow(eq(7L), any(), any(), any(), any())).thenReturn(List.of(rows));
    }

    private static ImportingFileView edge(String source, String target) {
        return new ImportingFileView() {
            public String getSourcePath() { return source; }
            public String getTargetPath() { return target; }
        };
    }

    private static CommitEvidenceView commit(long repo, String path, String sha, Instant at) {
        return commit(repo, path, sha, at, 200L);
    }

    private static CommitEvidenceView commit(long repo, String path, String sha, Instant at, long author) {
        CommitEvidenceView row = mock(CommitEvidenceView.class);
        when(row.getRepoId()).thenReturn(repo);
        when(row.getRelativePath()).thenReturn(path);
        when(row.getCommitSha()).thenReturn(sha);
        when(row.getCommittedAt()).thenReturn(at);
        when(row.getAuthorUserId()).thenReturn(author);
        return row;
    }
}
