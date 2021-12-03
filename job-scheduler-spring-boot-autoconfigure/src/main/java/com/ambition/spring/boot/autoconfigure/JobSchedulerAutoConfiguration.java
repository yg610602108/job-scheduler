package com.ambition.spring.boot.autoconfigure;

import com.alibaba.fastjson.JSONObject;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto configuration for JobScheduler. Contributes a {@link XxlJobSpringExecutor}.
 *
 * @author Elewin
 **/
@Configuration
@EnableConfigurationProperties(JobSchedulerProperties.class)
public class JobSchedulerAutoConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(JobSchedulerAutoConfiguration.class);

    @Autowired
    private JobSchedulerProperties properties;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        if (null == properties || null == properties.getExecutor()) {
            LOGGER.error("can not initialize job scheduler, missing required properties");
            throw new BeanInitializationException("can not initialize job scheduler");
        }

        XxlJobSpringExecutor springExecutor = new XxlJobSpringExecutor();
        springExecutor.setAdminAddresses(properties.getRegistryAddresses());
        springExecutor.setAccessToken(properties.getAccessToken());
        springExecutor.setAppname(properties.getExecutor().getName());
        springExecutor.setAddress(properties.getExecutor().getAddress());
        springExecutor.setIp(properties.getExecutor().getIp());
        springExecutor.setPort(properties.getExecutor().getPort());
        springExecutor.setLogPath(properties.getExecutor().getLogPath());
        springExecutor.setLogRetentionDays(properties.getExecutor().getLogRetentionDays());

        LOGGER.info("Initializing job scheduler completed, config : {}", JSONObject.toJSONString(properties));

        return springExecutor;
    }
}
