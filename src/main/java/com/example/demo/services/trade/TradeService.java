package com.example.demo.services.trade;

import com.example.demo.data.BalanceHistory;
import com.example.demo.data.BotSettings;
import com.example.demo.data.Trade;
import com.example.demo.interfaces.BalanceHistoryRepository;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.api.TelegramAPI;
import com.example.demo.utils.FormatterUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TradeService {

    /**
     * @see #openPosition(String, double, double) - покупка актива
     *
     * @see #closePosition(Trade, double, String) - продажа актива
     * @see #closeSpecificTradeManually(String) - Досрочное закрытие активной позиции в ручном режиме
     * @see #closeAllPositionsManually() - Досрочное закрытие всех активных позиции в ручном режиме
     *
     * @see #getActiveTrades() - Список активных сделок
     * @see #getTotalFeePercent(String) - Вспомогательный метод для расчета итоговый комиссии торговой площадки в процентах
     * @see #getCoolDownMap() - Список активов в листе ожидания
     * @see #getOccupiedBalance() - Сумма USDT в активных сделках
     * @see #getTotalEquity() - Свободные USDT + Стоимость всех открытых позиций
     *
     * @see #calculateTodayProfitUSDT() - Расчет показателя общей доходности за текущий день в USDT
     * @see #calculateAllProfitPercent() - Расчет показателя общей доходности в %
     * @see #calculateActiveProfitPercent(Trade, double) - Расчет показателя доходности текущей сделки в %
     * @see #calculateNetResultPercent(double, double, String, String) -  Единый метод расчета чистой прибыли с учетом комиссий Binance.
     *
     * @see #isCoolDown(String) - Проверка актива во временном стоп-листе

     */

    private final BinanceAPI binanceAPI;
    private final TelegramAPI telegramAPI;
    private final BotSettingsRepository botSettingsRepository;
    private final BalanceHistoryRepository balanceHistoryRepository;
    private final TradeRepository tradeRepository;
    private double usdtBalance;
    private final Map<String, LocalDateTime> coolDownMap = new ConcurrentHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(TradeService.class);

    @Value("${binance.cooldown.minutes:5}")
    private int cooldownMinutes;

    @Autowired
    public TradeService(BinanceAPI binanceAPI, TelegramAPI telegramAPI, BotSettingsRepository botSettingsRepository, BalanceHistoryRepository balanceHistoryRepository, TradeRepository tradeRepository) {
        this.binanceAPI = binanceAPI;
        this.telegramAPI = telegramAPI;
        this.botSettingsRepository = botSettingsRepository;
        this.balanceHistoryRepository = balanceHistoryRepository;
        this.tradeRepository = tradeRepository;
    }

    @PostConstruct
    public void init() {
        this.usdtBalance = binanceAPI.getAccountBalance();

        BotSettings botSettings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        if (botSettings.getBalance() == 0) {
            botSettings.setBalance(usdtBalance);
            botSettingsRepository.save(botSettings);
        }
    }

    public double getBalance() {
        try {
            this.usdtBalance = binanceAPI.getAccountBalance();
            return usdtBalance;
        } catch (Exception e) {
            System.err.println("Ошибка получения баланса: " + e.getMessage());
            return 0.0;
        }
    }

    /**
     * Покупка актива
     * @param symbol Валютная пара
     * @param price Цена входа
     * @param percent Процент от свободного баланса
     */
    public void openPosition(String symbol, double price, double percent) {
        double buyUsdt = Math.min(usdtBalance * (percent / 100.0), usdtBalance);
        if (buyUsdt < 5.0) return;

        // 1. Получаем правила округления от биржи
        double stepSize = binanceAPI.getStepSize(symbol);

        // 2. Рассчитываем и СТРОГО округляем количество
        double rawQuantity = buyUsdt / price;
        double quantity = FormatterUtil.roundToStep(rawQuantity, stepSize);

        // 3. Попытка покупки
        String orderId = null;
        try {
            orderId = binanceAPI.placeMarketBuy(symbol, quantity);
        } catch (Exception e) {
            telegramAPI.sendMessage("❌ Ошибка покупки " + symbol + ": " + e.getMessage());
            return;
        }

        if (orderId == null) return;

        // 4. Попытка установки защиты (Stop Loss)
        try {
            double stopPrice = price * 0.98;
            double limitPrice = stopPrice * 0.995;

            binanceAPI.placeStopLossLimit(symbol, quantity, stopPrice, limitPrice);

        } catch (Exception e) {
            telegramAPI.sendMessage(
                    "⚠️ ВНИМАНИЕ! Откат сделки " + symbol +
                    "\nНе удалось поставить StopLoss: " + e.getMessage()
            );
            try {
                binanceAPI.placeMarketSell(symbol, quantity);
            } catch (Exception sellEx) {
                telegramAPI.sendMessage("🆘 SOS! Не удалось продать актив обратно! Ручное вмешательство: " + symbol);
            }
            return;
        }

        logger.info("Открытие сделки: {}", symbol);
        logger.info("✅ Куплено монеты {}: {}", symbol, quantity);

        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm"));
        Trade trade = new Trade(startTime, symbol, "LONG", price, buyUsdt, quantity);
        trade.setStatus("OPEN");
        trade.setStopLoss(price * 0.98);
        trade.setBestPrice(price);
        trade.setQuantity(quantity);
        tradeRepository.save(trade);

        telegramAPI.sendMessage("🚀 ПОКУПКА\n" +
                "Актив: " + FormatterUtil.formatSymbol(symbol) + "\n" +
                "Сумма: " + String.format("%.2f", buyUsdt) + " USDT\n" +
                "Количество: " + String.format("%.6f", quantity) + "\n" +
                "Остаток USDT: " + String.format("%.2f", usdtBalance - buyUsdt));
    }

    /**
     * Продажа актива
     * @param trade Активная сделка
     * @param currentPrice Текущая цена
     * @param reason Причина продажи
     */
    public void closePosition(Trade trade, double currentPrice, String reason) {
        double quantity = trade.getQuantity();

        // ЗАЩИТА: Если в базе 0, пробуем взять реальный баланс с биржи
        if (quantity <= 0) {
            logger.warn("⚠️ В базе данных quantity=0 для {}. Запрашиваю баланс с биржи...", trade.getAsset());
            quantity = binanceAPI.getAssetBalance(trade.getAsset());
            trade.setQuantity(quantity); // Сразу обновляем объект
        }

        if (quantity <= 0) {
            logger.error("❌ Не удалось закрыть сделку {}: Баланс на бирже тоже 0", trade.getAsset());
            return;
        }

        // 1. Выполняем продажу на бирже
        String orderId = null;
        try {
            orderId = binanceAPI.placeMarketSell(trade.getAsset(), quantity);
        } catch (Exception e) {
            telegramAPI.sendMessage("❌ Ошибка продажи " + trade.getAsset() + ": " + e.getMessage());
            return;
        }
        logger.info("Закрытие сделки: {}", trade.getAsset());

        // 2. Рассчитываем финансовый результат через единый метод
        double netProfitPercent = calculateNetResultPercent(trade.getEntryPrice(), currentPrice, trade.getAsset(), trade.getType());
        double profitUsdt = trade.getVolume() * (netProfitPercent / 100.0);

        // 3. Обновляем баланс СТРОГО после завершения сделки на бирже
        this.usdtBalance = binanceAPI.getAccountBalance();

        // 4. Сохраняем историю баланса и настройки
        balanceHistoryRepository.save(new BalanceHistory(usdtBalance, LocalDateTime.now()));
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        settings.setBalance(usdtBalance);
        botSettingsRepository.save(settings);

        // 5. Обновляем и сохраняем сделку
        trade.setExitTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm")));
        trade.setExitPrice(currentPrice);
        trade.setProfit(profitUsdt);
        trade.setStatus("CLOSED");
        tradeRepository.save(trade);

        coolDownMap.put(trade.getAsset(), LocalDateTime.now().plusMinutes(cooldownMinutes));

        // 6. Уведомление Telegram
        String message = String.format("%s\nАктив: %s\nИтог: %s%.2f USDT (%.2f%%)",
                reason, FormatterUtil.formatSymbol(trade.getAsset()),
                (profitUsdt >= 0 ? "+" : ""), profitUsdt, netProfitPercent);
        telegramAPI.sendMessage(message);
    }

    /**
     * Синхронизация статусов.
     * Проверяет, остались ли монеты на балансе. Если нет — закрывает сделку в БД.
     */
    public void syncTradesWithExchange() {
        List<Trade> activeTrades = getActiveTrades();

        for (Trade trade : activeTrades) {
            // 1. Спрашиваем у Binance реальный баланс монеты
            double actualBalance = binanceAPI.getAssetBalance(trade.getAsset());

            // 2. Считаем порог "пыли" (остатков).
            double dustThreshold = trade.getQuantity() * 0.05;

            if (actualBalance < dustThreshold) {
                logger.info("📉 Обнаружено закрытие сделки на бирже: " + trade.getAsset());

                // 3. Фиксируем закрытие
                double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());

                closePositionInDB(trade, currentPrice, "⚖️ Exchange Stop/TP Triggered");
            }
        }
    }

    /**
     * Внутренний метод для закрытия сделки ТОЛЬКО в базе (без отправки ордера)
     */
    private void closePositionInDB(Trade trade, double exitPrice, String reason) {
        // Расчет прибыли
        double netProfitPercent = calculateNetResultPercent(trade.getEntryPrice(), exitPrice, trade.getAsset(), trade.getType());
        double profitUsdt = trade.getVolume() * (netProfitPercent / 100.0);

        // Обновляем баланс USDT в боте
        this.usdtBalance = binanceAPI.getAccountBalance(); // Обновляем общий кеш USDT

        // Сохраняем историю
        trade.setExitTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm")));
        trade.setExitPrice(exitPrice);
        trade.setProfit(profitUsdt);
        trade.setStatus("CLOSED"); // Закрываем статус
        tradeRepository.save(trade);

        // Ставим кулдаун
        coolDownMap.put(trade.getAsset(), LocalDateTime.now().plusMinutes(cooldownMinutes));

        telegramAPI.sendMessage(String.format("🔔 Синхронизация: Сделка %s закрыта биржей (%s).\nИтог: %.2f$ (%.2f%%)",
                trade.getAsset(), reason, profitUsdt, netProfitPercent));
    }

    /**
     * Досрочное закрытие всех активных позиции в ручном режиме
     */
    public void closeAllPositionsManually() {
        List<Trade> open = getActiveTrades();
        for (Trade t : open) {
            closePosition(t, binanceAPI.getCurrentPrice(t.getAsset()), "⚡ Manual Close");
        }
    }

    /**
     * Досрочное закрытие активной позиции в ручном режиме
     * @param symbol Валютная пара
     */
    public void closeSpecificTradeManually(String symbol) {
        Optional<Trade> tradeOpt = getActiveTrades().stream()
                .filter(t -> t.getAsset().equals(symbol))
                .findFirst();
        tradeOpt.ifPresent(trade -> closePosition(trade, binanceAPI.getCurrentPrice(symbol), "⚡ Manual Close"));
    }

    /**
     * Список активных сделок
     * @return Список активных сделок
     */
    public List<Trade> getActiveTrades() {
        return tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Единый метод расчета чистой прибыли с учетом комиссий Binance.
     * @param entryPrice Цена входа
     * @param exitPrice  Цена выхода (или текущая)
     * @param symbol     Символ (для получения комиссии)
     * @param type       Тип сделки (BUY/SHORT)
     * @return Объекта с данными: [0] - чистый профит в %, [1] - чистый профит в USDT (если передан объем)
     */
    public double calculateNetResultPercent(double entryPrice, double exitPrice, String symbol, String type) {
        if (entryPrice <= 0 || exitPrice <= 0) return 0.0;

        // 1. Получаем комиссию (обычно 0.001 для тейкера)
        double[] fees = binanceAPI.getTradeFee(symbol);
        double takerFeePercent = fees[1] * 100; // Конвертируем в % (0.1%)
        double totalFeePercent = takerFeePercent * 2; // Покупка + Продажа

        // 2. Считаем разницу цены в %
        double priceDiffPercent = ((exitPrice - entryPrice) / entryPrice) * 100;

        // Если это SHORT, профит идет при падении цены
        if ("SHORT".equals(type)) {
            priceDiffPercent *= -1;
        }

        // 3. Итог: Грязный профит - Суммарная комиссия
        return priceDiffPercent - totalFeePercent;
    }

    /**
     * Расчет показателя общей доходности за текущий день в USDT
     * @return USDT доходности за день
     */
    public double calculateTodayProfitUSDT() {
        String todayPrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        return tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .filter(t -> t.getExitTime() != null && t.getExitTime().startsWith(todayPrefix))
                .mapToDouble(Trade::getProfit)
                .sum();
    }

    /**
     * Расчет показателя общей доходности за текущий день в %
     * @return % доходности за день
     */
    public double calculateTodayProfitPercent() {
        double todayProfitUSDT = calculateTodayProfitUSDT();
        double currentEquity = getTotalEquity();
        double startEquityToday = currentEquity - todayProfitUSDT;

        if (Math.abs(todayProfitUSDT) < 0.0001 || startEquityToday < 1.0) {
            return 0.0;
        }

        double percent = (todayProfitUSDT / startEquityToday) * 100.0;

        return Math.round(percent * 100.0) / 100.0;
    }

    /**
     * Свободные USDT + Стоимость всех открытых позиций
     * @return
     */
    public double getTotalEquity() {
        double freeUsdt = getBalance();
        double lockedInTrades = getActiveTrades().stream()
                .mapToDouble(trade -> {
                    double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
                    return trade.getQuantity() * currentPrice;
                })
                .sum();
        return freeUsdt + lockedInTrades;
    }

    /**
     * Расчет показателя общей доходности в %
     * @return общий % доходности
     */
    public double calculateAllProfitPercent() {
        BotSettings botSettings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(null);
        if (botSettings == null || botSettings.getBalance() <= 0) return 0.0;

        double initialBalance = botSettings.getBalance();
        double currentTotalEquity = getTotalEquity();

        double diff = currentTotalEquity - initialBalance;
        double result = (diff / initialBalance) * 100.0;

        // Лог поможет понять, почему получается ноль
        logger.info("Расчет общего PnL: Equity={}, Start={}, Diff={}",
                String.format("%.2f", currentTotalEquity),
                String.format("%.2f", initialBalance),
                String.format("%.4f", diff));

        return Math.round(result * 100.0) / 100.0;
    }

    /**
     * Расчет показателя доходности текущей сделки в %
     * @param trade Активная сделка
     * @param currentPrice Текущая цена актива
     * @return % доходности текущих сделок
     */
    public double calculateActiveProfitPercent(Trade trade, double currentPrice) {
        return ((currentPrice - trade.getEntryPrice()) / trade.getEntryPrice()) * 100;
    }

    /**
     * Сумма USDT в активных сделках
     * @return USDT в активных сделках
     */
    public double getOccupiedBalance() {
        return getActiveTrades().stream()
                .mapToDouble(Trade::getVolume)
                .sum();
    }

    /**
     * Вспомогательный метод для расчета итоговый комиссии торговой площадки в процентах
     * @param symbol Валютная пара
     * @return %
     */
    public double getTotalFeePercent(String symbol) {
        double[] fees = binanceAPI.getTradeFee(symbol);

        return (fees[1] * 2) * 100;
    }

    /**
     * Список активов в стоп-листе
     * @return Список активов
     */
    public Map<String, LocalDateTime> getCoolDownMap() {
        return coolDownMap;
    }

    /**
     * Проверка актива во временном стоп-листе
     * @param symbol Валютная пара
     * @return true/false
     */
    public boolean isCoolDown(String symbol) {
        if (getCoolDownMap().containsKey(symbol)) {
            if (LocalDateTime.now().isBefore(getCoolDownMap().get(symbol))) {
                return true;
            } else {
                getCoolDownMap().remove(symbol);
            }
        }
        return false;
    }
}

