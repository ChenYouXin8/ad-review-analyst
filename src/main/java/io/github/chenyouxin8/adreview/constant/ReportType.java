package io.github.chenyouxin8.adreview.constant;

/**
 * 报告类型枚举
 */
public enum ReportType {
    DAILY("daily", "日报"),
    WEEKLY("weekly", "周报"),
    CUSTOM("custom", "自定义");

    private final String code;
    private final String desc;

    ReportType(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
