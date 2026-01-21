package com.example.demo.tasks;

import com.example.demo.DemoApplication;
import com.example.demo.data.BotSettings;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.trade.TradeService;
import com.example.demo.services.trade.strategys.PositionManager;
import com.example.demo.services.trade.strategys.TradingStrategy;
import com.example.demo.utils.TimeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class TradingBotTask {

    private final TradeService tradeService;
    private final BotSettingsRepository botSettingsRepository;
    private final TradingStrategy tradingStrategy;
    private final PositionManager positionManager;
    private  final BinanceAPI binanceAPI;

    @Autowired
    public TradingBotTask(
            TradeService tradeService,
            BotSettingsRepository botSettingsRepository,
            TradingStrategy tradingStrategy,
            PositionManager positionManager, BinanceAPI binanceAPI) {

        this.tradeService = tradeService;
        this.botSettingsRepository = botSettingsRepository;
        this.tradingStrategy = tradingStrategy;
        this.positionManager = positionManager;
        this.binanceAPI = binanceAPI;
    }

    @Scheduled(fixedDelay = 2000)
    public void executeTradeLogic() {
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        if (!settings.getStatus().equals("ONLINE")) return;

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

    @Scheduled(fixedRate = 10000)
    public void syncMarketStatus() {
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        if (settings.getStatus().equals("ONLINE")) {
            try {
                tradeService.syncTradesWithExchange();
            } catch (Exception e) {
                System.err.println("Ошибка синхронизации сделок: " + e.getMessage());
            }
        }
    }
}