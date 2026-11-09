package com.devlink.server.service;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.CommitInput;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.FileInput;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GitCommitBatchValidatorTest {
    private static final String REPO_KEY = "a".repeat(64);
    private static final String COMMIT_SHA = "1".repeat(40);
    private static final String COMMITTED_AT = "2026-11-09T01:00:00Z";
    private static ValidatorFactory factory;
    private static GitCommitBatchValidator subject;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        subject = new GitCommitBatchValidator(factory.getValidator());
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.close();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsCommitCountWithinLimit(int count) {
        List<CommitInput> commits = IntStream.rangeClosed(1, count)
                .mapToObj(index -> new CommitInput("%040x".formatted(index), COMMITTED_AT, 2L,
                        List.of(file("src/Auth.java"))))
                .toList();

        assertThatCode(() -> subject.validate(request(commits))).doesNotThrowAnyException();
    }

    @Test
    void rejects101RawItemsEvenWhenAllHaveTheSameSha() {
        var input = request(Collections.nCopies(101, commit(List.of())));

        BusinessException exception = assertThrows(BusinessException.class, () -> subject.validate(input));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(413);
    }

    @Test
    void rejectsNullRequestAndNullOrEmptyCommitList() {
        assertInvalid(null);
        assertInvalid(request(null));
        assertInvalid(request(List.of()));
    }

    @Test
    void rejectsNullCommitElement() {
        assertInvalid(request(Collections.singletonList(null)));
    }

    @ParameterizedTest
    @MethodSource("invalidRepoKeys")
    void rejectsInvalidRepoKey(String repoKey) {
        assertInvalid(new GitCommitBatchRequest(repoKey, List.of(commit(List.of()))));
    }

    static Stream<String> invalidRepoKeys() {
        return Stream.of(null, "", " ", "a".repeat(63), "a".repeat(65),
                "A".repeat(64), "g".repeat(64));
    }

    @ParameterizedTest
    @ValueSource(ints = {40, 64})
    void acceptsFullShaFormats(int length) {
        var input = new CommitInput("a".repeat(length), COMMITTED_AT, 2L, List.of());

        assertThatCode(() -> subject.validate(request(List.of(input)))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("invalidShas")
    void rejectsInvalidCommitSha(String sha) {
        assertInvalid(request(List.of(new CommitInput(sha, COMMITTED_AT, 2L, List.of()))));
    }

    static Stream<String> invalidShas() {
        return Stream.of(null, "", "abc1234", "a".repeat(39), "a".repeat(41),
                "a".repeat(63), "a".repeat(65), "A".repeat(40), "g".repeat(40));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2026-11-09T01:00:00Z",
            "2026-11-09T10:00:00+09:00",
            "2026-11-08T20:00:00-05:00",
            "2026-11-09T01:00:00.1Z",
            "2026-11-09T01:00:00.123456Z",
            "2024-02-29T01:00:00Z"
    })
    void acceptsValidOffsetTimestamps(String committedAt) {
        var input = new CommitInput(COMMIT_SHA, committedAt, 2L, List.of());

        assertThatCode(() -> subject.validate(request(List.of(input)))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("invalidTimestamps")
    void rejectsInvalidTimestamp(String committedAt) {
        assertInvalid(request(List.of(new CommitInput(COMMIT_SHA, committedAt, 2L, List.of()))));
    }

    static Stream<String> invalidTimestamps() {
        return Stream.of(null, "", " ", "2026-11-09T01:00:00",
                "2026-11-09 01:00:00Z", "2026-02-30T01:00:00Z",
                "2026-02-29T01:00:00Z", "2026-11-09T24:00:00Z",
                "2026-11-09T01:00:60Z", "2026-11-09T01:00:00.1234567Z",
                "2026-11-09T01:00:00+25:00");
    }

    @Test
    void acceptsUnidentifiedAuthorAndEmptyFiles() {
        var input = new CommitInput(COMMIT_SHA, COMMITTED_AT, null, List.of());

        assertThatCode(() -> subject.validate(request(List.of(input)))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void rejectsNonPositiveAuthorId(long authorUserId) {
        assertInvalid(request(List.of(new CommitInput(COMMIT_SHA, COMMITTED_AT, authorUserId, List.of()))));
    }

    @Test
    void rejectsNullFilesAndNullFileElement() {
        assertInvalid(request(List.of(commit(null))));
        assertInvalid(request(List.of(commit(Collections.singletonList(null)))));
    }

    @Test
    void rejectsMissingChangeType() {
        assertInvalid(request(List.of(commit(List.of(new FileInput("src/Auth.java", null))))));
    }

    @ParameterizedTest
    @MethodSource("validPaths")
    void acceptsRelativePathsWithoutChangingThem(String path) {
        var input = request(List.of(commit(List.of(file(path)))));

        assertThatCode(() -> subject.validate(input)).doesNotThrowAnyException();
        assertThat(input.commits().getFirst().files().getFirst().relativePath()).isEqualTo(path);
    }

    static Stream<String> validPaths() {
        return Stream.of("README.md", "src/main/Auth.java", ".gitignore",
                "src/한글.java", " src / File name .java ", "src/..hidden/File.java",
                "a".repeat(500));
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void rejectsInvalidRelativePath(String path) {
        assertInvalid(request(List.of(commit(List.of(file(path))))));
    }

    static Stream<String> invalidPaths() {
        return Stream.of(null, "", " ", "a".repeat(501), "/src/Auth.java",
                "C:/project/Auth.java", "c:Auth.java", "src\\Auth.java",
                "\\\\server\\share\\Auth.java", "//server/share/Auth.java",
                "src/" + '\0' + "Auth.java", ".", "..", "./Auth.java", "../Auth.java",
                "src/./Auth.java", "src/../Auth.java", "src//Auth.java", "src/");
    }

    @Test
    void rejectsRepeatedPathEvenWhenChangeTypesDiffer() {
        var files = List.of(file("src/Auth.java"),
                new FileInput("src/Auth.java", CommitChangeType.ADDED));

        assertInvalid(request(List.of(commit(files))));
    }

    @Test
    void allowsTheSamePathInDifferentCommits() {
        var first = commit(List.of(file("src/Auth.java")));
        var second = new CommitInput("2".repeat(40), COMMITTED_AT, 2L,
                List.of(file("src/Auth.java")));

        assertThatCode(() -> subject.validate(request(List.of(first, second))))
                .doesNotThrowAnyException();
    }

    @Test
    void preservesCaseSensitivePathsAndDistinctWhitespace() {
        var files = List.of(file("src/Auth.java"), file("src/auth.java"),
                file("src/ Auth.java "));

        assertThatCode(() -> subject.validate(request(List.of(commit(files)))))
                .doesNotThrowAnyException();
    }

    private static GitCommitBatchRequest request(List<CommitInput> commits) {
        return new GitCommitBatchRequest(REPO_KEY, commits);
    }

    private static CommitInput commit(List<FileInput> files) {
        return new CommitInput(COMMIT_SHA, COMMITTED_AT, 2L, files);
    }

    private static FileInput file(String path) {
        return new FileInput(path, CommitChangeType.MODIFIED);
    }

    private static void assertInvalid(GitCommitBatchRequest request) {
        BusinessException exception = assertThrows(BusinessException.class, () -> subject.validate(request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(400);
    }
}
