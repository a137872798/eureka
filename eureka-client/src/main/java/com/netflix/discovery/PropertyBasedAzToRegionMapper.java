package com.netflix.discovery;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 从配置对象中 获取region 对应的zone
 * @author Nitesh Kant
 */
public class PropertyBasedAzToRegionMapper extends AbstractAzToRegionMapper {

    public PropertyBasedAzToRegionMapper(EurekaClientConfig clientConfig) {
        super(clientConfig);
    }

    @Override
    protected Set<String> getZonesForARegion(String region) {
        return new HashSet<String>(Arrays.asList(clientConfig.getAvailabilityZones(region)));
    }
}
