package com.example.demo.tasks;

import com.example.demo.data.BalanceHistory;
import com.example.demo.data.BotSettings;
import com.example.demo.interfaces.BalanceHistoryRepository;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.trade.TradeService;
import com.example.demo.services.trade.strategys.PositionManager;
import com.example.demo.services.trade.strategys.TradingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

@Service
public class TradingBotTask {

    private final TradeService tradeService;
    private final BotSettingsRepository botSettingsRepository;
    private final BalanceHistoryRepository balanceHistoryRepository;
    private final TradingStrategy tradingStrategy;
    private final PositionManager positionManager;
    private final BinanceAPI binanceAPI;
    private static final Logger logger = LoggerFactory.getLogger(TradingBotTask.class);

    @Autowired
    public TradingBotTask(
            TradeService tradeService,
            BotSettingsRepository botSettingsRepository,
            BalanceHistoryRepository balanceHistoryRepository, TradingStrategy tradingStrategy,
            PositionManager positionManager, BinanceAPI binanceAPI) {

        this.tradeService = tradeService;
        this.botSettingsRepository = botSettingsRepository;
        this.balanceHistoryRepository = balanceHistoryRepository;
        this.tradingStrategy = tradingStrategy;
        this.positionManager = positionManager;
        this.binanceAPI = binanceAPI;
    }

    @Scheduled(fixedDelay = 15000)
    public void executeTradeLogic() {
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        if (!settings.getStatus().equals("ONLINE")) {
            System.out.println("OFFLINE - пропуск цикла");
            return;
        }

        Map<String, Double> allPrices = binanceAPI.getAllPrices();

        String[] assets = settings.getAssets().split(",");
        for (String asset : assets) {
            String symbol = asset.trim();
            Double currentPrice = allPrices.get(symbol);
            if (currentPrice != null && currentPrice > 0) {
                tradingStrategy.checkEntryConditions(symbol, currentPrice);
            }
        }

        tradeService.getActiveTrades().forEach(trade -> {
            Double currentPrice = allPrices.get(trade.getAsset());
            if (currentPrice != null) {
                positionManager.handleTradeStop(trade, currentPrice);
            }
        });
    }

    @Scheduled(fixedRate = 30000)
    public void syncMarketStatus() {

        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        if (!settings.getStatus().equals("ONLINE")) {
            System.out.println("OFFLINE - пропуск синхронизации");
            return;
        }

        try {
            tradeService.syncTradesWithExchange();
        } catch (Exception e) {
            System.err.println("Ошибка синхронизации сделок: " + e.getMessage());
        }
        tradeService.adjustForDeposits();
    }

    @Scheduled(cron = "0 0 * * * *")
    public void saveDailyBalanceSnapshot() {
        LocalDate today = LocalDate.now();
        LocalDateTime startOfDay = today.atStartOfDay();

        boolean hasTodayRecord = balanceHistoryRepository.findAll().stream()
                .anyMatch(bh -> bh.getTimestamp().toLocalDate().equals(today));

        if (!hasTodayRecord) {
            double currentBalance = binanceAPI.getAccountBalance();
            balanceHistoryRepository.save(new BalanceHistory(currentBalance, LocalDateTime.now()));
            logger.info("Сохранена запись баланса за новый день: {}", currentBalance);
        }
    }
}