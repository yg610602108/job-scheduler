package com.xxl.job.admin.core.thread;

import com.xxl.job.admin.core.conf.XxlJobAdminConfig;
import com.xxl.job.admin.core.trigger.TriggerTypeEnum;
import com.xxl.job.admin.core.trigger.XxlJobTrigger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * job trigger thread pool helper
 *
 * @author xuxueli 2018-07-03 21:08:07
 */
public class JobTriggerPoolHelper {

    private static Logger logger = LoggerFactory.getLogger(JobTriggerPoolHelper.class);

    // ---------------------- trigger pool ----------------------

    // fast/slow thread pool
    private ThreadPoolExecutor fastTriggerPool = null;
    private ThreadPoolExecutor slowTriggerPool = null;

    public void start() {
        /**
         * 初始化快速调度的线程池
         *
         * 最大线程数是 200
         */
        fastTriggerPool = new ThreadPoolExecutor(
                10,
                XxlJobAdminConfig.getAdminConfig().getTriggerPoolFastMax(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(1000),
                r -> new Thread(r, "xxl-job, admin JobTriggerPoolHelper-fastTriggerPool-" + r.hashCode())
        );

        /**
         * 初始化缓慢调度的线程池，比快速调度的线程池容易发生排队等待的情况
         *
         * 最大线程数是 100
         */
        slowTriggerPool = new ThreadPoolExecutor(
                10,
                XxlJobAdminConfig.getAdminConfig().getTriggerPoolSlowMax(),
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(2000),
                r -> new Thread(r, "xxl-job, admin JobTriggerPoolHelper-slowTriggerPool-" + r.hashCode())
        );
    }

    public void stop() {
        //triggerPool.shutdown();
        fastTriggerPool.shutdownNow();
        slowTriggerPool.shutdownNow();
        logger.info(">>>>>>>>> xxl-job trigger thread pool shutdown success.");
    }

    /**
     * 一分钟内超时的次数
     *
     * job timeout count
     */
    private volatile long minTim = System.currentTimeMillis() / 60000;     // ms > min

    /**
     * 保存调度超时的任务
     */
    private volatile ConcurrentMap<Integer, AtomicInteger> jobTimeoutCountMap = new ConcurrentHashMap<>();

    /**
     * 保存触发任务
     *
     * add trigger
     */
    public void addTrigger(final int jobId, final TriggerTypeEnum triggerType,
                           final int failRetryCount, final String executorShardingParam,
                           final String executorParam, final String addressList) {
        /**
         * 默认使用快速调度的线程池
         *
         * choose thread pool
         */
        ThreadPoolExecutor triggerPool = fastTriggerPool;
        // 获取任务对应的超时次数
        AtomicInteger jobTimeoutCount = jobTimeoutCountMap.get(jobId);
        /**
         * 如果一分钟内超时超过 10 次
         *
         * job-timeout 10 times in 1 min
         */
        if (jobTimeoutCount != null && jobTimeoutCount.get() > 10) {
            // 则使用缓慢调度的线程池
            triggerPool = slowTriggerPool;
        }

        /**
         * 添加触发任务调度的任务
         *
         * trigger
         */
        triggerPool.execute(() -> {
            // 任务调度开始时间，单位毫秒
            long start = System.currentTimeMillis();
            try {
                /**
                 * 触发任务调度
                 *
                 * do trigger
                 */
                XxlJobTrigger.trigger(jobId, triggerType, failRetryCount, executorShardingParam, executorParam, addressList);
            }
            catch (Exception e) {
                logger.error(e.getMessage(), e);
            }
            finally {
                /**
                 * 任务调度结束时间，单位分钟
                 *
                 * check timeout-count-map
                 */
                long minTimNow = System.currentTimeMillis() / 60000;
                // 不是同一个分钟的时间桶了
                if (minTim != minTimNow) {
                    // 更新时间桶
                    minTim = minTimNow;
                    // 清空调度超时的集合
                    jobTimeoutCountMap.clear();
                }

                /**
                 * 调度任务花费时间，单位毫秒
                 *
                 * incr timeout-count-map
                 */
                long cost = System.currentTimeMillis() - start;
                /**
                 * 调度任务花费时间超过 500 毫秒
                 *
                 * ob-timeout threshold 500ms
                 */
                if (cost > 500) {
                    // 添加到调度超时的集合中
                    AtomicInteger timeoutCount = jobTimeoutCountMap.putIfAbsent(jobId, new AtomicInteger(1));
                    // 不为空，说明已经存在调度超时的情况
                    if (timeoutCount != null) {
                        // 更新超时次数
                        timeoutCount.incrementAndGet();
                    }
                }
            }
        });
    }

    // ---------------------- helper ----------------------

    private static JobTriggerPoolHelper helper = new JobTriggerPoolHelper();

    public static void toStart() {
        helper.start();
    }

    public static void toStop() {
        helper.stop();
    }

    /**
     * @param jobId
     * @param triggerType
     * @param failRetryCount        >=0: use this param
     *                              <0: use param from job info config
     * @param executorShardingParam
     * @param executorParam         null: use job param
     *                              not null: cover job param
     */
    public static void trigger(int jobId, TriggerTypeEnum triggerType, int failRetryCount, String executorShardingParam, String executorParam, String addressList) {
        helper.addTrigger(jobId, triggerType, failRetryCount, executorShardingParam, executorParam, addressList);
    }

}
