package com.example.demo.services.trade;

import com.example.demo.data.BotSettings;
import com.example.demo.data.Trade;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class CalculatorService {

    /**
     * Сервис для расчёта различных финансовых метрик бота.
     *
     * @see #getRealizedProfit() - Общий реализованный профит (только закрытые сделки)
     * @see #getTodayRealizedProfit() - Реализованная прибыль за текущий день (только закрытые сделки сегодня)
     * @see #getUnrealizedPnLUsdt() - Суммарный нереализованный (грязный) PnL всех активных сделок в USDT
     * @see #getUnrealizedPnLUsdtWithFee() - Суммарный нереализованный PnL всех активных сделок в USDT с учётом предстоящей комиссии на продажу
     * @see #getTodayProfitUSDT() - Общая прибыль за текущий день в USDT (реализованная + нереализованная)
     * @see #getTodayProfitPercent() - Доходность за текущий день в процентах
     * @see #getAllProfitPercent() - Общая доходность с начала работы бота в процентах (учитывает реализованную и нереализованную прибыль)
     * @see #getActiveProfitPercent(Trade, double) - Нереализованная (грязная) доходность одной активной сделки в процентах
     * @see #getNetResultPercent(double, double, String, String) - Чистая доходность одной сделки в процентах с учётом комиссии Binance
     * @see #getTotalFeePercent(String) - Общая комиссия за полный цикл сделки (покупка + продажа) в процентах
     * @see #getTotalEquity() - Текущий общий эквити аккаунта (свободные USDT + рыночная стоимость открытых позиций)
     * @see #getOccupiedBalance() - Сумма USDT, вложенная в активные сделки (по текущей рыночной стоимости)
     */

    private final BinanceAPI binanceAPI;
    private final BotSettingsRepository botSettingsRepository;
    private final TradeRepository tradeRepository;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    @Autowired
    public CalculatorService(BinanceAPI binanceAPI, BotSettingsRepository botSettingsRepository,
                             TradeRepository tradeRepository) {
        this.binanceAPI = binanceAPI;
        this.botSettingsRepository = botSettingsRepository;
        this.tradeRepository = tradeRepository;
    }

    /**
     * Общий реализованный профит (только закрытые сделки)
     *
     * @return суммарная реализованная прибыль в USDT
     */
    public double getRealizedProfit() {
        return tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .mapToDouble(Trade::getProfit)
                .sum();
    }

    /**
     * Реализованная прибыль за текущий день (только закрытые сделки сегодня)
     *
     * @return реализованная прибыль за сегодня в USDT
     */
    public double getTodayRealizedProfit() {
        String today = LocalDate.now().format(DATE_FORMATTER);
        return tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .filter(t -> t.getExitTime() != null && t.getExitTime().startsWith(today))
                .mapToDouble(Trade::getProfit)
                .sum();
    }

    /**
     * Список активных сделок (внутренний метод)
     *
     * @return список активных сделок
     */
    private List<Trade> getActiveTrades() {
        return tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .toList();
    }

    /**
     * Чистая доходность одной сделки в процентах с учётом комиссии Binance.
     * <p>
     * Комиссия считается как сумма taker-комиссии за покупку и продажу (taker * 2).
     * Для SHORT-сделок изменение цены инвертируется.
     *
     * @param entryPrice цена входа в сделку
     * @param exitPrice  цена выхода (или текущая рыночная цена для активной сделки)
     * @param symbol     торговая пара (необходима для получения актуальной комиссии)
     * @param type       тип сделки ("LONG" или "SHORT")
     * @return чистая доходность в процентах
     */
    public double getNetResultPercent(double entryPrice, double exitPrice, String symbol, String type) {
        if (entryPrice <= 0 || exitPrice <= 0) return 0.0;

        double totalFeePercent = getTotalFeePercent(symbol);
        double priceDiffPercent = ((exitPrice - entryPrice) / entryPrice) * 100;
        if ("SHORT".equals(type)) priceDiffPercent *= -1;

        return priceDiffPercent - totalFeePercent;
    }

    /**
     * Общая прибыль за текущий день в USDT (реализованная + нереализованная с комиссией)
     *
     * @return прибыль за сегодня в USDT
     */
    public double getTodayProfitUSDT() {
        return getTodayRealizedProfit() + getUnrealizedPnLUsdtWithFee();
    }

    /**
     * Доходность за текущий день в процентах.
     * <p>
     * База расчёта — начальный баланс из настроек (BotSettings.balance).
     *
     * @return доходность за день в процентах
     */
    public double getTodayProfitPercent() {
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        double initial = settings.getBalance();

        if (initial <= 0) return 0.0;

        double todayProfit = getTodayProfitUSDT();
        return todayProfit / initial * 100.0;
    }

    /**
     * Общая доходность с начала работы бота в процентах.
     * <p>
     * Сравнивается текущий эквити (свободные USDT + рыночная стоимость открытых позиций)
     * с начальным балансом из настроек (BotSettings.balance).
     *
     * @return общая доходность в процентах
     */
    public double getAllProfitPercent() {
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        double initial = settings.getBalance();

        if (initial <= 0) return 0.0;

        double totalEquity = getTotalEquity();
        return (totalEquity - initial) / initial * 100.0;
    }

    /**
     * Нереализованная (грязная) доходность одной активной сделки в процентах.
     * <p>
     * Комиссия не вычитается.
     *
     * @param trade        объект активной сделки
     * @param currentPrice текущая рыночная цена актива
     * @return нереализованная доходность в процентах
     */
    public double getActiveProfitPercent(Trade trade, double currentPrice) {
        if (trade.getEntryPrice() <= 0) return 0.0;
        double diff = ((currentPrice - trade.getEntryPrice()) / trade.getEntryPrice()) * 100;
        if ("SHORT".equals(trade.getType())) diff *= -1;
        return diff;
    }

    /**
     * Суммарный нереализованный (грязный) PnL всех активных сделок в USDT.
     * <p>
     * Комиссия не учитывается.
     *
     * @return суммарный нереализованный PnL в USDT
     */
    public double getUnrealizedPnLUsdt() {
        return getActiveTrades().stream()
                .mapToDouble(t -> {
                    double currentPrice = binanceAPI.getCurrentPrice(t.getAsset());
                    if (currentPrice <= 0) return 0.0;
                    double percent = getResultPercent(t.getEntryPrice(), currentPrice, t.getAsset(), t.getType());
                    return t.getVolume() * (percent / 100.0);
                })
                .sum();
    }

    /**
     * Суммарный нереализованный PnL всех активных сделок в USDT с учётом предстоящей комиссии на продажу.
     *
     * @return суммарный чистый нереализованный PnL в USDT
     */
    public double getUnrealizedPnLUsdtWithFee() {
        return getActiveTrades().stream()
                .mapToDouble(t -> {
                    double currentPrice = binanceAPI.getCurrentPrice(t.getAsset());
                    if (currentPrice <= 0) return 0.0;
                    double[] fees = binanceAPI.getTradeFee(t.getAsset());
                    double takerFee = fees[1]; // taker для выхода
                    double percent = getResultPercent(t.getEntryPrice(), currentPrice, t.getAsset(), t.getType());
                    double gross = t.getVolume() * (percent / 100.0);
                    double feeCost = t.getVolume() * takerFee;
                    return gross - feeCost;
                })
                .sum();
    }

    /**
     * Общая комиссия за полный цикл сделки (покупка + продажа) в процентах.
     *
     * @param symbol торговая пара
     * @return комиссия в процентах (taker * 2)
     */
    public double getTotalFeePercent(String symbol) {
        double[] fees = binanceAPI.getTradeFee(symbol);
        return (fees[1] * 2) * 100;
    }

    /**
     * Текущий общий эквити аккаунта.
     * <p>
     * Эквити = свободные USDT + рыночная стоимость всех открытых позиций.
     *
     * @return текущий эквити в USDT
     */
    public double getTotalEquity() {
        double freeUsdt = binanceAPI.getAccountBalance();
        double openPositionsValue = getActiveTrades().stream()
                .mapToDouble(t -> {
                    double currentPrice = binanceAPI.getCurrentPrice(t.getAsset());
                    if (currentPrice <= 0) return 0.0;
                    return t.getQuantity() * currentPrice;
                })
                .sum();
        return freeUsdt + openPositionsValue;
    }

    /**
     * Сумма USDT, вложенная в активные сделки (по текущей рыночной стоимости).
     *
     * @return сумма в USDT, занятая в открытых позициях
     */
    public double getOccupiedBalance() {
        return getActiveTrades().stream()
                .mapToDouble(t -> {
                    double currentPrice = binanceAPI.getCurrentPrice(t.getAsset());
                    if (currentPrice <= 0) return t.getVolume(); // fallback на volume при входе
                    return t.getQuantity() * currentPrice;
                })
                .sum();
    }

    /**
     * Внутренний метод: расчёт доходности в процентах без комиссии
     */
    private double getResultPercent(double entry, double exit, String symbol, String type) {
        if (entry <= 0 || exit <= 0) return 0.0;
        if ("LONG".equals(type)) {
            return (exit - entry) / entry * 100.0;
        } else {
            return (entry - exit) / entry * 100.0;
        }
    }
}