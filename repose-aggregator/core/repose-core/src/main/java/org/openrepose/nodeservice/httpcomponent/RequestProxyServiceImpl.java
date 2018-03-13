/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package org.openrepose.nodeservice.httpcomponent;

import com.google.common.base.Throwables;
import io.opentracing.ActiveSpan;
import io.opentracing.tag.Tags;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.*;
import org.apache.http.client.utils.URIUtils;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.util.EntityUtils;
import org.openrepose.commons.config.manager.UpdateListener;
import org.openrepose.commons.utils.StringUriUtilities;
import org.openrepose.commons.utils.http.CommonHttpHeader;
import org.openrepose.commons.utils.http.ServiceClientResponse;
import org.openrepose.commons.utils.io.RawInputStreamReader;
import org.openrepose.commons.utils.io.stream.ReadLimitReachedException;
import org.openrepose.commons.utils.logging.TracingHeaderHelper;
import org.openrepose.commons.utils.logging.TracingKey;
import org.openrepose.core.filter.SystemModelInterrogator;
import org.openrepose.core.proxy.HttpException;
import org.openrepose.core.services.RequestProxyService;
import org.openrepose.core.services.config.ConfigurationService;
import org.openrepose.core.services.healthcheck.HealthCheckService;
import org.openrepose.core.services.healthcheck.HealthCheckServiceProxy;
import org.openrepose.core.services.healthcheck.Severity;
import org.openrepose.core.services.httpclient.HttpClientContainer;
import org.openrepose.core.services.httpclient.HttpClientService;
import org.openrepose.core.services.opentracing.OpenTracingService;
import org.openrepose.core.spring.ReposeSpringProperties;
import org.openrepose.core.systemmodel.config.ReposeCluster;
import org.openrepose.core.systemmodel.config.SystemModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

@Named
@Lazy
public class RequestProxyServiceImpl implements RequestProxyService {

    public static final String SYSTEM_MODEL_CONFIG_HEALTH_REPORT = "SystemModelConfigError";
    private static final Logger LOG = LoggerFactory.getLogger(RequestProxyServiceImpl.class);
    private static final String CHUNKED_ENCODING_PARAM = "chunked-encoding";

    private final ConfigurationService configurationService;
    private final SystemModelListener systemModelListener;
    private final String clusterId;
    private final String nodeId;
    private final HttpClientService httpClientService;
    private final OpenTracingService openTracingService;
    private final HealthCheckServiceProxy healthCheckServiceProxy;
    private boolean rewriteHostHeader = false;

    @Inject
    public RequestProxyServiceImpl(ConfigurationService configurationService,
                                   HealthCheckService healthCheckService,
                                   HttpClientService httpClientService,
                                   OpenTracingService openTracingService,
                                   @Value(ReposeSpringProperties.NODE.CLUSTER_ID) String clusterId,
                                   @Value(ReposeSpringProperties.NODE.NODE_ID) String nodeId) {

        this.configurationService = configurationService;
        this.httpClientService = httpClientService;
        this.openTracingService = openTracingService;
        this.clusterId = clusterId;
        this.nodeId = nodeId;

        this.systemModelListener = new SystemModelListener();
        healthCheckServiceProxy = healthCheckService.register();

    }

    @PostConstruct
    public void init() {
        configurationService.subscribeTo("system-model.cfg.xml", systemModelListener, SystemModel.class);
    }

    @PreDestroy
    public void destroy() {
        healthCheckServiceProxy.deregister();
        configurationService.unsubscribeFrom("system-model.cfg.xml", systemModelListener);
    }

    private HttpHost getProxiedHost(String targetHost) throws HttpException {
        try {
            return URIUtils.extractHost(new URI(targetHost));
        } catch (URISyntaxException ex) {
            LOG.error("Invalid target host url: " + targetHost, ex);
        }

        throw new HttpException("Invalid target host");
    }

    private HttpClientContainer getClient(String clientId) {
        return httpClientService.getClient(clientId);
    }

    @Override
    public int proxyRequest(String targetHost, HttpServletRequest request, HttpServletResponse response) throws IOException {
        return proxyRequest(targetHost, request, response, null);
    }

