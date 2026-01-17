package com.example.demo.services.app;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class TelegramAPI {

    private final String BOT_TOKEN = "8473858051:AAGOB7wANVfbU5hRQjCZs_k7J-JDFsT6quM";
    private final String CHAT_ID = "631369892";
    private final String API_URL = "https://api.telegram.org/bot" + BOT_TOKEN;

    private long lastUpdateId = 0;
    private final RestTemplate restTemplate = new RestTemplate();

    // Список для хранения ID сообщений меню выбора
    private List<Integer> activeMenuMessageIds = new ArrayList<>();
    private Integer lastMessageId;
    private String latestCallbackData;

    // 1. Очистка старых меню
    public void clearActiveMenus() {
        if (activeMenuMessageIds.isEmpty()) return;

        for (Integer msgId : new ArrayList<>(activeMenuMessageIds)) {
            try {
                String url = API_URL + "/deleteMessage?chat_id=" + CHAT_ID + "&message_id=" + msgId;
                restTemplate.getForObject(url, String.class);
            } catch (Exception e) {
            }
        }
        activeMenuMessageIds.clear();
    }

    // 2. Отправка обычного сообщения
    public void sendMessage(String text) {
        String url = API_URL + "/sendMessage";
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", CHAT_ID);
        body.put("text", text);
        body.put("parse_mode", "HTML");
        sendAndStoreId(url, body, false);
    }

    // 3. Отправка кнопок выбора
    public void sendMessageWithInlineButton(String text, String buttonText, String callbackData) {
        String url = API_URL + "/sendMessage";
        Map<String, Object> request = new HashMap<>();
        request.put("chat_id", CHAT_ID);
        request.put("text", text);
        request.put("parse_mode", "HTML");

        Map<String, Object> button = Map.of("text", buttonText, "callback_data", callbackData);
        request.put("reply_markup", Map.of("inline_keyboard", List.of(List.of(button))));

        sendAndStoreId(url, request, true);
    }

    // 4. Кнопки подтверждения (Да/Нет)
    public void sendConfirmationButtons(String text, String btn1Text, String btn1Data, String btn2Text, String btn2Data) {
        String url = API_URL + "/sendMessage";
        Map<String, Object> request = new HashMap<>();
        request.put("chat_id", CHAT_ID);
        request.put("text", text);
        request.put("parse_mode", "HTML");

        Map<String, Object> b1 = Map.of("text", btn1Text, "callback_data", btn1Data);
        Map<String, Object> b2 = Map.of("text", btn2Text, "callback_data", btn2Data);
        request.put("reply_markup", Map.of("inline_keyboard", List.of(Arrays.asList(b1, b2))));

        sendAndStoreId(url, request, false);
    }

    // 5. ЕДИНЫЙ метод для отправки и сохранения ID
    private void sendAndStoreId(String url, Object request, boolean isMenu) {
        try {
            Map<?, ?> response = restTemplate.postForObject(url, request, Map.class);
            if (response != null && response.containsKey("result")) {
                Map<?, ?> result = (Map<?, ?>) response.get("result");
                Integer msgId = (Integer) result.get("message_id");
                this.lastMessageId = msgId;

                if (isMenu) {
                    activeMenuMessageIds.add(msgId);
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Ошибка API: " + e.getMessage());
        }
    }

    // 6. ЕДИНЫЙ метод для получения последнего отправленного сообщения
    public String getLatestMessage() {
        try {
            String url = API_URL + "/getUpdates?offset=" + (lastUpdateId + 1) + "&limit=1&timeout=0";

            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null && response.containsKey("result")) {
                List<Map<String, Object>> result = (List<Map<String, Object>>) response.get("result");

                if (result.isEmpty()) return null;

                Map<String, Object> update = result.get(0);
                this.lastUpdateId = Long.parseLong(update.get("update_id").toString());

                if (update.containsKey("callback_query")) {
                    Map<String, Object> cb = (Map<String, Object>) update.get("callback_query");
                    this.latestCallbackData = cb.get("data").toString();

                    String cbId = cb.get("id").toString();
                    restTemplate.getForObject(API_URL + "/answerCallbackQuery?callback_query_id=" + cbId, String.class);

                    Map<String, Object> msgNode = (Map<String, Object>) cb.get("message");
                    this.lastMessageId = (Integer) msgNode.get("message_id");
                    return null;
                }

                if (update.containsKey("message")) {
                    Map<String, Object> msg = (Map<String, Object>) update.get("message");
                    if (msg.containsKey("text")) {
                        return msg.get("text").toString();
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Ошибка связи: " + e.getMessage());
        }
        return null;
    }

    // 7. ЕДИНЫЙ метод для удаления сообщений
    public void deleteLastMessage() {
        if (lastMessageId != null) {
            try {
                String url = API_URL + "/deleteMessage?chat_id=" + CHAT_ID + "&message_id=" + lastMessageId;
                restTemplate.getForObject(url, String.class);
                lastMessageId = null;
            } catch (Exception e) {
                System.out.println("ℹ️ Не удалось удалить сообщение (возможно, уже удалено)");
            }
        }
    }

    public String getLatestCallbackData() {
        String data = this.latestCallbackData;
        this.latestCallbackData = null;
        return data;
    }
}