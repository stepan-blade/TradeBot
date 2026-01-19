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
        if (tradeService.isCoolDown(symbol)) {
            return;
        }

        double volume24h = binanceAPI.get24hVolume(symbol);
        if (volume24h < 5000000) return;

        List<double[]> klines = binanceAPI.getKlines(symbol, "1m", 200);
        if (klines.isEmpty()) return;

        double rsi = indicatorService.calculateRSI(klines, 14);
        double sma200 = indicatorService.calculateSMA(klines, 200);
        double[] bb = indicatorService.calculateBollingerBands(klines, 20, 2.0);

        if (rsi <= 0 || sma200 <= 0 || bb == null) return;

        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElseThrow();
        double tradePercent = settings.getTradePercent();
        if (currentPrice > sma200 && rsi < 35 && currentPrice <= bb[2] && tradeService.getBalance() >= 5) {
            tradeService.openPosition(symbol, currentPrice, tradePercent);
        }
    }
}
