package com.devlink.server.dto.gitcollection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.stream.Stream;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.devlink.server.service.WorkingFileValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class WorkingFileRequestJsonTest {
    private static final String REPO_KEY = "a".repeat(64);
    private static ValidatorFactory factory;
    private static WorkingFileValidator validator;
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = new WorkingFileValidator(factory.getValidator());
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) factory.close();
    }

    @ParameterizedTest
    @EnumSource(WorkingFileChangeType.class)
    void acceptsEveryExactWorkingEnumName(WorkingFileChangeType changeType) throws Exception {
        ObjectNode input = validBody();
        ((ObjectNode) input.at("/files/0")).put("changeType", changeType.name());

        WorkingFileRequest request = json.readValue(input.toString(), WorkingFileRequest.class);
        validator.validate(REPO_KEY, request);

        assertThat(request.files().getFirst().changeType()).isEqualTo(changeType);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"branch\":null,\"files\":[]}", "{\"files\":[]}"})
    void acceptsDetachedHeadAndEmptyObservation(String body) throws Exception {
        WorkingFileRequest request = json.readValue(body, WorkingFileRequest.class);
        validator.validate(REPO_KEY, request);
        assertThat(request.branch()).isNull();
        assertThat(request.files()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("invalidEnumJson")
    void rejectsEnumCoercionAndUnsupportedNames(String value) throws Exception {
        ObjectNode input = validBody();
        ((ObjectNode) input.at("/files/0")).set("changeType", json.readTree(value));
        assertMalformed(input.toString());
    }

    static Stream<String> invalidEnumJson() {
        return Stream.of("0", "1", "2", "3", "4", "5", "-1", "1.0", "true", "false", "[]", "{}",
                "\"0\"", "\"1\"", "\"5\"", "\"modified\"", "\" MODIFIED\"",
                "\"MODIFIED \"", "\"\"", "\"RENAMED\"", "\"UNKNOWN\"");
    }

    @ParameterizedTest
    @MethodSource("invalidStringFields")
    void rejectsNumericBooleanOrContainerValuesForStringFields(String field, String value) throws Exception {
        ObjectNode input = validBody();
        ObjectNode target = field.equals("branch") ? input : (ObjectNode) input.at("/files/0");
        target.set(field, json.readTree(value));
        assertMalformed(input.toString());
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> invalidStringFields() {
        return Stream.of("branch", "relativePath").flatMap(field ->
                Stream.of("123", "1.5", "true", "false", "[]", "{}").map(value ->
                        org.junit.jupiter.params.provider.Arguments.of(field, value)));
    }

    @ParameterizedTest
    @CsvSource({
            "request, userId", "request, agentId", "request, repoKey", "request, observedAt",
            "request, expiresAt", "request, unknown", "file, diff", "file, content", "file, unknown"
    })
    void rejectsUndefinedFieldsAtBothLevels(String level, String field) throws Exception {
        ObjectNode input = validBody();
        ObjectNode target = level.equals("request") ? input : (ObjectNode) input.at("/files/0");
        target.put(field, "unexpected");
        assertMalformed(input.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"branch\":\"main\"}", "{\"branch\":null,\"files\":null}",
            "{\"files\":[null]}", "{\"files\":[{}]}",
            "{\"files\":[{\"relativePath\":\"src/App.tsx\"}]}",
            "{\"files\":[{\"relativePath\":\"src/App.tsx\",\"changeType\":null}]}",
            "{\"files\":[{\"changeType\":\"MODIFIED\"}]}",
            "{\"files\":[{\"relativePath\":null,\"changeType\":\"MODIFIED\"}]}",
            "{\"files\":[{\"relativePath\":\" \",\"changeType\":\"MODIFIED\"}]}", "null"})
    void rejectsMissingOrNullRequiredValuesDuringValidation(String body) throws Exception {
        WorkingFileRequest request = json.readValue(body, WorkingFileRequest.class);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> validator.validate(REPO_KEY, request));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"files\":{}}", "{\"files\":\"[]\"}", "{\"files\":0}",
            "{\"files\":false}", "{\"files\":[\"src/App.tsx\"]}",
            "{\"files\":[0]}", "{\"files\":[true]}", "{\"files\":[[]]}",
            "[]", "\"text\"", "123", "{\"files\":[", "{"})
    void rejectsMalformedJsonAndIncorrectArrayShapes(String body) {
        assertMalformed(body);
    }

    @Test
    void preservesBranchAndPathTextWithoutNormalization() throws Exception {
        ObjectNode input = validBody();
        input.put("branch", "feature/한글");
        ((ObjectNode) input.at("/files/0")).put("relativePath", " src / 한글 😀.tsx ");
        WorkingFileRequest request = json.readValue(input.toString(), WorkingFileRequest.class);

        validator.validate(REPO_KEY, request);

        assertThat(request.branch()).isEqualTo("feature/한글");
        assertThat(request.files().getFirst().relativePath()).isEqualTo(" src / 한글 😀.tsx ");
    }

    private ObjectNode validBody() {
        ObjectNode input = json.createObjectNode().put("branch", "feature/login");
        input.putArray("files").addObject()
                .put("relativePath", "src/App.tsx").put("changeType", "MODIFIED");
        return input;
    }

    private void assertMalformed(String body) {
        assertThatThrownBy(() -> json.readValue(body, WorkingFileRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }
}
