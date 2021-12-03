package com.xxl.job.core.executor;

import com.xxl.job.core.biz.AdminBiz;
import com.xxl.job.core.biz.client.AdminBizClient;
import com.xxl.job.core.handler.IJobHandler;
import com.xxl.job.core.handler.annotation.XxlJob;
import com.xxl.job.core.log.XxlJobFileAppender;
import com.xxl.job.core.server.EmbedServer;
import com.xxl.job.core.thread.JobLogFileCleanThread;
import com.xxl.job.core.thread.JobThread;
import com.xxl.job.core.thread.TriggerCallbackThread;
import com.xxl.job.core.util.IpUtil;
import com.xxl.job.core.util.NetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Created by xuxueli on 2016/3/2 21:14.
 */
public class XxlJobExecutor {

    private static final Logger logger = LoggerFactory.getLogger(XxlJobExecutor.class);

    // ---------------------- param ----------------------
    /**
     * 调度中心地址
     */
    private String adminAddresses;
    /**
     * 访问令牌
     */
    private String accessToken;
    /**
     * 执行器名称
     */
    private String appName;
    /**
     * 执行器地址，可根据IP和端口自动获取
     */
    private String address;
    /**
     * 执行器IP，可自动获取
     */
    private String ip;
    /**
     * 执行器暴露的端口
     */
    private int port;
    /**
     * 执行器运行日志文件存储的磁盘位置
     */
    private String logPath;
    /**
     * 执行器日志文件定期清理功能，指定日志保存天数，日志文件过期自动删除
     */
    private int logRetentionDays;

