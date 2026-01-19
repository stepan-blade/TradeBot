package com.example.demo.services.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class TelegramAPI {

    /**
     * @see #getLatestMessage() - Метод для получения текста отправленного пользователем сообщения
     * @see #getLatestCallbackData() - Получает последние данные обратного вызова (callback_data) от кнопки.
     *
     * @see #sendMessage(String) - Отправка сообщения
     * @see #sendMessageWithInlineButton(String, String, String) - Отправка сообщения с кнопкой действия
     * @see #sendConfirmationButtons(String, String, String, String, String) - Форма подтверждения действия (Да/нет)
     * @see #sendRequest(String, Map, boolean) - Универсальный вспомогательный метод для отправки POST-запросов к Telegram Bot API.
     *
     * @see #deleteMessageWithInlineButton() - Метод для удаления сообщений с кнопками действия
     * @see #deleteMessageWithConfirmationButtons() - Метод для удаления сообщения с формой подтверждения
     */

    private final String CHAT_ID;
    private final String API_URL;
    private long lastUpdateId = 0;
    private final RestTemplate restTemplate = new RestTemplate();

    private final List<Integer> activeMenuMessageIds = Collections.synchronizedList(new ArrayList<>());
    private Integer confirmationMessageId;
    private String latestCallbackData;

    @Autowired
    public TelegramAPI(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.chat.id}") String chatId) {
        this.CHAT_ID = chatId;
        this.API_URL = "https://api.telegram.org/bot" + botToken;
    }

    /**
     * Метод для получения последнего отправленного пользователем сообщения
     * @return Текст сообщения
     */
    public String getLatestMessage() {
        try {
            String url = API_URL + "/getUpdates?offset=" + (lastUpdateId + 1) + "&timeout=0";
            Map response = restTemplate.getForObject(url, Map.class);

            if (response == null || response.get("result") == null) return null;

            List<Object> updates = (List<Object>) response.get("result");
            String textToReturn = null;

            for (Object updateObj : updates) {
                Map<String, Object> update = (Map<String, Object>) updateObj;
                this.lastUpdateId = Long.parseLong(update.get("update_id").toString());

                // Обработка КНОПОК
                if (update.containsKey("callback_query")) {
                    Map<String, Object> cb = (Map<String, Object>) update.get("callback_query");
                    this.latestCallbackData = cb.get("data").toString();
                    System.out.println(">>> [DEBUG] Callback пойман: " + latestCallbackData);
                }

                // Обработка СООБЩЕНИЙ
                if (update.containsKey("message")) {
                    Map<String, Object> msg = (Map<String, Object>) update.get("message");
                    if (msg.containsKey("text")) {
                        textToReturn = msg.get("text").toString();
                    }
                }
            }
            return textToReturn;
        } catch (Exception e) {
            System.err.println(">>> [CRITICAL] Ошибка API: " + e.getMessage());
            return null;
        }
    }

    /**
     * Получает последние данные обратного вызова (callback_data) от кнопки.
     * Метод реализует принцип "прочитал — удалил". Это гарантирует, что одно нажатие
     * кнопки будет обработано строго один раз и не вызовет повторных срабатываний
     * при следующем цикле опроса (polling).
     * * @return String — строка данных из кнопки (например, "execute_close:BTCUSDT")
     * или null, если новых нажатий не было.
     */
    public String getLatestCallbackData() {
        String data = this.latestCallbackData;
        this.latestCallbackData = null;
        return data;
    }

    /**
     * Отправка сообщения
     * @param text Текст сообщения
     */
    public void sendMessage(String text) {
        sendRequest("/sendMessage", Map.of(
                        "chat_id", CHAT_ID,
                        "text", text,
                        "parse_mode",
                        "HTML"),
                false);
    }

    /**
     * Отправка сообщения с кнопкой действия
     * @param text - Текст сообщения
     * @param buttonText - Текст кнопки действия
     * @param callbackData - Функция кнопки действия
     */
    public void sendMessageWithInlineButton(String text, String buttonText, String callbackData) {
        Map<String, Object> button = Map.of("text", buttonText, "callback_data", callbackData);
        Map<String, Object> markup = Map.of("inline_keyboard", List.of(List.of(button)));
        sendRequest("/sendMessage", Map.of(
                        "chat_id", CHAT_ID,
                        "text", text,
                        "parse_mode",
                        "HTML",
                        "reply_markup", markup),
                true);
    }

    /**
     * Форма подтверждения действия (Да/нет)
     * @param text Текст сообщения
     * @param btn1Text - Текст кнопки подтверждения
     * @param btn1Data - Действие кнопки подтверждения
     * @param btn2Text - Текст кнопки отмены
     * @param btn2Data - Действие кнопки отмены
     */
    public void sendConfirmationButtons(String text, String btn1Text, String btn1Data, String btn2Text, String btn2Data) {
        String url = API_URL + "/sendMessage";
        Map<String, Object> request = new HashMap<>();
        request.put("chat_id", CHAT_ID);
        request.put("text", text);
        request.put("parse_mode", "HTML");

        Map<String, Object> b1 = Map.of("text", btn1Text, "callback_data", btn1Data);
        Map<String, Object> b2 = Map.of("text", btn2Text, "callback_data", btn2Data);
        request.put("reply_markup", Map.of("inline_keyboard", List.of(Arrays.asList(b1, b2))));

        try {
            Map<?, ?> response = restTemplate.postForObject(url, request, Map.class);
            if (response != null && response.get("result") != null) {
                Map<?, ?> result = (Map<?, ?>) response.get("result");
                this.confirmationMessageId = (Integer) result.get("message_id");
                System.out.println("✅ Кнопки подтверждения отправлены. ID: " + confirmationMessageId);
            }
        } catch (Exception e) {
            System.err.println("❌ Ошибка отправки кнопок: " + e.getMessage());
        }
    }

    /**
     * Универсальный вспомогательный метод для отправки POST-запросов к Telegram Bot API.
     * Инкапсулирует работу с restTemplate и обработку ошибок связи.
     * Если запрос помечен как "меню", ID созданного сообщения сохраняется для последующей очистки.
     * @param path   Эндпоинт метода Telegram API (например, "/sendMessage" или "/deleteMessage").
     * @param body   Карта (Map) с параметрами запроса, которая будет конвертирована в JSON.
     * @param isMenu Флаг, указывающий, является ли сообщение интерактивным меню.
     * Если true, ID сообщения будет добавлен в список activeMenuMessageIds
     * для массового удаления кнопок при выборе действия.
     */
    private void sendRequest(String path, Map<String, Object> body, boolean isMenu) {
        try {
            Map<?, ?> response = restTemplate.postForObject(API_URL + path, body, Map.class);
            if (isMenu && response != null && response.get("result") != null) {
                Map<?, ?> result = (Map<?, ?>) response.get("result");
                activeMenuMessageIds.add((Integer) result.get("message_id"));
            }
        } catch (Exception e) {
            System.err.println("Ошибка запроса: " + e.getMessage());
        }
    }

    /**
     * Метод для удаления сообщения с формой подтверждения
     */
    public void deleteMessageWithConfirmationButtons() {
        if (confirmationMessageId != null) {
            try {
                String url = API_URL + "/deleteMessage?chat_id=" + CHAT_ID + "&message_id=" + confirmationMessageId;
                restTemplate.getForObject(url, String.class);
                confirmationMessageId = null;
            } catch (Exception e) {
                System.err.println("ℹ️ Не удалось удалить подтверждение: " + e.getMessage());
            }
        }
    }

    /**
     * Метод для удаления сообщений с кнопками действия
     */
    public void deleteMessageWithInlineButton() {
        for (Integer id : new ArrayList<>(activeMenuMessageIds)) {
            restTemplate.getForObject(API_URL + "/deleteMessage?chat_id=" + CHAT_ID + "&message_id=" + id, String.class);
        }
        activeMenuMessageIds.clear();
    }
}