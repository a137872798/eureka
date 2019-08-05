/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.discovery.shared.transport.decorator;

import javax.ws.rs.core.UriBuilder;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.netflix.discovery.shared.dns.DnsService;
import com.netflix.discovery.shared.dns.DnsServiceImpl;
import com.netflix.discovery.shared.resolver.DefaultEndpoint;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.TransportException;
import com.netflix.discovery.shared.transport.TransportUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link EurekaHttpClient} that follows redirect links, and executes the requests against
 * the finally resolved endpoint.
 * If registration and query requests must handled separately, two different instances shall be created.
 * <h3>Thread safety</h3>
 * Methods in this class may be called concurrently.
 *
 * @author Tomasz Bak
 *  直接的 httpClient 对象
 */
public class RedirectingEurekaHttpClient extends EurekaHttpClientDecorator {

    private static final Logger logger = LoggerFactory.getLogger(RedirectingEurekaHttpClient.class);

    /**
     * 最大遵循数为 10
     */
    public static final int MAX_FOLLOWED_REDIRECTS = 10;
    private static final Pattern REDIRECT_PATH_REGEX = Pattern.compile("(.*/v2/)apps(/.*)?$");

    /**
     * 端点信息
     */
    private final EurekaEndpoint serviceEndpoint;
    /**
     * 工厂对象
     */
    private final TransportClientFactory factory;
    /**
     * dns解析器
     */
    private final DnsService dnsService;

    private final AtomicReference<EurekaHttpClient> delegateRef = new AtomicReference<>();

    /**
     * The delegate client should pass through 3xx responses without further processing.
     */
    public RedirectingEurekaHttpClient(String serviceUrl, TransportClientFactory factory, DnsService dnsService) {
        this.serviceEndpoint = new DefaultEndpoint(serviceUrl);
        this.factory = factory;
        this.dnsService = dnsService;
    }

    @Override
    public void shutdown() {
        TransportUtils.shutdown(delegateRef.getAndSet(null));
    }

    /**
     * 发起http 请求
     * @param requestExecutor
     * @param <R>
     * @return
     */
    @Override
    protected <R> EurekaHttpResponse<R> execute(RequestExecutor<R> requestExecutor) {
        EurekaHttpClient currentEurekaClient = delegateRef.get();
        if (currentEurekaClient == null) {
            AtomicReference<EurekaHttpClient> currentEurekaClientRef = new AtomicReference<>(factory.newClient(serviceEndpoint));
            try {
                EurekaHttpResponse<R> response = executeOnNewServer(requestExecutor, currentEurekaClientRef);
                // 关闭旧对象 使用新对象
                TransportUtils.shutdown(delegateRef.getAndSet(currentEurekaClientRef.get()));
                return response;
            } catch (Exception e) {
                logger.error("Request execution error. endpoint={}", serviceEndpoint, e);
                TransportUtils.shutdown(currentEurekaClientRef.get());
                throw e;
            }
        } else {
            try {
                return requestExecutor.execute(currentEurekaClient);
            } catch (Exception e) {
                logger.error("Request execution error. endpoint={}", serviceEndpoint, e);
                delegateRef.compareAndSet(currentEurekaClient, null);
                currentEurekaClient.shutdown();
                throw e;
            }
        }
    }

    /**
     * 创建 redirectClient
     * @param delegateFactory
     * @return
     */
    public static TransportClientFactory createFactory(final TransportClientFactory delegateFactory) {
        final DnsServiceImpl dnsService = new DnsServiceImpl();
        return new TransportClientFactory() {
            @Override
            public EurekaHttpClient newClient(EurekaEndpoint endpoint) {
                return new RedirectingEurekaHttpClient(endpoint.getServiceUrl(), delegateFactory, dnsService);
            }

            @Override
            public void shutdown() {
                delegateFactory.shutdown();
            }
        };
    }

    /**
     * 发起http请求
     * @param requestExecutor
     * @param currentHttpClientRef
     * @param <R>
     * @return
     */
    private <R> EurekaHttpResponse<R> executeOnNewServer(RequestExecutor<R> requestExecutor,
                                                         AtomicReference<EurekaHttpClient> currentHttpClientRef) {
        URI targetUrl = null;
        for (int followRedirectCount = 0; followRedirectCount < MAX_FOLLOWED_REDIRECTS; followRedirectCount++) {
            // 成功情况直接返回结果
            EurekaHttpResponse<R> httpResponse = requestExecutor.execute(currentHttpClientRef.get());
            if (httpResponse.getStatusCode() != 302) {
                if (followRedirectCount == 0) {
                    logger.debug("Pinning to endpoint {}", targetUrl);
                } else {
                    logger.info("Pinning to endpoint {}, after {} redirect(s)", targetUrl, followRedirectCount);
                }
                return httpResponse;
            }

            targetUrl = getRedirectBaseUri(httpResponse.getLocation());
            if (targetUrl == null) {
                throw new TransportException("Invalid redirect URL " + httpResponse.getLocation());
            }

            // 设置一个新对象
            currentHttpClientRef.getAndSet(null).shutdown();
            currentHttpClientRef.set(factory.newClient(new DefaultEndpoint(targetUrl.toString())));
        }
        String message = "Follow redirect limit crossed for URI " + serviceEndpoint.getServiceUrl();
        logger.warn(message);
        // 抛出异常
        throw new TransportException(message);
    }

    private URI getRedirectBaseUri(URI locationURI) {
        if (locationURI == null) {
            throw new TransportException("Missing Location header in the redirect reply");
        }
        Matcher pathMatcher = REDIRECT_PATH_REGEX.matcher(locationURI.getPath());
        if (pathMatcher.matches()) {
            return UriBuilder.fromUri(locationURI)
                    .host(dnsService.resolveIp(locationURI.getHost()))
                    .replacePath(pathMatcher.group(1))
                    .replaceQuery(null)
                    .build();
        }
        logger.warn("Invalid redirect URL {}", locationURI);
        return null;
    }
}
