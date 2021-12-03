package com.xxl.job.core.thread;

import com.xxl.job.core.biz.model.HandleCallbackParam;
import com.xxl.job.core.biz.model.ReturnT;
import com.xxl.job.core.biz.model.TriggerParam;
import com.xxl.job.core.executor.XxlJobExecutor;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.handler.impl.MethodJobHandler;
import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.log.XxlJobLogger;
import com.xxl.job.core.util.ShardingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.*;

/**
 * handler thread
 *
 * @author xuxueli 2016-1-16 19:52:47
 */
public class JobThread extends Thread {

    private static Logger logger = LoggerFactory.getLogger(JobThread.class);

    private int jobId;

    private IJobHandler handler;
    /**
     * 保存触发任务的队列，异步线程会一直监听该队列
     */
    private LinkedBlockingQueue<TriggerParam> triggerQueue;
    /**
     * 保存需要调度对应任务所生成的触发日志
     * <p>
     * 避免重复触发相同的触发日志
     */
    private Set<Long> triggerLogIdSet;        // avoid repeat trigger for the same TRIGGER_LOG_ID

    private volatile boolean toStop = false;
    private String stopReason;

    private boolean running = false;    // if running job
    /**
     * 空闲次数，从队列中获取不到数据的次数
     */
    private int idleTimes = 0;            // idel times

    public JobThread(int jobId, IJobHandler handler) {
        this.jobId = jobId;
        this.handler = handler;
        this.triggerQueue = new LinkedBlockingQueue<>();
        this.triggerLogIdSet = Collections.synchronizedSet(new HashSet<Long>());
    }

    public IJobHandler getHandler() {
        return handler;
    }

    /**
     * new trigger to queue
     *
     * @param triggerParam
     * @return
     */
    public ReturnT<String> pushTriggerQueue(TriggerParam triggerParam) {
        // avoid repeat
        if (triggerLogIdSet.contains(triggerParam.getLogId())) {
            logger.info(">>>>>>>>>>> repeate trigger job, logId:{}", triggerParam.getLogId());
            return new ReturnT<>(ReturnT.FAIL_CODE, "repeate trigger job, logId:" + triggerParam.getLogId());
        }

        triggerLogIdSet.add(triggerParam.getLogId());
        triggerQueue.add(triggerParam);
        return ReturnT.SUCCESS;
    }

    /**
     * kill job thread
     *
     * @param stopReason
     */
    public void toStop(String stopReason) {
        /**
         * Thread.interrupt只支持终止线程的阻塞状态(wait、join、sleep)，
         * 在阻塞出抛出InterruptedException异常,但是并不会终止运行的线程本身；
         * 所以需要注意，此处彻底销毁本线程，需要通过共享变量方式；
         */
        this.toStop = true;
        this.stopReason = stopReason;
    }

    /**
     * is running job
     *
     * @return
     */
    public boolean isRunningOrHasQueue() {
        return running || triggerQueue.size() > 0;
    }

