package com.xxl.job.admin.core.thread;

import com.xxl.job.admin.core.conf.XxlJobAdminConfig;
import com.xxl.job.admin.core.cron.CronExpression;
import com.xxl.job.admin.core.model.XxlJobInfo;
import com.xxl.job.admin.core.trigger.TriggerTypeEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * @author xuxueli 2019-05-21
 */
public class JobScheduleHelper {

    private static Logger logger = LoggerFactory.getLogger(JobScheduleHelper.class);

    private static JobScheduleHelper instance = new JobScheduleHelper();

    public static JobScheduleHelper getInstance() {
        return instance;
    }

    public static final long PRE_READ_MS = 5000;    // pre read

    private Thread scheduleThread;
    private Thread ringThread;
    private volatile boolean scheduleThreadToStop = false;
    private volatile boolean ringThreadToStop = false;

    private volatile static Map<Integer, List<Integer>> ringData = new ConcurrentHashMap<>();

    public void start() {
        // schedule thread
        scheduleThread = new Thread(() -> {
            try {
                TimeUnit.MILLISECONDS.sleep(5000 - System.currentTimeMillis() % 1000);
            }
            catch (InterruptedException e) {
                if (!scheduleThreadToStop) {
                    logger.error(e.getMessage(), e);
                }
            }
            logger.info(">>>>>>>>> init xxl-job admin scheduler success.");

            // pre-read count: threadpool-size * trigger-qps (each trigger cost 50ms, qps = 1000/50 = 20)
            int preReadCount = (XxlJobAdminConfig.getAdminConfig().getTriggerPoolFastMax() + XxlJobAdminConfig.getAdminConfig().getTriggerPoolSlowMax()) * 20;
            while (!scheduleThreadToStop) {
                // Scan Job
                long start = System.currentTimeMillis();

                Connection conn = null;
                Boolean connAutoCommit = null;
                PreparedStatement preparedStatement = null;

                boolean preReadSuc = true;
                try {
                    // 获取数据库连接
                    conn = XxlJobAdminConfig.getAdminConfig().getDataSource().getConnection();
                    // 获取事务提交方式
                    connAutoCommit = conn.getAutoCommit();
                    // 设置为手动提交
                    conn.setAutoCommit(false);
                    /**
                     * 加数据库表锁
                     *
                     * 有索引或者查询是具体的某一行或某几行数据时，加的是行锁
                     * 没有索引或者查询的是整张表的数据时，加的是表锁
                     */
                    preparedStatement = conn.prepareStatement("select * from xxl_job_lock where lock_name = 'schedule_lock' for update");
                    preparedStatement.execute();

                    // tx start

                    // 1、pre read
                    long nowTime = System.currentTimeMillis();
                    /**
                     * 下次调度时间距离当前时间延后 5 秒内的任务
                     *
                     * 超前 5 秒查询下次要调度的任务
                     */
                    List<XxlJobInfo> scheduleList = XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleJobQuery(nowTime + PRE_READ_MS, preReadCount);
                    if (scheduleList != null && scheduleList.size() > 0) {
                        // 2、push time-ring
                        for (XxlJobInfo jobInfo : scheduleList) {
                            /**
                             * time-ring jump
                             *
                             * 远远未到调度时间
                             */
                            if (nowTime > jobInfo.getTriggerNextTime() + PRE_READ_MS) {
                                // 2.1、trigger-expire > 5s：pass && make next-trigger-time
                                logger.warn(">>>>>>>>>>> xxl-job, schedule misfire, jobId = " + jobInfo.getId());
                                /**
                                 * fresh next
                                 *
                                 * 更新任务下次调度时间
                                 */
                                refreshNextValidTime(jobInfo, new Date());
                            }
                            // 即将到调度时间
                            else if (nowTime > jobInfo.getTriggerNextTime()) {
                                // 2.2、trigger-expire < 5s：direct-trigger && make next-trigger-time

                                /**
                                 * 1、trigger
                                 *
                                 * 直接调度
                                 */
                                JobTriggerPoolHelper.trigger(
                                        jobInfo.getId(),
                                        TriggerTypeEnum.CRON,
                                        -1,
                                        null,
                                        null,
                                        null
                                );
                                logger.debug(">>>>>>>>>>> xxl-job, schedule push trigger : jobId = " + jobInfo.getId());
                                /**
                                 * 2、fresh next
                                 *
                                 * 更新任务下次调度时间
                                 */
                                refreshNextValidTime(jobInfo, new Date());
                                /**
                                 * next-trigger-time in 5s, pre-read again
                                 *
                                 * 下次调度时间在 5 秒内，提前处理
                                 */
                                if (jobInfo.getTriggerStatus() == 1 && nowTime + PRE_READ_MS > jobInfo.getTriggerNextTime()) {
                                    /**
                                     * 1、make ring second
                                     *
                                     * 计算任务在时间轮中的索引
                                     */
                                    int ringSecond = (int) ((jobInfo.getTriggerNextTime() / 1000) % 60);
                                    /**
                                     * 2、push time ring
                                     *
                                     * 保存到时间轮中
                                     */
                                    pushTimeRing(ringSecond, jobInfo.getId());
                                    /**
                                     * 3、fresh next
                                     *
                                     * 更新任务下次调度时间
                                     */
                                    refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));
                                }
                            }
                            // 已经过了调度时间
                            else {
                                // 2.3、trigger-pre-read：time-ring trigger && make next-trigger-time

                                /**
                                 * 1、make ring second
                                 *
                                 * 计算任务在时间轮中的索引
                                 */
                                int ringSecond = (int) ((jobInfo.getTriggerNextTime() / 1000) % 60);
                                /**
                                 * 2、push time ring
                                 *
                                 * 保存到时间轮中
                                 */
                                pushTimeRing(ringSecond, jobInfo.getId());
                                /**
                                 * 3、fresh next
                                 *
                                 * 更新任务下次调度时间
                                 */
                                refreshNextValidTime(jobInfo, new Date(jobInfo.getTriggerNextTime()));
                            }
                        }

                        // 3、update trigger info
                        for (XxlJobInfo jobInfo : scheduleList) {
                            // 更新任务信息
                            XxlJobAdminConfig.getAdminConfig().getXxlJobInfoDao().scheduleUpdate(jobInfo);
                        }
                    }
                    else {
                        preReadSuc = false;
                    }

                    // tx stop

                }
                catch (Exception e) {
                    if (!scheduleThreadToStop) {
                        logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread error:{}", e);
                    }
                }
                finally {
                    // commit
                    if (conn != null) {
                        try {
                            conn.commit();
                        }
                        catch (SQLException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                        try {
                            // 还原之前的提交方式
                            conn.setAutoCommit(connAutoCommit);
                        }
                        catch (SQLException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                        try {
                            // 关闭数据库连接
                            conn.close();
                        }
                        catch (SQLException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }

                    // close PreparedStatement
                    if (null != preparedStatement) {
                        try {
                            preparedStatement.close();
                        }
                        catch (SQLException e) {
                            if (!scheduleThreadToStop) {
                                logger.error(e.getMessage(), e);
                            }
                        }
                    }
                }

                long cost = System.currentTimeMillis() - start;

                // Wait seconds, align second
                if (cost < 1000) {  // scan-overtime, not wait
                    try {
                        // pre-read period: success > scan each second; fail > skip this period;
                        TimeUnit.MILLISECONDS.sleep((preReadSuc ? 1000 : PRE_READ_MS) - System.currentTimeMillis() % 1000);
                    }
                    catch (InterruptedException e) {
                        if (!scheduleThreadToStop) {
                            logger.error(e.getMessage(), e);
                        }
                    }
                }
            }

            logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#scheduleThread stop");
        });
        scheduleThread.setDaemon(true);
        scheduleThread.setName("xxl-job, admin JobScheduleHelper#scheduleThread");
        scheduleThread.start();

        // ring thread
        ringThread = new Thread(() -> {
            // align second
            try {
                TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
            }
            catch (InterruptedException e) {
                if (!ringThreadToStop) {
                    logger.error(e.getMessage(), e);
                }
            }

            while (!ringThreadToStop) {
                try {
                    // second data
                    List<Integer> ringItemData = new ArrayList<>();
                    int nowSecond = Calendar.getInstance().get(Calendar.SECOND);
                    /**
                     * 避免处理耗时太长，获取当前时间刻度和前一个时间刻度的调度任务
                     */
                    for (int i = 0; i < 2; i++) {
                        // 获取时间轮中对应要调度的任务编号
                        List<Integer> tmpData = ringData.remove((nowSecond + 60 - i) % 60);
                        if (tmpData != null) {
                            ringItemData.addAll(tmpData);
                        }
                    }

                    // ring trigger
                    logger.debug(">>>>>>>>>>> xxl-job, time-ring beat : " + nowSecond + " = " + Collections.singletonList(ringItemData));
                    if (ringItemData.size() > 0) {
                        // do trigger
                        for (int jobId : ringItemData) {
                            /**
                             * do trigger
                             *
                             * 调度任务
                             */
                            JobTriggerPoolHelper.trigger(jobId, TriggerTypeEnum.CRON, -1, null, null, null);
                        }
                        // clear
                        ringItemData.clear();
                    }
                }
                catch (Exception e) {
                    if (!ringThreadToStop) {
                        logger.error(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread error:{}", e);
                    }
                }

                // next second, align second
                try {
                    TimeUnit.MILLISECONDS.sleep(1000 - System.currentTimeMillis() % 1000);
                }
                catch (InterruptedException e) {
                    if (!ringThreadToStop) {
                        logger.error(e.getMessage(), e);
                    }
                }
            }

            logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper#ringThread stop");
        });
        ringThread.setDaemon(true);
        ringThread.setName("xxl-job, admin JobScheduleHelper#ringThread");
        ringThread.start();
    }

    private void refreshNextValidTime(XxlJobInfo jobInfo, Date fromTime) throws ParseException {
        Date nextValidTime = new CronExpression(jobInfo.getJobCron()).getNextValidTimeAfter(fromTime);
        if (nextValidTime != null) {
            // 最近触发时间
            jobInfo.setTriggerLastTime(jobInfo.getTriggerNextTime());
            // 下次触发时间
            jobInfo.setTriggerNextTime(nextValidTime.getTime());
        }
        else {
            jobInfo.setTriggerStatus(0);
            jobInfo.setTriggerLastTime(0);
            jobInfo.setTriggerNextTime(0);
        }
    }

    private void pushTimeRing(int ringSecond, int jobId) {
        // push async ring
        List<Integer> ringItemData = ringData.get(ringSecond);
        if (ringItemData == null) {
            ringItemData = new ArrayList<>();
            ringData.put(ringSecond, ringItemData);
        }
        ringItemData.add(jobId);

        logger.debug(">>>>>>>>>>> xxl-job, schedule push time-ring : " + ringSecond + " = " + Collections.singletonList(ringItemData));
    }

    public void toStop() {
        // 1、stop schedule
        scheduleThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);  // wait
        }
        catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (scheduleThread.getState() != Thread.State.TERMINATED) {
            // interrupt and wait
            scheduleThread.interrupt();
            try {
                scheduleThread.join();
            }
            catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // if has ring data
        boolean hasRingData = false;
        if (!ringData.isEmpty()) {
            for (int second : ringData.keySet()) {
                List<Integer> tmpData = ringData.get(second);
                if (tmpData != null && tmpData.size() > 0) {
                    hasRingData = true;
                    break;
                }
            }
        }
        if (hasRingData) {
            try {
                TimeUnit.SECONDS.sleep(8);
            }
            catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        // stop ring (wait job-in-memory stop)
        ringThreadToStop = true;
        try {
            TimeUnit.SECONDS.sleep(1);
        }
        catch (InterruptedException e) {
            logger.error(e.getMessage(), e);
        }
        if (ringThread.getState() != Thread.State.TERMINATED) {
            // interrupt and wait
            ringThread.interrupt();
            try {
                ringThread.join();
            }
            catch (InterruptedException e) {
                logger.error(e.getMessage(), e);
            }
        }

        logger.info(">>>>>>>>>>> xxl-job, JobScheduleHelper stop");
    }

}
