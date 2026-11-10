package com.devlink.server.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.devlink.server.dto.gitcollection.GitCommitBatchRequest;
import com.devlink.server.dto.gitcollection.WorkingFileRequest;
import com.devlink.server.dto.gitcollection.WorkingFileRequest.FileInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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

/** Tests MVC body-size handling; production authentication and persistence are not connected yet. */
@SpringJUnitConfig(WorkingFileBodyAdviceTest.MvcConfig.class)
@WebAppConfiguration
class WorkingFileBodyAdviceTest {
    private static final int LIMIT = 2_097_152;
    private static final String PATH = "/test/working-files-body";
    private final ObjectMapper json = Jackson2ObjectMapperBuilder.json().build();

    @Autowired private WebApplicationContext context;
    @Autowired private ProbeController controller;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        controller.received = null;
        controller.calls = 0;
    }

    @Test
    void discoversWorkingAndCommitAdviceTogether() {
        assertThat(context.getBeansOfType(WorkingFileBodyAdvice.class)).hasSize(1);
        assertThat(context.getBeansOfType(GitCommitBatchBodyAdvice.class)).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {131_073, 2_097_151, 2_097_152})
    void acceptsWorkingBodiesAboveCommitLimitAndUpToTwoMiB(int size) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(payload(size, "src/App.tsx")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branch").value("feature/login"))
                .andExpect(jsonPath("$.fileCount").value(1));
        assertThat(controller.calls).isEqualTo(1);
        assertThat(controller.received.files().getFirst().relativePath()).isEqualTo("src/App.tsx");
    }

    @ParameterizedTest
    @ValueSource(ints = {2_097_153, 2_098_176})
    void rejectsOversizedBodyBeforeControllerInvocation(int size) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(payload(size, "src/App.tsx")))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(controller.calls).isZero();
        assertThat(controller.received).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {2_097_152, 2_097_153})
    void measuresUtf8BytesRatherThanStringLength(int size) throws Exception {
        String path = "src/" + "한".repeat(100) + "😀.tsx";
        byte[] bytes = payload(size, path);
        assertThat(new String(bytes, StandardCharsets.UTF_8).length()).isLessThan(LIMIT);

        var result = mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(bytes));
        if (size == LIMIT) {
            result.andExpect(status().isOk());
            assertThat(controller.received.files().getFirst().relativePath()).isEqualTo(path);
        } else {
            result.andExpect(status().isPayloadTooLarge())
                    .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
            assertThat(controller.calls).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"src/quote\"name.tsx", "src/back\\slash.tsx"})
    void countsSerializedEscapeBytesAndPreservesDecodedContent(String path) throws Exception {
        // Only the body-reading stage is tested here; the service validates path syntax separately.
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(payload(LIMIT, path)))
                .andExpect(status().isOk());
        assertThat(controller.received.files().getFirst().relativePath()).isEqualTo(path);
    }

    @Test
    void doesNotApplyWorkingLimitToUnrelatedDto() throws Exception {
        String text = "x".repeat(LIMIT + 1);
        mvc.perform(post("/test/working-other-body").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(new OtherRequest(text))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length").value(text.length()));
        assertThat(controller.calls).isZero();
    }

    @Test
    void retains128KiBLimitForCommitRequests() throws Exception {
        String body = """
                {"repoKey":"%s","commits":[]}
                """.formatted("a".repeat(64));
        body += " ".repeat(131_073 - body.getBytes(StandardCharsets.UTF_8).length);
        mvc.perform(post("/test/working-commit-body").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(controller.calls).isZero();
    }

    @Test
    void rejectsOversizedMalformedJsonBeforeParsing() throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("x".repeat(LIMIT + 1)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
        assertThat(controller.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "null", "{", "[]"})
    void preserves400ForMissingOrMalformedBodyWithinLimit(String body) throws Exception {
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        assertThat(controller.calls).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {2_097_152, 2_097_153})
    void checksActualBytesWithoutContentLengthAndPreservesHeaders(int size) throws Exception {
        byte[] bytes = payload(size, "src/App.tsx");
        ByteArrayInputStream source = new ByteArrayInputStream(bytes);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Test", "preserved");
        HttpInputMessage input = input(source, headers);
        var advice = context.getBean(WorkingFileBodyAdvice.class);
        assertThat(headers.getContentLength()).isEqualTo(-1);

        if (size == LIMIT) {
            HttpInputMessage result = advice.beforeBodyRead(input, parameter(), WorkingFileRequest.class,
                    MappingJackson2HttpMessageConverter.class);
            assertThat(result.getBody().readAllBytes()).isEqualTo(bytes);
            assertThat(result.getHeaders()).isSameAs(headers);
            assertThat(result.getHeaders().getFirst("X-Test")).isEqualTo("preserved");
            assertThat(result.getHeaders().getContentLength()).isEqualTo(-1);
        } else {
            BusinessException exception = assertThrows(BusinessException.class,
                    () -> advice.beforeBodyRead(input, parameter(), WorkingFileRequest.class,
                            MappingJackson2HttpMessageConverter.class));
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        }
    }

    @Test
    void readsAtMostLimitPlusOneByteEvenWithMisleadingLengthHeader() throws Exception {
        ByteArrayInputStream source = new ByteArrayInputStream(new byte[LIMIT * 2]);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setContentLength(1);
        var advice = context.getBean(WorkingFileBodyAdvice.class);
        BusinessException exception = assertThrows(BusinessException.class,
                () -> advice.beforeBodyRead(input(source, headers), parameter(), WorkingFileRequest.class,
                        MappingJackson2HttpMessageConverter.class));
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
        assertThat(source.available()).isEqualTo(LIMIT - 1);
    }

    private MethodParameter parameter() throws NoSuchMethodException {
        return new MethodParameter(ProbeController.class.getDeclaredMethod("upload", WorkingFileRequest.class), 0);
    }

    private HttpInputMessage input(InputStream source, HttpHeaders headers) {
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return source; }
            @Override public HttpHeaders getHeaders() { return headers; }
        };
    }

    private byte[] payload(int size, String path) throws JsonProcessingException {
        var request = new WorkingFileRequest("feature/login",
                List.of(new FileInput(path, WorkingFileChangeType.MODIFIED)));
        String body = json.writeValueAsString(request);
        int padding = size - body.getBytes(StandardCharsets.UTF_8).length;
        byte[] bytes = (body + " ".repeat(padding)).getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(size);
        return bytes;
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @ComponentScan(basePackageClasses = WorkingFileBodyAdvice.class, useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ANNOTATION,
                    classes = RestControllerAdvice.class))
    static class MvcConfig {
        @Bean ProbeController probeController() { return new ProbeController(); }
    }

    @RestController
    static class ProbeController {
        private WorkingFileRequest received;
        private int calls;

        @PostMapping(PATH)
        public Map<String, Object> upload(@RequestBody WorkingFileRequest request) {
            received = request;
            calls++;
            return Map.of("branch", request.branch(), "fileCount", request.files().size());
        }

        @PostMapping("/test/working-other-body")
        public Map<String, Integer> other(@RequestBody OtherRequest request) {
            return Map.of("length", request.text().length());
        }

        @PostMapping("/test/working-commit-body")
        public Map<String, Integer> commits(@RequestBody GitCommitBatchRequest request) {
            calls++;
            return Map.of("commitCount", request.commits().size());
        }
    }

    record OtherRequest(String text) {}
}
