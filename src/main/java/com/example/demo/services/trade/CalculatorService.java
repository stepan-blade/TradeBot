package com.example.demo.services.trade;

import com.example.demo.data.BotSettings;
import com.example.demo.data.Trade;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class CalculatorService {

    /**
     * @see #getTodayProfitUSDT() - Расчёт реализованной прибыли за текущий день в USDT.
     * @see #getAllProfitPercent() - Расчёт общей доходности с начала работы бота в процентах.
     *
     * @see #getUnrealizedPnLUsdt() - Расчёт суммарного нереализованного (грязного) PnL всех активных сделок в USDT.
     * @see #getUnrealizedPnLUsdtWithFee() - Расчёт суммарного нереализованного PnL всех активных сделок в USDT с учётом предстоящей комиссии на продажу.
     *
     * @see #getActiveProfitPercent(Trade, double) - Расчёт нереализованной (грязной) доходности одной активной сделки в процентах.
     * @see #getNetResultPercent(double, double, String, String) - Расчёт чистой доходности одной сделки в процентах с учётом комиссии Binance.
     *
     * @see #getTotalEquity() - Расчёт текущего общего эквити аккаунта.
     * @see #getTotalFeePercent(String) - Расчёт общей комиссии за полный цикл сделки (покупка + продажа) в процентах.
     * @see #getOccupiedBalance() - Расчёт суммы USDT, вложенной в активные сделки (по объёму на момент входа).
     *
     * @see #getActiveTrades() - Список активных сделок
     */

    private final BinanceAPI binanceAPI;
    private final BotSettingsRepository botSettingsRepository;
    private final TradeRepository tradeRepository;

    @Autowired
    public CalculatorService(BinanceAPI binanceAPI, BotSettingsRepository botSettingsRepository,
                             TradeRepository tradeRepository) {
        this.binanceAPI = binanceAPI;

        this.botSettingsRepository = botSettingsRepository;
        this.tradeRepository = tradeRepository;
    }

    /**
     * Список активных сделок
     * @return Список активных сделок
     */
    private List<Trade> getActiveTrades() {
        return tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .collect(Collectors.toList());
    }

    /**
     * Расчёт чистой доходности одной сделки в процентах с учётом комиссии Binance.
     * <p>
     * Комиссия считается как сумма taker-комиссии за покупку и продажу (fees[1] * 2).
     * Для SHORT-сделок изменение цены инвертируется.
     *
     * @param entryPrice цена входа в сделку
     * @param exitPrice  цена выхода (или текущая рыночная цена для активной сделки)
     * @param symbol     торговая пара (необходима для получения актуальной комиссии)
     * @param type       тип сделки ("LONG" или "SHORT")
     * @return чистая доходность в процентах (с вычетом комиссии)
     */
    public double getNetResultPercent(double entryPrice, double exitPrice, String symbol, String type) {
        if (entryPrice <= 0 || exitPrice <= 0) return 0.0;

        double totalFeePercent = getTotalFeePercent(symbol);
        double priceDiffPercent = ((exitPrice - entryPrice) / entryPrice) * 100;
        if ("SHORT".equals(type)) priceDiffPercent *= -1;

        return priceDiffPercent - totalFeePercent;
    }

    /**
     * Расчёт реализованной прибыли за текущий день в USDT.
     * <p>
     * Учитываются только закрытые сделки (status = "CLOSED"), завершённые сегодня.
     * Прибыль в каждой сделке уже сохранена с учётом комиссии (через getNetResultPercent).
     *
     * @return сумма реализованной прибыли за день в USDT
     */
    public double getTodayProfitUSDT() {
        String todayPrefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
        return tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .filter(t -> t.getExitTime() != null && t.getExitTime().startsWith(todayPrefix))
                .mapToDouble(Trade::getProfit)
                .sum();
    }

    /**
     * Расчёт реализованной доходности за текущий день в процентах.
     * <p>
     * База расчёта — общий эквити на начало дня (текущий эквити минус сегодняшняя реализованная прибыль).
     *
     * @return доходность за день в процентах (округлено до 2 знаков)
     */
    public double getTodayProfitPercent() {
        double todayProfitUSDT = getTodayProfitUSDT();
        double currentEquity = getTotalEquity();
        double startEquityToday = currentEquity - todayProfitUSDT;

        if (startEquityToday <= 0) return 0.0;
        return Math.round((todayProfitUSDT / startEquityToday) * 10000.0) / 100.0;
    }

    /**
     * Расчёт общей доходности с начала работы бота в процентах.
     * <p>
     * Сравнивается текущий эквити (свободные USDT + рыночная стоимость открытых позиций)
     * с начальным балансом из настроек (BotSettings.balance).
     * Включает нереализованную (грязную) прибыль активных сделок.
     *
     * @return общая доходность в процентах (округлено до 2 знаков)
     */
    public double getAllProfitPercent() {
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(null);
        if (settings == null || settings.getBalance() <= 0) return 0.0;

        double initial = settings.getBalance();
        double currentEquity = getTotalEquity();
        double diff = currentEquity - initial;
        return Math.round((diff / initial) * 10000.0) / 100.0;
    }

    /**
     * Расчёт нереализованной (грязной) доходности одной активной сделки в процентах.
     * <p>
     * Комиссия не вычитается — это "текущий" Рост/падение без учёта затрат на закрытие.
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
     * Расчёт суммарного нереализованного (грязного) PnL всех активных сделок в USDT.
     * <p>
     * Комиссия не учитывается.
     *
     * @return суммарный нереализованный PnL в USDT
     */
    public double getUnrealizedPnLUsdt() {
        return getActiveTrades().stream()
                .mapToDouble(trade -> {
                    double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
                    double pnlPercent = getActiveProfitPercent(trade, currentPrice);
                    return trade.getVolume() * (pnlPercent / 100.0);
                })
                .sum();
    }

    /**
     * Расчёт суммарного нереализованного PnL всех активных сделок в USDT с учётом предстоящей комиссии на продажу.
     * <p>
     * Используется для более реалистичной оценки потенциальной прибыли при закрытии.
     *
     * @return суммарный чистый нереализованный PnL в USDT
     */
    public double getUnrealizedPnLUsdtWithFee() {
        return getActiveTrades().stream()
                .mapToDouble(trade -> {
                    double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
                    double netPercent = getNetResultPercent(trade.getEntryPrice(), currentPrice, trade.getAsset(), trade.getType());
                    return trade.getVolume() * (netPercent / 100.0);
                })
                .sum();
    }

    /**
     * Расчёт общей комиссии за полный цикл сделки (покупка + продажа) в процентах.
     *
     * @param symbol торговая пара
     * @return комиссия в процентах (taker * 2)
     */
    public double getTotalFeePercent(String symbol) {
        double[] fees = binanceAPI.getTradeFee(symbol);
        return (fees[1] * 2) * 100;
    }

    /**
     * Расчёт текущего общего эквити аккаунта.
     * <p>
     * Эквити = свободные USDT + рыночная стоимость всех открытых позиций.
     *
     * @return текущий эквити в USDT
     */
    public double getTotalEquity() {
        double freeUsdt = binanceAPI.getAccountBalance();
        double locked = getActiveTrades().stream()
                .mapToDouble(trade -> trade.getQuantity() * binanceAPI.getCurrentPrice(trade.getAsset()))
                .sum();
        return freeUsdt + locked;
    }

    /**
     * Расчёт суммы USDT, вложенной в активные сделки (по объёму на момент входа).
     *
     * @return сумма в USDT, занятая в открытых позициях
     */
    public double getOccupiedBalance() {
        return getActiveTrades().stream()
                .mapToDouble(Trade::getVolume)
                .sum();
    }

}
