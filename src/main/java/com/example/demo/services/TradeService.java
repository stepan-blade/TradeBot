package com.example.demo.services;

import com.example.demo.DemoTradingBot;
import com.example.demo.data.BalanceHistory;
import com.example.demo.data.BotSettings;
import com.example.demo.data.Trade;
import com.example.demo.intarfaces.BalanceHistoryRepository;
import com.example.demo.intarfaces.BotSettingsRepository;
import com.example.demo.intarfaces.TradeRepository;
import com.example.demo.services.app.BinanceAPI;
import com.example.demo.services.app.TelegramAPI;
import com.example.demo.utils.Formatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TradeService {

    @Autowired
    private BinanceAPI priceService;
    @Autowired private BotSettingsRepository settingsRepository;
    @Autowired
    private TelegramAPI telegramAPI;
    @Autowired
    private BalanceHistoryRepository balanceHistoryRepository;
    @Autowired private TradeRepository tradeRepository;
    @Autowired
    DemoTradingBot demoTradingBot;

    private final java.util.Map<String, LocalDateTime> coolDownMap = new java.util.HashMap<>();
    private final double initialBalance = 1000.0;
    private double balance;

    @PostConstruct
    public void init() {
        this.balance = demoTradingBot.getBalance();
    }

    /**
     * Проверка условий для открытия сделки
     */
    public void checkEntryConditions(String symbol, double currentPrice, BotSettings settings, int currentOpenCount) {
        double volume = priceService.get24hVolume(symbol);
        if (volume < 5000000) return;

        double rsi = priceService.calculateRealRSI(symbol);
        double sma200 = priceService.calculateSMA(symbol, 200);
        double[] bb = priceService.calculateBollingerBands(symbol, 20, 2.0);

        if (rsi <= 0 || sma200 <= 0 || bb == null) return;

        // УСЛОВИЕ LONG: Тренд выше SMA200 + Перепроданность (RSI < 35) + Касание нижней границы BB
        if (currentPrice > sma200 && rsi < 35 && currentPrice <= bb[2]) { // bb[2] - нижняя граница
            openPosition(symbol, "LONG", currentPrice, (this.balance - calculateOccupiedVolume()), settings.getTradePercent());
        }
        // УСЛОВИЕ SHORT: Тренд ниже SMA200 + Перекупленность (RSI > 65) + Касание верхней границы BB
        else if (currentPrice < sma200 && rsi > 65 && currentPrice >= bb[0]) { // bb[0] - верхняя граница
            openPosition(symbol, "SHORT", currentPrice, (this.balance - calculateOccupiedVolume()), settings.getTradePercent());
        }
    }

    /**
     * Открытие позиции
     */
    public void openPosition(String symbol, String type, double price, double freeBalance, double settingsPercent) {
        double desiredVolume = this.balance * (settingsPercent / 100.0);
        double tradeVolume = Math.min(desiredVolume, freeBalance);
        tradeVolume = Math.round(tradeVolume * 100.0) / 100.0;

        if (tradeVolume < 1.0) {
            return;
        }
        tradeVolume = Math.round(tradeVolume * 100.0) / 100.0;

        String startTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm"));
        Trade trade = new Trade(startTime, symbol, type, price, tradeVolume);

        double sl = (type.equals("LONG")) ? price * 0.98 : price * 1.02;
        trade.setStopLoss(sl);
        trade.setBestPrice(price);
        tradeRepository.save(trade);

        telegramAPI.sendMessage("🚀 OPEN " + type + "\n" +
                "Актив: " + Formatter.formatSymbol(symbol) + "\n" +
                "Сумма закупа: " + tradeVolume + " USDT\n" +
                "Остаток: " + Math.round((freeBalance - tradeVolume) * 100.0) / 100.0 + " USDT");
    }

    /**
     * Закрытие позиции
     */
    public void closePosition(Trade trade, double currentPrice, String reason) {
        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings(balance));

        double diff = ((currentPrice - trade.getEntryPrice()) / trade.getEntryPrice()) * 100;
        if ("SHORT".equals(trade.getType())) diff *= -1;
        double netProfitPercent = diff - 0.2;
        double profitUsdt = Math.round(trade.getVolume() * (netProfitPercent / 100) * 100.0) / 100.0;

        this.balance = Math.round((this.balance + profitUsdt) * 100.0) / 100.0;
        settings.setBalance(this.balance);
        settingsRepository.save(settings);

        balanceHistoryRepository.save(new BalanceHistory(this.balance, LocalDateTime.now()));

        String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy | HH:mm"));
        trade.setExitTime(now);
        trade.setExitPrice(currentPrice);
        trade.setProfit(profitUsdt);
        trade.setStatus("CLOSED");
        tradeRepository.save(trade);

        // ФОРМИРУЕМ РАСШИРЕННОЕ СООБЩЕНИЕ
        String typeIcon = "LONG".equals(trade.getType()) ? "📈" : "📉";

        String message = String.format(
                "%s\n" +
                        "Актив: %s (%s %s)\n" +
                        "Объем: %.2f USDT\n" +
                        "Вход: %.8f\n" +
                        "Выход: %.8f\n" +
                        "Итог: %s %.2f USDT (%.2f%%)",
                reason,
                Formatter.formatSymbol(trade.getAsset()), typeIcon, trade.getType(),
                trade.getVolume(),
                trade.getEntryPrice(),
                currentPrice,
                (profitUsdt >= 0 ? "+" : ""), profitUsdt, netProfitPercent
        );

        telegramAPI.sendMessage(message);
    }

    /**
     * Закрытие позиции в ручном режиме
     */
    public void closeSpecificTradeManually(String symbol) {
        Optional<Trade> tradeOpt = getActiveTrades().stream()
                .filter(t -> t.getAsset().equals(symbol))
                .findFirst();
        tradeOpt.ifPresent(trade -> closePosition(trade, priceService.getCurrentPrice(symbol), "⚡ Manual Close via Telegram"));
    }

    /**
     * Закрытие позиции по RSI или при достижении процента по сделке
     */
    public void handleOpenTradeLogic(Trade trade, double currentPrice) {
        double rsi = priceService.calculateRealRSI(trade.getAsset());
        double netProfit = calculateNetProfitPercent(trade, currentPrice);

        if (trade.getType().equals("LONG") && rsi > 75) {
            closePosition(trade, currentPrice, "💰 RSI Overbought Exit");
            return;
        }
        if (trade.getType().equals("SHORT") && rsi < 25) {
            closePosition(trade, currentPrice, "💰 RSI Oversold Exit");
            return;
        }

        if (netProfit >= 2.5) {
            closePosition(trade, currentPrice, "🚀 Hard Take Profit 2.5%");
            return;
        }

        handleTrailingStop(trade, currentPrice, netProfit);
    }

    /**
     * Закрытие позиции по динамическому Stop-Loss
     */
    public void handleTrailingStop(Trade trade, double currentPrice, double netProfit) {
        double best = trade.getBestPrice();
        boolean updated = false;

        // 1. Обновляем лучшую цену
        if (trade.getType().equals("LONG") && currentPrice > best) {
            trade.setBestPrice(currentPrice);
            updated = true;
        } else if (trade.getType().equals("SHORT") && (currentPrice < best || best == 0)) {
            trade.setBestPrice(currentPrice);
            updated = true;
        }

        // 2. Новая ступенчатая защита (Защищаем прибыль, но даем расти)
        if (netProfit >= 0.8 && netProfit < 2.0) {
            // ШАГ 1: Прибыль достигла +0.8% -> Ставим стоп на +0.3% (чистая прибыль после комиссии)
            double safeStop = (trade.getType().equals("LONG"))
                    ? trade.getEntryPrice() * 1.005 // +0.5% от входа (0.3% чистыми)
                    : trade.getEntryPrice() * 0.995;

            if (trade.getType().equals("LONG") && trade.getStopLoss() < safeStop) {
                trade.setStopLoss(safeStop);
                updated = true;
            } else if (trade.getType().equals("SHORT") && trade.getStopLoss() > safeStop) {
                trade.setStopLoss(safeStop);
                updated = true;
            }
        } else if (netProfit >= 2.0) {
            // ШАГ 2: Прибыль > 2% -> Трейлинг более свободный (1.5% от пика)
            // Чтобы не закрывать сделку на первой же коррекции
            double activeTrailing = (trade.getType().equals("LONG"))
                    ? trade.getBestPrice() * 0.985 // Отступ 1.5% от лучшей цены
                    : trade.getBestPrice() * 1.015;

            if (trade.getType().equals("LONG") && trade.getStopLoss() < activeTrailing) {
                trade.setStopLoss(activeTrailing);
                updated = true;
            } else if (trade.getType().equals("SHORT") && trade.getStopLoss() > activeTrailing) {
                trade.setStopLoss(activeTrailing);
                updated = true;
            }
        }

        if (updated) tradeRepository.save(trade);

        // 3. Проверка выхода по стопу
        boolean longStop = trade.getType().equals("LONG") && currentPrice <= trade.getStopLoss();
        boolean shortStop = trade.getType().equals("SHORT") && currentPrice >= trade.getStopLoss();

        if (longStop || shortStop) {
            closePosition(trade, currentPrice, "🛡️ Trailing Stop (Secured)");
        }
    }

    /**
     * Закрытие всех активных сделок
     */
    public void closeAllPositionsManually() {
        List<Trade> open = tradeRepository.findAll().stream().filter(t -> "OPEN".equals(t.getStatus())).collect(Collectors.toList());
        for (Trade t : open) closePosition(t, priceService.getCurrentPrice(t.getAsset()), "⚡ Manual Close");
    }

    /**
     * Получает текущую цену через внутренний сервис цен
     */
    public double getCurrentPrice(String symbol) {
        return priceService.getCurrentPrice(symbol);
    }

    /**
     * Возвращает список только активных сделок из БД
     */
    public List<Trade> getActiveTrades() {
        return tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Рассчитывает чистый профит в процентах
     */
    public double calculateProfitPercent() {
        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS").orElse(null);
        if (settings == null) return 0.0;
        double currentBalance = settings.getBalance();
        double diff = currentBalance - initialBalance;
        double percent = (diff / initialBalance) * 100.0;

        return Math.round(percent * 100.0) / 100.0;
    }

    /**
     * Рассчитывает чистый профит в процентах с учетом комиссии
     */
    public double calculateNetProfitPercent(Trade trade, double currentPrice) {
        double diff = ((currentPrice - trade.getEntryPrice()) / trade.getEntryPrice()) * 100;
        if ("SHORT".equals(trade.getType())) diff *= -1;
        return diff - 0.2;
    }

    /**
     * Рассчитывает профит в USDT
     */
    public double getTodayProfitUsdt() {
        String todayPrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        return tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .filter(t -> t.getExitTime() != null && t.getExitTime().startsWith(todayPrefix))
                .mapToDouble(Trade::getProfit)
                .sum();
    }

    /**
     * Расчет суммы USDT, которая сейчас "заморожена" в открытых сделках
     */
    public double calculateOccupiedVolume() {
        List<Trade> allTrades = tradeRepository.findAll();
        return allTrades.stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .mapToDouble(Trade::getVolume)
                .sum();
    }

    /**
     * Проверка, находится ли монета в режиме "отдыха" после сделки
     */
    public boolean isCoolDown(String symbol) {
        if (coolDownMap.containsKey(symbol)) {
            if (LocalDateTime.now().isBefore(coolDownMap.get(symbol))) {
                return true;
            } else {
                coolDownMap.remove(symbol);
            }
        }
        return false;
    }

    public Map<String, LocalDateTime> getCoolDownMap() {
        return this.coolDownMap;
    }
}
