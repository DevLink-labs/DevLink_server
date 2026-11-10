package com.devlink.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.devlink.server.dto.gitcollection.WorkingFileRequest;
import com.devlink.server.dto.gitcollection.WorkingFileRequest.FileInput;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class WorkingFileValidatorTest {
    private static final String REPO_KEY = "a".repeat(64);
    private static ValidatorFactory factory;
    private static WorkingFileValidator subject;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        subject = new WorkingFileValidator(factory.getValidator());
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2000})
    void acceptsEmptyAndBoundaryFileCounts(int count) {
        List<FileInput> files = IntStream.range(0, count)
                .mapToObj(i -> file("src/File" + i + ".java"))
                .toList();

        assertThatCode(() -> subject.validate(REPO_KEY, new WorkingFileRequest("feature/login", files)))
                .doesNotThrowAnyException();
    }

    @Test
    void rejects2001FilesWith400WithoutChangingInput() {
        List<FileInput> files = IntStream.range(0, 2001)
                .mapToObj(i -> file("src/File" + i + ".java"))
                .toList();
        var request = new WorkingFileRequest("main", files);
        assertInvalid(REPO_KEY, request);
        assertThat(request.files()).isSameAs(files).hasSize(2001);
    }

    @Test
    void rejectsNullRequestAndMissingNestedValuesAsValidationErrors() {
        assertInvalid(REPO_KEY, null);
        assertInvalid(REPO_KEY, new WorkingFileRequest("main", null));
        assertInvalid(REPO_KEY, new WorkingFileRequest("main", Collections.singletonList(null)));
        assertInvalid(REPO_KEY, new WorkingFileRequest("main",
                List.of(new FileInput("src/App.java", null))));
    }

    @ParameterizedTest
    @MethodSource("invalidRepoKeys")
    void rejectsInvalidRepoKeys(String repoKey) {
        assertInvalid(repoKey, new WorkingFileRequest(null, List.of()));
    }

    static Stream<String> invalidRepoKeys() {
        return Stream.of(null, "", " ", "a".repeat(63), "a".repeat(65),
                "A".repeat(64), "g".repeat(64), " " + REPO_KEY, REPO_KEY + " ",
                "https://github.com/example/project.git");
    }

    @Test
    void acceptsMixedLowercaseHexRepoKey() {
        assertThatCode(() -> subject.validate("0123456789abcdef".repeat(4),
                new WorkingFileRequest(null, List.of()))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("validBranches")
    void acceptsDetachedHeadAndSupportedBranchNamesWithoutNormalizing(String branch) {
        var request = new WorkingFileRequest(branch, List.of());
        assertThatCode(() -> subject.validate(REPO_KEY, request)).doesNotThrowAnyException();
        assertThat(request.branch()).isEqualTo(branch);
    }

    static Stream<String> validBranches() {
        String supplementaryLetter = new String(Character.toChars(0x10400));
        return Stream.of(null, "main", "feature/login", "feature/로그인", "release/v1.2_3",
                "a".repeat(255), supplementaryLetter.repeat(255));
    }

    @ParameterizedTest
    @MethodSource("invalidBranches")
    void rejectsInvalidBranchNames(String branch) {
        assertInvalid(REPO_KEY, new WorkingFileRequest(branch, List.of()));
    }

    static Stream<String> invalidBranches() {
        String supplementaryLetter = new String(Character.toChars(0x10400));
        return Stream.of("", " ", " main", "main ", "-main", "feature/../login",
                "feature..login", "feature\\login", "feature:login", "feature@login",
                "feature\nlogin", "feature\tlogin", "feature\u0000login",
                "a".repeat(256), supplementaryLetter.repeat(256));
    }

    @ParameterizedTest
    @EnumSource(WorkingFileChangeType.class)
    void acceptsEveryWorkingChangeType(WorkingFileChangeType changeType) {
        assertThatCode(() -> subject.validate(REPO_KEY, new WorkingFileRequest(null,
                List.of(new FileInput("src/App.java", changeType))))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("validPaths")
    void acceptsValidRelativePathsWithoutNormalizing(String path) {
        var request = new WorkingFileRequest("main", List.of(file(path)));
        assertThatCode(() -> subject.validate(REPO_KEY, request)).doesNotThrowAnyException();
        assertThat(request.files().getFirst().relativePath()).isEqualTo(path);
    }

    static Stream<String> validPaths() {
        return Stream.of("README.md", ".gitignore", "src/한글.java", "src/😀.tsx",
                " src / File name .java ", "src/..hidden/file.java",
                "a".repeat(500), "é".repeat(500), "가".repeat(333) + "a", "😀".repeat(250));
    }

    @Test
    void accepts500CodePointsAnd1000Utf8BytesEvenWhenJavaLengthExceeds500() {
        String path = "😀".repeat(166) + "a".repeat(332) + "é".repeat(2);
        assertThat(path.codePointCount(0, path.length())).isEqualTo(500);
        assertThat(path.getBytes(StandardCharsets.UTF_8)).hasSize(1000);
        assertThat(path.length()).isGreaterThan(500);

        assertThatCode(() -> subject.validate(REPO_KEY,
                new WorkingFileRequest(null, List.of(file(path))))).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("invalidPaths")
    void rejectsInvalidPathsIncludingThePreviouslyMissingSegmentChecks(String path) {
        assertInvalid(REPO_KEY, new WorkingFileRequest("main", List.of(file(path))));
    }

    static Stream<String> invalidPaths() {
        return Stream.of(null, "", " ", "\t", "a".repeat(501),
                "é".repeat(501), "가".repeat(333) + "ab", "😀".repeat(251),
                "/src/App.java", "//server/share/App.java", "C:/project/App.java",
                "c:App.java", "src\\App.java", "\\\\server\\share\\App.java",
                ".", "..", "./App.java", "../App.java", "src/./App.java",
                "src/../App.java", "src//App.java", "src/", "src/\nApp.java",
                "src/\rApp.java", "src/\tApp.java", "src/\u0000App.java",
                "src/\u007fApp.java", "src/\u0085App.java");
    }

    @ParameterizedTest
    @EnumSource(value = WorkingFileChangeType.class, names = {"ADDED", "MODIFIED"})
    void rejectsDuplicatePathsEvenWhenTypesDiffer(WorkingFileChangeType secondType) {
        assertInvalid(REPO_KEY, new WorkingFileRequest("main", List.of(
                file("src/App.java"), new FileInput("src/App.java", secondType))));
    }

    @Test
    void preservesCaseWhitespaceAndInputOrderAndAllowsIndependentObservations() {
        List<FileInput> files = new ArrayList<>(List.of(
                file("src/App.java"), file("src/app.java"), file("src/ App.java ")));
        List<FileInput> before = List.copyOf(files);
        var request = new WorkingFileRequest("feature/Case", files);

        subject.validate(REPO_KEY, request);
        subject.validate(REPO_KEY, request);
        subject.validate("b".repeat(64), request);

        assertThat(request.files()).isSameAs(files).containsExactlyElementsOf(before);
        assertThat(request.branch()).isEqualTo("feature/Case");
    }

    private static FileInput file(String path) {
        return new FileInput(path, WorkingFileChangeType.MODIFIED);
    }

    private static void assertInvalid(String repoKey, WorkingFileRequest request) {
        BusinessException exception = assertThrows(BusinessException.class,
                () -> subject.validate(repoKey, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.getErrorCode().getStatus().value()).isEqualTo(400);
    }
}
