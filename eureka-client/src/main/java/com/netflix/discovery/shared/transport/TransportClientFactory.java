package com.netflix.discovery.shared.transport;

import com.netflix.discovery.shared.resolver.EurekaEndpoint;

/**
 * A low level client factory interface. Not advised to be used by top level consumers.
 * 创建具备 进行http请求交互的 client 工厂对象
 *
 * @author David Liu
 */
public interface TransportClientFactory {

    /**
     * 使用 endpoint 创建client 对象 endpoint 抽象了 httpClient的基本信息
     * @param serviceUrl
     * @return
     */
    EurekaHttpClient newClient(EurekaEndpoint serviceUrl);

    void shutdown();

}
