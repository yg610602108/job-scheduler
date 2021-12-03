package com.ambition.spring.boot.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JobScheduler.
 *
 * @author Elewin
 **/
@ConfigurationProperties(prefix = JobSchedulerProperties.JOB_SCHEDULER_PREFIX)
public class JobSchedulerProperties {

    public static final String JOB_SCHEDULER_PREFIX = "job.scheduler";

    private String registryAddresses;

    private String accessToken;

    private Executor executor;

    public String getRegistryAddresses() {
        return registryAddresses;
    }

    public void setRegistryAddresses(String registryAddresses) {
        this.registryAddresses = registryAddresses;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Executor getExecutor() {
        return executor;
    }

    public void setExecutor(Executor executor) {
        this.executor = executor;
    }

    public static class Executor {

        private String name;

        private String address;

        private String ip;

        private Integer port;

        private String logPath;

        private Integer logRetentionDays;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAddress() {
            return address;
        }

        public void setAddress(String address) {
            this.address = address;
        }

        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getLogPath() {
            return logPath;
        }

        public void setLogPath(String logPath) {
            this.logPath = logPath;
        }

        public Integer getLogRetentionDays() {
            return logRetentionDays;
        }

        public void setLogRetentionDays(Integer logRetentionDays) {
            this.logRetentionDays = logRetentionDays;
        }

    }

}
