/*
 * Copyright (c) 2022-2023 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.alibaba.higress.console;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.alibaba.higress.console.aop.ApiStandardizationAspect;
import com.alibaba.higress.console.config.SwaggerAccessConfig;
import com.alibaba.higress.console.config.SwaggerConfig;
import com.alibaba.higress.console.controller.ApiKeyController;
import com.alibaba.higress.console.controller.RoutesController;
import com.alibaba.higress.console.controller.ServiceSourceController;
import com.alibaba.higress.console.controller.SessionController;
import com.alibaba.higress.console.controller.WasmPluginInstancesController;
import com.alibaba.higress.console.controller.WasmPluginsController;
import com.alibaba.higress.console.controller.ai.AiRoutesController;
import com.alibaba.higress.console.controller.ai.LlmProvidersController;
import com.alibaba.higress.console.service.ApiKeyService;
import com.alibaba.higress.console.service.ConfigService;
import com.alibaba.higress.console.service.SessionServiceImpl;
import com.alibaba.higress.sdk.model.PaginatedResult;
import com.alibaba.higress.sdk.model.Route;
import com.alibaba.higress.sdk.model.WasmPlugin;
import com.alibaba.higress.sdk.service.DomainService;
import com.alibaba.higress.sdk.service.RouteService;
import com.alibaba.higress.sdk.service.ServiceService;
import com.alibaba.higress.sdk.service.ServiceSourceService;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.higress.sdk.service.WasmPluginService;
import com.alibaba.higress.sdk.service.ai.AiRouteService;
import com.alibaba.higress.sdk.service.ai.LlmProviderService;
import com.alibaba.higress.sdk.service.kubernetes.KubernetesClientService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kubernetes.client.openapi.models.V1Secret;

/**
 * Real MVC, Springdoc, authentication aspect and encrypted session cookies;
 * only infrastructure/service data is mocked. No real cluster is contacted.
 */
