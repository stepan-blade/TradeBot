package com.example.demo.utils;

public class FormatterUtil {

    /**
     * Вспомогательный метод форматирования валютных пар
     * @param symbol Валютная пара
    */
    public static String formatSymbol(String symbol) {
        return symbol == null ? "" : symbol.replace("USDT", "/USDT");
    }

    /**
     * Вспомогательный метод форматирования BinanceAPI
     * @param value цена актива, кол-во, стоп-лимиты
     */
    public static String formatValue(double value) {
        return String.format("%.8f", value).replace(",", ".");
    }
}
