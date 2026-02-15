package com.example.demo.services.telegram;

import com.example.demo.interfaces.BotCommandsRepository;
import com.example.demo.services.api.TelegramAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TelegramService {

    private final TelegramAPI telegramAPI;
    private final BotCurrentStatus botCurrentStatus;
    private final BotActiveTrades botActiveTrades;
    private final BotTradeHistory botTradeHistory;
    private final BotSettingsTelegram botSettingsTelegram;

    @Autowired
    public TelegramService(TelegramAPI telegramAPI, BotCurrentStatus botCurrentStatus, BotActiveTrades botActiveTrades, BotTradeHistory botTradeHistory, BotSettingsTelegram botSettingsTelegram) {
        this.telegramAPI = telegramAPI;
        this.botCurrentStatus = botCurrentStatus;
        this.botActiveTrades = botActiveTrades;
        this.botTradeHistory = botTradeHistory;
        this.botSettingsTelegram = botSettingsTelegram;
    }

    public void handleTelegramCommands() {
        String msg = telegramAPI.getLatestMessage();
        String callbackData = telegramAPI.getLatestCallbackData();

        if (callbackData != null && !callbackData.isEmpty()) {
            System.out.println("🔘 Получен Callback: " + callbackData);
            if (botSettingsTelegram.handleSettingsCallback(callbackData)) {
                return;
            }
            botActiveTrades.handleCallback(callbackData);
        }

        if (msg == null || msg.isEmpty()) return;

        System.out.println("📩 Получено сообщение: " + msg);

        if (botSettingsTelegram.handleSettingsInput(msg)) {
            return;
        }

        if (msg.startsWith(BotCommandsRepository.CMD_STATUS)) {
            botCurrentStatus.sendStatus();
        } else if (msg.startsWith(BotCommandsRepository.CMD_CLOSE_ALL)) {
            botActiveTrades.sendCloseAllTrades();
        } else if (msg.startsWith(BotCommandsRepository.CMD_CLOSE)) {
            botActiveTrades.sendCloseTradeSelection();
        } else if (msg.startsWith(BotCommandsRepository.CMD_CLEAR_HISTORY)) {
            botTradeHistory.sendClearTradeHistory();
        } else if (msg.startsWith(BotCommandsRepository.CMD_CHANGE_STATUS)) {
            botCurrentStatus.sendResponseForChangeStatus();
        } else if (msg.startsWith(BotCommandsRepository.CMD_SETTINGS)) {
            botSettingsTelegram.sendSettingsMenu();
        }
    }
}