-- ===========================================
-- AI 投流复盘分析师 - 数据库结构
-- H2（MODE=MySQL），幂等建表，可重复执行
-- ===========================================

-- 广告计划日度明细（导入/同步数据）
CREATE TABLE IF NOT EXISTS ad_campaign_record (
    campaign_id       BIGINT       NOT NULL,
    stat_date         DATE         NOT NULL,
    campaign_name     VARCHAR(255),
    adgroup_id        BIGINT,
    adgroup_name      VARCHAR(255),
    creative_id       BIGINT,
    creative_name     VARCHAR(255),
    show_cnt          BIGINT,
    click_cnt         BIGINT,
    ctr               DOUBLE,
    avg_click_cost    DOUBLE,
    cost              DOUBLE,
    convert_cnt       BIGINT,
    convert_cost      DOUBLE,
    convert_rate      DOUBLE,
    pay_order_amount  DOUBLE,
    pay_order_roi     DOUBLE,
    pay_order_cnt     BIGINT,
    avg_show_cost     DOUBLE,
    deep_convert_cnt  BIGINT,
    deep_convert_cost DOUBLE,
    data_source       VARCHAR(32),
    updated_at        TIMESTAMP,
    PRIMARY KEY (campaign_id, stat_date)
);

-- 复盘报告（列表字段独立成列用于索引，复杂嵌套对象序列化为 JSON 存 report_payload）
CREATE TABLE IF NOT EXISTS review_report (
    report_id      VARCHAR(64) PRIMARY KEY,
    report_type    VARCHAR(32),
    start_date     DATE,
    end_date       DATE,
    generated_at   TIMESTAMP,
    data_source    VARCHAR(32),
    degraded       BOOLEAN,
    report_payload CLOB
);

CREATE INDEX IF NOT EXISTS idx_review_report_generated_at ON review_report (generated_at DESC);
CREATE INDEX IF NOT EXISTS idx_review_report_type ON review_report (report_type);
CREATE INDEX IF NOT EXISTS idx_campaign_stat_date ON ad_campaign_record (stat_date);
