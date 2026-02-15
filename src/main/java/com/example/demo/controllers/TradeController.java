package com.example.demo.controllers;

import com.example.demo.data.BotSettings;
import com.example.demo.data.Trade;
import com.example.demo.interfaces.BalanceHistoryRepository;
import com.example.demo.interfaces.BotSettingsRepository;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.trade.CalculatorService;
import com.example.demo.services.trade.TradeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class TradeController {

    /**
     * @see #getStatus() - Формирует сводный отчет о текущем состоянии торговой системы.
     * @see #getCooldowns() - Возвращает список активов, находящихся в режиме "ожидания" (cooldown) после закрытия сделки.
     * @see #clearHistory() - Удаляет все завершенные сделки из базы данных.
     * @see #closeSpecificTrade(String) -  Выполняет принудительное закрытие конкретной активной сделки по запросу пользователя.
     * @see #getSettings() - Извлекает глобальные настройки бота из базы данных.
     * @see #saveSettings(String, String, String, String)   - Обновляет конфигурацию торгового алгоритма.
     * @see #previewClose(String) - Предварительный расчет финансового результата перед закрытием сделки.
     */

    private final BinanceAPI binanceAPI;
    private final TradeService tradeService;
    private final CalculatorService calculatorService;
    private final TradeRepository tradeRepository;
    private final BotSettingsRepository settingsRepository;
    private final BalanceHistoryRepository balanceHistoryRepository;

    @Autowired
    public TradeController(BinanceAPI binanceAPI, TradeService tradeService, TradeRepository tradeRepository, BotSettingsRepository settingsRepository, BalanceHistoryRepository balanceHistoryRepository, CalculatorService calculatorService) {
        this.binanceAPI = binanceAPI;
        this.tradeService = tradeService;
        this.calculatorService = calculatorService;
        this.tradeRepository = tradeRepository;
        this.settingsRepository = settingsRepository;
        this.balanceHistoryRepository = balanceHistoryRepository;
    }

    /**
     * Формирует сводный отчет о текущем состоянии торговой системы.
     * <p>
     * Метод агрегирует данные из разных репозиториев:
     * 1. Получает актуальный баланс через Binance API.
     * 2. Подтягивает историю всех сделок.
     * 3. Для всех ОТКРЫТЫХ позиций динамически обновляет цену последней фиксации (exitPrice),
     * чтобы интерфейс мог отображать актуальную нереализованную прибыль/убыток.
     *
     * @return Map со значениями баланса, общего профита в %, доходности за сегодня в USDT и полной историей.
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS").orElse(null);
        if (settings != null && "OFFLINE".equals(settings.getStatus())) {
            return new HashMap<>(); // Не выполнять запросы, если бот выключен
        }

        Map<String, Object> status = new HashMap<>();

        double balance = tradeService.getBalance();

        List<Trade> allTrades = tradeRepository.findAll();

        for (Trade trade : allTrades) {
            if ("OPEN".equals(trade.getStatus())) {
                double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
                if (currentPrice > 0) trade.setExitPrice(currentPrice);
            }
        }

        status.put("balance", balance);
        status.put("occupiedBalance", calculatorService.getOccupiedBalance());
        status.put("totalEquity", calculatorService.getTotalEquity());

        status.put("todayProfitUSDT", calculatorService.getTodayProfitUSDT());
        status.put("todayProfitPercent", calculatorService.getTodayProfitPercent());

        status.put("allProfitUsdt", calculatorService.getRealizedProfit() + calculatorService.getUnrealizedPnLUsdt());
        status.put("allProfitPercent", calculatorService.getAllProfitPercent());

        status.put("unrealizedPnLUsdt", calculatorService.getUnrealizedPnLUsdt());
        status.put("unrealizedPnLUsdtWithFee", calculatorService.getUnrealizedPnLUsdtWithFee());

        status.put("balanceHistory", balanceHistoryRepository.findAll());
        status.put("history", allTrades);

        return status;
    }

    /**
     * Возвращает список активов, находящихся в режиме "ожидания" (cooldown) после закрытия сделки.
     * <p>
     * Это защитный механизм: бот не заходит в одну и ту же монету сразу после продажи.
     * Метод фильтрует карту cooldownMap в сервисе, оставляя только те активы, время блокировки
     * которых еще не истекло относительно текущего момента.
     *
     * @return Map, где ключ — символ актива (BTCUSDT), а значение — время окончания блокировки (ЧЧ:мм).
     */
    @GetMapping("/cooldowns")
    public Map<String, String> getCooldowns() {
        Map<String, String> formattedMap = new HashMap<>();
        LocalDateTime now = LocalDateTime.now();

        tradeService.getCoolDownMap().forEach((symbol, time) -> {
            if (time.isAfter(now)) {
                formattedMap.put(symbol, time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
            }
        });
        return formattedMap;
    }

    /**
     * Удаляет все завершенные сделки из базы данных.
     * <p>
     * Используется для очистки графиков и списков от старых данных.
     * Метод находит в БД только те записи, статус которых равен "CLOSED",
     * чтобы случайно не удалить активные позиции.
     *
     * @return ResponseEntity с текстовым уведомлением о результате операции.
     */
    @PostMapping("/clear-history")
    public ResponseEntity<String> clearHistory() {
        List<Trade> closedTrades = tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .collect(Collectors.toList());

        if (!closedTrades.isEmpty()) {
            tradeRepository.deleteAll(closedTrades);
            return ResponseEntity.ok("История очищена");
        }
        return ResponseEntity.ok("Нет сделок для удаления");
    }

    /**
     * Выполняет принудительное закрытие конкретной активной сделки по запросу пользователя.
     * <p>
     * 1. Проверяет наличие открытой позиции по указанному символу.
     * 2. Запрашивает текущую рыночную цену.
     * 3. Если цена получена, вызывает бизнес-логику закрытия в TradeService.
     * @param symbol Валютная пара (например, "ETHUSDT").
     * @return Ответ 200 (OK), 404 (не найдено) или 502 (ошибка связи с биржей).
     */
    @PostMapping("/close-trade")
    public ResponseEntity<String> closeSpecificTrade(@RequestParam String symbol) {
        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS").orElse(null);
        if (settings != null && "OFFLINE".equals(settings.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Бот выключен. Запросы к бирже запрещены.");
        }

        Optional<Trade> tradeOpt = tradeService.getActiveTrades().stream()
                .filter(t -> t.getAsset().equals(symbol))
                .findFirst();

        if (tradeOpt.isPresent()) {
            double price = binanceAPI.getCurrentPrice(symbol);
            if (price > 0) {
                tradeService.closePosition(tradeOpt.get(), price, "Manual Close ⚡");
                return ResponseEntity.ok("Trade closed");
            } else {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("Could not get current price");
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Trade not found");
    }

    /**
     * Извлекает глобальные настройки бота из базы данных.
     * <p>
     * Если настройки еще ни разу не сохранялись, возвращает новый объект с
     * параметрами по умолчанию
     *
     * @return Объект BotSettings с данными о торгуемых парах, проценте входа в сделку и статус активности.
     */
    @GetMapping("/settings")
    public ResponseEntity<BotSettings> getSettings() {
        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS")
                .orElse(new BotSettings());
        return ResponseEntity.ok(settings);
    }

    /**
     * Обновляет конфигурацию торгового алгоритма.
     * <p>
     * Позволяет "на лету" изменить список монет, по которым бот ищет сигналы,
     * и объем USDT, выделяемый на каждую сделку (в % от баланса).
     *
     * @param assets          Строка с символами через запятую (напр. "BTCUSDT,ETHUSDT").
     * @param tradePercentStr Процент от свободного баланса для покупки актива.
     * @return JSON со статусом успеха или ошибкой 400, если формат данных неверный.
     */
    @PostMapping("/save-settings")
    public ResponseEntity<?> saveSettings(
            @RequestParam("assets") String assets,
            @RequestParam("trade_percent") String tradePercentStr,
            @RequestParam("status") String status,
            @RequestParam(value = "max_open_trades", defaultValue = "3") String maxOpenStr) {
        try {
            double tradePercent = Double.parseDouble(tradePercentStr);
            int maxOpen = Integer.parseInt(maxOpenStr);

            BotSettings settings = settingsRepository.findById("MAIN_SETTINGS")
                    .orElse(new BotSettings());

            settings.setAssets(assets);
            settings.setTradePercent(tradePercent);
            settings.setStatus(status);
            settings.setMaxOpenTrades(maxOpen);
            settingsRepository.save(settings);

            System.out.println("✅ Настройки успешно обновлены: \n" +
                    "Список доступных для торговли активов: " + assets + "\n" +
                    "Процент торговых сделок от баланса: " + tradePercent + "%\n" +
                    "Максимальное количество сделок единовременно: " + maxOpen);
            return ResponseEntity.ok().body(Map.of("status", "success"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Ошибка: " + e.getMessage());
        }
    }

    /**
     * Предварительный расчет финансового результата перед закрытием сделки.
     * <p>
     * Позволяет пользователю увидеть "грязную" прибыль, чистую прибыль (за вычетом
     * стандартной комиссии 0.08%) и текущую рыночную цену без фактического исполнения ордера.
     * Работает как симуляция закрытия в реальном времени.
     *
     * @param symbol Валютная пара для оценки.
     * @return Объект с потенциальным профитом в USDT и процентах.
     */
    @GetMapping("/preview-close")
    public ResponseEntity<Map<String, Object> > previewClose(@RequestParam String symbol) {
        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS").orElse(null);
        if (settings != null && "OFFLINE".equals(settings.getStatus())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(new HashMap<>());
        }

        Optional<Trade> tradeOpt = tradeRepository.findAll().stream()
                .filter(t -> t.getAsset().equals(symbol) && "OPEN".equals(t.getStatus()))
                .findFirst();

        if (tradeOpt.isPresent()) {
            Trade trade = tradeOpt.get();
            double currentPrice = binanceAPI.getCurrentPrice(symbol);

            double netProfitPercent = calculatorService.getNetResultPercent(
                    trade.getEntryPrice(), currentPrice, symbol, trade.getType()
            );
            double profitUsdt = trade.getVolume() * (netProfitPercent / 100.0);

            double feePercent = calculatorService.getTotalFeePercent(symbol);

            Map<String, Object> response = new HashMap<>();
            response.put("profitUsdt", Math.round(profitUsdt * 100.0) / 100.0);
            response.put("percent", Math.round(netProfitPercent * 100.0) / 100.0);
            response.put("currentPrice", currentPrice);
            response.put("feePercent", feePercent);

            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }
}