package com.netflix.discovery.shared.transport.jersey;

import java.util.Collection;
import java.util.Optional;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import com.netflix.appinfo.EurekaClientIdentity;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.resolver.EurekaEndpoint;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.decorator.MetricsCollectingEurekaHttpClient;
import com.sun.jersey.api.client.filter.ClientFilter;
import com.sun.jersey.client.apache4.ApacheHttpClient4;

/**
 * 该对象可以根据给定的参数 构建 factory对象
 */
public class Jersey1TransportClientFactories implements TransportClientFactories<ClientFilter> {
    @Deprecated
    public TransportClientFactory newTransportClientFactory(final Collection<ClientFilter> additionalFilters,
                                                                   final EurekaJerseyClient providedJerseyClient) {
        ApacheHttpClient4 apacheHttpClient = providedJerseyClient.getClient();
        if (additionalFilters != null) {
            for (ClientFilter filter : additionalFilters) {
                if (filter != null) {
                    apacheHttpClient.addFilter(filter);
                }
            }
        }

        // 适配工厂
        final TransportClientFactory jerseyFactory = new JerseyEurekaHttpClientFactory(providedJerseyClient, false);
        final TransportClientFactory metricsFactory = MetricsCollectingEurekaHttpClient.createFactory(jerseyFactory);

        // 将构建client的请求 转发到 metricsFactory
        return new TransportClientFactory() {
            @Override
            public EurekaHttpClient newClient(EurekaEndpoint serviceUrl) {
                return metricsFactory.newClient(serviceUrl);
            }

            @Override
            public void shutdown() {
                metricsFactory.shutdown();
                jerseyFactory.shutdown();
            }
        };
    }

    public TransportClientFactory newTransportClientFactory(final EurekaClientConfig clientConfig,
                                                                   final Collection<ClientFilter> additionalFilters,
                                                                   final InstanceInfo myInstanceInfo) {
        return newTransportClientFactory(clientConfig, additionalFilters, myInstanceInfo, Optional.empty(), Optional.empty());
    }

    /**
     * 根据当前基础信息生成client对象  (创建client的起点就是该方法)
     * @param clientConfig
     * @param additionalFilters
     * @param myInstanceInfo
     * @param sslContext
     * @param hostnameVerifier
     * @return
     */
    @Override
    public TransportClientFactory newTransportClientFactory(EurekaClientConfig clientConfig,
            Collection<ClientFilter> additionalFilters, InstanceInfo myInstanceInfo, Optional<SSLContext> sslContext,
            Optional<HostnameVerifier> hostnameVerifier) {
        // 最内层的是该工厂 之后的 都是 类似装饰器 增强它
        final TransportClientFactory jerseyFactory = JerseyEurekaHttpClientFactory.create(
                clientConfig,
                additionalFilters,
                myInstanceInfo,
                new EurekaClientIdentity(myInstanceInfo.getIPAddr()),
                sslContext,
                hostnameVerifier
        );

        // 增加统计能力
        final TransportClientFactory metricsFactory = MetricsCollectingEurekaHttpClient.createFactory(jerseyFactory);

        return new TransportClientFactory() {
            @Override
            public EurekaHttpClient newClient(EurekaEndpoint serviceUrl) {
                return metricsFactory.newClient(serviceUrl);
            }

            @Override
            public void shutdown() {
                metricsFactory.shutdown();
                jerseyFactory.shutdown();
            }
        };
    }
}