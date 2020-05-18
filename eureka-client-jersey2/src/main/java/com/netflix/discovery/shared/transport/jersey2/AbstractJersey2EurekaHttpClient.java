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

package com.netflix.discovery.shared.transport.jersey2;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.transport.EurekaHttpClient;
import com.netflix.discovery.shared.transport.EurekaHttpResponse;
import com.netflix.discovery.shared.transport.EurekaHttpResponse.EurekaHttpResponseBuilder;
import com.netflix.discovery.util.StringUtil;
import org.glassfish.jersey.client.authentication.HttpAuthenticationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.netflix.discovery.shared.transport.EurekaHttpResponse.anEurekaHttpResponse;

/**
 * @author Tomasz Bak
 *      基于 jersey 进行通信  该框架是一个 restFul 框架  那么该对象的发送心跳请求应该是有类似定时器在处理
 */
public abstract class AbstractJersey2EurekaHttpClient implements EurekaHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(AbstractJersey2EurekaHttpClient.class);

    protected final Client jerseyClient;
    /**
     * 被访问的 目标url 应该是前缀 后面再根据具体的行为设置url
     */
    protected final String serviceUrl;

    // 该用户名密码 由传入的url 对象携带
    private final String userName;
    private final String password;

    /**
     * 使用传入的 client 对象 进行初始化
     * @param jerseyClient
     * @param serviceUrl
     */
    public AbstractJersey2EurekaHttpClient(Client jerseyClient, String serviceUrl) {
        this.jerseyClient = jerseyClient;
        this.serviceUrl = serviceUrl;

        // Jersey2 does not read credentials from the URI. We extract it here and enable authentication feature.
        String localUserName = null;
        String localPassword = null;
        try {
            //将 String 转换成URL   看来 url 上可以携带用户名 和密码
            URI serviceURI = new URI(serviceUrl);
            if (serviceURI.getUserInfo() != null) {
                String[] credentials = serviceURI.getUserInfo().split(":");
                if (credentials.length == 2) {
                    localUserName = credentials[0];
                    localPassword = credentials[1];
                }
            }
        } catch (URISyntaxException ignore) {
        }
        //如果存在用户名密码 就设置
        this.userName = localUserName;
        this.password = localPassword;
    }

    /**
     * 将服务实例注册到 EurekaServer 上
     * @param info
     * @return
     */
    @Override
    public EurekaHttpResponse<Void> register(InstanceInfo info) {
        // 使用 特殊的命名 规则 apps/ + appName
        String urlPath = "apps/" + info.getAppName();
        Response response = null;
        try {
            // 生成请求对象
            Builder resourceBuilder = jerseyClient.target(serviceUrl).path(urlPath).request();
            // 为请求对象设置额外的 properties 和 headers
            addExtraProperties(resourceBuilder);
            addExtraHeaders(resourceBuilder);

            response = resourceBuilder
                    .accept(MediaType.APPLICATION_JSON)
                    .acceptEncoding("gzip")
                    .post(Entity.json(info));
            // 将response 适配成 EurekaHttpResponse
            return anEurekaHttpResponse(response.getStatus()).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey2 HTTP POST {}/{} with instance {}; statusCode={}", serviceUrl, urlPath, info.getId(),
                        response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 发起 关闭某个 应用下 某个实例的请求
     * @param appName
     * @param id
     * @return
     */
    @Override
    public EurekaHttpResponse<Void> cancel(String appName, String id) {
        // 拼接url
        String urlPath = "apps/" + appName + '/' + id;
        Response response = null;
        try {
            Builder resourceBuilder = jerseyClient.target(serviceUrl).path(urlPath).request();
            addExtraProperties(resourceBuilder);
            addExtraHeaders(resourceBuilder);
            // 使用 delete 请求 符合 RestFul 风格
            response = resourceBuilder.delete();
            return anEurekaHttpResponse(response.getStatus()).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey2 HTTP DELETE {}/{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 发送心跳检测
     * @param appName
     * @param id
     * @param info
     * @param overriddenStatus
     * @return
     */
    @Override
    public EurekaHttpResponse<InstanceInfo> sendHeartBeat(String appName, String id, InstanceInfo info, InstanceStatus overriddenStatus) {
        String urlPath = "apps/" + appName + '/' + id;
        Response response = null;
        try {
            WebTarget webResource = jerseyClient.target(serviceUrl)
                    .path(urlPath)
                    // queryParam 应该是在 url 上追加参数吧  要把当前 服务实例的 status 和 最后改动时间 也就是 (dirtyTimestamp) 发送过去
                    .queryParam("status", info.getStatus().toString())
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString());
            if (overriddenStatus != null) {
                webResource = webResource.queryParam("overriddenstatus", overriddenStatus.name());
            }
            // 构建请求对象
            Builder requestBuilder = webResource.request();
            addExtraProperties(requestBuilder);
            addExtraHeaders(requestBuilder);
            requestBuilder.accept(MediaType.APPLICATION_JSON_TYPE);
            // 使用put 请求 也就是update
            response = requestBuilder.put(Entity.entity("{}", MediaType.APPLICATION_JSON_TYPE)); // Jersey2 refuses to handle PUT with no body
            EurekaHttpResponseBuilder<InstanceInfo> eurekaResponseBuilder = anEurekaHttpResponse(response.getStatus(), InstanceInfo.class).headers(headersOf(response));
            if (response.hasEntity()) {
                // 返回响应结果
                eurekaResponseBuilder.entity(response.readEntity(InstanceInfo.class));
            }
            return eurekaResponseBuilder.build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey2 HTTP PUT {}/{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 更新状态的请求
     * @param appName
     * @param id
     * @param newStatus
     * @param info
     * @return
     */
    @Override
    public EurekaHttpResponse<Void> statusUpdate(String appName, String id, InstanceStatus newStatus, InstanceInfo info) {
        String urlPath = "apps/" + appName + '/' + id + "/status";
        Response response = null;
        try {
            Builder requestBuilder = jerseyClient.target(serviceUrl)
                    .path(urlPath)
                    // 代表新状态的 名字
                    .queryParam("value", newStatus.name())
                    // 同时更新最后操作的 时间戳
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString())
                    .request();
            addExtraProperties(requestBuilder);
            addExtraHeaders(requestBuilder);
            response = requestBuilder.put(Entity.entity("{}", MediaType.APPLICATION_JSON_TYPE)); // Jersey2 refuses to handle PUT with no body
            return anEurekaHttpResponse(response.getStatus()).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey2 HTTP PUT {}/{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 删除  statusOverride ???
     * @param appName
     * @param id
     * @param info
     * @return
     */
    @Override
    public EurekaHttpResponse<Void> deleteStatusOverride(String appName, String id, InstanceInfo info) {
        String urlPath = "apps/" + appName + '/' + id + "/status";
        Response response = null;
        try {
            Builder requestBuilder = jerseyClient.target(serviceUrl)
                    .path(urlPath)
                    .queryParam("lastDirtyTimestamp", info.getLastDirtyTimestamp().toString())
                    .request();
            addExtraProperties(requestBuilder);
            addExtraHeaders(requestBuilder);
            response = requestBuilder.delete();
            return anEurekaHttpResponse(response.getStatus()).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey2 HTTP DELETE {}/{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 发起 http 请求 并返回 指定region 下 所有的 Applications
     * @param regions
     * @return
     */
    @Override
    public EurekaHttpResponse<Applications> getApplications(String... regions) {
        return getApplicationsInternal("apps/", regions);
    }

    @Override
    public EurekaHttpResponse<Applications> getDelta(String... regions) {
        return getApplicationsInternal("apps/delta", regions);
    }

    @Override
    public EurekaHttpResponse<Applications> getVip(String vipAddress, String... regions) {
        return getApplicationsInternal("vips/" + vipAddress, regions);
    }

    @Override
    public EurekaHttpResponse<Applications> getSecureVip(String secureVipAddress, String... regions) {
        return getApplicationsInternal("svips/" + secureVipAddress, regions);
    }

    /**
     * 使用 appName 找到对应的 application
     * @param appName
     * @return
     */
    @Override
    public EurekaHttpResponse<Application> getApplication(String appName) {
        String urlPath = "apps/" + appName;
        Response response = null;
        try {
            Builder requestBuilder = jerseyClient.target(serviceUrl).path(urlPath).request();
            addExtraProperties(requestBuilder);
            addExtraHeaders(requestBuilder);
            response = requestBuilder.accept(MediaType.APPLICATION_JSON_TYPE).get();

            Application application = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.hasEntity()) {
                application = response.readEntity(Application.class);
            }
            return anEurekaHttpResponse(response.getStatus(), application).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey2 HTTP GET {}/{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 传入 url  和 指定的region 信息 访问对应的接口 并返回 Applications
     * @param urlPath
     * @param regions
     * @return
     */
    private EurekaHttpResponse<Applications> getApplicationsInternal(String urlPath, String[] regions) {
        Response response = null;
        try {
            WebTarget webTarget = jerseyClient.target(serviceUrl).path(urlPath);
            // regions 代表将会查询哪些region下的应用
            if (regions != null && regions.length > 0) {
                webTarget = webTarget.queryParam("regions", StringUtil.join(regions));
            }
            Builder requestBuilder = webTarget.request();
            addExtraProperties(requestBuilder); // 追加用户名和密码 避免对端需要权限认证
            addExtraHeaders(requestBuilder);  // 追加额外的请求头
            response = requestBuilder.accept(MediaType.APPLICATION_JSON_TYPE).get();

            Applications applications = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.hasEntity()) {
                applications = response.readEntity(Applications.class);
            }
            return anEurekaHttpResponse(response.getStatus(), applications).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey2 HTTP GET {}/{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    @Override
    public EurekaHttpResponse<InstanceInfo> getInstance(String id) {
        return getInstanceInternal("instances/" + id);
    }

    @Override
    public EurekaHttpResponse<InstanceInfo> getInstance(String appName, String id) {
        return getInstanceInternal("apps/" + appName + '/' + id);
    }

    private EurekaHttpResponse<InstanceInfo> getInstanceInternal(String urlPath) {
        Response response = null;
        try {
            Builder requestBuilder = jerseyClient.target(serviceUrl).path(urlPath).request();
            addExtraProperties(requestBuilder);
            addExtraHeaders(requestBuilder);
            response = requestBuilder.accept(MediaType.APPLICATION_JSON_TYPE).get();

            InstanceInfo infoFromPeer = null;
            if (response.getStatus() == Status.OK.getStatusCode() && response.hasEntity()) {
                infoFromPeer = response.readEntity(InstanceInfo.class);
            }
            return anEurekaHttpResponse(response.getStatus(), infoFromPeer).headers(headersOf(response)).build();
        } finally {
            if (logger.isDebugEnabled()) {
                logger.debug("Jersey2 HTTP GET {}/{}; statusCode={}", serviceUrl, urlPath, response == null ? "N/A" : response.getStatus());
            }
            if (response != null) {
                response.close();
            }
        }
    }

    /**
     * 子类实现停机 逻辑
     */
    @Override
    public void shutdown() {
    }

    /**
     * 添加用户名密码
     * @param webResource
     */
    protected void addExtraProperties(Builder webResource) {
        if (userName != null) {
            webResource.property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_USERNAME, userName)
                    .property(HttpAuthenticationFeature.HTTP_AUTHENTICATION_PASSWORD, password);
        }
    }

    protected abstract void addExtraHeaders(Builder webResource);

    /**
     * 从response 中 获取响应头信息
     * @param response
     * @return
     */
    private static Map<String, String> headersOf(Response response) {
        MultivaluedMap<String, String> jerseyHeaders = response.getStringHeaders();
        if (jerseyHeaders == null || jerseyHeaders.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new HashMap<>();
        for (Entry<String, List<String>> entry : jerseyHeaders.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                // 这里只保存第一个 结果
                headers.put(entry.getKey(), entry.getValue().get(0));
            }
        }
        return headers;
    }
}
