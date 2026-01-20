package com.example.demo.services.trade.strategys;

import com.example.demo.data.Trade;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.trade.CalculatorService;
import com.example.demo.services.trade.IndicatorService;
import com.example.demo.services.trade.TradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PositionManager {

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

        // Выход по RSI — на 5m таймфрейме (меньше шума)
        List<double[]> klines = binanceAPI.getKlines(trade.getAsset(), "5m", 30); // 30 свечей для стабильности
        double rsi = indicatorService.calculateRSI(klines, 14);

        boolean rsiExit = false;
        String rsiReason = "";

        if ("LONG".equals(trade.getType())) {
            if (rsi > 85 && netProfit > 0.5) { // Порог 80 + минимальная прибыль
                rsiExit = true;
                rsiReason = "💰 RSI Overbought Exit (80+)";
            }
        } else { // SHORT
            if (rsi < 20 && netProfit > 0.5) { // Порог 20 для oversold
                rsiExit = true;
                rsiReason = "💰 RSI Oversold Exit (20-)";
            }
        }

        if (rsiExit) {
            tradeService.closePosition(trade, currentPrice, rsiReason);
            return;
        }

        // Hard TP остаётся (чистая 2.5%)
        if (netProfit >= 2.5) {
            tradeService.closePosition(trade, currentPrice, "🚀 Hard Take Profit 2.5%");
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
        double oldStopInDb = trade.getStopLoss();
        boolean updated = false;
        double newStop = trade.getStopLoss();

        // 1. ОБНОВЛЯЕМ РЕКОРД ЦЕНЫ (Best Price)
        if ("LONG".equals(trade.getType())) {
            if (currentPrice > best) {
                trade.setBestPrice(currentPrice);
                tradeRepository.save(trade);
                best = currentPrice;
            }

            if (netProfit >= 0.8 && netProfit < 2.0) {
                double safeStop = trade.getEntryPrice() * 1.005;
                if (newStop < safeStop) newStop = safeStop;
            } else if (netProfit >= 2.0) {
                double trailing = trade.getBestPrice() * 0.985;
                if (newStop < trailing) newStop = trailing;
            }
        } else { // SHORT
            if (currentPrice < best || best == 0) {
                trade.setBestPrice(currentPrice);
                updated = true;
            }
            if (netProfit >= 0.8 && netProfit < 2.0) {
                double safeStop = trade.getEntryPrice() * 0.995;
                if (newStop > safeStop) newStop = safeStop;
            } else if (netProfit >= 2.0) {
                double trailing = trade.getBestPrice() * 1.015;
                if (newStop > trailing) newStop = trailing;
            }
        }

        // 2. БЛОК ОБНОВЛЕНИЯ ОРДЕРА НА БИРЖЕ
        double priceChangePercent = Math.abs(newStop - trade.getStopLoss()) / trade.getStopLoss() * 100;

        if (updated && priceChangePercent > 0.2) {
            try {
                try {
                    binanceAPI.cancelAllOrders(trade.getAsset());
                } catch (Exception e) {
                    if (e.getMessage().contains("-2011")) {
                        System.out.println("ℹ️Ордеров для отмены не найдено (уже исполнены или отсутствуют)");
                    } else {
                        System.err.println("❌ Критическая ошибка при отмене: " + e.getMessage());
                    }
                }
                String slSide = "LONG".equals(trade.getType()) ? "SELL" : "BUY";
                double limitPrice = newStop * ("LONG".equals(trade.getType()) ? 0.995 : 1.005);

                String response = binanceAPI.placeStopLossLimit(trade.getAsset(), trade.getQuantity(), newStop, limitPrice, slSide);

                if (response != null) {
                    trade.setStopLoss(newStop);
                    tradeRepository.save(trade);
                    System.out.println("✅ SL обновлен на бирже: " + newStop);
                }
            } catch (Exception e) {
                System.err.println("⚠️ Ошибка синхронизации SL: " + e.getMessage());
            }
        }

        // 3. БЛОК ПРОВЕРКИ ЗАКРЫТИЯ (Всегда вне условий обновления!)
        // Проверяем текущую цену относительно стопа, который сохранен в БД
        boolean triggered = "LONG".equals(trade.getType())
                ? currentPrice <= trade.getStopLoss()
                : currentPrice >= trade.getStopLoss();

        if (triggered) {
            tradeService.closePosition(trade, currentPrice, "🛡️ Trailing Stop Triggered");
        }
    }
}
