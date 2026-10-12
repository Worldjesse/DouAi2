package com.douai.module.ui;

/** A small presentation model; scanner details stay owned by the scan package. */
public final class ScanStatus {
    public final String title;
    public final String detail;
    public final boolean ready;
    public final boolean restartRequired;

    public ScanStatus(String title, String detail, boolean ready, boolean restartRequired) {
        this.title = title == null ? "等待扫描" : title;
        this.detail = detail == null ? "启动抖音后会检查当前版本的适配情况。" : detail;
        this.ready = ready;
        this.restartRequired = restartRequired;
    }

    public static ScanStatus pending() {
        return new ScanStatus("等待扫描", "启动抖音后会检查当前版本的适配情况。", false, false);
    }
}
