package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 报告存储服务（H2 / JDBC 实现）。
 *
 * <p>替换早期基于本地 JSON 文件的全量遍历方案：
 * 列表查询使用 SQL {@code ORDER BY generated_at DESC LIMIT ... OFFSET ...} 与 {@code COUNT(*)}，
 * 避免在内存中加载全部历史报告（O(n) 全量反序列化）。</p>
 *
 * <p>列表索引字段（report_id / report_type / start_date / end_date / generated_at / data_source / degraded）
 * 独立成列以支持高效索引查询；复杂嵌套对象整体序列化为 JSON 存于 {@code report_payload}。</p>
 */
@Service
@Slf4j
public class ReportStorageService {

    private static final String UPSERT_SQL = """
            MERGE INTO review_report (report_id, report_type, start_date, end_date, generated_at, data_source, degraded, report_payload)
            KEY (report_id) VALUES (?,?,?,?,?,?,?,?)
            """;

    private static final RowMapper<String> PAYLOAD_MAPPER = (rs, rowNum) -> rs.getString("report_payload");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    /**
     * 注入 Spring Boot 4 自动配置的 Jackson 3 ObjectMapper（默认已包含 java.time 支持）。
     */
    public ReportStorageService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private void validateReportId(String reportId) {
        // 防注入/非法 ID：reportId 只允许字母数字与连字符
        if (reportId == null || !reportId.matches("^[a-zA-Z0-9-]+$")) {
            throw new BusinessException(40005, "非法的报告 ID");
        }
    }

    public void saveReport(ReviewReport report) {
        validateReportId(report.getReportId());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(report);
        } catch (Exception e) {
            log.error("报告序列化失败: reportId={}", report.getReportId(), e);
            throw new BusinessException(50002, "保存报告失败");
        }
        try {
            jdbcTemplate.update(UPSERT_SQL,
                    report.getReportId(),
                    report.getReportType(),
                    report.getStartDate() != null ? Date.valueOf(report.getStartDate()) : null,
                    report.getEndDate() != null ? Date.valueOf(report.getEndDate()) : null,
                    report.getGeneratedAt() != null ? Timestamp.valueOf(report.getGeneratedAt()) : null,
                    report.getDataSource(),
                    Boolean.TRUE.equals(report.getDegraded()),
                    payload);
        } catch (DataAccessException e) {
            log.error("保存报告失败: reportId={}", report.getReportId(), e);
            throw new BusinessException(50002, "保存报告失败");
        }
    }

    public ReviewReport getReport(String reportId) {
        validateReportId(reportId);
        List<String> payloads = jdbcTemplate.query(
                "SELECT report_payload FROM review_report WHERE report_id = ?", PAYLOAD_MAPPER, reportId);
        if (payloads.isEmpty()) {
            throw new BusinessException(40401, "报告不存在");
        }
        ReviewReport report = deserialize(payloads.get(0));
        if (report == null) {
            throw new BusinessException(50003, "读取报告失败");
        }
        return report;
    }

    public void deleteReport(String reportId) {
        validateReportId(reportId);
        try {
            int deleted = jdbcTemplate.update("DELETE FROM review_report WHERE report_id = ?", reportId);
            if (deleted == 0) {
                throw new BusinessException(40401, "报告不存在");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (DataAccessException e) {
            log.error("删除报告失败: reportId={}", reportId, e);
            throw new BusinessException(50004, "删除报告失败");
        }
    }

    /** 兼容旧签名：不按报告类型过滤。 */
    public List<ReviewReport> listReports(int page, int size) {
        return listReports(page, size, null);
    }

    /**
     * 分页列出报告，按生成时间倒序；可选按报告类型过滤。
     * <p>使用 SQL 分页，不在内存中全量加载。</p>
     */
    public List<ReviewReport> listReports(int page, int size, String reportType) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        StringBuilder sql = new StringBuilder("SELECT report_payload FROM review_report");
        List<Object> params = new ArrayList<>();
        if (reportType != null && !reportType.isBlank()) {
            sql.append(" WHERE report_type = ?");
            params.add(reportType);
        }
        sql.append(" ORDER BY generated_at DESC LIMIT ? OFFSET ?");
        params.add(safeSize);
        params.add((long) (safePage - 1) * safeSize);

        List<String> payloads = jdbcTemplate.query(sql.toString(), PAYLOAD_MAPPER, params.toArray());
        List<ReviewReport> reports = new ArrayList<>(payloads.size());
        for (String payload : payloads) {
            ReviewReport report = deserialize(payload);
            if (report != null) {
                reports.add(report);
            }
        }
        return reports;
    }

    /** 兼容旧签名：统计全部报告数量。 */
    public long countReports() {
        return countReports(null);
    }

    /** 统计报告数量，可选按报告类型过滤。 */
    public long countReports(String reportType) {
        Long count;
        if (reportType != null && !reportType.isBlank()) {
            count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM review_report WHERE report_type = ?", Long.class, reportType);
        } else {
            count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_report", Long.class);
        }
        return count != null ? count : 0L;
    }

    private ReviewReport deserialize(String payload) {
        if (payload == null) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ReviewReport.class);
        } catch (Exception e) {
            log.warn("报告反序列化失败，已跳过", e);
            return null;
        }
    }
}
