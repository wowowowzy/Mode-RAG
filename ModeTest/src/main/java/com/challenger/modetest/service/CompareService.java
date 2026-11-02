package com.challenger.modetest.service;

import com.challenger.modetest.domain.TestRun;
import com.challenger.modetest.dto.ActiveRunView;

import java.util.List;

public interface CompareService {

    /** 对一条任务跑 baseline 与 ours 两臂，落库并返回记录。 */
    TestRun compare(String taskId);

    /** 当前正在跑的对比。空列表表示空闲。 */
    List<ActiveRunView> active();
}
