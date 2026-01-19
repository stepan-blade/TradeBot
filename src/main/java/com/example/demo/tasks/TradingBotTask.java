package com.example.demo.tasks;

import com.example.demo.data.BotSettings;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.trade.TradeService;
import com.example.demo.services.trade.strategys.PositionManager;
import com.example.demo.services.trade.strategys.TradingStrategy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

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

        String[] assets = settings.getAssets().split(",");
        for (String asset : assets) {
            double currentPrice = binanceAPI.getCurrentPrice(asset.trim());
            if (currentPrice > 0) {
                tradingStrategy.checkEntryConditions(asset.trim(), currentPrice);
            }
        }

        tradeService.getActiveTrades().forEach(trade -> {
            double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
            if (currentPrice > 0) {
                positionManager.handleTradeStop(trade, currentPrice);
            }
        });
    }

    @Scheduled(fixedRate = 10000)
    public void syncMarketStatus() {
        try {
            tradeService.syncTradesWithExchange();
        } catch (Exception e) {
            System.err.println("Ошибка синхронизации сделок: " + e.getMessage());
        }
    }
}