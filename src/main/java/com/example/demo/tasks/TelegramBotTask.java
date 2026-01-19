package com.example.demo.tasks;

import com.example.demo.services.telegram.TelegramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TelegramBotTask {

    private final TelegramService telegramService;

    @Autowired
    public TelegramBotTask(TelegramService telegramService) {
        this.telegramService = telegramService;
    }

    @Scheduled(fixedDelay = 2000)
    public void telegramLoop() {
        try {
            telegramService.handleTelegramCommands();
        } catch (Exception e) {
            System.err.println(">>> [TELEGRAM ERROR] Ошибка в цикле: " + e.getMessage());
        }
    }
}
