package com.netflix.discovery.shared.resolver;

import java.util.List;

/**
 * 负责将地址打乱后返回
 */
public interface EndpointRandomizer {
    <T extends EurekaEndpoint> List<T> randomize(List<T> list);
}
