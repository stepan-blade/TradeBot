package com.example.demo;

import com.example.demo.data.BalanceHistory;
import com.example.demo.data.BotSettings;

import com.example.demo.intarfaces.BalanceHistoryRepository;
import com.example.demo.intarfaces.BotSettingsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class DemoTradingBot {

    @Autowired private BotSettingsRepository settingsRepository;
    @Autowired
    private BalanceHistoryRepository balanceHistoryRepository;

    private final List<Double> balanceHistory = new ArrayList<>();
    private double balance;

    @PostConstruct
    public void init() {
        BotSettings settings = settingsRepository.findById("MAIN_SETTINGS").orElse(null);
        if (settings == null) {
            balance = 1000.0;
            BotSettings newSettings = new BotSettings(balance);
            newSettings.setAssets("BTCUSDT,ETHUSDT,SOLUSDT,BNBUSDT");
            newSettings.setTradePercent(10.0);
            settingsRepository.save(newSettings);
            balanceHistoryRepository.save(new BalanceHistory(balance, LocalDateTime.now()));
        } else {
            balance = settings.getBalance();
        }
        balanceHistory.add(balance);
    }

    public double getBalance() {
        return  balance;
    }
}