package com.douai.module.config;

/** A known backup limit, kept separate from provider errors and malformed data. */
public final class BackupLimitException extends IllegalArgumentException {
    public enum Section { CONFIG, ANALYTICS, FILE }
    public final Section section;

    public BackupLimitException(Section section) {
        super(section == Section.CONFIG ? "配置内容超过 2 MiB 限制。"
                : section == Section.ANALYTICS ? "本地分析数据超过 30 MiB 或记录数量限制。"
                : "备份文件超过 32 MiB 限制。");
        this.section = section;
    }
}
