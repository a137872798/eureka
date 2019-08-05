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

package com.netflix.discovery.shared.resolver;

/**
 * 抽象成一个具备监听http 请求的对象
 */
public interface EurekaEndpoint extends Comparable<Object> {

    /**
     * 该对象的url
     * @return
     */
    String getServiceUrl();

    /**
     * @deprecated use {@link #getNetworkAddress()}
     */
    @Deprecated
    String getHostName();

    /**
     * ip 地址
     * @return
     */
    String getNetworkAddress();

    /**
     * 监听的端口
     * @return
     */
    int getPort();

    /**
     * 是否使用ssh 进行访问
     * @return
     */
    boolean isSecure();

    /**
     * 相对路径
     * @return
     */
    String getRelativeUri();

}
