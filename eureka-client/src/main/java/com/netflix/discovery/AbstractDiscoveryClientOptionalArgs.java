package com.netflix.discovery;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import javax.inject.Provider;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;

import com.google.inject.Inject;
import com.netflix.appinfo.HealthCheckCallback;
import com.netflix.appinfo.HealthCheckHandler;
import com.netflix.discovery.shared.transport.TransportClientFactory;
import com.netflix.discovery.shared.transport.jersey.EurekaJerseyClient;
import com.netflix.discovery.shared.transport.jersey.TransportClientFactories;
import com.netflix.eventbus.spi.EventBus;

/**
 * <T> The type for client supplied filters (supports jersey1 and jersey2)
 * 该对象携带了一些额外的参数
 */
public abstract class AbstractDiscoveryClientOptionalArgs<T> {
    Provider<HealthCheckCallback> healthCheckCallbackProvider;

    /**
     * 该对象负责检查当前节点是否健康
     */
    Provider<HealthCheckHandler> healthCheckHandlerProvider;

    PreRegistrationHandler preRegistrationHandler;

    /**
     * 需要被设置的一组过滤器
     */
    Collection<T> additionalFilters;

    /**
     * 该对象内部维护了 jerseyClient  负责与 eureka-server 交互
     */
    EurekaJerseyClient eurekaJerseyClient;

    /**
     * 该对象可以根据endpoint的url构建EurekaHttpClient    这2个client在 功能上不同  一个负责管理内部真正通信用的client 一个负责使用该client发送请求
     */
    TransportClientFactory transportClientFactory;

    /**
     * 该对象提供特殊配置 生成 TransportClientFactory
     */
    TransportClientFactories transportClientFactories;

    /**
     * 监听器容器
     */
    private Set<EurekaEventListener> eventListeners;

    // https 相关的 先忽略
    private Optional<SSLContext> sslContext = Optional.empty();

    private Optional<HostnameVerifier> hostnameVerifier = Optional.empty();

    @Inject(optional = true)
    public void setEventListeners(Set<EurekaEventListener> listeners) {
        if (eventListeners == null) {
            eventListeners = new HashSet<>();
        }
        eventListeners.addAll(listeners);
    }

    /**
     * 这里注入了一个事件总线对象 好像就是可以收集平台 中所有触发的事件???
     * @param eventBus
     */
    @Inject(optional = true)
    public void setEventBus(final EventBus eventBus) {
        if (eventListeners == null) {
            eventListeners = new HashSet<>();
        }
        
        eventListeners.add(new EurekaEventListener() {
            @Override
            public void onEvent(EurekaEvent event) {
                eventBus.publish(event);
            }
        });
    }

    @Inject(optional = true) 
    public void setHealthCheckCallbackProvider(Provider<HealthCheckCallback> healthCheckCallbackProvider) {
        this.healthCheckCallbackProvider = healthCheckCallbackProvider;
    }

    @Inject(optional = true) 
    public void setHealthCheckHandlerProvider(Provider<HealthCheckHandler> healthCheckHandlerProvider) {
        this.healthCheckHandlerProvider = healthCheckHandlerProvider;
    }

    @Inject(optional = true)
    public void setPreRegistrationHandler(PreRegistrationHandler preRegistrationHandler) {
        this.preRegistrationHandler = preRegistrationHandler;
    }


    @Inject(optional = true) 
    public void setAdditionalFilters(Collection<T> additionalFilters) {
        this.additionalFilters = additionalFilters;
    }

    @Inject(optional = true) 
    public void setEurekaJerseyClient(EurekaJerseyClient eurekaJerseyClient) {
        this.eurekaJerseyClient = eurekaJerseyClient;
    }
    
    Set<EurekaEventListener> getEventListeners() {
        return eventListeners == null ? Collections.<EurekaEventListener>emptySet() : eventListeners;
    }
    
    public TransportClientFactories getTransportClientFactories() {
        return transportClientFactories;
    }

    @Inject(optional = true)
    public void setTransportClientFactories(TransportClientFactories transportClientFactories) {
        this.transportClientFactories = transportClientFactories;
    }
    
    public Optional<SSLContext> getSSLContext() {
        return sslContext;
    }

    @Inject(optional = true)
    public void setSSLContext(SSLContext sslContext) {
        this.sslContext = Optional.of(sslContext);
    }
    
    public Optional<HostnameVerifier> getHostnameVerifier() {
        return hostnameVerifier;
    }

    @Inject(optional = true)
    public void setHostnameVerifier(HostnameVerifier hostnameVerifier) {
        this.hostnameVerifier = Optional.of(hostnameVerifier);
    }
}