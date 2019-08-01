package com.netflix.discovery.guice;

import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.MyDataCenterInstanceConfig;

/**
 * @author xl.guo
 * @date 2019/8/1
 */
public class Test {

    public static void main(String[] args) {
        EurekaInstanceConfig instanceConfig = new MyDataCenterInstanceConfig();
        System.out.println(instanceConfig);
    }
}
