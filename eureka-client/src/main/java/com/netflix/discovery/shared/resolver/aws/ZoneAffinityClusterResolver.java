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

package com.netflix.discovery.shared.resolver.aws;

import java.util.Collections;
import java.util.List;

import com.netflix.discovery.shared.resolver.ClusterResolver;
import com.netflix.discovery.shared.resolver.ResolverUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * It is a cluster resolver that reorders the server list, such that the first server on the list
 * is in the same zone as the client. The server is chosen randomly from the available pool of server in
 * that zone. The remaining servers are appended in a random order, local zone first, followed by servers from other zones.
 *
 * 会将 eurekaServer 排序 第一个会与client 属于同一 zone  剩下的会打乱顺序
 * @author Tomasz Bak
 */
public class ZoneAffinityClusterResolver implements ClusterResolver<AwsEndpoint> {

    private static final Logger logger = LoggerFactory.getLogger(ZoneAffinityClusterResolver.class);

    /**
     * 被装饰的 ClusterResolver 对象
     */
    private final ClusterResolver<AwsEndpoint> delegate;
    private final String myZone;
    /**
     * 是否zone亲和
     */
    private final boolean zoneAffinity;

    /**
     * A zoneAffinity defines zone affinity (true) or anti-affinity rules (false).
     */
    public ZoneAffinityClusterResolver(ClusterResolver<AwsEndpoint> delegate, String myZone, boolean zoneAffinity) {
        this.delegate = delegate;
        this.myZone = myZone;
        this.zoneAffinity = zoneAffinity;
    }

    @Override
    public String getRegion() {
        return delegate.getRegion();
    }

    /**
     * 获取 端点信息 可以理解为获取可访问的 注册中心endpoint 对象
     * @return
     */
    @Override
    public List<AwsEndpoint> getClusterEndpoints() {
        // 按照 zone 进行划分
        List<AwsEndpoint>[] parts = ResolverUtils.splitByZone(delegate.getClusterEndpoints(), myZone);
        // 代表本zone 的元素
        List<AwsEndpoint> myZoneEndpoints = parts[0];
        // 代表剩余的元素
        List<AwsEndpoint> remainingEndpoints = parts[1];
        List<AwsEndpoint> randomizedList = randomizeAndMerge(myZoneEndpoints, remainingEndpoints);
        if (!zoneAffinity) {
            // 非亲和的情况 反转顺序后返回
            Collections.reverse(randomizedList);
        }

        logger.debug("Local zone={}; resolved to: {}", myZone, randomizedList);

        // 直接返回
        return randomizedList;
    }

    /**
     * 相同zone 的保持顺序不变 其余的打乱顺序
     * @param myZoneEndpoints
     * @param remainingEndpoints
     * @return
     */
    private static List<AwsEndpoint> randomizeAndMerge(List<AwsEndpoint> myZoneEndpoints, List<AwsEndpoint> remainingEndpoints) {
        if (myZoneEndpoints.isEmpty()) {
            // 按照hash值 随机选出一个元素 作为首个元素 其余的移动到他后面
            return ResolverUtils.randomize(remainingEndpoints);
        }
        if (remainingEndpoints.isEmpty()) {
            return ResolverUtils.randomize(myZoneEndpoints);
        }
        List<AwsEndpoint> mergedList = ResolverUtils.randomize(myZoneEndpoints);
        mergedList.addAll(ResolverUtils.randomize(remainingEndpoints));
        return mergedList;
    }
}
