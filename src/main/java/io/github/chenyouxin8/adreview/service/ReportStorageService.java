package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class ReportStorageService {

    @Value("${ad-review.report-storage-path:./data/reports}")
    private String storagePath;

    private final ObjectMapper objectMapper;

    /**
     * 注入 Spring Boot 4 自动配置的 Jackson 3 ObjectMapper（默认已包含 java.time 支持）
     */
    public ReportStorageService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(storagePath);
            if (!Files.exists(path)) Files.createDirectories(path);
        } catch (IOException e) { log.error("创建存储目录失败", e); }
    }

    private Path resolveReportPath(String reportId) {
        // 防止路径穿越：reportId 只允许字母数字
        if (reportId == null || !reportId.matches("^[a-zA-Z0-9-]+$")) {
            throw new BusinessException(40005, "非法的报告 ID");
        }
        return Paths.get(storagePath, reportId + ".json");
    }

    public void saveReport(ReviewReport report) {
        try {
            Path filePath = resolveReportPath(report.getReportId());
            Files.writeString(filePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (IOException e) { throw new BusinessException(50002, "保存报告失败"); }
    }

    public ReviewReport getReport(String reportId) {
        try {
            Path filePath = resolveReportPath(reportId);
            if (!Files.exists(filePath)) throw new BusinessException(40401, "报告不存在");
            return objectMapper.readValue(Files.readString(filePath), ReviewReport.class);
        } catch (BusinessException e) { throw e; }
        catch (IOException e) { throw new BusinessException(50003, "读取报告失败"); }
    }

    public void deleteReport(String reportId) {
        try {
            Path filePath = resolveReportPath(reportId);
            if (!Files.exists(filePath)) throw new BusinessException(40401, "报告不存在");
            Files.delete(filePath);
        } catch (BusinessException e) { throw e; }
        catch (IOException e) { throw new BusinessException(50004, "删除报告失败"); }
    }

    /**
     * 分页列出报告，按报告生成时间（generatedAt）倒序
     */
    public List<ReviewReport> listReports(int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 100);
        try (Stream<Path> paths = Files.list(Paths.get(storagePath))) {
            return paths.filter(p -> p.toString().endsWith(".json"))
                    .map(p -> { try { return objectMapper.readValue(Files.readString(p), ReviewReport.class); } catch (IOException e) { return null; } })
                    .filter(r -> r != null)
                    .sorted(Comparator.comparing(ReviewReport::getGeneratedAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .skip((long) (safePage - 1) * safeSize).limit(safeSize)
                    .collect(Collectors.toList());
        } catch (IOException e) { return new ArrayList<>(); }
    }

    public long countReports() {
        try (Stream<Path> paths = Files.list(Paths.get(storagePath))) {
            return paths.filter(p -> p.toString().endsWith(".json")).count();
        } catch (IOException e) { return 0; }
    }
}
