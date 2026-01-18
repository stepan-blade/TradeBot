package com.example.demo.tasks;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TradingBotTask {

    @Scheduled(fixedRate = 2000)
    public void executeTradeLogic() {
        //TODO:
    }
}