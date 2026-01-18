package com.example.demo.tasks;

import com.example.demo.services.telegram.TelegramService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TelegramBotTask {

    @Autowired
    private TelegramService telegramService;

    @Scheduled(fixedRate = 2000)
    public void telegramLoop() {
        telegramService.handleTelegramCommands();
    }
}
