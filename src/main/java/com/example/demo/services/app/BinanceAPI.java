package com.example.demo.services.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.*;

@Service
public class BinanceAPI {

    private int usedWeight = 0;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    // 1. Метод для получения свечей ОДИН раз для всех индикаторов
    private JsonNode getKlines(String symbol, String interval, int limit) {
        try {
            String url = "https://api.binance.com/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit;
            return mapper.readTree(new java.net.URL(url));
        } catch (Exception e) {
            return null;
        }
    }

    // 2. Метод для получения цены актива
    public double getCurrentPrice(String symbol) {
        try {
            String url = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            return root.get("price").asDouble();
        } catch (Exception e) {
            return -1.0;
        }
    }

    // 3. Объединенный метод расчета (RSI и BB из одних данных)
    public double calculateRealRSI(String symbol) {
        JsonNode root = getKlines(symbol, "1m", 50);
        if (root == null) return 50.0;

        double gain = 0, loss = 0;
        for (int i = 1; i < root.size(); i++) {
            double diff = root.get(i).get(4).asDouble() - root.get(i - 1).get(4).asDouble();
            if (diff > 0) gain += diff;
            else loss += Math.abs(diff);
        }
        if (loss == 0) return 100;
        return 100 - (100 / (1 + (gain / loss)));
    }

    // 4. Метод расчета BB
    public double[] calculateBollingerBands(String symbol, int period, double k) {
        JsonNode root = getKlines(symbol, "5m", period);
        if (root == null) return null;

        List<Double> closes = new ArrayList<>();
        double sum = 0;
        for (JsonNode node : root) {
            double close = node.get(4).asDouble();
            closes.add(close);
            sum += close;
        }

        double sma = sum / closes.size();
        double deviationSum = 0;
        for (double close : closes) {
            deviationSum += Math.pow(close - sma, 2);
        }
        double sd = Math.sqrt(deviationSum / closes.size());

        return new double[]{sma - (k * sd), sma, sma + (k * sd)};
    }

    // 5. Метод расчета SMA
    public double calculateSMA(String symbol, int period) {
        JsonNode root = getKlines(symbol, "1m", period);
        if (root == null) return 0;

        double sum = 0;
        for (JsonNode node : root) {
            sum += node.get(4).asDouble();
        }
        return sum / root.size();
    }

    // 6. Метод получения волатильности
    public double get24hVolume(String symbol) {
        try {
            String url = "https://api.binance.com/api/v3/ticker/24hr?symbol=" + symbol;
            JsonNode json = restTemplate.getForObject(url, JsonNode.class);
            return json.get("quoteVolume").asDouble();
        } catch (Exception e) {
            return 0;
        }
    }
}