    public void setAdminAddresses(String adminAddresses) {
        this.adminAddresses = adminAddresses;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public void setAppName(String appName) {
        this.appName = appName;
    }

    public void setAddress(String address) {
        this.address = address;
    }

    public void setIp(String ip) {
        this.ip = ip;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public void setLogPath(String logPath) {
        this.logPath = logPath;
    }

    public void setLogRetentionDays(int logRetentionDays) {
        this.logRetentionDays = logRetentionDays;
    }

    // ---------------------- start + stop ----------------------
    public void start() throws Exception {

        // init log path
        XxlJobFileAppender.initLogPath(logPath);

        /**
         * init invoker, admin-client
         *
         * 初始化调度中心
         */
        initAdminBizList(adminAddresses, accessToken);

        /**
         * init JobLogFileCleanThread
         *
         * 初始化任务日志文件清理线程
         */
        JobLogFileCleanThread.getInstance().start(logRetentionDays);

        /**
         * init TriggerCallbackThread
         *
         * 初始化回调触发线程
         */
        TriggerCallbackThread.getInstance().start();

        /**
         * init executor-server
         *
         * 初始化Netty服务，并异步将自己注册到调度中心
         */
        initEmbedServer(address, ip, port, appName, accessToken);
    }

    public void destroy() {
        // destroy executor-server
        stopEmbedServer();

        // destroy jobThreadRepository
        if (jobThreadRepository.size() > 0) {
            for (Map.Entry<Integer, JobThread> item : jobThreadRepository.entrySet()) {
                JobThread oldJobThread = removeJobThread(item.getKey(), "web container destroy and kill the job.");
                // wait for job thread push result to callback queue
                if (oldJobThread != null) {
                    try {
                        oldJobThread.join();
                    }
                    catch (InterruptedException e) {
                        logger.error(">>>>>>>>>>> xxl-job, JobThread destroy(join) error, jobId:{}", item.getKey(), e);
                    }
                }
            }
            jobThreadRepository.clear();
        }
        jobHandlerRepository.clear();

        // destroy JobLogFileCleanThread
        JobLogFileCleanThread.getInstance().toStop();

        // destroy TriggerCallbackThread
        TriggerCallbackThread.getInstance().toStop();
    }

    // ---------------------- admin-client (rpc invoker) ----------------------
    private static List<AdminBiz> adminBizList;

    private void initAdminBizList(String adminAddresses, String accessToken) throws Exception {
        if (adminAddresses != null && adminAddresses.trim().length() > 0) {
            for (String address : adminAddresses.trim().split(",")) {
                if (address != null && address.trim().length() > 0) {
                    // 实例化调度器
                    AdminBiz adminBiz = new AdminBizClient(address.trim(), accessToken);
                    if (adminBizList == null) {
                        adminBizList = new ArrayList<>();
                    }
                    adminBizList.add(adminBiz);
                }
            }
        }
    }

    public static List<AdminBiz> getAdminBizList() {
        return adminBizList;
    }

    // ---------------------- executor-server (rpc provider) ----------------------
    private EmbedServer embedServer = null;

    private void initEmbedServer(String address, String ip, int port, String appName, String accessToken) throws Exception {
        // fill ip port
        // 获取暴露的端口【默认是9999】
        port = port > 0 ? port : NetUtil.findAvailablePort(9999);
        // 获取配置的IP【默认是本机IP】
        ip = (ip != null && ip.trim().length() > 0) ? ip : IpUtil.getIp();

        // generate address
        if (address == null || address.trim().length() == 0) {
            // registry-address：default use address to registry , otherwise use ip:port if address is null
            String ipPortAddress = IpUtil.getIpPort(ip, port);
            address = "http://{ip_port}/".replace("{ip_port}", ipPortAddress);
        }

        // start
        embedServer = new EmbedServer();
        /**
         * 将自己作为服务端暴露Socket连接，并异步将自己注册到调度中心
         */
        embedServer.start(address, port, appName, accessToken);
    }

    private void stopEmbedServer() {
        // stop provider factory
        try {
            embedServer.stop();
        }
        catch (Exception e) {
            logger.error(e.getMessage(), e);
        }
    }

    // ---------------------- job handler repository ----------------------
    /**
     * key   - 任务名称【判断了不能重复】{@link XxlJob#value()}
     * value - 封装了目标 Bean 和目标方法的{@link com.xxl.job.core.handler.impl.MethodJobHandler}
     */
    private static ConcurrentMap<String, IJobHandler> jobHandlerRepository = new ConcurrentHashMap<>();

    public static IJobHandler registerJobHandler(String name, IJobHandler jobHandler) {
        logger.info(">>>>>>>>>>> xxl-job register job handler success, name:{}, jobHandler:{}", name, jobHandler);
        return jobHandlerRepository.put(name, jobHandler);
    }

    public static IJobHandler loadJobHandler(String name) {
        return jobHandlerRepository.get(name);
    }

    // ---------------------- job thread repository ----------------------
    private static ConcurrentMap<Integer, JobThread> jobThreadRepository = new ConcurrentHashMap<>();

    public static JobThread registerJobThread(int jobId, IJobHandler handler, String removeOldReason) {
        /**
         * 初始化{@link JobThread}
         */
        JobThread newJobThread = new JobThread(jobId, handler);
        /**
         * 开启任务线程监控
         *
         * @see JobThread#run()
         */
        newJobThread.start();
        logger.info(">>>>>>>>>>> xxl-job register JobThread success, jobId:{}, handler:{}", new Object[]{jobId, handler});
        // 存入集合
        JobThread oldJobThread = jobThreadRepository.put(jobId, newJobThread);    // putIfAbsent | oh my god, map's put method return the old value!!!
        /**
         * 已经存在
         */
        if (oldJobThread != null) {
            oldJobThread.toStop(removeOldReason);
            /**
             * 中断此线程（此线程不一定是当前线程，而是指调用该方法的{@link Thread}实例所代表的线程）
             * 但实际上只是给线程设置一个中断标志，线程仍会继续运行，并且是存活状态
             */
            oldJobThread.interrupt();
        }

        return newJobThread;
    }

    public static JobThread removeJobThread(int jobId, String removeOldReason) {
        JobThread oldJobThread = jobThreadRepository.remove(jobId);
        if (oldJobThread != null) {
            oldJobThread.toStop(removeOldReason);
            /**
             * 中断此线程（此线程不一定是当前线程，而是指调用该方法的{@link Thread}实例所代表的线程）
             * 但实际上只是给线程设置一个中断标志，线程仍会继续运行，并且是存活状态
             */
            oldJobThread.interrupt();

            return oldJobThread;
        }
        return null;
    }

    public static JobThread loadJobThread(int jobId) {
        JobThread jobThread = jobThreadRepository.get(jobId);
        return jobThread;
    }

}
