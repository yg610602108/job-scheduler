package com.xxl.job.core.thread;

import com.xxl.job.core.biz.AdminBiz;
import com.xxl.job.core.biz.model.HandleCallbackParam;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.enums.RegistryConfig;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.log.XxlJobLogger;
import com.xxl.job.core.util.FileUtil;
import com.xxl.job.core.util.JdkSerializeTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by xuxueli on 16/7/22.
 */
public class TriggerCallbackThread {

    private static Logger logger = LoggerFactory.getLogger(TriggerCallbackThread.class);

    private static TriggerCallbackThread instance = new TriggerCallbackThread();

    public static TriggerCallbackThread getInstance() {
        return instance;
    }

    /**
     * 保存调度完成的任务的队列，对失败的任务进行重试
     *
     * job results callback queue
     */
    private LinkedBlockingQueue<HandleCallbackParam> callBackQueue = new LinkedBlockingQueue<>();

    public static void pushCallBack(HandleCallbackParam callback) {
        /**
         * 调度完成任务存入队列
         *
         * @see TriggerCallbackThread#start()
         */
        getInstance().callBackQueue.add(callback);
        logger.debug(">>>>>>>>>>> xxl-job, push callback request, logId:{}", callback.getLogId());
    }

    /**
     * callback thread
     */
    private Thread triggerCallbackThread;

    private Thread triggerRetryCallbackThread;

    private volatile boolean toStop = false;

