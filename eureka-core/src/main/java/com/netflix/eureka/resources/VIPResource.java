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

package com.netflix.eureka.resources;

import com.netflix.appinfo.EurekaAccept;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.EurekaServerContextHolder;
import com.netflix.eureka.registry.Key;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

/**
 * A <em>jersey</em> resource for retrieving all instances with a given VIP address.
 *
 * @author Karthik Ranganathan
 * 作为 VIP 的Controller  可能设置了 @Path 的 对象会自动被初始化
 * 看来 eureka 有2套启动机制 一套是 嵌入在 tomcat 中 通过监听器的方式来 初始化 一种是 利用@Singleton 和 @Inject 来初始化对象
 */
@Path("/{version}/vips")
@Produces({"application/xml", "application/json"})
public class VIPResource extends AbstractVIPResource {

    @Inject
    VIPResource(EurekaServerContext server) {
        super(server);
    }

    public VIPResource() {
        this(EurekaServerContextHolder.getInstance().getServerContext());
    }

    /**
     * 填入vip 地址 获取 对应的服务数据
     * @param version
     * @param vipAddress
     * @param acceptHeader
     * @param eurekaAccept
     * @return
     */
    @GET
    @Path("{vipAddress}")
    public Response statusUpdate(@PathParam("version") String version,
                                 @PathParam("vipAddress") String vipAddress,
                                 @HeaderParam("Accept") final String acceptHeader,
                                 @HeaderParam(EurekaAccept.HTTP_X_EUREKA_ACCEPT) String eurekaAccept) {
        return getVipResponse(version, vipAddress, acceptHeader,
                                                        // 代表是 VIP 类型
                EurekaAccept.fromString(eurekaAccept), Key.EntityType.VIP);
    }

}