    @Override
    public int proxyRequest(String targetHost, HttpServletRequest request, HttpServletResponse response, String connPoolId) throws IOException {
        HttpClientContainer httpClientContainer = getClient(connPoolId);

        try {
            final String chunkedEncoding = (String) httpClientContainer.getHttpClient().getParams().getParameter(CHUNKED_ENCODING_PARAM);
            final HttpHost proxiedHost = getProxiedHost(targetHost);
            final String target = proxiedHost.toURI() + request.getRequestURI();
            final HttpComponentRequestProcessor processor = new HttpComponentRequestProcessor(request, new URI(proxiedHost.toURI()), rewriteHostHeader, chunkedEncoding);
            final HttpComponentProcessableRequest method = HttpComponentFactory.getMethod(request.getMethod(), processor.getUri(target));

            if (method != null) {
                HttpRequestBase processedMethod = method.process(processor);
                LOG.info("DIMA do stuff on open tracing service" + openTracingService);

                if (openTracingService.isEnabled()) {
                    LOG.info("DIMA IS ABOUT TO GO CRAZY");

                    // check that the span didn't start yet
                    ActiveSpan activeSpan = openTracingService.getGlobalTracer().activeSpan();

                    // set the next span as a child of current span
                    final ActiveSpan span = openTracingService.getGlobalTracer().buildSpan(
                        request.getRequestURI())
                        .asChildOf(activeSpan)
                        .withTag(Tags.SPAN_KIND.getKey(), Tags.SPAN_KIND_CLIENT)
                        .withTag(Tags.HTTP_METHOD.getKey(), request.getMethod())
                        .withTag(Tags.HTTP_URL.getKey(), request.getRequestURL().toString())
                        .startActive();

                    span.setOperationName(request.getRequestURI());
                    request.setAttribute("RequestProxyService.activeSpanContext", span.context());

                    return executeProxyRequest(httpClientContainer.getHttpClient(),
                        processedMethod, response, span);
                }

                return executeProxyRequest(httpClientContainer.getHttpClient(), processedMethod, response, null);
            }
        } catch (URISyntaxException | HttpException ex) {
            LOG.error("Error processing request", ex);
        } finally {
            httpClientService.releaseClient(httpClientContainer);
        }

        //Something exploded; return a status code that doesn't exist
        return -1;
    }

    private int executeProxyRequest(HttpClient httpClient, HttpRequestBase httpMethodProxyRequest,
                                    HttpServletResponse response, ActiveSpan span) throws IOException, HttpException {
        try {
            HttpResponse httpResponse = httpClient.execute(httpMethodProxyRequest);
            int responseCode = httpResponse.getStatusLine().getStatusCode();

            // finish up that span if it exists
            if (span != null) {
                span.setTag(Tags.HTTP_STATUS.getKey(), responseCode);
                span.close();
            }

            HttpComponentResponseProcessor responseProcessor = new HttpComponentResponseProcessor(httpResponse, response, responseCode);

            if (responseCode >= HttpServletResponse.SC_MULTIPLE_CHOICES && responseCode < HttpServletResponse.SC_NOT_MODIFIED) {
                responseProcessor.sendTranslatedRedirect(responseCode);
            } else {
                responseProcessor.process();
            }

            return responseCode;
        } catch (ClientProtocolException ex) {
            if (span != null) {
                span.setTag(Tags.HTTP_STATUS.getKey(), HttpStatus.SC_INTERNAL_SERVER_ERROR);
                span.close();
            }
            if (Throwables.getRootCause(ex) instanceof ReadLimitReachedException) {
                LOG.error("Error reading request content", ex);
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Error reading request content");
            } else {
                //Sadly, because of how this is implemented, I can't make sure my problem is actually with
                // the origin service. I can only "fail" here.
                LOG.error("Error processing outgoing request", ex);
                return -1;
            }
        }
        return 1;

    }

    private void setHeaders(HttpRequestBase base, Map<String, String> headers) {

        final Set<Map.Entry<String, String>> entries = headers.entrySet();
        for (Map.Entry<String, String> entry : entries) {
            base.addHeader(entry.getKey(), entry.getValue());
        }

        //Tack on the tracing ID for requests via the dist datastore
        String traceGUID = MDC.get(TracingKey.TRACING_KEY);
        if (!StringUtils.isEmpty(traceGUID)) {
            Header viaHeader = base.getFirstHeader(CommonHttpHeader.VIA);
            base.addHeader(CommonHttpHeader.TRACE_GUID,
                    TracingHeaderHelper.createTracingHeader(traceGUID, viaHeader != null ? viaHeader.getValue() : null)
            );
        }
    }

