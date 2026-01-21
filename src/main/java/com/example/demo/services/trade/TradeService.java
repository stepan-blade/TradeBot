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
import com.example.demo.utils.TimeUtil;
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
     * @see #openPosition(String, double, double, String)  - покупка актива
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
        updateBalanceFromExchange();

        BotSettings botSettings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        if (botSettings.getBalance() == 0) {
            botSettings.setBalance(usdtBalance);
            botSettingsRepository.save(botSettings);
        }
    }

    private void updateBalanceFromExchange() {
        try {
            this.usdtBalance = binanceAPI.getAccountBalance();
        } catch (Exception e) {
            logger.error("Ошибка инициализации баланса: {}", e.getMessage());
            this.usdtBalance = 0.0;
        }
    }

    /**
     * Текущий остаток USDT на счете Binance
     * @return Остаток USDT на счете Binance
     */
    public double getBalance() {
        updateBalanceFromExchange();
        return usdtBalance;
    }

    /**
     * Открытие позиции (LONG или SHORT)
     * @param symbol  Валютная пара
     * @param price   Текущая цена
     * @param percent Процент от баланса
     * @param type    "LONG" или "SHORT"
     */
    public void openPosition(String symbol, double price, double percent, String type) {
        double availableUsdt = binanceAPI.getAccountBalance();
        this.usdtBalance = availableUsdt;

        double buyUsdt = Math.min(availableUsdt * (percent / 100.0), availableUsdt);

        if (buyUsdt < 10.0) {
            logger.warn("Пропуск: На бирже {} USDT. Минимум 10.0 USDT", availableUsdt);
            return;
        }

        double stepSize = binanceAPI.getStepSize(symbol);
        double rawQuantity = buyUsdt / price;
        int precision = 0;

        if (stepSize < 1) {
            precision = (int) Math.round(-Math.log10(stepSize));
        }

        java.math.BigDecimal bd = new java.math.BigDecimal(String.valueOf(rawQuantity));
        bd = bd.setScale(precision, java.math.RoundingMode.DOWN);
        double quantity = bd.doubleValue();
        logger.info("Расчет quantity: Raw={}, Step={}, Precision={}, Final={}", rawQuantity, stepSize, precision, quantity);

        try {
            binanceAPI.cancelAllOrders(symbol);
        } catch (Exception e) {
            System.out.println(TimeUtil.getTime() + " --- [BINANCE API] Ордеров для закрытия не найдено");
        }

        String orderId = null;
        try {
            if ("LONG".equals(type)) {
                orderId = binanceAPI.placeMarketBuy(symbol, quantity);
            } else {
                orderId = binanceAPI.placeMarketSell(symbol, quantity);
            }
        } catch (Exception e) {
            telegramAPI.sendMessage("❌ Ошибка открытия " + type + " " + symbol + ": " + e.getMessage());
            return;
        }

        if (orderId == null) {
            telegramAPI.sendMessage("❌ Не удалось открыть " + type + " " + symbol);
            return;
        }

        // Получаем реальное количество монет после исполнения
        double actualQuantity = 0;
        try {
            String baseAsset = symbol.replace("USDT", "");
            actualQuantity = binanceAPI.getAssetBalance(baseAsset);
            if ("SHORT".equals(type)) {

                actualQuantity = binanceAPI.getAccountBalance() - usdtBalance;
            }
        } catch (Exception e) {
            telegramAPI.sendMessage("⚠️ Ошибка получения актуального quantity для " + symbol);
        }

        if (actualQuantity <= 0) {
            telegramAPI.sendMessage("⚠️ Получено 0 монет после открытия " + symbol + " — откат");
            try {
                if ("LONG".equals(type)) binanceAPI.placeMarketSell(symbol, quantity);
                else binanceAPI.placeMarketBuy(symbol, quantity);
            } catch (Exception rollbackEx) {
                telegramAPI.sendMessage("🆘 SOS! Не удалось откатить позицию " + symbol);
            }
            return;
        }

        // Округляем actualQuantity под stepSize
        stepSize = binanceAPI.getStepSize(symbol);
        actualQuantity = FormatUtil.roundToStep(actualQuantity, stepSize);

        // Ставим stop-loss с актуальным quantity
        try {
            double stopPrice;
            double limitPrice;
            String slSide = "LONG".equals(type) ? "SELL" : "BUY";

            if ("LONG".equals(type)) {
                stopPrice = price * 0.98;
                limitPrice = stopPrice * 0.995;
            } else {
                stopPrice = price * 1.02;
                limitPrice = stopPrice * 1.005;
            }

            binanceAPI.placeStopLossLimit(symbol, actualQuantity, stopPrice, limitPrice, slSide);
        } catch (Exception e) {
            telegramAPI.sendMessage("⚠️ Не удалось поставить SL для " + type + " " + symbol + ": " + e.getMessage());
            try {
                if ("LONG".equals(type)) {
                    binanceAPI.placeMarketSell(symbol, actualQuantity);
                } else {
                    binanceAPI.placeMarketBuy(symbol, actualQuantity);
                }
            } catch (Exception rollbackEx) {
                telegramAPI.sendMessage("🆘 SOS! Критическая ошибка — позиция " + symbol + " открыта без SL!");
            }
            return;
        }

        // Обновляем баланс и сохраняем сделку actualQuantity
        updateBalanceFromExchange();

        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm"));
        Trade trade = new Trade(symbol, startTime, price, type, buyUsdt, actualQuantity, ("LONG".equals(type) ? price * 0.98 : price * 1.02));
        tradeRepository.save(trade);

        telegramAPI.sendMessage(String.format("""
                        🚀 ОТКРЫТА %s ПОЗИЦИЯ
                        Актив: %s
                        Сумма: %.2f USDT
                        Количество: %.6f (фактическое)
                        Остаток USDT: %.2f""",
                type, FormatUtil.formatSymbol(symbol), buyUsdt, actualQuantity, usdtBalance));
    }

    /**
     * Продажа актива
     * @param trade Активная сделка
     * @param currentPrice Текущая цена
     * @param reason Причина продажи
     */
    public void closePosition(Trade trade, double currentPrice, String reason) {
        try {
            binanceAPI.cancelAllOrders(trade.getAsset());
        } catch (Exception e) {
            System.out.println(TimeUtil.getTime() + " --- [BINANCE API] Ордеров для закрытия не найдено");
        }

        double quantity = trade.getQuantity();
        if (quantity <= 0) {
            quantity = binanceAPI.getAssetBalance(trade.getAsset().replace("USDT", ""));
            if (quantity <= 0) {
                logger.error("Не удалось закрыть {}: количество монет на бирже - 0", trade.getAsset());
                return;
            }
        }

        String orderId = null;
        try {
            if ("LONG".equals(trade.getType())) {
                orderId = binanceAPI.placeMarketSell(trade.getAsset(), quantity);
            } else {
                orderId = binanceAPI.placeMarketBuy(trade.getAsset(), quantity);
            }
        } catch (Exception e) {
            telegramAPI.sendMessage("❌ Ошибка закрытия " + trade.getType() + " " + trade.getAsset() + ": " + e.getMessage());
            return;
        }

        if (orderId == null) {
            telegramAPI.sendMessage("❌ Не удалось закрыть позицию " + trade.getAsset() + ". Возможно, позиция была закрыта вручную");
            return;
        }

        // Актуальный расчёт прибыли
        double netProfitPercent = calculatorService.getNetResultPercent(trade.getEntryPrice(), currentPrice, trade.getAsset(), trade.getType());
        double profitUsdt = trade.getVolume() * (netProfitPercent / 100.0);

        // Обновляем баланс
        updateBalanceFromExchange();

        // Сохраняем историю
        balanceHistoryRepository.save(new BalanceHistory(usdtBalance, LocalDateTime.now()));

        // Обновляем сделку
        trade.setExitTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm")));
        trade.setExitPrice(currentPrice);
        trade.setProfit(profitUsdt);
        trade.setStatus("CLOSED");
        tradeRepository.save(trade);

        coolDownMap.put(trade.getAsset(), LocalDateTime.now().plusMinutes(cooldownMinutes));

        telegramAPI.sendMessage(String.format("%s\nАктив: %s\nИтог: %s%.2f USDT (%.2f%%)",
                reason, FormatUtil.formatSymbol(trade.getAsset()),
                (profitUsdt >= 0 ? "+" : ""), profitUsdt, netProfitPercent));
    }

    /**
     * Внутренний метод для закрытия сделки ТОЛЬКО в базе (без отправки ордера)
     */
    public void closePositionInDB(Trade trade, double exitPrice, String reason) {
        try {
            binanceAPI.cancelAllOrders(trade.getAsset());
        } catch (Exception e) {
            System.out.println(TimeUtil.getTime() + " --- [BINANCE API] Ордеров для закрытия не найдено");
        }

        double netProfitPercent = calculatorService.getNetResultPercent(trade.getEntryPrice(), exitPrice, trade.getAsset(), trade.getType());
        double profitUsdt = trade.getVolume() * (netProfitPercent / 100.0);

        updateBalanceFromExchange();

        trade.setExitTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm")));
        trade.setExitPrice(exitPrice);
        trade.setProfit(profitUsdt);
        trade.setStatus("CLOSED");
        tradeRepository.save(trade);

        coolDownMap.put(trade.getAsset(), LocalDateTime.now().plusMinutes(cooldownMinutes));

        telegramAPI.sendMessage(String.format(
                "🔔 Синхронизация\n " +
                        "Актив: %s \n" +
                        "Закрыт биржей: (%s)\n" +
                        "Итог: %.2f$ (%.2f%%)",
                trade.getAsset(), reason, profitUsdt, netProfitPercent));
    }

    /**
     * Досрочное закрытие всех активных позиции в ручном режиме
     */
    public void closeAllPositionsManually() {
        List<Trade> open = getActiveTrades();
        for (Trade t : open) {
            double price = binanceAPI.getCurrentPrice(t.getAsset());
            closePosition(t, price, "⚡ Manual Close All");
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
        tradeOpt.ifPresent(trade -> {
            double price = binanceAPI.getCurrentPrice(symbol);
            closePosition(trade, price, "⚡ Manual Close");
        });
    }


    /**
     * Синхронизация статусов.
     * Проверяет, остались ли монеты на балансе. Если нет — закрывает сделку в БД.
     */
    public void syncTradesWithExchange() {
        List<Trade> activeTrades = getActiveTrades();

        for (Trade trade : activeTrades) {
            String baseAsset = trade.getAsset().replace("USDT", "");
            double actualBalance = binanceAPI.getAssetBalance(baseAsset);

            double dustThreshold = trade.getQuantity() * 0.05;

            if (actualBalance < dustThreshold) {
                double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
                closePositionInDB(trade, currentPrice, "Exchange Auto Close");
                balanceHistoryRepository.save(new BalanceHistory(usdtBalance, LocalDateTime.now()));
            }
        }
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