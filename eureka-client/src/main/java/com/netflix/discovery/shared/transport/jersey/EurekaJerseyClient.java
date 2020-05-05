package com.netflix.discovery.shared.transport.jersey;

import com.sun.jersey.client.apache4.ApacheHttpClient4;

/**
 * @author David Liu
 * 适配对象  在eureka中很多适配组件都是将2个框架的名字拼接起来
 */
public interface EurekaJerseyClient {

    /**
     * 获取client的部分
     * @return
     */
    ApacheHttpClient4 getClient();

    /**
     * Clean up resources.
     */
    void destroyResources();
}