    @SuppressWarnings("squid:S2093")
    private ServiceClientResponse execute(HttpRequestBase base, String connPoolId) {
        // I'm not exactly sure why this rule is triggering on this since HttpClientContainer does NOT implement one of the required interfaces.
        // It is also not simply closed, but it is released back to the pool from which it was originally retrieved.
        // So it is safe to suppress warning squid:S2093
        HttpClientContainer httpClientContainer = getClient(connPoolId);
        try {
            HttpResponse httpResponse = httpClientContainer.getHttpClient().execute(base);
            HttpEntity entity = httpResponse.getEntity();
            int responseCode = httpResponse.getStatusLine().getStatusCode();

            InputStream stream = null;
            if (entity != null) {
                stream = new ByteArrayInputStream(RawInputStreamReader.instance().readFully(entity.getContent()));
                EntityUtils.consume(entity);
            }

            return new ServiceClientResponse(responseCode, stream);
        } catch (IOException ex) {
            LOG.error("Error executing request to {}", base.getURI().toString(), ex);
        } finally {
            base.releaseConnection();
            httpClientService.releaseClient(httpClientContainer);
        }

        return new ServiceClientResponse(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, null);
    }

    @Override
    public ServiceClientResponse get(String uri, Map<String, String> headers, String connPoolId) {
        HttpGet get = new HttpGet(uri);
        setHeaders(get, headers);
        return execute(get, connPoolId);
    }

    @Override
    public ServiceClientResponse get(String baseUri, String extraUri, Map<String, String> headers, String connPoolId) {
        return get(StringUriUtilities.appendPath(baseUri, extraUri), headers, connPoolId);
    }

    @Override
    public ServiceClientResponse delete(String baseUri, String extraUri, Map<String, String> headers, String connPoolId) {
        HttpDelete delete = new HttpDelete(StringUriUtilities.appendPath(baseUri, extraUri));
        setHeaders(delete, headers);
        return execute(delete, connPoolId);
    }

    @Override
    public ServiceClientResponse put(String uri, Map<String, String> headers, byte[] body, String connPoolId) {
        HttpPut put = new HttpPut(uri);
        setHeaders(put, headers);
        if (body != null && body.length > 0) {
            put.setEntity(new InputStreamEntity(new ByteArrayInputStream(body), body.length));
        }
        return execute(put, connPoolId);
    }

    @Override
    public ServiceClientResponse put(String baseUri, String path, Map<String, String> headers, byte[] body, String connPoolId) {
        return put(StringUriUtilities.appendPath(baseUri, path), headers, body, connPoolId);
    }

    @Override
    public ServiceClientResponse patch(String baseUri, String path, Map<String, String> headers, byte[] body, String connPoolId) {
        HttpPatch patch = new HttpPatch(StringUriUtilities.appendPath(baseUri, path));
        setHeaders(patch, headers);
        if (body != null && body.length > 0) {
            patch.setEntity(new InputStreamEntity(new ByteArrayInputStream(body), body.length));
        }
        return execute(patch, connPoolId);
    }

    @Override
    public void setRewriteHostHeader(boolean value) {
        this.rewriteHostHeader = value;
    }

    private class SystemModelListener implements UpdateListener<SystemModel> {

        private boolean isInitialized = false;

        @Override
        public void configurationUpdated(SystemModel config) {
            SystemModelInterrogator systemModelInterrogator = new SystemModelInterrogator(clusterId, nodeId);
            Optional<ReposeCluster> localCluster = systemModelInterrogator.getLocalCluster(config);

            if (localCluster.isPresent()) {
                setRewriteHostHeader(localCluster.get().isRewriteHostHeader());
                isInitialized = true;

                healthCheckServiceProxy.resolveIssue(SYSTEM_MODEL_CONFIG_HEALTH_REPORT);
            } else {
                LOG.error("Unable to identify the local host (cluster:{}, node:{}) in the system model - please check your system-model.cfg.xml", clusterId, nodeId);
                healthCheckServiceProxy.reportIssue(SYSTEM_MODEL_CONFIG_HEALTH_REPORT, "Unable to identify the " +
                        "local host in the system model - please check your system-model.cfg.xml", Severity.BROKEN);
            }
        }

        @Override
        public boolean isInitialized() {
            return isInitialized;
        }
    }
}
