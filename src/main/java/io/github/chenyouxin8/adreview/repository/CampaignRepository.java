package io.github.chenyouxin8.adreview.repository;

import io.github.chenyouxin8.adreview.constant.DataSourceType;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 广告计划日度明细仓储：支持按 {@code (campaign_id, stat_date)} 批量 Upsert 与区间查询。
 */
@Repository
public class CampaignRepository {

    private static final String UPSERT_SQL = """
            MERGE INTO ad_campaign_record (
                campaign_id, stat_date, campaign_name, adgroup_id, adgroup_name, creative_id, creative_name,
                show_cnt, click_cnt, ctr, avg_click_cost, cost, convert_cnt, convert_cost, convert_rate,
                pay_order_amount, pay_order_roi, pay_order_cnt, avg_show_cost, deep_convert_cnt, deep_convert_cost,
                data_source, updated_at
            ) KEY (campaign_id, stat_date) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static final RowMapper<AdCampaign> ROW_MAPPER = (rs, rowNum) -> {
        AdCampaign c = new AdCampaign();
        c.setCampaignId(rs.getLong("campaign_id"));
        Date statDate = rs.getDate("stat_date");
        c.setStatDate(statDate != null ? statDate.toLocalDate() : null);
        c.setCampaignName(rs.getString("campaign_name"));
        c.setAdgroupId(getLong(rs, "adgroup_id"));
        c.setAdgroupName(rs.getString("adgroup_name"));
        c.setCreativeId(getLong(rs, "creative_id"));
        c.setCreativeName(rs.getString("creative_name"));
        c.setShowCnt(getLong(rs, "show_cnt"));
        c.setClickCnt(getLong(rs, "click_cnt"));
        c.setCtr(getDouble(rs, "ctr"));
        c.setAvgClickCost(getDouble(rs, "avg_click_cost"));
        c.setCost(getDouble(rs, "cost"));
        c.setConvertCnt(getLong(rs, "convert_cnt"));
        c.setConvertCost(getDouble(rs, "convert_cost"));
        c.setConvertRate(getDouble(rs, "convert_rate"));
        c.setPayOrderAmount(getDouble(rs, "pay_order_amount"));
        c.setPayOrderRoi(getDouble(rs, "pay_order_roi"));
        c.setPayOrderCnt(getLong(rs, "pay_order_cnt"));
        c.setAvgShowCost(getDouble(rs, "avg_show_cost"));
        c.setDeepConvertCnt(getLong(rs, "deep_convert_cnt"));
        c.setDeepConvertCost(getDouble(rs, "deep_convert_cost"));
        return c;
    };

    private final JdbcTemplate jdbcTemplate;

    public CampaignRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 批量 Upsert（存在则更新，不存在则插入），并统一标记数据来源。
     *
     * @return 实际写入的行数
     */
    public int upsertAll(List<AdCampaign> campaigns, DataSourceType dataSource) {
        List<AdCampaign> rows = campaigns.stream()
                .filter(c -> c.getCampaignId() != null && c.getStatDate() != null)
                .toList();
        if (rows.isEmpty()) {
            return 0;
        }
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        int[] result = jdbcTemplate.batchUpdate(UPSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                AdCampaign c = rows.get(i);
                ps.setLong(1, c.getCampaignId());
                ps.setDate(2, Date.valueOf(c.getStatDate()));
                setString(ps, 3, c.getCampaignName());
                setLong(ps, 4, c.getAdgroupId());
                setString(ps, 5, c.getAdgroupName());
                setLong(ps, 6, c.getCreativeId());
                setString(ps, 7, c.getCreativeName());
                setLong(ps, 8, c.getShowCnt());
                setLong(ps, 9, c.getClickCnt());
                setDouble(ps, 10, c.getCtr());
                setDouble(ps, 11, c.getAvgClickCost());
                setDouble(ps, 12, c.getCost());
                setLong(ps, 13, c.getConvertCnt());
                setDouble(ps, 14, c.getConvertCost());
                setDouble(ps, 15, c.getConvertRate());
                setDouble(ps, 16, c.getPayOrderAmount());
                setDouble(ps, 17, c.getPayOrderRoi());
                setLong(ps, 18, c.getPayOrderCnt());
                setDouble(ps, 19, c.getAvgShowCost());
                setLong(ps, 20, c.getDeepConvertCnt());
                setDouble(ps, 21, c.getDeepConvertCost());
                setString(ps, 22, dataSource != null ? dataSource.name() : null);
                ps.setTimestamp(23, now);
            }

            @Override
            public int getBatchSize() {
                return rows.size();
            }
        });
        return result.length;
    }

    /** 按统计日期区间查询（含端点），按消耗降序。 */
    public List<AdCampaign> findByDateRange(LocalDate startDate, LocalDate endDate) {
        return jdbcTemplate.query(
                "SELECT * FROM ad_campaign_record WHERE stat_date BETWEEN ? AND ? ORDER BY cost DESC",
                ROW_MAPPER, Date.valueOf(startDate), Date.valueOf(endDate));
    }

    /** 区间内是否存在数据。 */
    public boolean hasData(LocalDate startDate, LocalDate endDate) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ad_campaign_record WHERE stat_date BETWEEN ? AND ?",
                Long.class, Date.valueOf(startDate), Date.valueOf(endDate));
        return count != null && count > 0;
    }

    private static void setString(PreparedStatement ps, int idx, String v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR);
        else ps.setString(idx, v);
    }

    private static void setLong(PreparedStatement ps, int idx, Long v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.BIGINT);
        else ps.setLong(idx, v);
    }

    private static void setDouble(PreparedStatement ps, int idx, Double v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.DOUBLE);
        else ps.setDouble(idx, v);
    }

    private static Long getLong(java.sql.ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Double getDouble(java.sql.ResultSet rs, String col) throws SQLException {
        double v = rs.getDouble(col);
        return rs.wasNull() ? null : v;
    }
}
