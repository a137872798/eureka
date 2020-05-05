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
 * @author Tomasz Bak
 * 解析器工厂 通过该对象可以构建一个 用于解析出 EurekaEndpoint 的对象
 */
public interface ClusterResolverFactory<T extends EurekaEndpoint> {

    ClusterResolver<T> createClusterResolver();
}
