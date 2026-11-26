package com.alibaba.higress.sdk.service.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.alibaba.higress.sdk.exception.BusinessException;
import com.alibaba.higress.sdk.exception.ResourceConflictException;
import com.alibaba.higress.sdk.model.WasmPluginInstance;
import com.alibaba.higress.sdk.model.ai.AiRoute;
import com.alibaba.higress.sdk.model.ai.AiUpstream;
import com.alibaba.higress.sdk.model.ai.AiRouteFallbackConfig;
import com.alibaba.higress.sdk.model.route.UpstreamService;
import com.alibaba.higress.sdk.service.RouteService;
import com.alibaba.higress.sdk.service.WasmPluginInstanceService;
import com.alibaba.higress.sdk.service.kubernetes.KubernetesClientService;
import com.alibaba.higress.sdk.service.kubernetes.KubernetesModelConverter;
import com.alibaba.higress.sdk.service.kubernetes.crd.istio.V1alpha3EnvoyFilter;

import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1Ingress;
import io.kubernetes.client.openapi.models.V1ObjectMeta;

class AiRouteStatusTest {
    private KubernetesClientService kube;
    private KubernetesModelConverter converter;
    private RouteService routes;
    private WasmPluginInstanceService plugins;
    private LlmProviderService providers;
    private AiRouteServiceImpl service;
    private Map<String, V1ConfigMap> maps;
    private AtomicInteger version;

    @BeforeEach
    void setup() throws Exception {
        kube = mock(KubernetesClientService.class);
        converter = new KubernetesModelConverter(kube);
        routes = mock(RouteService.class);
        plugins = mock(WasmPluginInstanceService.class);
        providers = mock(LlmProviderService.class);
        V1alpha3EnvoyFilter filter = new V1alpha3EnvoyFilter();
        filter.setMetadata(new V1ObjectMeta().name("ai-route-model-a.internal"));
        when(kube.loadFromYaml(anyString(), eq(V1alpha3EnvoyFilter.class))).thenReturn(filter);
        when(plugins.createEmptyInstance(anyString())).thenAnswer(i -> new WasmPluginInstance());
        when(providers.buildUpstreamService(anyString()))
            .thenAnswer(i -> { UpstreamService u = new UpstreamService(); u.setName("provider.dns"); return u; });
        maps = new HashMap<>();
        version = new AtomicInteger(1);
        when(kube.readConfigMap(anyString())).thenAnswer(i -> maps.get(i.getArgument(0)));
        when(kube.createConfigMap(any())).thenAnswer(i -> {
            V1ConfigMap cm = i.getArgument(0);
            if (maps.containsKey(cm.getMetadata().getName())) { throw new ApiException(409, "exists"); }
            cm.getMetadata().setResourceVersion(String.valueOf(version.incrementAndGet()));
            maps.put(cm.getMetadata().getName(), cm);
            return cm;
        });
        when(kube.replaceConfigMap(any())).thenAnswer(i -> {
            V1ConfigMap cm = i.getArgument(0);
            V1ConfigMap old = maps.get(cm.getMetadata().getName());
            if (!old.getMetadata().getResourceVersion().equals(cm.getMetadata().getResourceVersion())) {
                throw new ApiException(409, "stale");
            }
            cm.getMetadata().setResourceVersion(String.valueOf(version.incrementAndGet()));
            maps.put(cm.getMetadata().getName(), cm);
            return cm;
        });
        doAnswer(i -> { maps.remove(i.getArgument(0)); return null; }).when(kube).deleteConfigMap(anyString());
        service = new AiRouteServiceImpl(converter, kube, routes, providers, plugins);
        AiRoute route = new AiRoute();
        route.setName("model-a"); route.setVersion("1");
        AiUpstream upstream = new AiUpstream(); upstream.setProvider("provider"); upstream.setWeight(100);
        route.setUpstreams(Collections.singletonList(upstream));
        maps.put("ai-route-model-a", converter.aiRoute2ConfigMap(route));
    }

    @Test
    void legacyRoutesAreEnabled() {
        assertEquals(true, service.query("model-a").getEnabled());
    }

    @Test
    void disableRetainsDefinitionPluginsAndFallbackConfiguration() throws Exception {
        AiRoute disabled = service.setEnabled("model-a", "1", false);
        assertEquals(false, disabled.getEnabled());
        assertEquals(false, service.query("model-a").getEnabled());
        assertEquals(1, maps.size());
        verify(kube).deleteIngress("ai-route-model-a.internal");
        verify(kube).deleteIngress("ai-route-model-a.fallback.internal");
        verifyNoInteractions(routes, plugins, providers);
        verify(kube, never()).deleteEnvoyFilter(anyString());
    }

