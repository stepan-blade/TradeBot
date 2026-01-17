package com.example.demo.intarfaces;

import com.example.demo.data.BotSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BotSettingsRepository extends JpaRepository<BotSettings, String> {
}