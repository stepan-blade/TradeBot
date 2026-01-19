package com.example.demo.services.telegram;

import com.example.demo.data.Trade;
import com.example.demo.interfaces.TradeRepository;
import com.example.demo.services.api.BinanceAPI;
import com.example.demo.services.api.TelegramAPI;
import com.example.demo.services.trade.TradeService;
import com.example.demo.utils.FormatterUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class BotCurrentStatus {

    private final TelegramAPI telegramAPI;
    private final BinanceAPI binanceAPI;
    private final TradeService tradeService;
    private final TradeRepository tradeRepository;

    @Autowired
    public BotCurrentStatus(TelegramAPI telegramAPI, BinanceAPI binanceAPI, TradeService tradeService, TradeRepository tradeRepository) {
        this.telegramAPI = telegramAPI;
        this.binanceAPI = binanceAPI;
        this.tradeService = tradeService;
        this.tradeRepository = tradeRepository;
    }

    public void sendStatus() {
        List<Trade> openTrades = tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .collect(Collectors.toList());

        StringBuilder sb = new StringBuilder();
        sb.append("📊 ТЕКУЩИЙ СТАТУС\n");
        sb.append("💰 Баланс: ").append(String.format("%.6f", tradeService.getBalance())).append(" USDT\n");
        sb.append("🔄 В обороте: ").append(String.format("%.2f", tradeService.getOccupiedBalance())).append(" USDT\n");
        sb.append("📈 Рост: ").append(tradeService.calculateAllProfitPercent()).append("%\n\n");

        if (openTrades.isEmpty()) {
            sb.append("🔎 Открытых сделок нет");
        } else {
            sb.append("🚀 ОТКРЫТЫЕ ПОЗИЦИИ:\n\n");
            for (Trade dataTrade : openTrades) {
                double currentPrice = binanceAPI.getCurrentPrice(dataTrade.getAsset());

                // 1. Считаем PnL в процентах (у вас уже есть)
                double pnl = ((currentPrice - dataTrade.getEntryPrice()) / dataTrade.getEntryPrice()) * 100;
                if ("SHORT".equals(dataTrade.getType())) pnl *= -1;

                double distToSL = ((currentPrice - dataTrade.getStopLoss()) / currentPrice) * 100;
                if ("SHORT".equals(dataTrade.getType())) distToSL *= -1;

                // 2. Считаем PnL в USDT (Добавляем эту строку)
                double pnlUsdt = dataTrade.getVolume() * (pnl / 100.0);

                // 3. Формируем строку для вывода
                String pnlIcon = (pnl > 0) ? "🟢" : "🔴";
                String pnlUsdtFormatted = (pnlUsdt > 0 ? "+" : "") + String.format("%.2f", pnlUsdt); // Добавит "+" если прибыль

                sb.append("🔸 ").append(FormatterUtil.formatSymbol(dataTrade.getAsset())).append(" | ").append(dataTrade.getType()).append("\n");
                sb.append("   📥 Вход: ").append(String.format("%.8f", dataTrade.getEntryPrice())).append("\n");
                sb.append("   🕒 Цена: ").append(String.format("%.8f", currentPrice)).append("\n");
                sb.append("   🛡️ SL: ").append(String.format("%.8f", dataTrade.getStopLoss())).append(" (").append(String.format("%.2f", Math.abs(distToSL))).append("%)\n");
                sb.append("   ").append(pnlIcon).append(" PnL: ").append(String.format("%.2f", pnl)).append("% (").append(pnlUsdtFormatted).append(" USDT)\n\n");
            }
        }
        telegramAPI.sendMessage(sb.toString());
    }
}