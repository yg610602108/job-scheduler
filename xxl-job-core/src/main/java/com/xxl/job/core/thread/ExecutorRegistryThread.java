package com.xxl.job.core.thread;

import com.xxl.job.core.biz.AdminBiz;
import com.xxl.job.core.biz.client.AdminBizClient;
import com.xxl.job.core.biz.model.RegistryParam;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.enums.RegistryConfig;
import com.xxl.job.core.executor.XxlJobExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Created by xuxueli on 17/3/2.
 */
public class ExecutorRegistryThread {

    private static Logger logger = LoggerFactory.getLogger(ExecutorRegistryThread.class);

    private static ExecutorRegistryThread instance = new ExecutorRegistryThread();

    public static ExecutorRegistryThread getInstance() {
        return instance;
    }

    private Thread registryThread;

    private volatile boolean toStop = false;

    public void start(final String appName, final String address) {
        // valid
        if (appName == null || appName.trim().length() == 0) {
            logger.warn(">>>>>>>>>>> xxl-job, executor registry config fail, appName is null.");
            return;
        }
        if (XxlJobExecutor.getAdminBizList() == null) {
            logger.warn(">>>>>>>>>>> xxl-job, executor registry config fail, adminAddresses is null.");
            return;
        }

        registryThread = new Thread(() -> {
            // registry
            while (!toStop) {
                try {
                    // 封装注册参数
                    RegistryParam registryParam = new RegistryParam(RegistryConfig.RegistrationType.EXECUTOR.name(), appName, address);
                    /**
                     * 遍历所有的调度器
                     */
                    for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
                        try {
                            /**
                             * 执行注册逻辑
                             *
                             * @see AdminBizClient#registry(com.xxl.job.core.biz.model.RegistryParam)
                             */
                            ReturnT<String> registryResult = adminBiz.registry(registryParam);
                            if (registryResult != null && ReturnT.SUCCESS_CODE == registryResult.getCode()) {
                                registryResult = ReturnT.SUCCESS;
                                logger.debug(">>>>>>>>>>> xxl-job registry success, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                                break;
                            }
                            else {
                                logger.info(">>>>>>>>>>> xxl-job registry fail, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                            }
                        }
                        catch (Exception e) {
                            logger.info(">>>>>>>>>>> xxl-job registry error, registryParam:{}", registryParam, e);
                        }
                    }
                }
                catch (Exception e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }

                try {
                    if (!toStop) {
                        TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
                    }
                }
                catch (InterruptedException e) {
                    if (!toStop) {
                        logger.warn(">>>>>>>>>>> xxl-job, executor registry thread interrupted, error msg:{}", e.getMessage());
                    }
                }
            }

            // registry remove
            try {
                // 封装注册参数
                RegistryParam registryParam = new RegistryParam(RegistryConfig.RegistrationType.EXECUTOR.name(), appName, address);
                /**
                 * 遍历所有的调度器
                 */
                for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
                    try {
                        /**
                         * 执行删除注册逻辑
                         *
                         * @see AdminBizClient#registryRemove(RegistryParam)
                         */
                        ReturnT<String> registryResult = adminBiz.registryRemove(registryParam);
                        if (registryResult != null && ReturnT.SUCCESS_CODE == registryResult.getCode()) {
                            registryResult = ReturnT.SUCCESS;
                            logger.info(">>>>>>>>>>> xxl-job registry-remove success, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                            break;
                        }
                        else {
                            logger.info(">>>>>>>>>>> xxl-job registry-remove fail, registryParam:{}, registryResult:{}", new Object[]{registryParam, registryResult});
                        }
                    }
                    catch (Exception e) {
                        if (!toStop) {
                            logger.info(">>>>>>>>>>> xxl-job registry-remove error, registryParam:{}", registryParam, e);
                        }
                    }
                }
            }
            catch (Exception e) {
                if (!toStop) {
                    logger.error(e.getMessage(), e);
                }
            }

            logger.info(">>>>>>>>>>> xxl-job, executor registry thread destory.");
        });
        registryThread.setDaemon(true);
        registryThread.setName("xxl-job, executor ExecutorRegistryThread");
        registryThread.start();
    }

    public void toStop() {
        toStop = true;
        // interrupt and wait
        registryThread.interrupt();
        try {
            registryThread.join();
        }
        catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
    }

}
