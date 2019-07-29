package com.netflix.discovery.converters.wrappers;

import javax.ws.rs.core.MediaType;

/**
 * @author David Liu
 * 对应着 编解码器的基础信息
 */
public interface CodecWrapperBase {

    /**
     * 获取编解码器的名字
     * @return
     */
    String codecName();

    /**
     * 是否 支持对特定的 MediaType 进行编解码
     * @param mediaType
     * @return
     */
    boolean support(MediaType mediaType);
}
