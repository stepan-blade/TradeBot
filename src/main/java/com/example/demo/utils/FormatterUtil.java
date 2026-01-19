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
        return String.format("%.2f", value).replace(",", ".");
    }

    public static double roundToStep(double value, double stepSize) {
        if (stepSize <= 0) return value;
        double precision = Math.log10(1 / stepSize);
        int scale = (int) Math.round(precision);
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(value));
        bd = bd.setScale(scale, java.math.RoundingMode.FLOOR); // Всегда округляем вниз для безопасности
        return bd.doubleValue();
    }
}
