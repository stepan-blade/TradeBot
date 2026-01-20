package com.example.demo.utils;

public class FormatUtil {

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


    /**
     * Округляет числовое значение (количество или цену) в соответствии с шагом,
     * установленным торговой площадкой (Binance Step Size).
     * <p>
     * Метод вычисляет необходимую точность (количество знаков после запятой) на основе
     * размера шага и отсекает лишние знаки, используя режим округления FLOOR (в меньшую сторону).
     * Это гарантирует, что итоговое количество не превысит доступный баланс из-за округления.
     *
     * @param value    Исходное значение, которое необходимо округлить (например, рассчитанное
     * количество монет для покупки).
     * @param stepSize Минимальный шаг изменения значения, разрешенный биржей
     * (например, 0.01 или 0.0001). Берется из настроек торговой пары (Lot Size).
     * @return         Округленное значение типа double, приведенное к допустимому
     * количеству знаков после запятой. Если stepSize <= 0, возвращает
     * исходное значение без изменений.
     */
    public static double roundToStep(double value, double stepSize) {
        if (stepSize <= 0) return value;
        double precision = Math.log10(1 / stepSize);

        int scale = (int) Math.round(precision);
        java.math.BigDecimal bd = new java.math.BigDecimal(Double.toString(value));
        bd = bd.setScale(scale, java.math.RoundingMode.FLOOR);

        return bd.doubleValue();
    }
}
