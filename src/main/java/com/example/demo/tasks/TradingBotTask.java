package com.example.demo.tasks;

import com.example.demo.DemoTradingBot;
import com.example.demo.data.BotSettings;
import com.example.demo.data.Trade;
import com.example.demo.intarfaces.BotSettingsRepository;
import com.example.demo.intarfaces.TradeRepository;
import com.example.demo.services.TradeService;
import com.example.demo.services.app.BinanceAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class TradingBotTask {

    @Autowired
    private TradeRepository tradeRepository;
    @Autowired private BotSettingsRepository settingsRepository;
    @Autowired
    private BinanceAPI priceService;
    @Autowired
    private TradeService tradeService;

    private final double initialBalance = 1000.0;
    private final int MAX_OPEN_TRADES = 5;
    private double balance;

    @Scheduled(fixedRate = 2000)
    public void executeTradeLogic() {
        System.out.println("DEBUG: Цикл обновления запущен: " + LocalDateTime.now());

        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS")
                .orElseGet(() -> new BotSettings(initialBalance));

        this.balance = settings.getBalance();
        List<String> dynamicSymbols = Arrays.asList(settings.getAssets().split(","));

        // Выносим тяжелые запросы к БД за пределы цикла
        List<Trade> openTrades = tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .collect(Collectors.toList());

        for (String symbol : dynamicSymbols) {
            String trimmedSymbol = symbol.trim(); // Создаем новую переменную
            if (trimmedSymbol.isEmpty() || tradeService.isCoolDown(trimmedSymbol)) continue;

            double currentPrice = priceService.getCurrentPrice(trimmedSymbol);
            if (currentPrice <= 0) continue;

            // Теперь используем trimmedSymbol — она не меняется, и компилятор будет доволен
            Optional<Trade> openTradeOpt = openTrades.stream()
                    .filter(t -> t.getAsset().equals(trimmedSymbol))
                    .findFirst();

            // ШАГ 2: Если сделка открыта — проверяем закрытие
            if (openTradeOpt.isPresent()) {
                tradeService.handleOpenTradeLogic(openTradeOpt.get(), currentPrice);
            }
            // ШАГ 3: Если сделки нет — проверяем условия входа (ТОЛЬКО ЕСЛИ ЕСТЬ ЛИМИТЫ)
            else if (openTrades.size() < MAX_OPEN_TRADES) {
                tradeService.checkEntryConditions(symbol, currentPrice, settings, openTrades.size());
            }
        }
    }
}
