package com.devlink.server.handler;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.CommitInput;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest.FileInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tests the MVC body-reading stage using test-only endpoints, without DB or Agent authentication. */
@SpringJUnitConfig(GitCommitBatchBodyAdviceTest.MvcConfig.class)
@WebAppConfiguration
class GitCommitBatchBodyAdviceTest {
    private static final int LIMIT = 131_072;
    private static final String PATH = "/test/git-commits-batch";
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProbeController controller;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        controller.received = null;
        controller.calls = 0;
    }

    @Test
    void discoversAdviceThroughItsAnnotation() {
        assertThat(context.getBeansOfType(GitCommitBatchBodyAdvice.class)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {131_071, 131_072})
    void acceptsBodiesUpToTheExactLimitAndPreservesContent(int size) throws Exception {
        byte[] body = payload(size, "src/Auth.java");

        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repoKey").value("a".repeat(64)))
                .andExpect(jsonPath("$.commitCount").value(1));

        assertThat(controller.calls).isEqualTo(1);
        assertThat(controller.received.commits().getFirst().files().getFirst().relativePath())
                .isEqualTo("src/Auth.java");
    }

    @ParameterizedTest
    @ValueSource(ints = {131_073, 132_096})
    void rejectsOversizedBodiesBeforeCallingController(int size) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(payload(size, "src/Auth.java")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(controller.calls).isZero();
        assertThat(controller.received).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {131_072, 131_073})
    void measuresUtf8BytesRatherThanCharacterCount(int size) throws Exception {
        String path = "src/" + "한".repeat(100) + ".java";
        byte[] body = payload(size, path);
        assertThat(new String(body, StandardCharsets.UTF_8).length()).isLessThan(LIMIT);

        var action = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));

        if (size <= LIMIT) {
            action.andExpect(status().isOk());
            assertThat(controller.received.commits().getFirst().files().getFirst().relativePath())
                    .isEqualTo(path);
        } else {
            action.andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
            assertThat(controller.calls).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/quote\"name.java", "src/tab\tname.java"})
    void countsEscapedJsonBytesAndPreservesDecodedPath(String path) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(payload(LIMIT, path)))
                .andExpect(status().isOk());

        assertThat(controller.received.commits().getFirst().files().getFirst().relativePath())
                .isEqualTo(path);
    }

    @Test
    void leavesUnrelatedRequestTypesOutsideThisLimit() throws Exception {
        String text = "x".repeat(LIMIT + 1);
        byte[] body = json.writeValueAsBytes(new OtherRequest(text));

        mvc.perform(post("/test/other-body").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length").value(text.length()));

        assertThat(controller.calls).isZero();
    }

    @Test
    void rejectsOversizedMalformedJsonWith413BeforeParsing() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("x".repeat(LIMIT + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

        assertThat(controller.calls).isZero();
    }

    @Test
    void preserves400ForMalformedJsonWithinLimit() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(controller.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {131_072, 131_073})
    void checksActualStreamBytesWhenContentLengthIsAbsent(int size) throws Exception {
        byte[] bytes = payload(size, "src/Auth.java");
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpInputMessage input = new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return source;
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
        var parameter = new MethodParameter(
                ProbeController.class.getDeclaredMethod("upload", GitCommitBatchRequest.class), 0);
        var advice = context.getBean(GitCommitBatchBodyAdvice.class);
        assertThat(headers.getContentLength()).isEqualTo(-1);

        if (size <= LIMIT) {
            HttpInputMessage result = advice.beforeBodyRead(input, parameter, GitCommitBatchRequest.class,
                    MappingJackson2HttpMessageConverter.class);
            assertThat(result.getBody().readAllBytes()).isEqualTo(bytes);
            assertThat(result.getHeaders().getContentLength()).isEqualTo(-1);
        } else {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> advice.beforeBodyRead(input, parameter, GitCommitBatchRequest.class,
                            MappingJackson2HttpMessageConverter.class));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        }
    }

    @Test
    void stopsReadingAfterOneByteBeyondLimitEvenWithIncorrectLengthHeader() throws Exception {
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[LIMIT * 2]);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentLength(1);
        HttpInputMessage input = new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return source;
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
        var parameter = new MethodParameter(
                ProbeController.class.getDeclaredMethod("upload", GitCommitBatchRequest.class), 0);
        var advice = context.getBean(GitCommitBatchBodyAdvice.class);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> advice.beforeBodyRead(input, parameter, GitCommitBatchRequest.class,
                        MappingJackson2HttpMessageConverter.class));

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(source.available()).isEqualTo(LIMIT - 1);
    }

    private byte[] payload(int size, String path) throws JsonProcessingException {
        var request = new GitCommitBatchRequest("a".repeat(64),
                List.of(new CommitInput("1".repeat(40), "2026-11-09T01:00:00Z", 2L,
                        List.of(new FileInput(path, CommitChangeType.MODIFIED)))));
        String body = json.writeValueAsString(request);
        int padding = size - body.getBytes(StandardCharsets.UTF_8).length;
        if (padding < 0) {
            throw new IllegalArgumentException("Requested test payload is too small");
        }
        byte[] bytes = (body + " ".repeat(padding)).getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(size);
        return bytes;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @ComponentScan(basePackageClasses = GitCommitBatchBodyAdvice.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
                    classes = RestControllerAdvice.class))
    static class MvcConfig {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }
    }

    @RestController
    static class ProbeController {
        private GitCommitBatchRequest received;
        private int calls;

        @PostMapping(PATH)
        public Map<String, Object> upload(@Valid @RequestBody GitCommitBatchRequest request) {
            received = request;
            calls++;
            return Map.of("repoKey", request.repoKey(), "commitCount", request.commits().size());
        }

        @PostMapping("/test/other-body")
        public Map<String, Integer> other(@RequestBody OtherRequest request) {
            return Map.of("length", request.text().length());
        }
    }

    record OtherRequest(String text) {
    }
}
