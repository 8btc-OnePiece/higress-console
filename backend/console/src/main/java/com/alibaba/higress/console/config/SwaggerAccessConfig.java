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
package com.alibaba.higress.console.config;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.alibaba.higress.console.controller.dto.Response;
import com.alibaba.higress.console.service.SessionService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Springdoc handlers are outside the business controller authentication aspect.
 *
 * @author Higress Authors
 */
@Configuration
public class SwaggerAccessConfig implements WebMvcConfigurer {

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    @Value("${springdoc.api-docs.path:/v3/api-docs}")
    private String apiDocsPath;

    @Value("${springdoc.swagger-ui.path:/swagger-ui.html}")
    private String swaggerUiPath;

    @Value("${springdoc.api-docs.enabled:true}")
    private boolean apiDocsEnabled;

    @Value("${springdoc.swagger-ui.enabled:true}")
    private boolean swaggerUiEnabled;

    public SwaggerAccessConfig(SessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(documentationInterceptor(apiDocsEnabled))
            .addPathPatterns(apiDocsPath, apiDocsPath + "/**", apiDocsPath + ".yaml", apiDocsPath + ".yaml/**");
        registry.addInterceptor(documentationInterceptor(apiDocsEnabled && swaggerUiEnabled))
            .addPathPatterns(swaggerUiPath, "/swagger-ui/**", "/webjars/swagger-ui/**",
                swaggerUiPath.substring(0, swaggerUiPath.lastIndexOf('/')) + "/swagger-ui/**");
    }

    private HandlerInterceptor documentationInterceptor(boolean enabled) {
        return new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
                throws Exception {
                response.setHeader("Cache-Control", "no-store");
                if (!enabled) {
                    // Do not let the console SPA fallback masquerade as an available documentation endpoint.
                    response.setStatus(HttpStatus.NOT_FOUND.value());
                    return false;
                }
                try {
                    if (sessionService.validateSession(request) != null) {
                        return true;
                    }
                } catch (IllegalArgumentException e) {
                    // A malformed Basic header is not an authenticated session.
                }
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                objectMapper.writeValue(response.getOutputStream(), Response.failure("Login required."));
                return false;
            }
        };
    }
}
