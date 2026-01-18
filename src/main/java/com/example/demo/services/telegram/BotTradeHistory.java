package com.example.demo.services.telegram;

import com.example.demo.data.Trade;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.TelegramAPI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BotTradeHistory {

    private final TradeRepository tradeRepository;
    private final TelegramAPI telegramAPI;

    @Autowired
    public BotTradeHistory(TradeRepository tradeRepository, TelegramAPI telegramAPI) {
        this.tradeRepository = tradeRepository;
        this.telegramAPI = telegramAPI;
    }

    public void sendClearTradeHistory() {
        List<Trade> closed = tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .collect(Collectors.toList());
        if (!closed.isEmpty()) {
            tradeRepository.deleteAll(closed);
            telegramAPI.sendMessage("🧹 История очищена.");
        } else {
            telegramAPI.sendMessage("История сделок отсутствует.");
        }
    }
}
