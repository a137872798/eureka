package com.netflix.discovery.shared.transport.jersey2;

import javax.ws.rs.client.Client;

/**
 * 桥接到 eureka 中的 jerseyClient ???
 * @author David Liu
 */
public interface EurekaJersey2Client {

    Client getClient();

    /**
     * Clean up resources.
     * 清除 内部 client 对象的资源
     */
    void destroyResources();
}
