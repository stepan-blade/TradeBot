package com.example.demo.data;

import javax.persistence.*;

@Entity
@Table(name = "trades")
public class Trade {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id; //ID сделки (№)
    private String asset; //Тип актива (валютная пара)
    private double quantity; //Кол-во монет
    private double volume; // Объем сделки в USDT
    private String entryTime; //Время открытия сделки
    private String exitTime; //Время закрытия сделки
    private double entryPrice; //Цена актива на входе
    private double exitPrice; //Цена актива на выходе
    private Double bestPrice; //Лучшая цена актива за период открытой сделки
    private double profit; //Результат сделки в %
    private String status; // "OPEN" или "CLOSED"
    private Double stopLoss; //Точка для стоп-лосс
    private Double takeProfit; //Точка для тэйк-профит
    private String type; // LONG или SHORT

    public Trade() {
    }

    public Trade( String asset, String entryTime, double entryPrice, String type, double volume, double quantity, Double stopLoss) {
        this.asset = asset;
        this.entryTime = entryTime;
        this.entryPrice = entryPrice;
        this.bestPrice = entryPrice;
        this.type = type;
        this.volume = volume;
        this.quantity = quantity;
        this.status = "OPEN";
        this.profit = 0.0;
        this.exitPrice = 0.0;
        this.takeProfit = 0.0;
        this.stopLoss = stopLoss;
    }

    public Long getId() { return id; }

    public String getAsset() { return asset; }

    public double getQuantity() { return quantity; }

    public double getBestPrice() { return bestPrice == null ? 0.0 : bestPrice; }

    public double getEntryPrice() { return entryPrice; }

    public double getExitPrice() { return exitPrice; }

    public double getProfit() { return profit; }

    public String getStatus() { return status; }

    public double getStopLoss() { return stopLoss == null ? 0.0 : stopLoss; }

    public double getTakeProfit() { return takeProfit == null ? 0.0 : takeProfit; }

    public String getType() { return type; }

    public double getVolume() { return volume; }

    public String getEntryTime() { return entryTime; }

    public String getExitTime() { return exitTime; }



    public void setAsset(String asset) { this.asset = asset; }

    public void setQuantity(double quantity) { this.quantity = quantity; }

    public void setBestPrice(double bestPrice) { this.bestPrice = bestPrice; }

    public void setEntryPrice(double entryPrice) { this.entryPrice = entryPrice; }

    public void setExitPrice(double exitPrice) { this.exitPrice = exitPrice; }

    public void setProfit(double profit) { this.profit = profit; }

    public void setStatus(String status) { this.status = status; }

    public void setStopLoss(double stopLoss) { this.stopLoss = stopLoss; }

    public void setTakeProfit(double takeProfit) { this.takeProfit = takeProfit; }

    public void setType(String type) { this.type = type; }

    public void setVolume(double volume) { this.volume = volume; }

    public void setEntryTime(String time) { this.entryTime = time; }

    public void setExitTime(String exitTime) { this.exitTime = exitTime; }
}