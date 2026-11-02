package com.challenger.modetest.service;

import com.challenger.modetest.domain.TestRun;

public interface CompareService {

    /** 对一条任务跑 baseline 与 ours 两臂，落库并返回记录。 */
    TestRun compare(String taskId);
}
