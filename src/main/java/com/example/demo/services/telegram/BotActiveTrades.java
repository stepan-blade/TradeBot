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
import java.util.Optional;

@Service
public class BotActiveTrades {

    private final TelegramAPI telegramAPI;
    private final BinanceAPI binanceAPI;
    private final TradeService tradeService;
    private final CalculatorService calculatorService;
    private final TradeRepository tradeRepository;
    private final BotSettingsRepository botSettingsRepository;

    @Autowired
    public BotActiveTrades(TelegramAPI telegramAPI, BinanceAPI binanceAPI, TradeService tradeService, CalculatorService calculatorService, TradeRepository tradeRepository, BotSettingsRepository botSettingsRepository) {
        this.telegramAPI = telegramAPI;
        this.binanceAPI = binanceAPI;
        this.tradeService = tradeService;
        this.calculatorService = calculatorService;
        this.tradeRepository = tradeRepository;
        this.botSettingsRepository = botSettingsRepository;
    }

    /**
     * Отправляет запрос на подтверждение закрытия ВСЕХ сделок.
     */
    public void sendCloseAllTrades() {
        boolean hasOpenTrades = tradeRepository.findAll().stream()
                .anyMatch(t -> "OPEN".equals(t.getStatus()));

        if (!hasOpenTrades) {
            telegramAPI.sendMessage("Нет активных сделок для закрытия.");
            return;
        }

        String text = """
                ⚠️ ВНИМАНИЕ
                Вы уверены, что хотите закрыть ВСЕ активные сделки?
                Это действие нельзя отменить.
                """;

        telegramAPI.sendConfirmationButtons(
                text,
                "✅ Подтвердить", BotCommandsRepository.ACTION_EXECUTE_ALL,
                "❌ Отмена", BotCommandsRepository.ACTION_CANCEL
        );
    }

    /**
     * Отправляет список активных сделок с кнопками для их индивидуального закрытия.
     */
    public void sendCloseTradeSelection() {
        List<Trade> openTrades = tradeService.getActiveTrades();

        if (openTrades.isEmpty()) {
            telegramAPI.sendMessage("Нет активных сделок для закрытия.");
            return;
        }

        for (Trade t : openTrades) {
            double currentPrice = binanceAPI.getCurrentPrice(t.getAsset());
            double pnl = calculatorService.getActiveProfitPercent(t, currentPrice);
            String pnlIcon = pnl >= 0 ? "🟢" : "🔴";

            String text = String.format(
                    """
                            📝 Сделка: %s (%s)
                            💰 Объем: %.2f USDT
                            📊 PnL: %s %.2f%%""",
                    FormatUtil.formatSymbol(t.getAsset()), t.getType(), t.getVolume(), pnlIcon, pnl
            );

            telegramAPI.sendMessageWithInlineButton(
                    text,
                    "Завершить " + t.getAsset(),
                    BotCommandsRepository.ACTION_CONFIRM + ":" + t.getAsset()
            );
        }
    }

    /**
     * Отправляет запрос на подтверждение закрытия выбранной сделки.
     */
    private void handleConfirmSingleClose(String symbol) {
        Optional<Trade> tradeOpt = tradeService.getActiveTrades().stream()
                .filter(t -> t.getAsset().equals(symbol))
                .findFirst();

        if (tradeOpt.isPresent()) {
            Trade t = tradeOpt.get();
            double price = binanceAPI.getCurrentPrice(symbol);
            double profit = calculatorService.getActiveProfitPercent(t, price);

            String text = String.format(
                    """
                            Закрыть %s?
                            Текущий профит: %.2f%%""",
                    symbol, profit);

            telegramAPI.sendConfirmationButtons(text, "✅ Да",
                    BotCommandsRepository.ACTION_EXECUTE + ":" + symbol,
                    "❌ Нет", BotCommandsRepository.ACTION_CANCEL);
        } else {
            telegramAPI.sendMessage("Ошибка: Сделка по " + symbol + " не найдена.");
        }
    }


    /**
     * Обработка нажатий на инлайн-кнопки.
     */
    public void handleCallback(String data) {
        if (data == null || data.isEmpty()) return;

        String[] parts = data.split(":+");

        if (parts.length < 1) return;

        String command = parts[0];
        String argument = (parts.length > 1) ? parts[parts.length - 1] : "";

        System.out.println("⚙️Обработка команды: " + command + " | Аргумент: " + argument);

        if (command.startsWith("confirm")) {
            telegramAPI.deleteMessageWithInlineButton();
        } else {
            telegramAPI.deleteMessageWithConfirmationButtons();
        }

        switch (command) {
            case BotCommandsRepository.ACTION_CONFIRM:
                if (!argument.isEmpty()) {
                    handleConfirmSingleClose(argument);
                } else {
                    System.out.println("⚠️Ошибка: Аргумент пуст для ACTION_CONFIRM");
                }
                break;

            case BotCommandsRepository.ACTION_EXECUTE:
                if (!argument.isEmpty()) {
                    tradeService.closeSpecificTradeManually(argument);
                    telegramAPI.sendMessage("Активная сделка были закрыта.");
                }
                break;

            case BotCommandsRepository.ACTION_EXECUTE_ALL:
                tradeService.closeAllPositionsManually();
                telegramAPI.sendMessage("Все активные сделки были закрыты.");
                break;

            case BotCommandsRepository.ACTION_SET_STATUS:
                BotSettings botSettings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());
                String botStatus = botSettings.getStatus();
                if ("ONLINE".equals(botStatus)) {
                    botSettings.setStatus("OFFLINE");
                    botSettingsRepository.save(botSettings);

                    telegramAPI.sendMessage("⚠️Торговый алгоритм выключен");
                } else {
                    botSettings.setStatus("ONLINE");
                    botSettingsRepository.save(botSettings);

                    telegramAPI.sendMessage("✅ Торговый алгоритм включен");
                }
                break;

            case BotCommandsRepository.ACTION_CANCEL:
                telegramAPI.sendMessage("❌ Действие отменено.");
                break;
        }
    }
}