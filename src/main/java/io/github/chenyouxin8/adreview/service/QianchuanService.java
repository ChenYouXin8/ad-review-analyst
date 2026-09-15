package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class QianchuanService {

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpToolProvider;

    @Autowired
    public QianchuanService(ChatClient.Builder chatClientBuilder,
                            @Autowired(required = false) ToolCallbackProvider mcpToolProvider) {
        this.chatClient = chatClientBuilder.build();
        this.mcpToolProvider = mcpToolProvider;
    }

    public List<AdCampaign> getCampaignReports(LocalDate startDate, LocalDate endDate) {
        log.info("拉取千川计划报表: {} ~ {}", startDate, endDate);
        if (mcpToolProvider == null) {
            log.warn("MCP 工具未配置，返回模拟数据");
            return generateMockCampaignData(startDate, endDate);
        }
        try {
            ToolCallback[] tools = mcpToolProvider.getToolCallbacks();
            String prompt = String.format("请查询巨量千川账户从 %s 到 %s 的广告计划报表数据，需要获取：计划ID、计划名称、展示数、点击数、点击率、消耗、转化数、转化成本、支付GMV、支付ROI，按消耗降序排列。", startDate, endDate);
            ChatResponse response = chatClient.prompt().user(prompt).tools(tools).call().chatResponse();
            String content = response.getResult().getOutput().getText();
            return parseCampaignData(content, startDate, endDate);
        } catch (Exception e) {
            log.error("拉取千川数据失败", e);
            throw new BusinessException(50001, "拉取千川数据失败: " + e.getMessage());
        }
    }

    public AccountSummary getAccountSummary(LocalDate date) {
        List<AdCampaign> campaigns = getCampaignReports(date, date);
        AccountSummary summary = new AccountSummary();
        summary.setAdvertiserId(0L);
        summary.setAdvertiserName("当前账户");
        summary.setStatDate(date);
        long totalShow = 0, totalClick = 0, totalConvert = 0;
        double totalCost = 0, totalGmv = 0;
        int abnormalCount = 0;
        for (AdCampaign c : campaigns) {
            totalShow += c.getShowCnt() != null ? c.getShowCnt() : 0;
            totalClick += c.getClickCnt() != null ? c.getClickCnt() : 0;
            totalConvert += c.getConvertCnt() != null ? c.getConvertCnt() : 0;
            totalCost += c.getCost() != null ? c.getCost() : 0;
            totalGmv += c.getPayOrderAmount() != null ? c.getPayOrderAmount() : 0;
            if (c.getPayOrderRoi() != null && c.getPayOrderRoi() < 1.5) abnormalCount++;
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

    private List<AdCampaign> parseCampaignData(String aiContent, LocalDate startDate, LocalDate endDate) {
        return new ArrayList<>();
    }

    private List<AdCampaign> generateMockCampaignData(LocalDate startDate, LocalDate endDate) {
        List<AdCampaign> list = new ArrayList<>();
        String[] names = {"夏季新品推广-信息流", "品牌词搜索-精准", "达人带货-短视频", "直播间引流-智能", "老客召回-定向", "竞品拦截-广泛"};
        for (int i = 0; i < 6; i++) {
            AdCampaign c = new AdCampaign();
            c.setCampaignId(10000L + i);
            c.setCampaignName(names[i]);
            c.setStatDate(endDate);
            c.setShowCnt((long) (Math.random() * 500000 + 10000));
            c.setClickCnt((long) (c.getShowCnt() * (Math.random() * 0.05 + 0.01)));
            c.setCtr((double) c.getClickCnt() / c.getShowCnt() * 100);
            c.setCost(Math.random() * 5000 + 500);
            c.setAvgClickCost(c.getCost() / c.getClickCnt());
            c.setConvertCnt((long) (c.getClickCnt() * (Math.random() * 0.05 + 0.01)));
            c.setConvertCost(c.getConvertCnt() > 0 ? c.getCost() / c.getConvertCnt() : 0);
            c.setConvertRate(c.getClickCnt() > 0 ? (double) c.getConvertCnt() / c.getClickCnt() * 100 : 0);
            c.setPayOrderAmount(c.getCost() * (Math.random() * 3 + 0.5));
            c.setPayOrderRoi(c.getCost() > 0 ? c.getPayOrderAmount() / c.getCost() : 0);
            c.setPayOrderCnt((long) (c.getConvertCnt() * 0.6));
            c.setAvgShowCost(c.getShowCnt() > 0 ? c.getCost() / c.getShowCnt() * 1000 : 0);
            list.add(c);
        }
        return list;
    }
}
