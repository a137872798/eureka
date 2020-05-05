package com.netflix.appinfo.providers;

import com.netflix.config.DynamicPropertyFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * archaius 是 netflix的 配置中心
 * 这个类的意思是 如果address 本身携带占位符那么去配置中心替换成实际配置
 */
public class Archaius1VipAddressResolver implements VipAddressResolver {

    private static final Logger logger = LoggerFactory.getLogger(Archaius1VipAddressResolver.class);

    /**
     * VIP 地址格式 为 ${*****}
     */
    private static final Pattern VIP_ATTRIBUTES_PATTERN = Pattern.compile("\\$\\{(.*?)\\}");

    @Override
    public String resolveDeploymentContextBasedVipAddresses(String vipAddressMacro) {
        if (vipAddressMacro == null) {
            return null;
        }

        String result = vipAddressMacro;

        Matcher matcher = VIP_ATTRIBUTES_PATTERN.matcher(result);
        while (matcher.find()) {
            String key = matcher.group(1);
            // 去配置中心读取数据  他这个配置中心开放的api
            String value = DynamicPropertyFactory.getInstance().getStringProperty(key, "").get();

            logger.debug("att:{}", matcher.group());
            logger.debug(", att key:{}", key);
            logger.debug(", att value:{}", value);
            logger.debug("");
            result = result.replaceAll("\\$\\{" + key + "\\}", value);
            matcher = VIP_ATTRIBUTES_PATTERN.matcher(result);
        }

        return result;
    }
}
