package io.github.chenyouxin8.adreview.constant;

/**
 * 数据来源类型，用于标识本次取数究竟是真实接入还是降级兜底。
 */
public enum DataSourceType {

    /** 千川实时数据（通过 MCP 工具获取） */
    MCP_LIVE("千川实时数据"),

    /** 模拟演示数据（MCP 不可用或调用失败时的兜底） */
    MOCK_FALLBACK("模拟演示数据"),

    /** 本地导入数据（预留） */
    LOCAL_IMPORT("本地导入数据");

    private final String desc;

    DataSourceType(String desc) {
        this.desc = desc;
    }

    public String getDesc() {
        return desc;
    }
}
