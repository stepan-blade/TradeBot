package com.example.demo.services.telegram;

import com.example.demo.data.BotSettings;
import com.example.demo.data.Trade;
import com.example.demo.interfaces.BotCommandsRepository;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.api.TelegramAPI;
import com.example.demo.services.trade.CalculatorService;
import com.example.demo.services.trade.TradeService;
import com.example.demo.utils.FormatUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BotCurrentStatus {

    private final TelegramAPI telegramAPI;
    private final BinanceAPI binanceAPI;
    private final TradeService tradeService;
    private final CalculatorService calculatorService;
    private final TradeRepository tradeRepository;
    private final BotSettingsRepository botSettingsRepository;

    @Autowired
    public BotCurrentStatus(TelegramAPI telegramAPI, BinanceAPI binanceAPI, TradeService tradeService, TradeRepository tradeRepository, CalculatorService calculatorService, BotSettingsRepository botSettingsRepository) {
        this.telegramAPI = telegramAPI;
        this.binanceAPI = binanceAPI;
        this.tradeService = tradeService;
        this.tradeRepository = tradeRepository;
        this.calculatorService = calculatorService;
        this.botSettingsRepository = botSettingsRepository;
    }

    public void sendStatus() {
        List<Trade> openTrades = tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .collect(Collectors.toList());

        BotSettings botSettings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());

        double unrealizedUsdt = calculatorService.getUnrealizedPnLUsdt();
        double todayUsdt = calculatorService.getTodayProfitUSDT();
        double allUsdt = calculatorService.getRealizedProfit() + unrealizedUsdt;

        double unrealizedPercent = calculatorService.getOccupiedBalance() > 0 ? (unrealizedUsdt / calculatorService.getOccupiedBalance()) * 100 : 0.0;
        double todayPercent = calculatorService.getTodayProfitPercent();
        double allPercent = calculatorService.getAllProfitPercent();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 ТЕКУЩИЙ СТАТУС: " + botSettings.getStatus() + "\n");
        sb.append("💰 Баланс: ").append(String.format("%.6f", tradeService.getBalance())).append(" USDT\n");
        sb.append("🔄 В обороте: ").append(String.format("%.2f", calculatorService.getOccupiedBalance())).append(" USDT\n");
        sb.append("📈 Общий PnL: ").append(String.format("%.2f", allUsdt)).append(" USDT (").append(String.format("%.2f", allPercent)).append("%)\n");
        sb.append("📊 Нереализ. PnL: ").append(String.format("%.2f", unrealizedUsdt)).append(" USDT (").append(String.format("%.2f", unrealizedPercent)).append("%)\n");
        sb.append("📊 Дневной PnL: ").append(String.format("%.2f", todayUsdt)).append(" USDT (").append(String.format("%.2f", todayPercent)).append("%)\n");

        if (openTrades.isEmpty()) {
            sb.append("🔎 Открытых сделок нет");
        } else {
            sb.append("🚀 ОТКРЫТЫЕ ПОЗИЦИИ:\n\n");
            for (Trade trade : openTrades) {
                double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
                double pnlPercent = calculatorService.getActiveProfitPercent(trade, currentPrice); // грязный
                double pnlUsdt = trade.getVolume() * (pnlPercent / 100.0);

                String pnlIcon = pnlPercent >= 0 ? "🟢" : "🔴";
                sb.append("🔸 ").append(FormatUtil.formatSymbol(trade.getAsset())).append(" | ").append(trade.getType()).append("\n");
                sb.append("   📥 Вход: ").append(String.format("%.8f", trade.getEntryPrice())).append("\n");
                sb.append("   🕒 Цена: ").append(String.format("%.8f", currentPrice)).append("\n");
                sb.append("   🛡️ SL: ").append(String.format("%.8f", trade.getStopLoss())).append("\n");
                sb.append("   ").append(pnlIcon).append(" PnL: ").append(pnlUsdt >= 0 ? "+" : "").append(String.format("%.2f", pnlUsdt)).append(" USDT (").append(String.format("%.2f", pnlPercent)).append("%)\n\n");
            }
        }
        telegramAPI.sendMessage(sb.toString());
    }

    /**
     * Отправляет запрос на подтверждение смены статуса.
     */
    public void sendResponseForChangeStatus() {
        BotSettings botSettings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
        String botStatus = botSettings.getStatus();

        if ("ONLINE".equals(botStatus)){
            String text = """
                ⚠️ ВНИМАНИЕ
                Вы уверены, что хотите отключить торговую сессию?
                После смены статуса, бот не сможет открывать новые и контролировать активные сделки.
                """;

            telegramAPI.sendConfirmationButtons(
                    text,
                    "✅ Подтвердить", BotCommandsRepository.ACTION_SET_STATUS,
                    "❌ Отмена", BotCommandsRepository.ACTION_CANCEL
            );
        } else {
            String text = """
                ⚠️ ВНИМАНИЕ
                Вы уверены, что хотите включить торговую сессию?
                После смены статуса, бот сможет открывать новые и контролировать активные сделки.
                """;

            telegramAPI.sendConfirmationButtons(
                    text,
                    "✅ Подтвердить", BotCommandsRepository.ACTION_SET_STATUS,
                    "❌ Отмена", BotCommandsRepository.ACTION_CANCEL
            );
        }

    }
}