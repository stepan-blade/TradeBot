package com.example.demo.intarfaces;

import com.example.demo.data.BalanceHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BalanceHistoryRepository extends JpaRepository<BalanceHistory, Long> {
    // Здесь можно будет добавить методы для получения данных за конкретный период
}