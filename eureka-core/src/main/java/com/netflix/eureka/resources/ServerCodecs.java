package com.netflix.eureka.resources;

import com.netflix.appinfo.EurekaAccept;
import com.netflix.discovery.converters.wrappers.CodecWrapper;
import com.netflix.discovery.converters.wrappers.EncoderWrapper;
import com.netflix.eureka.registry.Key;

/**
 * @author David Liu
 * 服务端 编解码器
 */
public interface ServerCodecs {

    /**
     * 获取 json 编解码器
     * @return
     */
    CodecWrapper getFullJsonCodec();

    /**
     * 获取压缩的 json 编解码器
     * @return
     */
    CodecWrapper getCompactJsonCodec();

    /**
     * 获取 xml 编解码器
     * @return
     */
    CodecWrapper getFullXmlCodec();

    /**
     * 获取压缩的 xml 编解码器
     * @return
     */
    CodecWrapper getCompactXmlCodecr();

    /**
     * 根据编解码类型 以及 是否 压缩 返回合适的 编码器
     * @param keyType
     * @param compact
     * @return
     */
    EncoderWrapper getEncoder(Key.KeyType keyType, boolean compact);

    /**
     * eurekaAccept 中携带了是否 压缩的信息
     * @param keyType
     * @param eurekaAccept
     * @return
     */
    EncoderWrapper getEncoder(Key.KeyType keyType, EurekaAccept eurekaAccept);
}