    @Test
    void oldClientEditDoesNotReactivateDisabledRoute() {
        AiRoute disabled = service.setEnabled("model-a", "1", false);
        disabled.setEnabled(null);
        disabled.setDomains(Collections.singletonList("changed.example.com"));
        AiRoute edited = service.update(disabled);
        assertEquals(false, edited.getEnabled());
        assertEquals("changed.example.com", edited.getDomains().get(0));
        verifyNoInteractions(routes, plugins, providers);
    }

    @Test
    void enablingRecreatesForwarding() {
        AiRoute disabled = service.setEnabled("model-a", "1", false);
        AiRoute enabled = service.setEnabled("model-a", disabled.getVersion(), true);
        assertEquals(true, enabled.getEnabled());
        verify(routes).add(argThat(r -> "ai-route-model-a.internal".equals(r.getName())));
    }

    @Test
    void disableAndEnableRetainAndRestoreFallback() throws Exception {
        AiRoute original = service.query("model-a");
        AiRouteFallbackConfig fallback = new AiRouteFallbackConfig();
        fallback.setEnabled(true);
        fallback.setUpstreams(original.getUpstreams());
        fallback.setResponseCodes(Collections.singletonList("5xx"));
        original.setFallbackConfig(fallback);
        maps.put("ai-route-model-a", converter.aiRoute2ConfigMap(original));
        AiRoute disabled = service.setEnabled("model-a", "1", false);
        assertTrue(disabled.getFallbackConfig().getEnabled());
        verifyNoInteractions(routes, plugins);
        service.setEnabled("model-a", disabled.getVersion(), true);
        verify(routes).add(argThat(r -> "ai-route-model-a.internal".equals(r.getName())));
        verify(routes).add(argThat(r -> "ai-route-model-a.fallback.internal".equals(r.getName())));
        verify(kube).createEnvoyFilter(any());
    }

    @Test
    void staleVersionDoesNotTouchForwarding() throws Exception {
        assertThrows(ResourceConflictException.class, () -> service.setEnabled("model-a", "stale", false));
        verify(kube, never()).deleteIngress(anyString());
        verifyNoInteractions(routes, plugins);
        assertEquals(1, maps.size());
    }

    @Test
    void duplicateAddDoesNotTouchExistingResources() {
        assertThrows(ResourceConflictException.class, () -> service.add(service.query("model-a")));
        verifyNoInteractions(routes, plugins);
    }

    @Test
    void failureDoesNotReportDisabledAndCanBeRetried() throws Exception {
        doThrow(new ApiException(503, "unavailable")).doNothing()
            .when(kube).deleteIngress("ai-route-model-a.fallback.internal");
        assertThrows(BusinessException.class, () -> service.setEnabled("model-a", "1", false));
        assertTrue(service.query("model-a").getEnabled());
        assertFalse(service.setEnabled("model-a", "1", false).getEnabled());
    }

    @Test
    void asynchronousDeletionIsNotReportedAsDisabled() throws Exception {
        when(kube.readIngress("ai-route-model-a.internal")).thenReturn(new V1Ingress());
        assertThrows(BusinessException.class, () -> service.setEnabled("model-a", "1", false));
        assertTrue(service.query("model-a").getEnabled());
    }

    @Test
    void failedEnableRemovesPartiallyCreatedForwarding() throws Exception {
        AiRoute disabled = service.setEnabled("model-a", "1", false);
        clearInvocations(kube);
        doThrow(new RuntimeException("partial failure")).when(routes).add(any());
        assertThrows(RuntimeException.class, () -> service.setEnabled("model-a", disabled.getVersion(), true));
        assertFalse(service.query("model-a").getEnabled());
        verify(kube).deleteIngress("ai-route-model-a.internal");
        verify(kube).deleteIngress("ai-route-model-a.fallback.internal");
    }

    @Test
    void lockExcludesAnotherConsoleInstanceAndIsNotStolen() throws Exception {
        AiRouteServiceImpl other = new AiRouteServiceImpl(converter, kube, routes, providers, plugins);
        doAnswer(i -> {
            assertThrows(ResourceConflictException.class, () -> other.setEnabled("model-a", "1", true));
            return null;
        }).when(kube).deleteIngress("ai-route-model-a.internal");
        assertFalse(service.setEnabled("model-a", "1", false).getEnabled());
        assertEquals(1, maps.size());
    }
}
