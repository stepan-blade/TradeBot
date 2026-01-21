package com.example.demo.services.trade;

import com.example.demo.services.api.BinanceAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class IndicatorService {

    private final BinanceAPI binanceAPI;

    @Autowired
    public IndicatorService(BinanceAPI binanceAPI) {
        this.binanceAPI = binanceAPI;
    }

    /**
     * Функция расчета RSI
     * @param klines Свечи
     * @param period Период
     * @return Показатель
     */
    public double calculateRSI(List<double[]> klines, int period) {
        if (klines.size() < period + 1) return 50.0;
        double gain = 0, loss = 0;
        for (int i = klines.size() - period; i < klines.size(); i++) {
            double diff = klines.get(i)[3] - klines.get(i - 1)[3];
            if (diff > 0) gain += diff;
            else loss += Math.abs(diff);
        }
        double avgGain = gain / period, avgLoss = loss / period;
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    /**
     * Функция расчета SMA
     * @param klines Свечи
     * @param period Период
     * @return Показатель
     */
    public double calculateSMA(List<double[]> klines, int period) {
        if (klines.size() < period) return 0;
        double sum = 0;
        for (int i = klines.size() - period; i < klines.size(); i++) {
            sum += klines.get(i)[3];
        }
        return sum / period;
    }

    /**
     * Функция расчета линий Болинджера
     * @param klines Свечи
     * @param period Период
     * @param k Коэффициент отклонения
     * @return Показатель
     */
    public double[] calculateBollingerBands(List<double[]> klines, int period, double k) {
        if (klines.size() < period) return new double[]{0, 0, 0};
        double sum = 0;
        for (int i = klines.size() - period; i < klines.size(); i++) {
            sum += klines.get(i)[3];
        }
        double sma = sum / period;
        double variance = 0;
        for (int i = klines.size() - period; i < klines.size(); i++) {
            variance += Math.pow(klines.get(i)[3] - sma, 2);
        }
        double sd = Math.sqrt(variance / period);
        return new double[]{sma + k * sd, sma, sma - k * sd};
    }

    /**
     * Рассчитывает показатель ATR (Average True Range) для предоставленного списка свечей.
     * * Алгоритм:
     * 1. Вычисляет True Range (TR) как максимум из трех величин:
     * - Разница между текущим максимумом и минимумом (High - Low).
     * - Модуль разницы между текущим максимумом и ценой закрытия предыдущей свечи |High - PrevClose|.
     * - Модуль разницы между текущим минимумом и ценой закрытия предыдущей свечи |Low - PrevClose|.
     * 2. Применяет сглаживание по формуле: ATR = (Previous_ATR * (n - 1) + Current_TR) / n.
     * * @param klines Список данных о свечах. Индексы массива: [1] - High, [2] - Low, [3] - Close.
     * @param period Период усреднения (стандартно — 14).
     * @return Значение волатильности актива в денежном эквиваленте.
     */
    public double calculateATR(List<double[]> klines, int period) {
        if (klines.size() < period + 1) return 0.0;

        double sum = 0.0;
        // Первый TR (без prev close)
        sum += klines.get(1)[1] - klines.get(1)[2];

        for (int i = 2; i < klines.size(); i++) {
            double high = klines.get(i)[1];
            double low = klines.get(i)[2];
            double prevClose = klines.get(i - 1)[3];

            double tr1 = high - low;
            double tr2 = Math.abs(high - prevClose);
            double tr3 = Math.abs(low - prevClose);

            double tr = Math.max(tr1, Math.max(tr2, tr3));

            sum += tr;
        }

        return sum / (klines.size() - 1);
    }


    /**
     * Рассчитывает экспоненциальную скользящую среднюю (EMA).
     * В отличие от простой скользящей средней (SMA), EMA быстрее реагирует на изменения
     * цены, так как учитывает исторические данные с экспоненциально убывающим весом.
     * * Формула:
     * Multiplier = 2 / (period + 1)
     * EMA_today = (Close_today - EMA_yesterday) * Multiplier + EMA_yesterday
     * * @param klines Список свечей. Индекс [3] — цена закрытия (Close).
     * @param period Временной период для расчета (например, 9, 20 или 50).
     * @return Текущее значение экспоненциальной средней. Используется для определения
     * тренда и уровней динамической поддержки/сопротивления.
     */
    public double calculateEMA(List<double[]> klines, int period) {
        double multiplier = 2.0 / (period + 1);
        double ema = klines.get(0)[3];
        for (int i = 1; i < klines.size(); i++) {
            ema = (klines.get(i)[3] - ema) * multiplier + ema;
        }
        return ema;
    }

    /**
     * Вычисляет среднее значение индикатора ATR за последние 30 дней.
     * Метод использует скользящее окно: для каждой из 30 последних свечей рассчитывается
     * классический ATR за 14 периодов, после чего результаты суммируются и усредняются.
     * * @param symbol Торговая пара (например, "BTCUSDT").
     * @return Среднее значение волатильности (ATR) за 30-дневный цикл.
     * Используется для оценки общей рыночной динамики и фильтрации аномальных скачков цены.
     */
    public double calculateAverageAtr(String symbol) {
        List<double[]> klines = binanceAPI.getKlines(symbol, "1d", 30); // Средний ATR за 30 дней
        double sum = 0;
        for (int i = 0; i < klines.size(); i++) {
            sum += calculateATR(klines.subList(0, i + 1), 14);
        }
        return sum / klines.size();
    }

    /**
     * Рассчитывает индикатор MACD (Moving Average Convergence Divergence).
     * <p>
     * MACD — трендовый осциллятор, который показывает разницу между двумя EMA (быстрой и медленной).
     * Signal Line — EMA от MACD Line для сглаживания.
     * Гистограмма = MACD Line - Signal Line.
     * <p>
     * Используется для выявления сигналов на покупку/продажу (пересечения линий).
     *
     * @param klines Список свечей (индекс [3] — close).
     * @param fast   Период быстрой EMA (стандартно 12).
     * @param slow   Период медленной EMA (стандартно 26).
     * @param signal Период EMA для сигнальной линии (стандартно 9).
     * @return double[]: [0] — MACD Line, [1] — Signal Line, [2] — Histogram.
     */
    public double[] calculateMACD(List<double[]> klines, int fast, int slow, int signal) {
        if (klines.size() < Math.max(fast, slow) + signal) return new double[]{0, 0, 0};

        // Рассчитываем EMA fast и slow
        double emaFast = calculateEMA(klines, fast);
        double emaSlow = calculateEMA(klines, slow);
        double macdLine = emaFast - emaSlow;

        // Signal Line — EMA от MACD
        double signalLine = calculateEMAForMACD(klines, signal, fast, slow);

        double histogram = macdLine - signalLine;

        return new double[]{macdLine, signalLine, histogram};
    }

    /**
     * Рассчитывает EMA для сигнальной линии MACD на основе серии MACD значений.
     * <p>
     * Для точного MACD signal нужно сначала вычислить серию MACD Line за период, затем EMA на ней.
     * Метод принимает klines и вычисляет полную серию.
     *
     * @param klines Список свечей.
     * @param signal Период EMA для сигнальной линии.
     * @param fast   Период быстрой EMA для MACD.
     * @param slow   Период медленной EMA для MACD.
     * @return Значение signal line (последнее EMA от MACD series).
     */
    public double calculateEMAForMACD(List<double[]> klines, int signal, int fast, int slow) {
        if (klines.size() < slow + signal) return 0.0;

        // Вычисляем серию MACD Line
        List<Double> macdSeries = new ArrayList<>();
        for (int i = slow; i < klines.size(); i++) {
            List<double[]> subKlinesFast = klines.subList(i - fast + 1, i + 1);
            List<double[]> subKlinesSlow = klines.subList(i - slow + 1, i + 1);
            double emaFast = calculateEMA(subKlinesFast, fast);
            double emaSlow = calculateEMA(subKlinesSlow, slow);
            macdSeries.add(emaFast - emaSlow);
        }

        // Теперь EMA на macdSeries
        if (macdSeries.isEmpty()) return 0.0;
        double multiplier = 2.0 / (signal + 1);
        double ema = macdSeries.get(0);
        for (int i = 1; i < macdSeries.size(); i++) {
            ema = (macdSeries.get(i) - ema) * multiplier + ema;
        }
        return ema;
    }
}
