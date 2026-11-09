package com.devlink.server.dto.gitcollection;

import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitCommitBatchRequestJsonTest {

    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @ParameterizedTest
    @ValueSource(strings = {"2", "9223372036854775807"})
    void acceptsInteger(String value) throws Exception {
        var request = json.readValue(body(value), GitCommitBatchRequest.class);

        assertThat(request.commits().getFirst().authorUserId())
                .isEqualTo(Long.valueOf(value));
    }

    @Test
    void acceptsExplicitNull() throws Exception {
        var request = json.readValue(body("null"), GitCommitBatchRequest.class);

        assertThat(request.commits().getFirst().authorUserId()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"2\"",
            "2.0",
            "2.5",
            "2e0",
            "true",
            "false",
            "[]",
            "{}",
            "9223372036854775808",
            "-9223372036854775809"
    })
    void rejectsInvalidTypeOrOverflow(String value) {
        assertThatThrownBy(() -> json.readValue(body(value), GitCommitBatchRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void rejectsMissingAuthorUserId() {
        assertThatThrownBy(() -> json.readValue(body(null), GitCommitBatchRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @ParameterizedTest
    @EnumSource(CommitChangeType.class)
    void acceptsDefinedChangeTypeNames(CommitChangeType changeType) throws Exception {
        ObjectNode input = bodyWithFile();
        ((ObjectNode) input.at("/commits/0/files/0")).put("changeType", changeType.name());

        var request = json.readValue(input.toString(), GitCommitBatchRequest.class);

        assertThat(request.commits().getFirst().files().getFirst().changeType())
                .isSameAs(changeType);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void rejectsNumericChangeType(int value) throws Exception {
        ObjectNode input = bodyWithFile();
        ((ObjectNode) input.at("/commits/0/files/0")).put("changeType", value);

        assertThatThrownBy(() -> json.readValue(input.toString(), GitCommitBatchRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "2", "3"})
    void rejectsNumericStringChangeType(String value) throws Exception {
        ObjectNode input = bodyWithFile();
        ((ObjectNode) input.at("/commits/0/files/0")).put("changeType", value);

        assertThatThrownBy(() -> json.readValue(input.toString(), GitCommitBatchRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"modified", "UNKNOWN"})
    void rejectsUndefinedChangeTypeNames(String value) throws Exception {
        ObjectNode input = bodyWithFile();
        ((ObjectNode) input.at("/commits/0/files/0")).put("changeType", value);

        assertThatThrownBy(() -> json.readValue(input.toString(), GitCommitBatchRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @ParameterizedTest
    @CsvSource({
            "request, originUrl",
            "commit, authorEmail",
            "file, diff"
    })
    void rejectsUnknownFieldsAtEveryLevel(String level, String field) throws Exception {
        ObjectNode input = bodyWithFile();
        ObjectNode target = switch (level) {
            case "request" -> input;
            case "commit" -> (ObjectNode) input.at("/commits/0");
            case "file" -> (ObjectNode) input.at("/commits/0/files/0");
            default -> throw new IllegalArgumentException(level);
        };
        target.put(field, "unexpected");

        assertThatThrownBy(() -> json.readValue(input.toString(), GitCommitBatchRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    private ObjectNode bodyWithFile() throws JsonProcessingException {
        ObjectNode input = (ObjectNode) json.readTree(body("2"));
        ObjectNode commit = (ObjectNode) input.at("/commits/0");
        commit.withArray("files").addObject()
                .put("relativePath", "src/Auth.java")
                .put("changeType", "MODIFIED");
        return input;
    }

    private static String body(String authorValue) {
        String authorField = authorValue == null
                ? ""
                : "\"authorUserId\": " + authorValue + ",";

        return """
                {
                  "repoKey": "%s",
                  "commits": [
                    {
                      "commitSha": "%s",
                      "committedAt": "2026-11-09T01:00:00Z",
                      %s
                      "files": []
                    }
                  ]
                }
                """.formatted(
                        "a".repeat(64),
                        "1".repeat(40),
                        authorField);
    }
}
