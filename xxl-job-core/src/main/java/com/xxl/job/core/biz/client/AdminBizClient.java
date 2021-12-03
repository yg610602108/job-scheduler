package com.xxl.job.core.biz.client;

import com.xxl.job.core.biz.AdminBiz;
import com.xxl.job.core.biz.model.HandleCallbackParam;
import com.xxl.job.core.biz.model.RegistryParam;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.util.XxlJobRemotingUtil;

import java.util.List;

/**
 * admin api test
 *
 * @author xuxueli 2017-07-28 22:14:52
 */
public class AdminBizClient implements AdminBiz {

    public AdminBizClient() { }

    public AdminBizClient(String addressUrl, String accessToken) {
        this.addressUrl = addressUrl;
        this.accessToken = accessToken;

        // valid
        if (!this.addressUrl.endsWith("/")) {
            this.addressUrl = this.addressUrl + "/";
        }
    }

    /**
     * 调度中心地址
     */
    private String addressUrl;

    private String accessToken;

    private int timeout = 3;

    @Override
    public ReturnT<String> callback(List<HandleCallbackParam> callbackParamList) {
        /**
         * @see com.xxl.job.admin.controller.JobApiController#api(javax.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
         */
        return XxlJobRemotingUtil.postBody(addressUrl + "api/callback", accessToken, timeout, callbackParamList, String.class);
    }

    @Override
    public ReturnT<String> registry(RegistryParam registryParam) {
        /**
         * @see com.xxl.job.admin.controller.JobApiController#api(javax.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
         */
        return XxlJobRemotingUtil.postBody(addressUrl + "api/registry", accessToken, timeout, registryParam, String.class);
    }

    @Override
    public ReturnT<String> registryRemove(RegistryParam registryParam) {
        /**
         * @see com.xxl.job.admin.controller.JobApiController#api(javax.servlet.http.HttpServletRequest, java.lang.String, java.lang.String)
         */
        return XxlJobRemotingUtil.postBody(addressUrl + "api/registryRemove", accessToken, timeout, registryParam, String.class);
    }

}