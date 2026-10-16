package io.github.chenyouxin8.adreview.service;

import io.github.chenyouxin8.adreview.common.BusinessException;
import io.github.chenyouxin8.adreview.constant.DataSourceType;
import io.github.chenyouxin8.adreview.model.AccountSummary;
import io.github.chenyouxin8.adreview.model.AdCampaign;
import io.github.chenyouxin8.adreview.repository.CampaignRepository;
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
import java.util.Random;

@Service
@Slf4j
public class QianchuanService {

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpToolProvider;
    private final CampaignRepository campaignRepository;

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
                            @Autowired(required = false) ToolCallbackProvider mcpToolProvider,
                            CampaignRepository campaignRepository) {
        this.chatClient = chatClientBuilder.build();
        this.mcpToolProvider = mcpToolProvider;
        this.campaignRepository = campaignRepository;
    }

    /**
     * 本次取数结果的轻量包装：计划列表 + 明确的数据来源标识，
     * 使调用方能够区分「真实 MCP 数据」与「Mock 兜底数据」。
     */
    public record CampaignFetchResult(List<AdCampaign> campaigns, DataSourceType dataSource) {
    }

    /**
     * 拉取千川计划报表（兼容旧签名，仅返回计划列表）。
     *
     * @see #fetchCampaigns(LocalDate, LocalDate) 需要数据来源标识时请使用该方法
     */
    public List<AdCampaign> getCampaignReports(LocalDate startDate, LocalDate endDate) {
        return fetchCampaigns(startDate, endDate).campaigns();
    }

    /**
     * 拉取千川计划报表，并按优先级路由数据来源：
     * <ol>
     *   <li><b>本地导入数据</b>：{@link CampaignRepository} 在 [startDate, endDate] 内有数据 → {@link DataSourceType#LOCAL_IMPORT}（不消耗 LLM Token）；</li>
     *   <li><b>千川 MCP 实时数据</b>：本地库无该时段数据且 MCP 工具可用 → {@link DataSourceType#MCP_LIVE}；</li>
     *   <li><b>Mock 兜底</b>：前两者均不可用且允许兜底 → {@link DataSourceType#MOCK_FALLBACK}。</li>
     * </ol>
     */
    public CampaignFetchResult fetchCampaigns(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(40002, "开始日期不能晚于结束日期");
        }
        log.info("拉取千川计划报表: {} ~ {}", startDate, endDate);

        // 优先级 1：本地已导入的真实数据
        List<AdCampaign> local = campaignRepository.findByDateRange(startDate, endDate);
        if (!local.isEmpty()) {
            log.info("命中本地导入数据，共 {} 条", local.size());
            return new CampaignFetchResult(local, DataSourceType.LOCAL_IMPORT);
        }

        // 优先级 2：千川 MCP 实时数据
        ToolCallback[] tools = resolveMcpTools();
        if (tools == null) {
            log.warn("千川 MCP 工具不可用，返回模拟数据");
            return new CampaignFetchResult(generateMockCampaignData(startDate, endDate), DataSourceType.MOCK_FALLBACK);
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
                return new CampaignFetchResult(new ArrayList<>(), DataSourceType.MCP_LIVE);
            }
            campaigns.forEach(c -> {
                if (c.getStatDate() == null) c.setStatDate(endDate);
            });
            log.info("千川计划报表解析成功，共 {} 条", campaigns.size());
            return new CampaignFetchResult(campaigns, DataSourceType.MCP_LIVE);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("拉取千川数据失败", e);
            if (fallbackMockOnError) {
                log.warn("已降级为模拟数据（可通过 ad-review.fallback-mock-on-error=false 关闭降级）");
                return new CampaignFetchResult(generateMockCampaignData(startDate, endDate), DataSourceType.MOCK_FALLBACK);
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
     * 按时间段汇总账户数据。
     * <p>先取数一次，再委托 {@link #calculateAccountSummary} 在内存中聚合，
     * 避免内部重复调用 {@link #fetchCampaigns}。</p>
     */
    public AccountSummary getAccountSummary(LocalDate startDate, LocalDate endDate) {
        CampaignFetchResult result = fetchCampaigns(startDate, endDate);
        AccountSummary summary = calculateAccountSummary(result.campaigns(), startDate, endDate);
        summary.setDataSource(result.dataSource().name());
        summary.setDegraded(false);
        return summary;
    }

    /**
     * 基于已获取的计划列表在内存中聚合账户汇总指标。
     * <p>该方法是纯计算逻辑，<b>严禁</b>在内部再次触发取数（MCP/LLM 调用）。</p>
     */
    public AccountSummary calculateAccountSummary(List<AdCampaign> campaigns, LocalDate startDate, LocalDate endDate) {
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

    /**
     * 生成模拟演示数据。
     * <p>使用基于 {@code (startDate.toEpochDay() + campaignId)} 的固定种子，
     * 保证同一日期多次查询结果幂等，避免每次刷新数据乱跳。</p>
     */
    private List<AdCampaign> generateMockCampaignData(LocalDate startDate, LocalDate endDate) {
        List<AdCampaign> list = new ArrayList<>();
        String[] names = {"夏季新品推广-信息流", "品牌词搜索-精准", "达人带货-短视频", "直播间引流-智能", "老客召回-定向", "竞品拦截-广泛"};
        long days = startDate.until(endDate).getDays() + 1;
        for (int i = 0; i < 6; i++) {
            long campaignId = 10000L + i;
            Random random = new Random(startDate.toEpochDay() + campaignId);
            AdCampaign c = new AdCampaign();
            c.setCampaignId(campaignId);
            c.setCampaignName(names[i]);
            c.setStatDate(endDate);
            long showCnt = (long) (random.nextDouble() * 500000 + 10000);
            long clickCnt = (long) (showCnt * (random.nextDouble() * 0.05 + 0.01));
            double cost = random.nextDouble() * 5000 + 500;
            long convertCnt = (long) (clickCnt * (random.nextDouble() * 0.05 + 0.01));
            double payAmount = cost * (random.nextDouble() * 3 + 0.5);
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
