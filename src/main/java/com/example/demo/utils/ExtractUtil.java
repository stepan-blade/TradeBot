package com.example.demo.utils;

import com.fasterxml.jackson.databind.JsonNode;

public class ExtractUtil {

    /**
     * Вспомогательный метод для извлечения ID
     * @param response запрос серверу
     */
    public static String extractOrderId(JsonNode response) {
        if (response != null && response.has("orderId")) {
            return response.get("orderId").asText();
        }

        if (response != null && response.has("msg")) {
            System.err.println(">>> [BINANCE ERROR] Ошибка ExtracterUtil: " + response.get("msg").asText());
        }
        return null;
    }
}
