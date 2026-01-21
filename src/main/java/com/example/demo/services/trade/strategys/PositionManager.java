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
     * Основной обработчик условий выхода из сделки по техническим и фиксированным параметрам.
     * <p>
     * Метод анализирует состояние текущей сделки по трем направлениям:
     * 1. Импульс (RSI): Если актив перекуплен (RSI > 75) на минутном графике, позиция закрывается для фиксации локального пика.
     * 2. Жесткий лимит (Hard Take Profit): Если чистая прибыль достигла 2.5%, сделка закрывается автоматически.
     * 3. Динамическая защита: Если условия выше не выполнены, управление передается трейлинг-стопу.
     * @param trade Объект активной сделки из базы данных.
     * @param currentPrice Текущая рыночная цена актива.
     */
    public void handleTradeStop(Trade trade, double currentPrice) {
        double netProfit = calculatorService.getNetResultPercent(
                trade.getEntryPrice(), currentPrice, trade.getAsset(), trade.getType()
        );

        // Таймер на сделку — если >30 мин и profit <0.5% — exit
        LocalDateTime entryTime = LocalDateTime.parse(trade.getEntryTime(), DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm"));
        long minutesHeld = ChronoUnit.MINUTES.between(entryTime, LocalDateTime.now());
        if (minutesHeld > 30 && netProfit < 0.5) {
            tradeService.closePosition(trade, currentPrice, "⏰ Time Limit Exit");
            return;
        }

        // Выход по RSI — на 1m для скорости (скальпинг)
        List<double[]> klines = binanceAPI.getKlines(trade.getAsset(), "1m", 15);
        double rsi = indicatorService.calculateRSI(klines, 14);

        boolean rsiExit = false;
        String rsiReason = "";

        if ("LONG".equals(trade.getType())) {
            if (rsi > 70 && netProfit > 0.3) {
                rsiExit = true;
                rsiReason = "💰 RSI Quick Exit (70+)";
            }
        } else {
            if (rsi < 30 && netProfit > 0.3) {
                rsiExit = true;
                rsiReason = "💰 RSI Quick Exit (30-)";
            }
        }

        if (rsiExit) {
            tradeService.closePosition(trade, currentPrice, rsiReason);
            return;
        }

        // Hard TP снижен до 1% для малой прибыли
        if (netProfit >= 1.0) {
            tradeService.closePosition(trade, currentPrice, "🚀 Quick Take Profit 1%");
            return;
        }

        handleTrailingStop(trade, currentPrice, netProfit);
    }

    /**
     * Логика динамического перемещения уровня Stop-Loss (Трейлинг-стоп).
     * <p>
     * Метод реализует ступенчатую защиту прибыли:
     * 1. Сохранение пика: Обновляет в базе данных значение 'bestPrice', если цена поставила новый рекорд.
     * 2. Уровень "Безубыток+": При достижении профита 0.8%, стоп-лосс переносится в зону профита (+0.5% от входа).
     * 3. Активный трейлинг: При профите выше 2.0%, стоп-лосс начинает следовать за ценой на расстоянии 1.5% от пика.
     * 4. Исполнение: Если текущая цена касается или падает ниже рассчитанного стоп-лосса, сделка закрывается.
     * @param trade Объект активной сделки.
     * @param currentPrice Текущая рыночная цена актива.
     * @param netProfit    Текущая доходность сделки в процентах.
     */
    public void handleTrailingStop(Trade trade, double currentPrice, double netProfit) {
        double best = trade.getBestPrice();
        double newStop = trade.getStopLoss();

        if ("LONG".equals(trade.getType())) {
            if (currentPrice > best) {
                trade.setBestPrice(currentPrice);
                tradeRepository.save(trade);
            }

            if (netProfit >= 0.5 && netProfit < 1.0) { // Снижен для безубытка
                double safeStop = trade.getEntryPrice() * 1.003;
                if (newStop < safeStop) newStop = safeStop;
            } else if (netProfit >= 1.0) { // Трейлинг 1%
                double trailing = trade.getBestPrice() * 0.99; // 1% от пика
                if (newStop < trailing) newStop = trailing;
            }
        } else {
            if (currentPrice < best || best == 0) {
                trade.setBestPrice(currentPrice);
                tradeRepository.save(trade);
            }

            if (netProfit >= 0.5 && netProfit < 1.0) {
                double safeStop = trade.getEntryPrice() * 0.997;
                if (newStop > safeStop) newStop = safeStop;
            } else if (netProfit >= 1.0) {
                double trailing = trade.getBestPrice() * 1.01;
                if (newStop > trailing) newStop = trailing;
            }
        }

        // Обновляем SL на бирже если изменился >0.2%
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

        // Проверка триггера
        boolean triggered = "LONG".equals(trade.getType())
                ? currentPrice <= trade.getStopLoss()
                : currentPrice >= trade.getStopLoss();

        if (triggered) {
            tradeService.closePosition(trade, currentPrice, "🛡️ Trailing Stop");
        }
    }
}
