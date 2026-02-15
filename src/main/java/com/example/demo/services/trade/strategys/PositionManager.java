package com.example.demo.services.trade.strategys;

import com.example.demo.data.Trade;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.trade.CalculatorService;
import com.example.demo.services.trade.IndicatorService;
import com.example.demo.services.trade.TradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PositionManager {

    private static final Logger logger = LoggerFactory.getLogger(BinanceAPI.class);

    private final BinanceAPI binanceAPI;
    private final TradeService tradeService;
    private final CalculatorService calculatorService;
    private final IndicatorService indicatorService;
    private final TradeRepository tradeRepository;

    @Autowired
    public PositionManager(BinanceAPI binanceAPI, TradeService tradeService, IndicatorService indicatorService, CalculatorService calculatorService, TradeRepository tradeRepository) {
        this.binanceAPI = binanceAPI;
        this.tradeService = tradeService;
        this.calculatorService = calculatorService;
        this.indicatorService = indicatorService;
        this.tradeRepository = tradeRepository;
    }

    /**
     * Основной обработчик условий выхода из сделки.
     */
    public void handleTradeStop(Trade trade, double currentPrice) {
        double netProfit = calculatorService.getNetResultPercent(
                trade.getEntryPrice(), currentPrice, trade.getAsset(), trade.getType()
        );

        // Таймер: 120 мин если profit <0% (удлинен)
        LocalDateTime entryTime = LocalDateTime.parse(trade.getEntryTime(), DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm"));
        long minutesHeld = ChronoUnit.MINUTES.between(entryTime, LocalDateTime.now());
        if (minutesHeld > 120 && netProfit < 0) {
            tradeService.closePosition(trade, currentPrice, "⏰ Time Limit Exit");
            return;
        }

        // RSI выход: строже (80+ for LONG, >1% profit)
        List<double[]> klines = binanceAPI.getKlines(trade.getAsset(), "15m", 15); // 15m
        double rsi = indicatorService.calculateRSI(klines, 14);

        boolean rsiExit = false;
        String rsiReason = "";

        if ("LONG".equals(trade.getType())) {
            if (rsi > 80 && netProfit > 1.0) {
                rsiExit = true;
                rsiReason = "💰 RSI Exit (80+)";
            }
        } else {
            if (rsi < 20 && netProfit > 1.0) {
                rsiExit = true;
                rsiReason = "💰 RSI Exit (20-)";
            }
        }

        if (rsiExit) {
            tradeService.closePosition(trade, currentPrice, rsiReason);
            return;
        }

        // Hard TP: 3%
        if (netProfit >= 3.0) {
            tradeService.closePosition(trade, currentPrice, "🚀 Take Profit 3%");
            return;
        }

        handleTrailingStop(trade, currentPrice, netProfit);
    }

    /**
     * Динамический trailing stop на ATR.
     */
    public void handleTrailingStop(Trade trade, double currentPrice, double netProfit) {
        double best = trade.getBestPrice();
        double newStop = trade.getStopLoss();

        List<double[]> klines = binanceAPI.getKlines(trade.getAsset(), "15m", 14);
        double atr = indicatorService.calculateATR(klines, 14);

        if ("LONG".equals(trade.getType())) {
            if (currentPrice > best) {
                trade.setBestPrice(currentPrice);
                tradeRepository.save(trade);
            }

            if (netProfit >= 1.5) {
                double trailing = trade.getBestPrice() - (2 * atr); // 2*ATR trail
                if (newStop < trailing) newStop = trailing;
            }
        } else {
            if (currentPrice < best || best == 0) {
                trade.setBestPrice(currentPrice);
                tradeRepository.save(trade);
            }

            if (netProfit >= 1.5) {
                double trailing = trade.getBestPrice() + (2 * atr);
                if (newStop > trailing) newStop = trailing;
            }
        }

        // Update SL if changed >0.2%
        double priceChangePercent = Math.abs(newStop - trade.getStopLoss()) / trade.getStopLoss() * 100;
        if (priceChangePercent > 0.2) {
            try {
                binanceAPI.cancelAllOrders(trade.getAsset());
                String slSide = "LONG".equals(trade.getType()) ? "SELL" : "BUY";
                double limitPrice = newStop * ("LONG".equals(trade.getType()) ? 0.995 : 1.005);
                binanceAPI.placeStopLossLimit(trade.getAsset(), trade.getQuantity(), newStop, limitPrice, slSide);

                trade.setStopLoss(newStop);
                tradeRepository.save(trade);
            } catch (Exception e) {
                logger.error("Ошибка обновления SL: {}", e.getMessage());
            }
        }

        boolean triggered = "LONG".equals(trade.getType())
                ? currentPrice <= trade.getStopLoss()
                : currentPrice >= trade.getStopLoss();

        if (triggered) {
            tradeService.closePosition(trade, currentPrice, "🛡️ Trailing Stop");
        }
    }
}