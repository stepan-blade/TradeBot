package com.example.demo.services.api;

import com.example.demo.utils.ExtractUtil;
import com.example.demo.utils.FormatUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.codec.Hex;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class BinanceAPI {

    /**
     * @see #getAccountBalance() - Баланс USDT (доступный free)
     * @see #getAssetBalance(String) - Получить баланс конкретной монеты
     * @see #getCurrentPrice(String) - Текущая цена актива (USDT)
     * @see #get24hVolume(String) - 24h объём в quote (USDT)
     * @see #getKlines(String, String, int) - Klines (свечи)
     * @see #getStepSize(String) -  Получает шаг лота (stepSize) для символа, чтобы правильно округлить количество
     * @see #getTradeFee(String) - Получение актуальной комиссии для конкретной торговой пары.
     * @see #getDailyPnl() - Получение дневного PNL (изменения баланса) через снимки аккаунта (Account Snapshot)
     * @see #getAllTimePnl(String) - Получение PNL за все время на основе истории сделок.
     *
     * @see #placeMarketBuy(String, double) - Покупка на рынке (MARKET BUY)
     * @see #placeMarketSell(String, double) - Продажа на рынке (MARKET SELL)
     * @see #placeTakeProfitLimit(String, double, double, double) - Тейк-Профит Лимитный ордер (фиксация прибыли)
     * @see #placeStopLossLimit(String, double, double, double, String)  - Стоп-Лосс Лимитный ордер (защита от падения)
     * @see #placeOCOOrder(String, double, double, double, double) - OCO Ордер (One-Cancels-the-Other)
     *
     * @see #cancelAllOrders(String) - Отменить все открытые ордера по символу
     */

    private static final Logger logger = LoggerFactory.getLogger(BinanceAPI.class);
    private final String apiKey;
    private final String secretKey;
    private final String baseUrl;
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final AtomicInteger usedWeight = new AtomicInteger(0);

    @Autowired
    public BinanceAPI(@Value("${binance.api.key}") String apiKey,
                      @Value("${binance.api.secret}") String secretKey,
                      @Value("${binance.testnet:false}") boolean testnet) {
        this.apiKey = apiKey;
        this.secretKey = secretKey;
        this.baseUrl = testnet ? "https://testnet.binance.vision" : "https://api.binance.com";
        this.restTemplate = new RestTemplate();
        this.mapper = new ObjectMapper();
    }

    private String sign(String query) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return new String(Hex.encode(mac.doFinal(query.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private <T> T signedRequest(String endpoint, HttpMethod method, MultiValueMap<String, String> params, Class<T> responseType) {
        try {
            long timestamp = System.currentTimeMillis();
            params.add("timestamp", String.valueOf(timestamp));

            String queryString = params.entrySet().stream()
                    .flatMap(e -> e.getValue().stream().map(v -> e.getKey() + "=" + v))
                    .collect(Collectors.joining("&"));

            String signature = sign(queryString);
            if (signature == null) return null;
            params.add("signature", signature);

            HttpHeaders headers = new HttpHeaders();
            headers.add("X-MBX-APIKEY", apiKey);

            String url = baseUrl + endpoint;
            HttpEntity<?> entity;

            if (method == HttpMethod.GET) {
                url = UriComponentsBuilder.fromHttpUrl(url).queryParams(params).build().toUriString();
                entity = new HttpEntity<>(headers);
            } else {
                entity = new HttpEntity<>(params, headers);
            }

            // 1. ВЫПОЛНЕНИЕ ЗАПРОСА
            ResponseEntity<T> response = restTemplate.exchange(url, method, entity, responseType);

            // 2. ОБНОВЛЕНИЕ ВЕСА ПОСЛЕ ЗАПРОСА
            String weightHeader = response.getHeaders().getFirst("x-mbx-used-weight-1m");
            if (weightHeader != null) {
                try {
                    int currentWeight = Integer.parseInt(weightHeader);
                    usedWeight.set(currentWeight); // Обновляем наш счетчик реальными данными от Binance
                } catch (NumberFormatException e) {
                    logger.error("Ошибка парсинга веса: {}", weightHeader);
                }
            }

            return response.getBody();

        } catch (Exception e) {
            logger.error("Ошибка при выполнении запроса к {}: {}", endpoint, e.getMessage());
            return null;
        }
    }

    /**
     * Баланс USDT (доступный free)
     */
    public double getAccountBalance() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        JsonNode response = signedRequest("/api/v3/account", HttpMethod.GET, params, JsonNode.class);
        if (response != null && response.has("balances")) {
            for (JsonNode balance : response.get("balances")) {
                if ("USDT".equals(balance.get("asset").asText())) {
                    return balance.get("free").asDouble();
                }
            }
        }
        return 0.0;
    }

    /**
     * Получить баланс конкретной монеты
     * @param asset Валютная пара
     * @return Остаточная сумма монеты
     */
    public double getAssetBalance(String asset) {
        String cleanAsset = asset.replace("USDT", "");

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        JsonNode response = signedRequest("/api/v3/account", HttpMethod.GET, params, JsonNode.class);

        if (response != null && response.has("balances")) {
            for (JsonNode balance : response.get("balances")) {
                if (cleanAsset.equals(balance.get("asset").asText())) {
                    return balance.get("free").asDouble() + balance.get("locked").asDouble();
                }
            }
        }
        return 0.0;
    }

    /**
     * Текущая цена актива (USDT)
     * @param symbol Валютная пара (например, BTCUSDT)
     */
    public double getCurrentPrice(String symbol) {
        try {
            String url = baseUrl + "/api/v3/ticker/price?symbol=" + symbol;
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            assert root != null;
            return root.get("price").asDouble();
        } catch (Exception e) {
            e.printStackTrace();
            return -1.0;
        }
    }

    /**
     * 24h объём в quote (USDT)
     * @param symbol Валютная пара
     */
    public double get24hVolume(String symbol) {
        try {
            String url = baseUrl + "/api/v3/ticker/24hr?symbol=" + symbol;
            JsonNode json = restTemplate.getForObject(url, JsonNode.class);
            assert json != null;
            return json.get("quoteVolume").asDouble();
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Klines (свечи)
     * @param symbol Валютная пара (например, BTCUSDT)
     * @param interval временной промежуток
     * @param limit кол-во
     */
    public List<double[]> getKlines(String symbol, String interval, int limit) {
        try {
            String url = baseUrl + "/api/v3/klines?symbol=" + symbol + "&interval=" + interval + "&limit=" + limit;
            JsonNode root = mapper.readTree(restTemplate.getForObject(url, String.class));
            List<double[]> klines = new ArrayList<>();
            for (JsonNode candle : root) {
                klines.add(new double[]{
                        candle.get(1).asDouble(), // open
                        candle.get(2).asDouble(), // high
                        candle.get(3).asDouble(), // low
                        candle.get(4).asDouble(), // close
                        candle.get(5).asDouble()  // volume
                });
            }
            return klines;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * Получает шаг лота (stepSize) для символа, чтобы правильно округлить количество.
     * @param symbol Валютная пара (например, BTCUSDT)
     */
    public double getStepSize(String symbol) {
        try {
            String url = baseUrl + "/api/v3/exchangeInfo?symbol=" + symbol;
            JsonNode root = restTemplate.getForObject(url, JsonNode.class);
            JsonNode symbolNode = root.get("symbols").get(0);
            for (JsonNode filter : symbolNode.get("filters")) {
                if (filter.get("filterType").asText().equals("LOT_SIZE")) {
                    return filter.get("stepSize").asDouble();
                }
            }
        } catch (Exception e) {
            System.err.println("Ошибка получения stepSize для " + symbol + ": " + e.getMessage());
        }
        return 0.000001;
    }

    /**
     * Получение актуальной комиссии для конкретной торговой пары.
     * Позволяет точно рассчитать чистую прибыль с учетом VIP-уровня и скидок.
     * @param symbol Валютная пара (например, BTCUSDT).
     * @return double[] где [0] - комиссия мейкера, [1] - комиссия тейкера (в десятичном виде, т.е. 0.001 = 0.1%).
     */
    public double[] getTradeFee(String symbol) {
        if (baseUrl.contains("testnet")) {
            return new double[]{0.001, 0.001};
        }

        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("symbol", symbol);
            JsonNode response = signedRequest("/sapi/v1/asset/tradeFee", HttpMethod.GET, params, JsonNode.class);

            if (response != null && response.isArray() && !response.isEmpty()) {
                JsonNode feeNode = response.get(0);
                return new double[]{
                        feeNode.get("makerCommission").asDouble(),
                        feeNode.get("takerCommission").asDouble()
                };
            }
        } catch (Exception e) {
            System.err.println("⚠️ Не удалось получить TradeFee (возможно, пара недоступна): " + e.getMessage());
        }
        // Fallback значения
        return new double[]{0.001, 0.001};
    }

    /**
     * Получение PNL за все время на основе истории сделок.
     * Метод агрегирует все исполненные ордера (реализованная прибыль).
     * @param symbol Валютная пара для анализа.
     * @return double Суммарный реализованный профит/убыток в USDT.
     */
    public double getAllTimePnl(String symbol) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol", symbol);

        JsonNode trades = signedRequest("/api/v3/myTrades", HttpMethod.GET, params, JsonNode.class);

        double totalPnl = 0.0;
        if (trades != null && trades.isArray()) {
            for (JsonNode trade : trades) {
                double price = trade.get("price").asDouble();
                double qty = trade.get("qty").asDouble();
                double commission = trade.get("commission").asDouble();
                String side = trade.get("isBuyer").asBoolean() ? "BUY" : "SELL";

                if ("BUY".equals(side)) {
                    totalPnl -= (price * qty);
                } else {
                    totalPnl += (price * qty);
                }

                if ("USDT".equals(trade.get("commissionAsset").asText())) {
                    totalPnl -= commission;
                }
            }
        }
        return totalPnl;
    }

    /**
     * Получение дневного PNL (изменения баланса) через снимки аккаунта (Account Snapshot).
     * Метод сравнивает общую оценку аккаунта в USDT за последние 2 дня.
     * * @return double Значение PNL в USDT за последние 24 часа.
     */
    public double getDailyPnl() {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("type", "SPOT");
        params.add("limit", "5");

        JsonNode response = signedRequest("/sapi/v1/accountSnapshot", HttpMethod.GET, params, JsonNode.class);

        if (response != null && response.has("snapshotVos") && response.get("snapshotVos").size() >= 2) {
            JsonNode snapshots = response.get("snapshotVos");
            int lastIdx = snapshots.size() - 1;

            double currentTotalBtc = snapshots.get(lastIdx).get("data").get("totalAssetOfBtc").asDouble();
            double yesterdayTotalBtc = snapshots.get(lastIdx - 1).get("data").get("totalAssetOfBtc").asDouble();
            double btcPrice = getCurrentPrice("BTCUSDT");

            return (currentTotalBtc - yesterdayTotalBtc) * btcPrice;
        }
        return 0.0;
    }

    /**
     * Покупка на рынке (MARKET BUY)
     * @param symbol Валютная пара (например, BTCUSDT)
     * @param quantity Количество монет для продажи
     */
    public String placeMarketBuy(String symbol, double quantity) {
        double stepSize = getStepSize(symbol);
        double formattedQuantity = FormatUtil.roundToStep(quantity, stepSize);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol", symbol);
        params.add("side", "BUY");
        params.add("type", "MARKET");
        params.add("quantity", String.format(java.util.Locale.US, "%.8f", formattedQuantity));

        JsonNode response = signedRequest("/api/v3/order", HttpMethod.POST, params, JsonNode.class);

        if (response != null && response.has("orderId")) {
            return response.get("orderId").asText();
        }
        return null;
    }

    /**
     * Продажа на рынке (MARKET SELL)
     * @param symbol Валютная пара (например, BTCUSDT)
     * @param quantity Количество монет для продажи
     */
    public String placeMarketSell(String symbol, double quantity) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol", symbol);
        params.add("side", "SELL");
        params.add("type", "MARKET");
        params.add("quantity", String.format(java.util.Locale.US, "%.8f", quantity));

        JsonNode response = signedRequest("/api/v3/order", HttpMethod.POST, params, JsonNode.class);

        if (response != null && response.has("orderId")) {
            return response.get("orderId").asText();
        }
        return null;
    }

    /**
     * Стоп-Лосс Лимитный ордер (защита от падения).
     * @param symbol Валютная пара (например, BTCUSDT)
     * @param quantity Количество монет для продажи
     * @param stopPrice Цена активации (триггер)
     * @param limitPrice Цена, по которой ордер пойдет в стакан после активации (обычно чуть ниже stopPrice)
     */
    public String placeStopLossLimit(String symbol, double quantity, double stopPrice, double limitPrice, String side) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol", symbol);
        params.add("side", side);
        params.add("type", "STOP_LOSS_LIMIT");
        params.add("timeInForce", "GTC");
        params.add("quantity", FormatUtil.formatValue(quantity));
        params.add("stopPrice", FormatUtil.formatValue(stopPrice));
        params.add("price", FormatUtil.formatValue(limitPrice));

        JsonNode response = signedRequest("/api/v3/order", HttpMethod.POST, params, JsonNode.class);
        return ExtractUtil.extractOrderId(response);
    }

    /**
     * Тейк-Профит Лимитный ордер (фиксация прибыли).
     * @param symbol Валютная пара
     * @param quantity Количество
     * @param stopPrice Цена активации (триггер)
     * @param limitPrice Цена исполнения (обычно равна или чуть выше stopPrice)
     */
    public String placeTakeProfitLimit(String symbol, double quantity, double stopPrice, double limitPrice) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol", symbol);
        params.add("side", "SELL");
        params.add("type", "TAKE_PROFIT_LIMIT");
        params.add("timeInForce", "GTC");
        params.add("quantity", FormatUtil.formatValue(quantity));
        params.add("stopPrice", FormatUtil.formatValue(stopPrice));
        params.add("price", FormatUtil.formatValue(limitPrice));

        JsonNode response = signedRequest("/api/v3/order", HttpMethod.POST, params, JsonNode.class);
        return ExtractUtil.extractOrderId(response);
    }

    /**
     * OCO Ордер (One-Cancels-the-Other).
     * Выставляется одновременно Тейк-профит (лимитный) и Стоп-лосс.
     * Если срабатывает один, второй отменяется автоматически.
     *
     * @param symbol Валютная пара
     * @param quantity Количество
     * @param takeProfitPrice Цена, по которой мы хотим продать с прибылью (Limit Maker)
     * @param stopLossTrigger Цена, при которой активируется стоп-лосс
     * @param stopLossLimit Цена, по которой стоп-лосс будет продан (защита от проскальзывания)
     */
    public String placeOCOOrder(String symbol, double quantity, double takeProfitPrice, double stopLossTrigger, double stopLossLimit) {
        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("symbol", symbol);
        params.add("side", "SELL");
        params.add("quantity", FormatUtil.formatValue(quantity));

        // Take Profit (Limit Maker)
        params.add("price", FormatUtil.formatValue(takeProfitPrice));

        // Stop Loss (Limit Maker)
        params.add("stopPrice", FormatUtil.formatValue(stopLossTrigger));
        params.add("stopLimitPrice", FormatUtil.formatValue(stopLossLimit));
        params.add("stopLimitTimeInForce", "GTC");

        // Endpoint (/v3/order/oco)
        JsonNode response = signedRequest("/api/v3/order/oco", HttpMethod.POST, params, JsonNode.class);

        // OCO orderListId
        if (response != null && response.has("orderListId")) {
            return response.get("orderListId").asText();
        }
        return null;
    }

    /**
     * Отменить все открытые ордера по символу
     * @param symbol Валютная пара
     */
    public void cancelAllOrders(String symbol) {
        try {
            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("symbol", symbol);
            signedRequest("/api/v3/openOrders", HttpMethod.DELETE, params, String.class);
        } catch (Exception e) {
            // Игнорируем -2011 — ордеров просто нет
            if (e.getMessage() != null && e.getMessage().contains("-2011")) {
                logger.debug("Нет открытых ордеров для {} (нормально)", symbol);
            } else {
                logger.warn("Ошибка отмены ордеров {}: {}", symbol, e.getMessage());
            }
        }
    }
}