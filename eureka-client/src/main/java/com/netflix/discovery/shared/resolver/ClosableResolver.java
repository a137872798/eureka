package com.netflix.discovery.shared.resolver;

/**
 * 具备关闭功能
 * @author David Liu
 */
public interface ClosableResolver<T extends EurekaEndpoint> extends ClusterResolver<T> {
    void shutdown();
}
