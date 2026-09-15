package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class QianchuanService {

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpToolProvider;

    @Value("${ad-review.roi-threshold:1.5}")
    private double roiThreshold;

    /**
     * AI 调用失败时是否降级为模拟数据（默认 true，便于开发测试；
     * 生产环境可设为 false 让错误直接暴露）
     */
    @Value("${ad-review.fallback-mock-on-error:true}")
    private boolean fallbackMockOnError;

    @Autowired
    public QianchuanService(ChatClient.Builder chatClientBuilder,
                            @Autowired(required = false) ToolCallbackProvider mcpToolProvider) {
        this.chatClient = chatClientBuilder.build();
        this.mcpToolProvider = mcpToolProvider;
    }

    /**
     * 拉取千川计划报表。
     * <p>配置了 MCP 工具时，通过 AI + 千川 MCP 工具获取真实数据，并使用结构化输出解析为 {@link AdCampaign} 列表；
     * 未配置或调用失败时（可配置）返回模拟数据，方便本地开发测试。</p>
     */
    public List<AdCampaign> getCampaignReports(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(40002, "开始日期不能晚于结束日期");
        }
        log.info("拉取千川计划报表: {} ~ {}", startDate, endDate);
        ToolCallback[] tools = resolveMcpTools();
        if (tools == null) {
            log.warn("千川 MCP 工具不可用，返回模拟数据");
            return generateMockCampaignData(startDate, endDate);
        }
        try {
            BeanOutputConverter<List<AdCampaign>> converter =
                    new BeanOutputConverter<>(new ParameterizedTypeReference<List<AdCampaign>>() {});
            String prompt = String.format("""
                    请通过可用的 MCP 工具查询巨量千川账户从 %s 到 %s 的广告计划报表数据。
                    需要获取每个计划的：计划ID、计划名称、展示数、点击数、点击率、平均点击成本、消耗、转化数、转化成本、转化率、支付GMV、支付ROI、支付订单数、千次展示成本。
                    如果查询结果包含多个日期或多个计划，请全部列出，按消耗降序排列。
                    请仅输出符合要求的 JSON 数组，不要输出任何其他解释文字。
                    %s
                    """, startDate, endDate, converter.getFormat());
            ChatResponse response = chatClient.prompt().user(prompt).tools((Object[]) tools).call().chatResponse();
            String content = response.getResult().getOutput().getText();
            List<AdCampaign> campaigns = converter.convert(content);
            if (campaigns == null || campaigns.isEmpty()) {
                log.warn("千川 MCP 返回数据为空，期间 {} ~ {}", startDate, endDate);
                return new ArrayList<>();
            }
            campaigns.forEach(c -> {
                if (c.getStatDate() == null) c.setStatDate(endDate);
            });
            log.info("千川计划报表解析成功，共 {} 条", campaigns.size());
            return campaigns;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("拉取千川数据失败", e);
            if (fallbackMockOnError) {
                log.warn("已降级为模拟数据（可通过 ad-review.fallback-mock-on-error=false 关闭降级）");
                return generateMockCampaignData(startDate, endDate);
            }
            throw new BusinessException(50001, "拉取千川数据失败: " + e.getMessage());
        }
    }

    /**
     * 解析可用的千川 MCP 工具。
     * <p>MCP provider 未配置、工具为空或获取失败时返回 {@code null}，
     * 调用方据此降级为模拟数据。</p>
     */
    private ToolCallback[] resolveMcpTools() {
        if (mcpToolProvider == null) return null;
        try {
            ToolCallback[] tools = mcpToolProvider.getToolCallbacks();
            return (tools == null || tools.length == 0) ? null : tools;
        } catch (Exception e) {
            log.warn("获取 MCP 工具失败", e);
            return null;
        }
    }

    public AccountSummary getAccountSummary(LocalDate date) {
        return getAccountSummary(date, date);
    }

    /**
     * 按时间段汇总账户数据
     */
    public AccountSummary getAccountSummary(LocalDate startDate, LocalDate endDate) {
        List<AdCampaign> campaigns = getCampaignReports(startDate, endDate);
        AccountSummary summary = new AccountSummary();
        summary.setAdvertiserId(0L);
        summary.setAdvertiserName("当前账户");
        summary.setStatDate(endDate);
        long totalShow = 0, totalClick = 0, totalConvert = 0;
        double totalCost = 0, totalGmv = 0;
        int abnormalCount = 0;
        for (AdCampaign c : campaigns) {
            totalShow += c.getShowCnt() != null ? c.getShowCnt() : 0;
            totalClick += c.getClickCnt() != null ? c.getClickCnt() : 0;
            totalConvert += c.getConvertCnt() != null ? c.getConvertCnt() : 0;
            totalCost += c.getCost() != null ? c.getCost() : 0;
            totalGmv += c.getPayOrderAmount() != null ? c.getPayOrderAmount() : 0;
            if (c.getPayOrderRoi() != null && c.getPayOrderRoi() < roiThreshold) abnormalCount++;
        }
        summary.setTotalShow(totalShow);
        summary.setTotalClick(totalClick);
        summary.setTotalConvert(totalConvert);
        summary.setTotalCost(totalCost);
        summary.setTotalGmv(totalGmv);
        summary.setOverallRoi(totalCost > 0 ? totalGmv / totalCost : 0);
        summary.setOverallCtr(totalShow > 0 ? (double) totalClick / totalShow * 100 : 0);
        summary.setOverallCpc(totalClick > 0 ? totalCost / totalClick : 0);
        summary.setOverallCpa(totalConvert > 0 ? totalCost / totalConvert : 0);
        summary.setActiveCampaignCount(campaigns.size());
        summary.setAbnormalCampaignCount(abnormalCount);
        return summary;
    }

    private List<AdCampaign> generateMockCampaignData(LocalDate startDate, LocalDate endDate) {
        List<AdCampaign> list = new ArrayList<>();
        String[] names = {"夏季新品推广-信息流", "品牌词搜索-精准", "达人带货-短视频", "直播间引流-智能", "老客召回-定向", "竞品拦截-广泛"};
        long days = startDate.until(endDate).getDays() + 1;
        for (int i = 0; i < 6; i++) {
            AdCampaign c = new AdCampaign();
            c.setCampaignId(10000L + i);
            c.setCampaignName(names[i]);
            c.setStatDate(endDate);
            long showCnt = (long) (Math.random() * 500000 + 10000);
            long clickCnt = (long) (showCnt * (Math.random() * 0.05 + 0.01));
            double cost = Math.random() * 5000 + 500;
            long convertCnt = (long) (clickCnt * (Math.random() * 0.05 + 0.01));
            double payAmount = cost * (Math.random() * 3 + 0.5);
            c.setShowCnt(showCnt * days);
            c.setClickCnt(clickCnt * days);
            c.setCtr(clickCnt > 0 ? (double) clickCnt / showCnt * 100 : 0);
            c.setCost(cost * days);
            c.setAvgClickCost(clickCnt > 0 ? cost / clickCnt : 0);
            c.setConvertCnt(convertCnt * days);
            c.setConvertCost(convertCnt > 0 ? cost / convertCnt : 0);
            c.setConvertRate(clickCnt > 0 ? (double) convertCnt / clickCnt * 100 : 0);
            c.setPayOrderAmount(payAmount * days);
            c.setPayOrderRoi(cost > 0 ? payAmount / cost : 0);
            c.setPayOrderCnt((long) (convertCnt * 0.6 * days));
            c.setAvgShowCost(showCnt > 0 ? cost / showCnt * 1000 : 0);
            list.add(c);
        }
        return list;
    }
}
