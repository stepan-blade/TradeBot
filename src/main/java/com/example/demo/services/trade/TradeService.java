package com.example.demo.services.trade;

import com.example.demo.data.BalanceHistory;
import com.example.demo.data.BotSettings;
import com.example.demo.data.Trade;
import com.example.demo.interfaces.BalanceHistoryRepository;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.api.TelegramAPI;
import com.example.demo.utils.FormatUtil;
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
     * @see #getBalance() - текущий остаток USDT на счете Binance
     * @see #openPosition(String, double, double) - покупка актива
     *
     * @see #closePosition(Trade, double, String) - продажа актива
     * @see #closePositionInDB(Trade, double, String) - Внутренний метод для закрытия сделки ТОЛЬКО в базе (без отправки ордера)
     * @see #closeSpecificTradeManually(String) - Досрочное закрытие активной позиции в ручном режиме
     * @see #closeAllPositionsManually() - Досрочное закрытие всех активных позиции в ручном режиме
     *
     * @see #getActiveTrades() - Список активных сделок
     * @see #getCoolDownMap() - Список активов в листе ожидания
     *
     * @see #syncTradesWithExchange() - Проверяет, остались ли монеты на балансе. Если нет — закрывает сделку в БД.
     * @see #isCoolDown(String) - Проверка актива во временном стоп-листе
     */

    private final BinanceAPI binanceAPI;
    private final TelegramAPI telegramAPI;
    private final CalculatorService calculatorService;
    private final BotSettingsRepository botSettingsRepository;
    private final BalanceHistoryRepository balanceHistoryRepository;
    private final TradeRepository tradeRepository;
    private double usdtBalance;
    private final Map<String, LocalDateTime> coolDownMap = new ConcurrentHashMap<>();

    private static final Logger logger = LoggerFactory.getLogger(TradeService.class);

    @Value("${binance.cooldown.minutes:5}")
    private int cooldownMinutes;

    @Autowired
    public TradeService(BinanceAPI binanceAPI, TelegramAPI telegramAPI,
                        CalculatorService calculatorService, BotSettingsRepository botSettingsRepository,
                        BalanceHistoryRepository balanceHistoryRepository, TradeRepository tradeRepository) {
        this.binanceAPI = binanceAPI;
        this.telegramAPI = telegramAPI;
        this.calculatorService = calculatorService;
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

    /**
     * Текущий остаток USDT на счете Binance
     * @return Остаток USDT на счете Binance
     */
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
        double quantity = FormatUtil.roundToStep(rawQuantity, stepSize);

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

        Trade trade = new Trade(
                symbol,
                startTime,
                price,
                "LONG",
                buyUsdt,
                quantity,
                price * 0.98
        );

        tradeRepository.save(trade);

        telegramAPI.sendMessage("🚀 ПОКУПКА\n" +
                "Актив: " + FormatUtil.formatSymbol(symbol) + "\n" +
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
        double netProfitPercent = calculatorService.getNetResultPercent(trade.getEntryPrice(), currentPrice, trade.getAsset(), trade.getType());
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
                reason, FormatUtil.formatSymbol(trade.getAsset()),
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
        double netProfitPercent = calculatorService.getNetResultPercent(trade.getEntryPrice(), exitPrice, trade.getAsset(), trade.getType());
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