package io.github.chenyouxin8.adreview.constant;

/**
 * 异常类型枚举
 */
public enum AbnormalType {
    LOW_ROI("low_roi", "ROI过低", "high"),
    HIGH_CPA("high_cpa", "转化成本过高", "medium"),
    LOW_CTR("low_ctr", "点击率过低", "medium"),
    BUDGET_OVERSPEND("budget_overspend", "预算超支", "high"),
    SUDDEN_DROP("sudden_drop", "数据突然下滑", "high"),
    CREATIVE_DECAY("creative_decay", "创意衰退", "low");

    private final String code;
    private final String desc;
    private final String defaultLevel;

    AbnormalType(String code, String desc, String defaultLevel) {
        this.code = code;
        this.desc = desc;
        this.defaultLevel = defaultLevel;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public String getDefaultLevel() {
        return defaultLevel;
    }
}
