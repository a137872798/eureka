package com.netflix.discovery.shared.transport.jersey2;

import java.io.IOException;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;

import com.netflix.appinfo.AbstractEurekaIdentity;

/**
 * 设置针对请求的 过滤器
 */
public class EurekaIdentityHeaderFilter implements ClientRequestFilter {

    /**
     * 具备惟一性的对象
     */
    private final AbstractEurekaIdentity authInfo;

    public EurekaIdentityHeaderFilter(AbstractEurekaIdentity authInfo) {
        this.authInfo = authInfo;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        // 将唯一信息设置到请求头中
        if (authInfo != null) {
            requestContext.getHeaders().putSingle(AbstractEurekaIdentity.AUTH_NAME_HEADER_KEY, authInfo.getName());
            requestContext.getHeaders().putSingle(AbstractEurekaIdentity.AUTH_VERSION_HEADER_KEY, authInfo.getVersion());

            if (authInfo.getId() != null) {
                requestContext.getHeaders().putSingle(AbstractEurekaIdentity.AUTH_ID_HEADER_KEY, authInfo.getId());
            }
        }
        
    }
}
