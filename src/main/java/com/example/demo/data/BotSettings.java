package com.example.demo.data;

import javax.persistence.*;

@Entity
@Table(name = "bot_settings")
public class BotSettings {
    @Id
    private String id = "MAIN_SETTINGS";
    private double balance;

    @Column(length = 1000)
    private String assets = "BTCUSDT,ETHUSDT,SOLUSDT";
    private double tradePercent = 10.0;
    public BotSettings() {}
    public BotSettings(double balance) {
        this.balance = balance;
    }

    // --- ГЕТТЕРЫ ---
    public double getTradePercent() { return tradePercent; }
    public String getAssets() { return assets; }
    public double getBalance() { return balance; }

    // ---  СЕТТЕРЫ ---
    public void setBalance(double balance) { this.balance = balance; }
    public void setAssets(String assets) { this.assets = assets; }
    public void setTradePercent(double tradePercent) { this.tradePercent = tradePercent; }
}