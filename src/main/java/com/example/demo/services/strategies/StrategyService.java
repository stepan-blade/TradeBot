package com.example.demo.services.strategies;

import com.example.demo.DemoTradingBot;
import com.example.demo.data.BotSettings;
import com.example.demo.services.TradeService;
import com.example.demo.services.app.BinanceAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class StrategyService {
    @Autowired
    private BinanceAPI binanceAPI;
    @Autowired
    private TradeService tradeService;
    @Autowired
    DemoTradingBot demoTradingBot;

    private void checkEntryConditions(String symbol, double currentPrice, BotSettings settings, int currentOpenCount) {
        double volume = binanceAPI.get24hVolume(symbol);
        if (volume < 5000000) return;

        double rsi = binanceAPI.calculateRealRSI(symbol);
        double sma200 = binanceAPI.calculateSMA(symbol, 200);
        double[] bb = binanceAPI.calculateBollingerBands(symbol, 20, 2.0);

        if (rsi <= 0 || sma200 <= 0 || bb == null) return;

        // УСЛОВИЕ LONG: Тренд выше SMA200 + Перепроданность (RSI < 35) + Касание нижней границы BB
        if (currentPrice > sma200 && rsi < 35 && currentPrice <= bb[2]) { // bb[2] - нижняя граница
            tradeService.openPosition(symbol, "LONG", currentPrice, (demoTradingBot.getBalance() - tradeService.calculateOccupiedVolume()), settings.getTradePercent());
        }
        // УСЛОВИЕ SHORT: Тренд ниже SMA200 + Перекупленность (RSI > 65) + Касание верхней границы BB
        else if (currentPrice < sma200 && rsi > 65 && currentPrice >= bb[0]) { // bb[0] - верхняя граница
            tradeService.openPosition(symbol, "SHORT", currentPrice, (demoTradingBot.getBalance() - tradeService.calculateOccupiedVolume()), settings.getTradePercent());
        }
    }
}