    @Override
    public void run() {
        // init
        try {
            /**
             * 调用初始化方法
             */
            handler.init();
        }
        catch (Throwable e) {
            logger.error(e.getMessage(), e);
        }

        // execute
        while (!toStop) {
            running = false;
            idleTimes++;

            TriggerParam triggerParam = null;
            ReturnT<String> executeResult = null;
            try {
                // to check toStop signal, we need cycle, so wo cannot use queue.take(), Instead of queue.poll()(timeout)
                /**
                 * 从队列中获取待调度的任务
                 *
                 * {@link BlockingQueue#poll(long, java.util.concurrent.TimeUnit)}移除并返回队列头部的元素，如果队列为空，则返回{@code null}
                 * {@link BlockingQueue#take()}移除并返回队列头部的元素，如果队列为空，则会阻塞
                 */
                triggerParam = triggerQueue.poll(3L, TimeUnit.SECONDS);
                if (triggerParam != null) {
                    running = true;
                    idleTimes = 0;
                    // 移除当前要处理的该条日志记录
                    triggerLogIdSet.remove(triggerParam.getLogId());

                    // log filename, like "logPath/yyyy-MM-dd/9999.log"
                    String logFileName = XxlJobFileAppender.makeLogFileName(new Date(triggerParam.getLogDateTime()), triggerParam.getLogId());
                    // 保存当前任务的文件存储名称
                    XxlJobFileAppender.contextHolder.set(logFileName);
                    // 保存当前任务的分片数据
                    ShardingUtil.setShardingVo(new ShardingUtil.ShardingVO(triggerParam.getBroadcastIndex(), triggerParam.getBroadcastTotal()));

                    // execute
                    XxlJobLogger.log("<br>----------- xxl-job job execute start -----------<br>----------- Param:" + triggerParam.getExecutorParams());

                    // 有超时时间限制
                    if (triggerParam.getExecutorTimeout() > 0) {
                        // limit timeout
                        Thread futureThread = null;
                        try {
                            final TriggerParam triggerParamTmp = triggerParam;
                            // 执行调度任务
                            FutureTask<ReturnT<String>> futureTask = new FutureTask<>(() -> handler.execute(triggerParamTmp.getExecutorParams()));
                            futureThread = new Thread(futureTask);
                            // 异步调度
                            futureThread.start();
                            // 等待超时时间获取响应
                            executeResult = futureTask.get(triggerParam.getExecutorTimeout(), TimeUnit.SECONDS);
                        }
                        catch (TimeoutException e) {
                            XxlJobLogger.log("<br>----------- xxl-job job execute timeout");
                            XxlJobLogger.log(e);
                            executeResult = new ReturnT<>(IJobHandler.FAIL_TIMEOUT.getCode(), "job execute timeout ");
                        }
                        finally {
                            futureThread.interrupt();
                        }
                    }
                    else {
                        /**
                         * 反射调用目标方法
                         *
                         * @see MethodJobHandler#execute(java.lang.String)
                         *
                         * just execute
                         */
                        executeResult = handler.execute(triggerParam.getExecutorParams());
                    }

                    if (executeResult == null) {
                        executeResult = IJobHandler.FAIL;
                    }
                    else {
                        // 信息过长则截取
                        executeResult.setMsg(executeResult.getMsg() != null && executeResult.getMsg().length() > 50000 ? executeResult.getMsg().substring(0, 50000).concat("...") : executeResult.getMsg());
                        executeResult.setContent(null);    // limit obj size
                    }
                    XxlJobLogger.log("<br>----------- xxl-job job execute end(finish) -----------<br>----------- ReturnT:" + executeResult);
                }
                else {
                    // 获取次数超过阈值
                    if (idleTimes > 30) {
                        // 确认队列中是否有元素
                        if (triggerQueue.size() == 0) {    // avoid concurrent trigger causes jobId-lost
                            /**
                             * 从任务仓库中移除，并停止该异步线程
                             */
                            XxlJobExecutor.removeJobThread(jobId, "executor idle times over limit.");
                        }
                    }
                }
            }
            catch (Throwable e) {
                if (toStop) {
                    XxlJobLogger.log("<br>----------- JobThread toStop, stopReason:" + stopReason);
                }

                StringWriter stringWriter = new StringWriter();
                e.printStackTrace(new PrintWriter(stringWriter));
                String errorMsg = stringWriter.toString();
                executeResult = new ReturnT<>(ReturnT.FAIL_CODE, errorMsg);

                XxlJobLogger.log("<br>----------- JobThread Exception:" + errorMsg + "<br>----------- xxl-job job execute end(error) -----------");
            }
            finally {
                if (triggerParam != null) {
                    // callback handler info
                    if (!toStop) {
                        /**
                         * 调度完成，保存该任务信息
                         */
                        TriggerCallbackThread.pushCallBack(new HandleCallbackParam(triggerParam.getLogId(), triggerParam.getLogDateTime(), executeResult));
                    }
                    // 调度逻辑被终止了
                    else {
                        /**
                         * 调度逻辑被终止了，构造返回值
                         *
                         * is killed
                         */
                        ReturnT<String> stopResult = new ReturnT<>(ReturnT.FAIL_CODE, stopReason + " [job running, killed]");
                        /**
                         * 调度失败，保存该任务信息
                         */
                        TriggerCallbackThread.pushCallBack(new HandleCallbackParam(triggerParam.getLogId(), triggerParam.getLogDateTime(), stopResult));
                    }
                }
            }
        }

        /**
         * 调度逻辑被终止了，再次确认队列中是否还有触发任务
         *
         * callback trigger request in queue
         */
        while (triggerQueue != null && triggerQueue.size() > 0) {
            // 队列中还有触发任务，获取触发任务
            TriggerParam triggerParam = triggerQueue.poll();
            if (triggerParam != null) {
                /**
                 * 调度逻辑被终止了，构造返回值
                 *
                 * is killed
                 */
                ReturnT<String> stopResult = new ReturnT<>(ReturnT.FAIL_CODE, stopReason + " [job not executed, in the job queue, killed.]");
                /**
                 * 调度失败，保存该任务信息
                 */
                TriggerCallbackThread.pushCallBack(new HandleCallbackParam(triggerParam.getLogId(), triggerParam.getLogDateTime(), stopResult));
            }
        }

        // destroy
        try {
            /**
             * 调用销毁方法
             */
            handler.destroy();
        }
        catch (Throwable e) {
            logger.error(e.getMessage(), e);
        }

        logger.info(">>>>>>>>>>> xxl-job JobThread stoped, hashCode:{}", Thread.currentThread());
    }

}
