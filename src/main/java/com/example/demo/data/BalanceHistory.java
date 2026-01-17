package com.example.demo.data;

import javax.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "balance_history")
public class BalanceHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Double balance;
    private LocalDateTime timestamp;

    public BalanceHistory() {}

    public BalanceHistory(Double balance, LocalDateTime timestamp) {
        this.balance = balance;
        this.timestamp = timestamp;
    }

    // --- ГЕТТЕРЫ ---
    public Long getId() { return id; }
    public Double getBalance() { return balance; }

    // ---  СЕТТЕРЫ ---
    public void setBalance(Double balance) { this.balance = balance; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}