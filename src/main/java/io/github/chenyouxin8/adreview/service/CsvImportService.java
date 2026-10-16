package io.github.chenyouxin8.adreview.service;

import cn.hutool.core.text.csv.CsvData;
import cn.hutool.core.text.csv.CsvReadConfig;
import cn.hutool.core.text.csv.CsvReader;
import cn.hutool.core.text.csv.CsvRow;
import cn.hutool.core.text.csv.CsvUtil;
import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.constant.DataSourceType;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.repository.CampaignRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 千川 CSV 报表导入服务。
 *
 * <p>使用 hutool {@code CsvUtil} 解析上传文件（UTF-8 / GBK 自动识别），
 * 支持中英文表头映射；对未提供或为空的衍生指标（CTR / CPA / CVR / CPM / CPC / ROI）自动计算并四舍五入，
 * 校验通过后写入 {@link CampaignRepository} 并标记 {@link DataSourceType#LOCAL_IMPORT}。</p>
 */
@Service
@Slf4j
public class CsvImportService {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd")
    };

    private final CampaignRepository campaignRepository;

    public CsvImportService(CampaignRepository campaignRepository) {
        this.campaignRepository = campaignRepository;
    }

    /** 导入结果：成功条数与覆盖的日期范围。 */
    public record ImportResult(int importedCount, LocalDate startDate, LocalDate endDate) {
    }

    public ImportResult importCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(40006, "上传文件不能为空");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BusinessException(40007, "读取上传文件失败");
        }
        List<AdCampaign> campaigns;
        try {
            campaigns = parse(bytes, detectCharset(bytes));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("CSV 解析失败", e);
            throw new BusinessException(40008, "CSV 解析失败: " + e.getMessage());
        }
        if (campaigns.isEmpty()) {
            throw new BusinessException(40008, "CSV 中未解析到有效数据");
        }
        campaignRepository.upsertAll(campaigns, DataSourceType.LOCAL_IMPORT);

        LocalDate min = campaigns.stream().map(AdCampaign::getStatDate).min(LocalDate::compareTo).orElse(null);
        LocalDate max = campaigns.stream().map(AdCampaign::getStatDate).max(LocalDate::compareTo).orElse(null);
        log.info("CSV 导入成功，共 {} 条，日期范围 {} ~ {}", campaigns.size(), min, max);
        return new ImportResult(campaigns.size(), min, max);
    }

    /**
     * 解析 CSV 字节流。首行为表头（中英文皆可），其余为数据行。
     */
    private List<AdCampaign> parse(byte[] bytes, Charset charset) {
        CsvReadConfig config = CsvReadConfig.defaultConfig();
        config.setContainsHeader(false);
        config.setSkipEmptyRows(true);
        CsvReader reader = CsvUtil.getReader(config);
        CsvData data = reader.read(new InputStreamReader(new ByteArrayInputStream(bytes), charset));
        List<CsvRow> rows = data.getRows();
        if (rows.isEmpty()) {
            return List.of();
        }
        CsvRow headerRow = rows.get(0);
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < headerRow.size(); i++) {
            String key = normalizeKey(headerRow.get(i));
            if (!key.isEmpty()) {
                index.putIfAbsent(key, i);
            }
        }
        List<AdCampaign> result = new ArrayList<>();
        for (int r = 1; r < rows.size(); r++) {
            AdCampaign campaign = toCampaign(rows.get(r), index, r);
            if (campaign != null) {
                result.add(campaign);
            }
        }
        return result;
    }

    private AdCampaign toCampaign(CsvRow row, Map<String, Integer> index, int rowNo) {
        Long campaignId = parseLong(cell(row, index, "计划id", "计划编号", "campaignid", "id"));
        LocalDate statDate = parseDate(cell(row, index, "日期", "统计日期", "statdate", "date"));
        if (campaignId == null || statDate == null) {
            log.warn("CSV 第 {} 行缺少计划ID或日期，已跳过", rowNo + 1);
            return null;
        }
        long showCnt = nz(parseLong(cell(row, index, "展示数", "展示", "曝光数", "曝光", "showcnt")));
        long clickCnt = nz(parseLong(cell(row, index, "点击数", "点击", "clickcnt")));
        long convertCnt = nz(parseLong(cell(row, index, "转化数", "转化", "convertcnt")));
        long payOrderCnt = nz(parseLong(cell(row, index, "支付订单数", "订单数", "payordercount", "payordercnt")));
        double cost = nz(parseDouble(cell(row, index, "消耗", "花费", "消费", "cost")));
        double payAmount = nz(parseDouble(cell(row, index, "支付gmv", "成交金额", "gmv", "paygmv", "payorderamount")));

        AdCampaign c = new AdCampaign();
        c.setCampaignId(campaignId);
        c.setStatDate(statDate);
        c.setCampaignName(defaultString(cell(row, index, "计划名称", "名称", "campaignname")));
        c.setShowCnt(showCnt);
        c.setClickCnt(clickCnt);
        c.setConvertCnt(convertCnt);
        c.setPayOrderCnt(payOrderCnt);
        c.setCost(cost);
        c.setPayOrderAmount(payAmount);

        // 衍生指标：优先取 CSV 值，缺失则按基础字段计算（防除零）
        c.setCtr(orCompute(parseDouble(cell(row, index, "点击率", "ctr", "clickrate")),
                showCnt > 0 ? clickCnt * 100.0 / showCnt : 0.0));
        c.setAvgClickCost(orCompute(parseDouble(cell(row, index, "平均点击成本", "平均点击单价", "cpc", "avgclickcost")),
                clickCnt > 0 ? cost / clickCnt : 0.0));
        c.setConvertCost(orCompute(parseDouble(cell(row, index, "转化成本", "cpa", "convertcost")),
                convertCnt > 0 ? cost / convertCnt : 0.0));
        c.setConvertRate(orCompute(parseDouble(cell(row, index, "转化率", "cvr", "convertrate")),
                clickCnt > 0 ? convertCnt * 100.0 / clickCnt : 0.0));
        c.setAvgShowCost(orCompute(parseDouble(cell(row, index, "千次展示成本", "cpm", "avgshowcost")),
                showCnt > 0 ? cost / showCnt * 1000 : 0.0));
        c.setPayOrderRoi(orCompute(parseDouble(cell(row, index, "支付roi", "roi", "payroi", "payorderroi")),
                cost > 0 ? payAmount / cost : 0.0));
        return c;
    }

    /** 下载用的标准模板：中文表头 + 5 条逼真电商投流样本数据。 */
    public String buildTemplateCsv() {
        StringBuilder sb = new StringBuilder();
        // UTF-8 BOM，保证 Excel 正确识别中文
        sb.append('\uFEFF');
        sb.append("计划ID,计划名称,日期,消耗,展示数,点击数,转化数,支付GMV,支付订单数\n");
        sb.append("10001,夏季新品连衣裙-信息流,2026-10-10,5230.50,420000,10500,320,15600,260\n");
        sb.append("10002,品牌词搜索-精准,2026-10-10,3120.00,85000,6200,410,18200,380\n");
        sb.append("10003,达人带货-短视频,2026-10-10,4380.75,260000,7800,210,9450,180\n");
        sb.append("10004,直播间引流-智能,2026-10-11,6150.20,510000,14200,520,23800,470\n");
        sb.append("10005,老客召回-定向,2026-10-12,2870.00,96000,4800,360,12600,330\n");
        return sb.toString();
    }

    /**
     * 字符集识别：优先 UTF-8（含 BOM），严格解码失败则回退 GBK。
     */
    private Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            return StandardCharsets.UTF_8;
        }
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            return Charset.forName("GBK");
        }
    }

    private static String cell(CsvRow row, Map<String, Integer> index, String... aliases) {
        for (String alias : aliases) {
            Integer i = index.get(alias);
            if (i != null && i < row.size()) {
                String v = row.get(i);
                if (v != null && !v.isBlank()) {
                    return v.trim();
                }
            }
        }
        return null;
    }

    /** 归一化表头：小写、移除空格/下划线/百分号等非字母数字字符，便于中英文别名匹配。 */
    private static String normalizeKey(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : raw.trim().toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static Long parseLong(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return (long) Double.parseDouble(v.replace(",", "").replace(" ", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String cleaned = v.replace(",", "").replace("%", "").replace("¥", "").replace("￥", "").replace(" ", "").trim();
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String s = v.trim();
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return LocalDate.parse(s, fmt);
            } catch (Exception ignored) {
                // 尝试下一种格式
            }
        }
        return null;
    }

    private static double orCompute(Double provided, double computed) {
        return provided != null ? round4(provided) : round4(computed);
    }

    private static long nz(Long v) {
        return v != null ? v : 0L;
    }

    private static double nz(Double v) {
        return v != null ? v : 0.0;
    }

    private static String defaultString(String v) {
        return v != null ? v : "";
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
