package io.github.chenyouxin8.adreview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.model.ReviewReport;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

    public ReportStorageService() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        try {
            Path path = Paths.get(storagePath);
            if (!Files.exists(path)) Files.createDirectories(path);
        } catch (IOException e) { log.error("创建存储目录失败", e); }
    }

    public void saveReport(ReviewReport report) {
        try {
            Path filePath = Paths.get(storagePath, report.getReportId() + ".json");
            Files.writeString(filePath, objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } catch (IOException e) { throw new BusinessException(50002, "保存报告失败"); }
    }

    public ReviewReport getReport(String reportId) {
        try {
            Path filePath = Paths.get(storagePath, reportId + ".json");
            if (!Files.exists(filePath)) throw new BusinessException(40401, "报告不存在");
            return objectMapper.readValue(Files.readString(filePath), ReviewReport.class);
        } catch (IOException e) { throw new BusinessException(50003, "读取报告失败"); }
    }

    public List<ReviewReport> listReports(int page, int size) {
        try (Stream<Path> paths = Files.list(Paths.get(storagePath))) {
            return paths.filter(p -> p.toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> { try { return Files.getLastModifiedTime(p).toInstant(); } catch (IOException e) { return java.time.Instant.MIN; } }, Comparator.reverseOrder()))
                    .skip((long) (page - 1) * size).limit(size)
                    .map(p -> { try { return objectMapper.readValue(Files.readString(p), ReviewReport.class); } catch (IOException e) { return null; } })
                    .filter(r -> r != null).collect(Collectors.toList());
        } catch (IOException e) { return new ArrayList<>(); }
    }
}
