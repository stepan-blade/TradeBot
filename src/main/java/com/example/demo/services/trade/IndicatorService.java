package com.example.demo.services.trade;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class IndicatorService {

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
}
