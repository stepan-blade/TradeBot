package com.example.demo.services.telegram;

import com.example.demo.data.Trade;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.api.TelegramAPI;
import com.example.demo.services.trade.CalculatorService;
import com.example.demo.services.trade.TradeService;
import com.example.demo.utils.FormatUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BotCurrentStatus {

    private final TelegramAPI telegramAPI;
    private final BinanceAPI binanceAPI;
    private final TradeService tradeService;
    private final CalculatorService calculatorService;
    private final TradeRepository tradeRepository;

    @Autowired
    public BotCurrentStatus(TelegramAPI telegramAPI, BinanceAPI binanceAPI, TradeService tradeService, TradeRepository tradeRepository, CalculatorService calculatorService) {
        this.telegramAPI = telegramAPI;
        this.binanceAPI = binanceAPI;
        this.tradeService = tradeService;
        this.tradeRepository = tradeRepository;
        this.calculatorService = calculatorService;
    }

    public void sendStatus() {
        List<Trade> openTrades = tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("📊 ТЕКУЩИЙ СТАТУС\n");
        sb.append("💰 Баланс: ").append(String.format("%.6f", tradeService.getBalance())).append(" USDT\n");
        sb.append("🔄 В обороте: ").append(String.format("%.2f", calculatorService.getOccupiedBalance())).append(" USDT\n");
        sb.append("📈 Текущий Pnl: ").append(calculatorService.getAllProfitPercent()).append("%\n");
        sb.append("📊 Нереализ. PnL: ").append(String.format("%.2f", calculatorService.getUnrealizedPnLUsdt())).append(" USDT\n\n");

        if (openTrades.isEmpty()) {
            sb.append("🔎 Открытых сделок нет");
        } else {
            sb.append("🚀 ОТКРЫТЫЕ ПОЗИЦИИ:\n\n");
            for (Trade trade : openTrades) {
                double currentPrice = binanceAPI.getCurrentPrice(trade.getAsset());
                double pnlPercent = calculatorService.getActiveProfitPercent(trade, currentPrice); // грязный
                double pnlUsdt = trade.getVolume() * (pnlPercent / 100.0);

                String pnlIcon = pnlPercent >= 0 ? "🟢" : "🔴";
                sb.append("🔸 ").append(FormatUtil.formatSymbol(trade.getAsset())).append(" | ").append(trade.getType()).append("\n");
                sb.append("   📥 Вход: ").append(String.format("%.8f", trade.getEntryPrice())).append("\n");
                sb.append("   🕒 Цена: ").append(String.format("%.8f", currentPrice)).append("\n");
                sb.append("   🛡️ SL: ").append(String.format("%.8f", trade.getStopLoss())).append("\n");
                sb.append("   ").append(pnlIcon).append(" PnL: ").append(String.format("%.2f", pnlPercent)).append("% (")
                        .append(pnlUsdt >= 0 ? "+" : "").append(String.format("%.2f", pnlUsdt)).append(" USDT)\n\n");
            }
        }
        telegramAPI.sendMessage(sb.toString());
    }
}