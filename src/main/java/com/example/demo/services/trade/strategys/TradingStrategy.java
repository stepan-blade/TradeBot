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

        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        int maxOpenTrades = settings.getMaxOpenTrades() != 0 ? settings.getMaxOpenTrades() : 2;
        if (tradeService.getActiveTrades().size() >= maxOpenTrades) return;

        List<double[]> klines = binanceAPI.getKlines(symbol, "15m", 250);

        double rsi = indicatorService.calculateRSI(klines, 14);
        double ema9 = indicatorService.calculateEMA(klines, 9);
        double ema21 = indicatorService.calculateEMA(klines, 21);
        double adx = indicatorService.calculateADX(klines, 14);
        double vwap = indicatorService.calculateVWAP(klines, 20);
        double[] volumes = klines.stream()
                .filter(k -> k != null && k.length > 5)
                .mapToDouble(k -> k[5])
                .toArray();

        double avgVolume = volumes.length >= 20
                ? indicatorService.calculateSMA(volumes, 20)
                : 0.0;
        double currentVolume = klines.get(klines.size() - 1)[4];

        double tradePercent = settings.getTradePercent() != 0 ? settings.getTradePercent() : 15.0;

//        System.out.println(
//                symbol + "\n" +
//                "ema9: " + ema9 + " > ema21: " + ema21 + "?\n" +
//                "adx: " + adx + " > 25\n" +
//                "rsi: " + rsi + " < 55\n" +
//                "currentVolume: " + currentVolume + " > avgVolume: " + avgVolume + "\n" +
//                "currentPrice: " + currentPrice + " > vwap: " + vwap);

        // LONG: EMA crossover, strong trend, not overbought, volume breakout, above VWAP
        if (ema9 > ema21 && adx > 25 && rsi < 55 && currentVolume > avgVolume && currentPrice > vwap) {
            tradeService.openPosition(symbol, currentPrice, tradePercent, "LONG");
        }
    }
}