package com.example.demo.controllers;

import com.example.demo.data.BotSettings;
import com.example.demo.DemoTradingBot;
import com.example.demo.data.Trade;
import com.example.demo.intarfaces.BalanceHistoryRepository;
import com.example.demo.intarfaces.BotSettingsRepository;
import com.example.demo.intarfaces.TradeRepository;
import com.example.demo.services.TradeService;
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

    private final DemoTradingBot bot;
    @Autowired
    private TradeRepository tradeRepository;
    @Autowired
    private BotSettingsRepository settingsRepository;
    @Autowired
    private BalanceHistoryRepository balanceHistoryRepository;
    @Autowired
    private TradeService tradeService;

    public TradeController(DemoTradingBot bot) {
        this.bot = bot;
    }

    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();

        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS")
                .orElse(new BotSettings(1000.0));

        // 1. Получаем все сделки
        List<Trade> allTrades = tradeRepository.findAll();

        // 2. ОБНОВЛЯЕМ ТЕКУЩИЕ ЦЕНЫ ДЛЯ ОТКРЫТЫХ СДЕЛОК
        for (Trade trade : allTrades) {
            if ("OPEN".equals(trade.getStatus())) {
                double currentPrice = tradeService.getCurrentPrice(trade.getAsset());
                if (currentPrice > 0) {
                    trade.setExitPrice(currentPrice);
                }
            }
        }

        status.put("balance", settings.getBalance());
        status.put("profitPercent", tradeService.calculateProfitPercent());
        status.put("history", allTrades);
        status.put("todayProfitUSDT", tradeService.getTodayProfitUsdt());
        status.put("balanceHistory", balanceHistoryRepository.findAll());

        return status;
    }

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

    @PostMapping("/close-trade")
    public ResponseEntity<String> closeSpecificTrade(@RequestParam String symbol) {
        Optional<Trade> tradeOpt = tradeService.getActiveTrades().stream()
                .filter(t -> t.getAsset().equals(symbol))
                .findFirst();

        if (tradeOpt.isPresent()) {
            double price = tradeService.getCurrentPrice(symbol);
            if (price > 0) {
                tradeService.closePosition(tradeOpt.get(), price, "Manual Close ⚡");
                return ResponseEntity.ok("Trade closed");
            } else {
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body("Could not get current price");
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Trade not found");
    }

    @GetMapping("/settings")
    public ResponseEntity<BotSettings> getSettings() {
        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS")
                .orElse(new BotSettings());
        return ResponseEntity.ok(settings);
    }

    @PostMapping("/save-settings")
    public ResponseEntity<?> saveSettings(
            @RequestParam("assets") String assets,
            @RequestParam("trade_percent") String tradePercentStr) {
        try {
            double tradePercent = Double.parseDouble(tradePercentStr);

            BotSettings settings = settingsRepository.findById("MAIN_SETTINGS")
                    .orElse(new BotSettings());

            settings.setAssets(assets);
            settings.setTradePercent(tradePercent);
            settingsRepository.save(settings);

            System.out.println("✅ Настройки успешно обновлены: " + tradePercent + "%");
            return ResponseEntity.ok().body(Map.of("status", "success"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("Ошибка: " + e.getMessage());
        }
    }

    @GetMapping("/preview-close")
    public ResponseEntity<Map<String, Object>> previewClose(@RequestParam String symbol) {
        Optional<Trade> tradeOpt = tradeRepository.findAll().stream()
                .filter(t -> t.getAsset().equals(symbol) && "OPEN".equals(t.getStatus()))
                .findFirst();

        if (tradeOpt.isPresent()) {
            Trade trade = tradeOpt.get();
            double currentPrice = tradeService.getCurrentPrice(symbol);

            double diff = ((currentPrice - trade.getEntryPrice()) / trade.getEntryPrice()) * 100;
            if ("SHORT".equals(trade.getType())) diff *= -1;

            double netProfitPercent = diff - 0.2;
            double profitUsdt = trade.getVolume() * (netProfitPercent / 100);

            Map<String, Object> response = new HashMap<>();
            response.put("profitUsdt", Math.round(profitUsdt * 100.0) / 100.0);
            response.put("percent", Math.round(netProfitPercent * 100.0) / 100.0);
            response.put("currentPrice", currentPrice);

            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }
}