    public void start() {
        // valid
        if (XxlJobExecutor.getAdminBizList() == null) {
            logger.warn(">>>>>>>>>>> xxl-job, executor callback config fail, adminAddresses is null.");
            return;
        }

        /**
         * 启动线程异步执行
         *
         * callback
         */
        triggerCallbackThread = new Thread(() -> {
            // normal callback
            while (!toStop) {
                try {
                    /**
                     * 从队列中阻塞获取调度完成的任务
                     *
                     * {@link BlockingQueue#poll(long, java.util.concurrent.TimeUnit)}移除并返回队列头部的元素，如果队列为空，则返回{@code null}
                     * {@link BlockingQueue#take()}移除并返回队列头部的元素，如果队列为空，则会阻塞
                     */
                    HandleCallbackParam callback = getInstance().callBackQueue.take();
                    if (callback != null) {
                        // callback list param
                        List<HandleCallbackParam> callbackParamList = new ArrayList<>();
                        /**
                         * 从这个队列删除所有可用的元素并将它们添加到给定的集合
                         */
                        getInstance().callBackQueue.drainTo(callbackParamList);
                        // 加上之前阻塞获取的
                        callbackParamList.add(callback);

                        // callback, will retry if error
                        if (callbackParamList.size() > 0) {
                            /**
                             * 处理调度后的逻辑
                             * 1、是否有子任务需要调度
                             * 2、调度失败日志写入日志文件
                             */
                            doCallback(callbackParamList);
                        }
                    }
                }
                catch (Exception e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }

            /**
             * 如果异步逻辑被终止了，则执行最后的逻辑
             *
             * last callback
             */
            try {
                List<HandleCallbackParam> callbackParamList = new ArrayList<>();
                getInstance().callBackQueue.drainTo(callbackParamList);
                if (callbackParamList.size() > 0) {
                    /**
                     * 处理调度后的逻辑
                     * 1、是否有子任务需要调度
                     * 2、调度失败日志写入日志文件
                     */
                    doCallback(callbackParamList);
                }
            }
            catch (Exception e) {
                if (!toStop) {
                    logger.error(e.getMessage(), e);
                }
            }
            logger.info(">>>>>>>>>>> xxl-job, executor callback thread destory.");
        });
        /**
         * 将此线程标记为守护程序线程或用户线程
         * 当所有正在运行的线程都是守护程序线程时，Java 虚拟机将退出
         */
        triggerCallbackThread.setDaemon(true);
        triggerCallbackThread.setName("xxl-job, executor TriggerCallbackThread");
        triggerCallbackThread.start();

        // retry
        triggerRetryCallbackThread = new Thread(() -> {
            while (!toStop) {
                try {
                    retryFailCallbackFile();
                }
                catch (Exception e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
                try {
                    TimeUnit.SECONDS.sleep(RegistryConfig.BEAT_TIMEOUT);
                }
                catch (InterruptedException e) {
                    if (!toStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }
            logger.info(">>>>>>>>>>> xxl-job, executor retry callback thread destory.");
        });
        triggerRetryCallbackThread.setDaemon(true);
        triggerRetryCallbackThread.start();
    }

    public void toStop() {
        toStop = true;
        // stop callback, interrupt and wait
        if (triggerCallbackThread != null) {    // support empty admin address
            triggerCallbackThread.interrupt();
            try {
                triggerCallbackThread.join();
            }
            catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // stop retry, interrupt and wait
        if (triggerRetryCallbackThread != null) {
            triggerRetryCallbackThread.interrupt();
            try {
                triggerRetryCallbackThread.join();
            }
            catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }
    }

    /**
     * do callback, will retry if error
     *
     * @param callbackParamList
     */
    private void doCallback(List<HandleCallbackParam> callbackParamList) {
        boolean callbackRet = false;
        // callback, will retry if error
        for (AdminBiz adminBiz : XxlJobExecutor.getAdminBizList()) {
            try {
                /**
                 * @see com.xxl.job.admin.service.impl.AdminBizImpl#callback(java.util.List)
                 */
                ReturnT<String> callbackResult = adminBiz.callback(callbackParamList);
                // 批量处理成功
                if (callbackResult != null && ReturnT.SUCCESS_CODE == callbackResult.getCode()) {
                    // 保存日志文件名称和日志
                    callbackLog(callbackParamList, "<br>----------- xxl-job job callback finish.");
                    callbackRet = true;
                    break;
                }
                else {
                    // 保存日志文件名称和日志
                    callbackLog(callbackParamList, "<br>----------- xxl-job job callback fail, callbackResult:" + callbackResult);
                }
            }
            catch (Exception e) {
                // 保存日志文件名称和日志
                callbackLog(callbackParamList, "<br>----------- xxl-job job callback error, errorMsg:" + e.getMessage());
            }
        }

        if (!callbackRet) {
            /**
             * 调度失败日志写入日志文件
             */
            appendFailCallbackFile(callbackParamList);
        }
    }

    /**
     * callback log
     */
    private void callbackLog(List<HandleCallbackParam> callbackParamList, String logContent) {
        for (HandleCallbackParam callbackParam : callbackParamList) {
            String logFileName = XxlJobFileAppender.makeLogFileName(new Date(callbackParam.getLogDateTim()), callbackParam.getLogId());
            XxlJobFileAppender.contextHolder.set(logFileName);
            XxlJobLogger.log(logContent);
        }
    }

    // ---------------------- fail-callback file ----------------------

    private static String failCallbackFilePath = XxlJobFileAppender.getLogPath().concat(File.separator).concat("callbacklog").concat(File.separator);

    private static String failCallbackFileName = failCallbackFilePath.concat("xxl-job-callback-{x}").concat(".log");

    /**
     * 调度日志写入日志文件
     */
    private void appendFailCallbackFile(List<HandleCallbackParam> callbackParamList) {
        // valid
        if (callbackParamList == null || callbackParamList.size() == 0) {
            return;
        }

        // append file
        byte[] callbackParamList_bytes = JdkSerializeTool.serialize(callbackParamList);

        File callbackLogFile = new File(failCallbackFileName.replace("{x}", String.valueOf(System.currentTimeMillis())));
        if (callbackLogFile.exists()) {
            for (int i = 0; i < 100; i++) {
                callbackLogFile = new File(failCallbackFileName.replace("{x}", String.valueOf(System.currentTimeMillis()).concat("-").concat(String.valueOf(i))));
                if (!callbackLogFile.exists()) {
                    break;
                }
            }
        }

        /**
         * 写入日志文件
         */
        FileUtil.writeFileContent(callbackLogFile, callbackParamList_bytes);
    }

    private void retryFailCallbackFile() {
        /**
         * 获取调度失败的文件目录
         *
         * valid
         */
        File callbackLogPath = new File(failCallbackFilePath);
        // 目录不存在
        if (!callbackLogPath.exists()) {
            return;
        }
        // 是文件不是目录
        if (callbackLogPath.isFile()) {
            callbackLogPath.delete();
        }
        // 是目录且目录下有文件
        if (!(callbackLogPath.isDirectory() && callbackLogPath.list() != null && callbackLogPath.list().length > 0)) {
            return;
        }

        // load and clear file, retry
        for (File callbackLogFile : callbackLogPath.listFiles()) {
            /**
             * 读取文件内容
             */
            byte[] callbackParamList_bytes = FileUtil.readFileContent(callbackLogFile);
            /**
             * 反序列化
             */
            List<HandleCallbackParam> callbackParamList =
                    (List<HandleCallbackParam>) JdkSerializeTool.deserialize(callbackParamList_bytes, List.class);
            // 删除文件
            callbackLogFile.delete();
            /**
             * 处理调度后的逻辑
             * 1、是否有子任务需要调度
             * 2、调度失败日志写入日志文件
             */
            doCallback(callbackParamList);
        }
    }

}
