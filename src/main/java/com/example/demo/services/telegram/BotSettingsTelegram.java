package com.example.demo.services.telegram;

import com.example.demo.data.BotSettings;
import com.example.demo.interfaces.BotCommandsRepository;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.services.api.TelegramAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class BotSettingsTelegram {

    private final TelegramAPI telegramAPI;
    private final BotSettingsRepository botSettingsRepository;

    // Состояние для чата: какой параметр ожидает ввода
    private final Map<String, String> waitingInput = new HashMap<>(); // chatId -> параметр (assets/percent/max_trades)

    @Autowired
    public BotSettingsTelegram(TelegramAPI telegramAPI, BotSettingsRepository botSettingsRepository) {
        this.telegramAPI = telegramAPI;
        this.botSettingsRepository = botSettingsRepository;
    }

    /**
     * Отправляет меню настроек
     */
    public void sendSettingsMenu() {
        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());

        String text = """
                <b>⚙️ Текущие настройки бота</b>
                
                <b>Активы:</b> %s
                <b>Процент на сделку:</b> %.1f%%
                <b>Макс. открытых сделок:</b> %d
                
                Выберите параметр для изменения:""".formatted(
                settings.getAssets() != null ? settings.getAssets() : "Не заданы",
                settings.getTradePercent(),
                settings.getMaxOpenTrades()
        );

        List<Map<String, String>> buttons = List.of(
                Map.of("text", "🔤 Активы", "callback_data", "settings_assets"),
                Map.of("text", "📊 Процент", "callback_data", "settings_percent"),
                Map.of("text", "📈 Макс. сделки", "callback_data", "settings_max_trades"),
                Map.of("text", "❌ Отмена", "callback_data", "settings_cancel")
        );

        telegramAPI.sendMessageWithInlineButtons(text, buttons);
    }

    /**
     * Обрабатывает callback от кнопок меню
     * @param data callback_data
     * @return true если обработано
     */
    public boolean handleSettingsCallback(String data) {
        if (!data.startsWith("settings_")) return false;

        telegramAPI.deleteMessageWithInlineButton(); // Удаляем меню

        String param = data.substring(9); // assets / percent / max_trades / cancel / back

        if ("cancel".equals(param)) {
            telegramAPI.sendMessage("Изменение настроек отменено");
            waitingInput.remove(telegramAPI.getChatId());

            return true;
        }

        if ("back".equals(param)) {
            waitingInput.remove(telegramAPI.getChatId());
            sendSettingsMenu();

            return true;
        }

        waitingInput.put(telegramAPI.getChatId(), param);

        String prompt = switch (param) {
            case "assets" -> "Введите новый список активов (через запятую):";
            case "percent" -> "Введите новый процент (число от 1 до 100):";
            case "max_trades" -> "Введите новое макс. количество сделок (число от 1 до 10):";
            default -> "";
        };

        telegramAPI.sendMessageWithInlineButton(prompt, "🔙 Назад", "settings_back");
        return true;
    }

    /**
     * Обрабатывает ввод пользователя, если ожидается
     * @param message Текст сообщения
     * @return true если обработано как ввод настроек
     */
    public boolean handleSettingsInput(String message) {
        String param = waitingInput.get(telegramAPI.getChatId());
        if (param == null) return false;

        waitingInput.remove(telegramAPI.getChatId());

        BotSettings settings = botSettingsRepository.findById("MAIN_SETTINGS").orElse(new BotSettings());

        try {
            switch (param) {
                case "assets" -> settings.setAssets(message.trim());
                case "percent" -> {
                    double perc = Double.parseDouble(message.trim());
                    if (perc < 1 || perc > 100) throw new Exception();
                    settings.setTradePercent(perc);
                }
                case "max_trades" -> {
                    int max = Integer.parseInt(message.trim());
                    if (max < 1 || max > 10) throw new Exception();
                    settings.setMaxOpenTrades(max);
                }
            }

            botSettingsRepository.save(settings);
            telegramAPI.sendMessage("✅ Настройки обновлены!");
        } catch (Exception e) {
            telegramAPI.sendMessage("❌ Ошибка: неверный формат. Изменения не сохранены.");
        }

        return true;
    }
}