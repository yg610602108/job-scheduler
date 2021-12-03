package com.xxl.job.core.handler.impl;

import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.log.XxlJobLogger;

/**
 * glue job handler
 *
 * @author xuxueli 2016-5-19 21:05:45
 */
public class GlueJobHandler extends IJobHandler {

    private long glueUpdateTime;

    private IJobHandler jobHandler;

    public GlueJobHandler(IJobHandler jobHandler, long glueUpdateTime) {
        this.jobHandler = jobHandler;
        this.glueUpdateTime = glueUpdateTime;
    }

    public long getGlueUpdateTime() {
        return glueUpdateTime;
    }

    @Override
    public ReturnT<String> execute(String param) throws Exception {
        XxlJobLogger.log("----------- glue.version:" + glueUpdateTime + " -----------");
        return jobHandler.execute(param);
    }

}
