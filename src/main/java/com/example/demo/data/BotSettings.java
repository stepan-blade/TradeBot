package com.example.demo.data;

import javax.persistence.*;

@Entity
@Table(name = "bot_settings")
public class BotSettings {
    @Id
    private String id = "MAIN_SETTINGS";
    private String status = "ONLINE";
    private double balance;

    @Column(length = 1000)
    private String assets = "BTCUSDT,ETHUSDT,SOLUSDT";
    private double tradePercent = 10.0;
    private int maxOpenTrades = 1;

    public BotSettings() {}

    public double getTradePercent() { return tradePercent; }
    public String getAssets() { return assets; }
    public double getBalance() { return balance; }
    public String getStatus() { return status; }
    public int getMaxOpenTrades() { return maxOpenTrades; }

    public void setBalance(double balance) { this.balance = balance; }
    public void setAssets(String assets) { this.assets = assets; }
    public void setTradePercent(double tradePercent) { this.tradePercent = tradePercent; }
    public void setStatus(String status) { this.status = status; }
    public void setMaxOpenTrades(int maxOpenTrades) { this.maxOpenTrades = maxOpenTrades; }
}