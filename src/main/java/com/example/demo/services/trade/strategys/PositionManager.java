package com.example.demo.services.trade.strategys;

import com.example.demo.data.Trade;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.trade.CalculatorService;
import com.example.demo.services.trade.IndicatorService;
import com.example.demo.services.trade.TradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

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
        // Используем единый метод для оценки РЕАЛЬНОГО профита в сделке прямо сейчас
        double netProfit = calculatorService.getNetResultPercent(
                trade.getEntryPrice(), currentPrice, trade.getAsset(), trade.getType()
        );

        // Выход по RSI (минутный таймфрейм)
        double rsi = indicatorService.calculateRSI(binanceAPI.getKlines(trade.getAsset(), "1m", 15), 14);
        if (rsi > 75) {
            tradeService.closePosition(trade, currentPrice, "💰 RSI Overbought Exit");
            return;
        }

        // Хард тейк-профит сравнивается с ЧИСТОЙ прибылью
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
        boolean needsUpdateOnExchange = false;
        double newStopPrice = trade.getStopLoss();

        if (currentPrice > best) {
            trade.setBestPrice(currentPrice);
            tradeRepository.save(trade);
        }

        // Логика расчета
        if (netProfit >= 0.8 && netProfit < 2.0) {
            double safeStop = trade.getEntryPrice() * 1.005;
            if (newStopPrice < safeStop) {
                newStopPrice = safeStop;
                needsUpdateOnExchange = true;
            }
        } else if (netProfit >= 2.0) {
            double activeTrailing = trade.getBestPrice() * 0.985;
            if (newStopPrice < activeTrailing) {
                newStopPrice = activeTrailing;
                needsUpdateOnExchange = true;
            }
        }

        if (needsUpdateOnExchange) {
            try {
                // 1. Отменяем старые ордера
                binanceAPI.cancelAllOrders(trade.getAsset());

                // 2. Ставим новый ордер
                double limitPrice = newStopPrice * 0.995;
                String response = binanceAPI.placeStopLossLimit(trade.getAsset(), trade.getQuantity(), newStopPrice, limitPrice);

                if (response != null) {
                    trade.setStopLoss(newStopPrice);
                    tradeRepository.save(trade);
                    System.out.println("✅ SL обновлен: " + newStopPrice);
                }
            } catch (Exception e) {
                System.err.println("❌ Ошибка трейлинга: " + e.getMessage());
            }
        }
    }
}
