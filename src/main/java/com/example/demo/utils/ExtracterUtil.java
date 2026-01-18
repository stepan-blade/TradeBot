package com.example.demo.utils;

import com.fasterxml.jackson.databind.JsonNode;

public class ExtracterUtil {

    /**
     * Вспомогательный метод для извлечения ID
     * @param response запрос серверу
     */
    public static String extractOrderId(JsonNode response) {
        if (response != null && response.has("orderId")) {
            return response.get("orderId").asText();
        }

        if (response != null && response.has("msg")) {
            System.err.println("Binance Error: " + response.get("msg").asText());
        }
        return null;
    }
}
