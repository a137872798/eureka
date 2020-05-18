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

package com.netflix.eureka;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.converters.Converters.InstanceInfoConverter;
import com.netflix.eureka.resources.CurrentRequestVersion;

/**
 * Support for {@link Version#V1}. {@link Version#V2} introduces a new status
 * {@link InstanceStatus#OUT_OF_SERVICE}.
 *
 * @author Karthik Ranganathan, Greg Kim。
 *
 */
public class V1AwareInstanceInfoConverter extends InstanceInfoConverter {

    /**
     * 解析节点时 status 从 info 中获取
     */
    @Override
    public String getStatus(InstanceInfo info) {
        // 获取当前请求的版本
        Version version = CurrentRequestVersion.get();
        // 默认情况下 还没有设置  或者是 V1
        if (version == null || version == Version.V1) {
            InstanceStatus status = info.getStatus();
            switch (status) {
                case DOWN:
                case STARTING:
                case UP:
                    break;
                default:
                    // otherwise return DOWN
                    status = InstanceStatus.DOWN;
                    break;
            }
            return status.name();
        } else {
            return super.getStatus(info);
        }
    }
}
