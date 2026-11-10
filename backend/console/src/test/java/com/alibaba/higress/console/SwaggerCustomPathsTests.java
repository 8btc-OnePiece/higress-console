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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import com.alibaba.higress.console.model.User;
import com.alibaba.higress.console.service.ApiKeyService;
import com.alibaba.higress.console.service.ConfigService;
import com.alibaba.higress.console.service.SessionService;
import com.alibaba.higress.sdk.service.DomainService;
import com.alibaba.higress.sdk.service.RouteService;
import com.alibaba.higress.sdk.service.ServiceService;
import com.alibaba.higress.sdk.service.ServiceSourceService;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.higress.sdk.service.WasmPluginService;
import com.alibaba.higress.sdk.service.ai.AiRouteService;
import com.alibaba.higress.sdk.service.ai.LlmProviderService;
import com.alibaba.higress.sdk.service.kubernetes.KubernetesClientService;

@SpringBootTest(classes = SwaggerAccessTests.TestApplication.class,
    properties = {"springdoc.api-docs.path=/docs/spec", "springdoc.swagger-ui.path=/docs/ui.html"})
@AutoConfigureMockMvc
@MockBean({KubernetesClientService.class, ConfigService.class, ApiKeyService.class, DomainService.class,
    RouteService.class, ServiceService.class, ServiceSourceService.class, WasmPluginInstanceService.class,
    WasmPluginService.class, AiRouteService.class, LlmProviderService.class})
class SwaggerCustomPathsTests {

    @Autowired
    private MockMvc mvc;
    @MockBean
    private SessionService sessions;

    @ParameterizedTest
    @ValueSource(strings = {"/docs/spec", "/docs/spec/Higress", "/docs/spec.yaml/Higress",
        "/docs/ui.html", "/docs/swagger-ui/index.html"})
    void customDocumentationPathsRequireAuthentication(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        when(sessions.validateSession(any())).thenReturn(User.builder().name("test").build());
        if (path.endsWith("ui.html")) {
            mvc.perform(get(path)).andExpect(status().is3xxRedirection());
        } else {
            mvc.perform(get(path)).andExpect(status().isOk());
        }
    }
}
