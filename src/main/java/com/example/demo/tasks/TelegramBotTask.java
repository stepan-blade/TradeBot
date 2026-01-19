package com.example.demo.tasks;

import com.example.demo.services.telegram.TelegramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TelegramBotTask {

    @Autowired
    private TelegramService telegramService;

    @Scheduled(fixedDelay = 2000)
    public void telegramLoop() {
        try {
            telegramService.handleTelegramCommands();
        } catch (Exception e) {
            System.err.println(">>> [ERROR] Ошибка в цикле: " + e.getMessage());
        }
    }
}
