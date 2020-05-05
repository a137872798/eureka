/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.netflix.discovery.shared;

import java.util.List;

import com.netflix.appinfo.InstanceInfo;

/**
 * Lookup service for finding active instances.
 *
 * @author Karthik Ranganathan, Greg Kim.
 * @param <T> for backward compatibility
 *           代表具备 发现服务功能的接口
 *           这个接口应该在client 和 server 端都有实现吧
 */
public interface LookupService<T> {

    /**
     * Returns the corresponding {@link Application} object which is basically a
     * container of all registered <code>appName</code> {@link InstanceInfo}s.
     *
     * @param appName
     * @return a {@link Application} or null if we couldn't locate any app of
     *         the requested appName
     *         根据应用名找到应用对象  （该对象内部包含能提供该功能的所有实例 有点类似与 service 和 node 的关系）
     */
    Application getApplication(String appName);

    /**
     * Returns the {@link Applications} object which is basically a container of
     * all currently registered {@link Application}s.
     *
     * @return {@link Applications}
     *          获取注册中心当前所有实例 (各种service/application)
     */
    Applications getApplications();

    /**
     * Returns the {@link List} of {@link InstanceInfo}s matching the the passed
     * in id. A single {@link InstanceInfo} can possibly be registered w/ more
     * than one {@link Application}s
     *
     * @param id
     * @return {@link List} of {@link InstanceInfo}s or
     *         {@link java.util.Collections#emptyList()}
     *         通过id 定位到实例信息
     *         这里会返回list的原因是 每个instanceInfo 虽然id 相同 但是它们内部的 application 属性可能不同
     *         这也意味着该实例本身提供多个服务
     */
    List<InstanceInfo> getInstancesById(String id);

    /**
     * Gets the next possible server to process the requests from the registry
     * information received from eureka.
     *
     * <p>
     * The next server is picked on a round-robin fashion. By default, this
     * method just returns the servers that are currently with
     * {@link com.netflix.appinfo.InstanceInfo.InstanceStatus#UP} status.
     * This configuration can be controlled by overriding the
     * {@link com.netflix.discovery.EurekaClientConfig#shouldFilterOnlyUpInstances()}.
     *
     * Note that in some cases (Eureka emergency mode situation), the instances
     * that are returned may not be unreachable, it is solely up to the client
     * at that point to timeout quickly and retry the next server.
     * </p>
     *
     * @param virtualHostname
     *            the virtual host name that is associated to the servers.
     * @param secure
     *            indicates whether this is a HTTP or a HTTPS request - secure
     *            means HTTPS.
     * @return the {@link InstanceInfo} information which contains the public
     *         host name of the next server in line to process the request based
     *         on the round-robin algorithm.
     * @throws java.lang.RuntimeException if the virtualHostname does not exist
     *          实例信息 代表的不仅仅是能够提供服务的实例 也代表着 集群中其他含义的节点  比如 eureka-client eureka-server
     *          这里是从某个虚拟地址找到下一个可以注册服务实例的 eureka-server
     */
    InstanceInfo getNextServerFromEureka(String virtualHostname, boolean secure);
}
