package com.xxl.job.core.biz;

import com.xxl.job.core.biz.model.*;

/**
 * Created by xuxueli on 17/3/1.
 */
public interface ExecutorBiz {

    /**
     * beat
     */
    ReturnT<String> beat();

    /**
     * idle beat
     */
    ReturnT<String> idleBeat(IdleBeatParam idleBeatParam);

    /**
     * run
     */
    ReturnT<String> run(TriggerParam triggerParam);

    /**
     * kill
     */
    ReturnT<String> kill(KillParam killParam);

    /**
     * log
     */
    ReturnT<LogResult> log(LogParam logParam);

}
