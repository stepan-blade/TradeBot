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
import org.springframework.transaction.annotation.Transactional;

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

    /**
     * Текущий остаток USDT на счете Binance
     * @return Остаток USDT на счете Binance
     */
    public double getBalance() {
        updateBalanceFromExchange();
        return usdtBalance;
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
     * Открытие позиции (LONG или SHORT)
     * @param symbol  Валютная пара
     * @param price   Текущая цена
     * @param percent Процент от баланса
     * @param type    "LONG" или "SHORT"
     */
    @Transactional
    public void openPosition(String symbol, double price, double percent, String type) {
        double availableUsdt = binanceAPI.getAccountBalance();
        this.usdtBalance = availableUsdt;

        double buyUsdt = Math.min(availableUsdt * (percent / 100.0), availableUsdt);

        if (buyUsdt > availableUsdt) {
            logger.warn("Недостаточно средств: \n Сумма закупа: {} \n Доступные средства: {}", buyUsdt, availableUsdt);
            return;
        }

        if (buyUsdt < 10.0) {
            logger.warn("Пропуск. Доступные средства на счете {} USDT. Минимум 10.0 USDT", availableUsdt);
            return;
        }

        try {
            binanceAPI.cancelAllOrders(symbol);
        } catch (Exception e) {
            logger.debug("Ордеров для закрытия не найдено: {}", e.getMessage());
        }

        Map<String, Double> orderResult = null;
        double actualQuantity = 0.0;
        double actualBuyUsdt = 0.0;
        Trade trade = null;
        try {
            if ("LONG".equals(type)) {
                orderResult = binanceAPI.placeMarketBuy(symbol, buyUsdt);
            } else {
                double rawQuantity = buyUsdt / price;
                double stepSize = binanceAPI.getStepSize(symbol);
                double quantity = FormatUtil.roundToStep(rawQuantity, stepSize);
                orderResult = binanceAPI.placeMarketSell(symbol, quantity);
            }

            if (orderResult == null || orderResult.get("quantity") == 0) {
                telegramAPI.sendMessage("❌ Не удалось открыть " + type + " " + symbol + " (ордер не исполнен или частично)");
                return;
            }

            actualQuantity = orderResult.get("quantity");
            double actualQuoteQty = orderResult.get("quoteQty");

            actualBuyUsdt = "LONG".equals(type) ? actualQuoteQty : buyUsdt;

            // Не округляем actualQuantity повторно, используем executedQty как есть
            // double stepSize = binanceAPI.getStepSize(symbol);
            // actualQuantity = FormatUtil.roundToStep(actualQuantity, stepSize);

            // Сохраняем сделку сразу после успешной покупки
            String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm"));
            trade = new Trade(
                    symbol,
                    startTime,
                    price,
                    type,
                    actualBuyUsdt,
                    actualQuantity,
                    price * ("LONG".equals(type) ? 0.98 : 1.02)
            );
            tradeRepository.save(trade);

            updateBalanceFromExchange();

            telegramAPI.sendMessage(String.format("""
                            🚀 ОТКРЫТА %s ПОЗИЦИЯ
                            Актив: %s
                            Сумма: %.2f USDT
                            Количество: %.6f (фактическое)
                            Остаток USDT: %.2f""",
                    type, FormatUtil.formatSymbol(symbol), actualBuyUsdt, actualQuantity, usdtBalance));

        } catch (Exception e) {
            telegramAPI.sendMessage("❌ Ошибка открытия " + type + " " + symbol + ": " + e.getMessage());
            return;
        }

        // Небольшая задержка для обновления баланса на бирже
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }

        // Ставим stop-loss
        boolean slPlaced = false;
        int retries = 0;
        String slError = "";
        while (!slPlaced && retries < 3) {
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

                double tickSize = binanceAPI.getTickSize(symbol);

                if ("LONG".equals(type)) {
                    stopPrice = Math.floor(stopPrice / tickSize) * tickSize;
                    limitPrice = Math.floor(limitPrice / tickSize) * tickSize;
                } else {
                    stopPrice = Math.ceil(stopPrice / tickSize) * tickSize;
                    limitPrice = Math.ceil(limitPrice / tickSize) * tickSize;
                }

                String slOrderId = binanceAPI.placeStopLossLimit(symbol, actualQuantity, stopPrice, limitPrice, slSide);
                if (slOrderId != null) {
                    slPlaced = true;
                }
            } catch (Exception e) {
                slError = e.getMessage();
                logger.error("Ошибка установки SL (попытка {}): {}", retries + 1, slError);
                retries++;
                try {
                    Thread.sleep(2000 * retries);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        if (!slPlaced) {
            telegramAPI.sendMessage("⚠️ Не удалось поставить SL для " + type + " " + symbol + ": " + slError);
            // Откат
            Map<String, Double> rollbackResult = null;
            try {
                if ("LONG".equals(type)) {
                    rollbackResult = binanceAPI.placeMarketSell(symbol, actualQuantity);
                } else {
                    rollbackResult = binanceAPI.placeMarketBuy(symbol, actualQuantity);
                }
            } catch (Exception rollbackEx) {
                telegramAPI.sendMessage("🆘 SOS! Не удалось откатить позицию " + symbol + ": " + rollbackEx.getMessage());
            }

            if (rollbackResult != null && rollbackResult.get("quantity") > 0) {
                telegramAPI.sendMessage("🆘 SOS! Не удалось установить SL — позиция закрыта принудительно");
                double rollbackPrice = rollbackResult.get("quoteQty") / rollbackResult.get("quantity");
                double netProfitPercent = calculatorService.getNetResultPercent(price, rollbackPrice, symbol, type);
                double profitUsdt = actualBuyUsdt * (netProfitPercent / 100.0);

                if (trade != null) {
                    trade.setExitTime(LocalDateTime.now().plusSeconds(1).format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm")));
                    trade.setExitPrice(rollbackPrice);
                    trade.setProfit(profitUsdt);
                    trade.setStatus("CLOSED");
                    tradeRepository.save(trade);
                }

                telegramAPI.sendMessage(String.format("🚫 Откат позиции %s: Итог %.2f USDT (%.2f%%)", symbol, profitUsdt, netProfitPercent));
            } else {
                telegramAPI.sendMessage("🆘 SOS! Критическая ошибка — позиция " + symbol + " открыта без SL!");
            }
            return;
        }
    }

    /**
     * Продажа актива
     * @param trade Активная сделка
     * @param currentPrice Текущая цена
     * @param reason Причина продажи
     */
    public void closePosition(Trade trade, double currentPrice, String reason) {
        String baseAsset = trade.getAsset().replace("USDT", "");
        double actualBalance = binanceAPI.getAssetBalance(baseAsset);

        double dustThreshold = trade.getQuantity() * 0.05;

        if (actualBalance < dustThreshold) {
            // Already closed, sync DB
            closePositionInDB(trade, currentPrice, reason + " (Already closed on exchange)");
            return;
        }

        try {
            binanceAPI.cancelAllOrders(trade.getAsset());
        } catch (Exception e) {
            System.out.println(TimeUtil.getTime() + " --- [BINANCE API] Ордеров для закрытия не найдено");
        }

        double quantity = trade.getQuantity();

        Map<String, Double> orderResult = null;
        try {
            if ("LONG".equals(trade.getType())) {
                orderResult = binanceAPI.placeMarketSell(trade.getAsset(), quantity);
            } else {
                orderResult = binanceAPI.placeMarketBuy(trade.getAsset(), quantity);
            }
        } catch (Exception e) {
            telegramAPI.sendMessage("❌ Ошибка закрытия " + trade.getType() + " " + trade.getAsset() + ": " + e.getMessage());
            return;
        }

        if (orderResult == null || orderResult.get("quantity") <= 0) {
            telegramAPI.sendMessage("❌ Не удалось закрыть позицию " + trade.getAsset() + ". Возможно, позиция была закрыта вручную");
            actualBalance = binanceAPI.getAssetBalance(baseAsset);
            if (actualBalance < dustThreshold) {
                closePositionInDB(trade, currentPrice, reason + " (Closed manually)");
            }
            return;
        }

        // Актуальный расчёт прибыли (используем сохранённый volume из trade)
        double netProfitPercent = calculatorService.getNetResultPercent(trade.getEntryPrice(), currentPrice, trade.getAsset(), trade.getType());
        double profitUsdt = trade.getVolume() * (netProfitPercent / 100.0);

        updateBalanceFromExchange();

        balanceHistoryRepository.save(new BalanceHistory(usdtBalance, LocalDateTime.now()));

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
                double currentPrice = 0.0;
                try {
                    currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
                } catch (Exception e) {
                    telegramAPI.sendMessage("🆘 Ошибка: Не удалось получить цену закрытия для " + trade.getAsset() + ". Установлен статус ERROR.");
                    trade.setStatus("ERROR");
                    trade.setProfit(0.0);
                    trade.setExitPrice(0.0);
                    trade.setExitTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm")));
                    tradeRepository.save(trade);
                    continue;
                }
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

    /**
     * Adjusts the initial balance in BotSettings to account for deposits or withdrawals,
     * ensuring that profit calculations reflect only trading performance, not balance changes.
     *
     * Logic:
     * 1. Calculate total realized profit from all closed trades.
     * 2. Calculate unrealized PnL from active trades (with fees).
     * 3. Compute expected equity = initial balance + realized profit + unrealized PnL.
     * 4. Compare with current actual equity.
     *    - If actual > expected: Deposit detected, increase initial by difference.
     *    - If actual < expected: Withdrawal detected, decrease initial by difference (to keep profit accurate).
     * 5. Save updated initial balance to BotSettings.
     *
     * Call this method periodically (e.g., in syncMarketStatus task) or before profit calculations.
     *
     * @return The adjusted initial balance.
     */
    public double adjustForDeposits() {
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        double initial = settings.getBalance();

        // Realized profit from all closed trades
        double realizedProfit = tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .mapToDouble(Trade::getProfit)
                .sum();

        // Unrealized PnL (with fees) from active trades
        double unrealizedPnL = calculatorService.getUnrealizedPnLUsdtWithFee();

        // Expected equity based on trading only
        double expectedEquity = initial + realizedProfit + unrealizedPnL;

        // Current actual equity
        double currentEquity = calculatorService.getTotalEquity();

        // Difference: positive = deposit, negative = withdrawal
        double difference = currentEquity - expectedEquity;

        if (Math.abs(difference) > 1.0) { // Threshold to ignore minor discrepancies (e.g., fees, rounding)
            // Adjust initial balance
            double newInitial = initial + difference;
            settings.setBalance(newInitial);
            botSettingsRepository.save(settings);
            return newInitial;
        }

        return initial;
    }
}