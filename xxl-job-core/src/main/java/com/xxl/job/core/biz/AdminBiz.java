package com.xxl.job.core.biz;

import com.xxl.job.core.biz.model.HandleCallbackParam;
import com.xxl.job.core.biz.model.RegistryParam;
import com.xxl.job.core.biz.model.ReturnT;

import java.util.List;

/**
 * @author xuxueli 2017-07-27 21:52:49
 */
public interface AdminBiz {

    // ---------------------- callback ----------------------

    /**
     * callback
     */
    ReturnT<String> callback(List<HandleCallbackParam> callbackParamList);

    // ---------------------- registry ----------------------

    /**
     * registry
     */
    ReturnT<String> registry(RegistryParam registryParam);

    /**
     * registry remove
     */
    ReturnT<String> registryRemove(RegistryParam registryParam);

    // ---------------------- biz (custome) ----------------------
    // group、job ... manage

}