@SpringBootTest(classes = SwaggerAccessTests.TestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class SwaggerAccessTests {

    @Configuration
    @EnableAutoConfiguration
    @Import({WebMvcInitializer.class, SwaggerConfig.class, SwaggerAccessConfig.class,
        ApiStandardizationAspect.class, SessionServiceImpl.class, SessionController.class,
        ServiceSourceController.class, LlmProvidersController.class, AiRoutesController.class,
        RoutesController.class, WasmPluginsController.class, WasmPluginInstancesController.class,
        ApiKeyController.class})
    static class TestApplication {}

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate http;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @MockBean
    private KubernetesClientService kubernetes;
    @MockBean
    private ConfigService config;
    @MockBean
    private ServiceSourceService sources;
    @MockBean
    private LlmProviderService providers;
    @MockBean
    private AiRouteService aiRoutes;
    @MockBean
    private RouteService routes;
    @MockBean
    private WasmPluginService plugins;
    @MockBean
    private WasmPluginInstanceService instances;
    @MockBean
    private DomainService domains;
    @MockBean
    private ServiceService services;
    @MockBean
    private ApiKeyService keys;

    @BeforeEach
    void prepareInfrastructure() throws Exception {
        Map<String, byte[]> secret = new HashMap<>();
        secret.put("adminUsername", "swagger-test".getBytes(StandardCharsets.UTF_8));
        secret.put("adminPassword", "test-only-password".getBytes(StandardCharsets.UTF_8));
        secret.put("adminDisplayName", "Test".getBytes(StandardCharsets.UTF_8));
        secret.put("key", "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        secret.put("iv", "0123456789012345".getBytes(StandardCharsets.UTF_8));
        when(kubernetes.readSecret(anyString())).thenReturn(new V1Secret().data(secret));
        when(sources.list(any())).thenReturn(PaginatedResult.createFromFullList(Collections.emptyList(), null));
        when(providers.list(any())).thenReturn(PaginatedResult.createFromFullList(Collections.emptyList(), null));
        when(aiRoutes.list(any())).thenReturn(PaginatedResult.createFromFullList(Collections.emptyList(), null));
        when(routes.list(any())).thenReturn(PaginatedResult.createFromFullList(Collections.emptyList(), null));
        when(routes.query("existing-route")).thenReturn(Route.builder().name("existing-route").build());
        when(keys.listGroups(null)).thenReturn(Collections.emptyList());
    }

    private Cookie login() throws Exception {
        return mvc.perform(post("/session/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"swagger-test\",\"password\":\"test-only-password\"}"))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("swagger-test"))
            .andReturn().getResponse().getCookie("_hi_sess");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/v3/api-docs/Higress", "/v3/api-docs.yaml",
        "/v3/api-docs.yaml/Higress", "/v3/api-docs/swagger-config", "/swagger-ui.html",
        "/swagger-ui/index.html", "/swagger-ui/swagger-ui-bundle.js"})
    void documentationRequiresLogin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidCredentialsCannotReadDocumentation() throws Exception {
        mvc.perform(get("/v3/api-docs/Higress").header("Authorization", "Basic !!!"))
            .andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs/Higress").header("Authorization", "Basic "
            + Base64.getEncoder().encodeToString("wrong:password".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedUiAndAssetsAreAvailable() throws Exception {
        Cookie cookie = login();
        mvc.perform(get("/swagger-ui.html").cookie(cookie)).andExpect(status().is3xxRedirection());
        mvc.perform(get("/swagger-ui/index.html").cookie(cookie)).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mvc.perform(get("/swagger-ui/swagger-ui-bundle.js").cookie(cookie)).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/swagger-config").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.urls[0].url").value("/v3/api-docs/Higress"));
    }

    @Test
    void generatedContractIncludesCurrentControllersAndAuthentication() throws Exception {
        String body = mvc.perform(get("/v3/api-docs/Higress").cookie(login())).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.openapi").value("3.0.1"))
            .andExpect(jsonPath("$.info.version").isNotEmpty())
            .andReturn().getResponse().getContentAsString();
        JsonNode document = mapper.readTree(body);
        for (String path : new String[] {"/v1/service-sources", "/v1/ai/providers", "/v1/ai/routes",
            "/v1/routes", "/v1/api-keys/group/list", "/v1/wasm-plugins/{name}",
            "/v1/routes/{routeName}/plugin-instances/{name}"}) {
            assertThat(document.path("paths").has(path)).as(path).isTrue();
            assertThat(document.path("paths").path(path).path("get").path("security").size()).isEqualTo(2);
        }
        assertThat(document.path("paths").path("/session/login").path("post").has("security")).isFalse();
        assertThat(document.path("paths").path("/session/login").path("post").path("responses")
            .path("201").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/User");
        assertThat(document.path("components").path("securitySchemes").path("basicAuth").path("scheme").asText())
            .isEqualTo("basic");
        assertThat(document.path("components").path("securitySchemes").path("sessionCookie").path("name").asText())
            .isEqualTo("_hi_sess");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v1/service-sources", "/v1/ai/providers", "/v1/ai/routes", "/v1/routes",
        "/v1/api-keys/group/list", "/v1/routes/existing-route/plugin-instances/user-balance-check"})
    void businessQueriesStillRequireSession(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).cookie(login())).andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void embeddedHttpServerServesAuthenticatedOpenApi() throws Exception {
        assertThat(http.getForEntity("/v3/api-docs/Higress", String.class).getStatusCodeValue()).isEqualTo(401);
        ResponseEntity<String> response = http.withBasicAuth("swagger-test", "test-only-password")
            .getForEntity("/v3/api-docs/Higress", String.class);
        assertThat(response.getStatusCodeValue()).isEqualTo(200);
        assertThat(mapper.readTree(response.getBody()).path("paths").has("/v1/api-keys/group/list")).isTrue();
    }

    @Test
    @EnabledIfSystemProperty(named = "higress.openapi.smoke", matches = "true")
    void readOnlySmokeScriptRunsAgainstEmbeddedHttpServer(@TempDir Path temporary) throws Exception {
        when(plugins.query("user-balance-check", null))
            .thenReturn(WasmPlugin.builder().name("user-balance-check").build());
        Path auth = temporary.resolve("auth.json");
        Files.write(auth, "{\"username\":\"swagger-test\",\"password\":\"test-only-password\"}"
            .getBytes(StandardCharsets.UTF_8));
        Path result = Paths.get("target/openapi-smoke-local.json");
        Process process = new ProcessBuilder("python3", "../../scripts/verify-openapi.py", "--base-url",
            "http://127.0.0.1:" + port, "--auth-file", auth.toString(), "--route", "existing-route",
            "--output", result.toString()).redirectErrorStream(true)
                .redirectOutput(temporary.resolve("smoke.log").toFile()).start();
        try {
            assertThat(process.waitFor(45, TimeUnit.SECONDS)).as("smoke process finished").isTrue();
            assertThat(process.exitValue()).as("smoke exit code").isZero();
            assertThat(mapper.readTree(result.toFile()).path("result").asText()).isEqualTo("passed");
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly().waitFor();
            }
            Files.deleteIfExists(auth);
        }
    }

    @Test
    void basicAuthenticationAndLogoutStillWork() throws Exception {
        mvc.perform(get("/v3/api-docs/Higress").header("Authorization", "Basic "
            + Base64.getEncoder().encodeToString("swagger-test:test-only-password".getBytes(StandardCharsets.UTF_8))))
            .andExpect(status().isOk());
        Cookie cookie = mvc.perform(get("/session/logout").cookie(login())).andExpect(status().isOk())
            .andReturn().getResponse().getCookie("_hi_sess");
        assertThat(cookie.getMaxAge()).isZero();
        mvc.perform(post("/session/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"username\":\"swagger-test\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized());
    }
}
