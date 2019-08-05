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

import java.util.List;

/**
 * 具备获取 本集群所在 region 和 获取本集群所有 endpoint 的能力
 * @author Tomasz Bak
 */
public interface ClusterResolver<T extends EurekaEndpoint> {

    /**
     * 获取该集群所在的region
     * @return
     */
    String getRegion();

    /**
     * 获取本集群所有的 endpoint
     * @return
     */
    List<T> getClusterEndpoints();
}
