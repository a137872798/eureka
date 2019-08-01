package com.netflix.eureka.transport;

import com.netflix.eureka.EurekaServerConfig;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;
import javax.ws.rs.client.ClientResponseContext;
import javax.ws.rs.client.ClientResponseFilter;
import javax.ws.rs.core.HttpHeaders;
import java.io.IOException;

/**
 * 针对 jersey2 请求时的过滤器 会同时处理req和 res
 */
public class Jersey2DynamicGZIPContentEncodingFilter implements ClientRequestFilter, ClientResponseFilter {

    /**
     * eurekaServer 的配置对象
     */
    private final EurekaServerConfig config;

    public Jersey2DynamicGZIPContentEncodingFilter(EurekaServerConfig config) {
        this.config = config;
    }

    /**
     * 请求 过滤器
     * @param requestContext
     * @throws IOException
     */
    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        // 添加 gzip 请求头 代表需要将 返回结果压缩
        if (!requestContext.getHeaders().containsKey(HttpHeaders.ACCEPT_ENCODING)) {
            requestContext.getHeaders().add(HttpHeaders.ACCEPT_ENCODING, "gzip");
        }

        // hasEntity 默认返回false 忽视
        if (hasEntity(requestContext) && isCompressionEnabled()) {
            Object contentEncoding = requestContext.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
            if (!"gzip".equals(contentEncoding)) {
                requestContext.getHeaders().add(HttpHeaders.CONTENT_ENCODING, "gzip");
            }
        }
    }

    /**
     * 拦截响应结果
     * @param requestContext
     * @param responseContext
     * @throws IOException
     */
    @Override
    public void filter(ClientRequestContext requestContext, ClientResponseContext responseContext) throws IOException {
        Object contentEncoding = responseContext.getHeaders().getFirst(HttpHeaders.CONTENT_ENCODING);
        if ("gzip".equals(contentEncoding)) {
            // 移除之前的影响
            responseContext.getHeaders().remove(HttpHeaders.CONTENT_ENCODING);
        }
    }

    private boolean hasEntity(ClientRequestContext requestContext) {
        return false;
    }

    private boolean isCompressionEnabled() {
        return config.shouldEnableReplicatedRequestCompression();
    }

}