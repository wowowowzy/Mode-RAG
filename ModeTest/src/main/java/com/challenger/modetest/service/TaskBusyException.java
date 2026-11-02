package com.challenger.modetest.service;

/** 同一任务已有一次双臂测试在进行中。映射为 HTTP 409。 */
public class TaskBusyException extends RuntimeException {

    public TaskBusyException(String message) {
        super(message);
    }
}
