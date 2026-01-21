package com.example.demo.services.trade.strategys;

import com.example.demo.data.BotSettings;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.trade.IndicatorService;
import com.example.demo.services.trade.TradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TradingStrategy {

    private final BinanceAPI binanceAPI;
    private final TradeService tradeService;
    private final IndicatorService indicatorService;
    private final BotSettingsRepository botSettingsRepository;

    @Autowired
    public TradingStrategy(BinanceAPI binanceAPI, TradeService tradeService, IndicatorService indicatorService, BotSettingsRepository botSettingsRepository) {
        this.binanceAPI = binanceAPI;
        this.tradeService = tradeService;
        this.indicatorService = indicatorService;
        this.botSettingsRepository = botSettingsRepository;
    }

    /**
     * Проверка условий для открытия позиции
     * @param symbol Валютная пара
     * @param currentPrice Текущая цена актива
     */
    public void checkEntryConditions(String symbol, double currentPrice) {
        if (tradeService.isCoolDown(symbol)) return;

        // Лимит открытых сделок
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        int maxOpenTrades = settings.getMaxOpenTrades() != 0 ? settings.getMaxOpenTrades() : 3;
        if (tradeService.getActiveTrades().size() >= maxOpenTrades) return;

        List<double[]> klines = binanceAPI.getKlines(symbol, "5m", 250);

        double rsi = indicatorService.calculateRSI(klines, 14);
        double sma200 = indicatorService.calculateSMA(klines, 200);
        double[] bb = indicatorService.calculateBollingerBands(klines, 20, 2.0);

        // MACD для cross
        double[] macd = indicatorService.calculateMACD(klines, 12, 26, 9);
        double macdLine = macd[0];
        double signalLine = macd[1];

        double tradePercent = settings.getTradePercent();

        // LONG: агрессивнее (RSI <45, price < mid BB, MACD cross up)
        if (currentPrice > sma200 && rsi < 50 && currentPrice < bb[1] && macdLine > signalLine) {
            tradeService.openPosition(symbol, currentPrice, tradePercent, "LONG");
        }
    }
}