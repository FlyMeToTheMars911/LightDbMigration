package com.hundsun.lightdbmigration.service;

public class StopRequestedException extends Exception {

    private static final long serialVersionUID = 1L;

    public StopRequestedException() {
        super("任务已被终止");
    }
}
