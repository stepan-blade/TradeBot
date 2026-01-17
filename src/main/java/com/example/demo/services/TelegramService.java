package com.example.demo.services;

import com.example.demo.DemoTradingBot;
import com.example.demo.data.Trade;
import com.example.demo.intarfaces.TradeRepository;
import com.example.demo.services.app.BinanceAPI;
import com.example.demo.services.app.TelegramAPI;
import com.example.demo.utils.Formatter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class TelegramService {

    @Autowired
    private TelegramAPI telegramAPI;
    @Autowired
    private BinanceAPI binanceAPI;
    @Autowired
    private TradeService tradeService;
    @Autowired
    private DemoTradingBot demoTradingBot;
    @Autowired private TradeRepository tradeRepository;

    public void handleTelegramCommands() {
        String msg = telegramAPI.getLatestMessage();
        String callbackData = telegramAPI.getLatestCallbackData();

        if (callbackData != null) {
            handleCallback(callbackData);
            return;
        }

        if (msg == null) return;

        if (msg.startsWith("/status")) sendCurrentStatus();
        if (msg.startsWith("/close")) sendCloseTradeSelection();
        if (msg.startsWith("/closeall")) tradeService.closeAllPositionsManually();
        if (msg.startsWith("/clearhistory")) clearHistory();
    }

    private void sendCurrentStatus() {
        List<Trade> openTrades = tradeRepository.findAll().stream()
                .filter(t -> "OPEN".equals(t.getStatus()))
                .collect(Collectors.toList());

        double inTrade = openTrades.stream().mapToDouble(Trade::getVolume).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("📊 ТЕКУЩИЙ СТАТУС\n");
        sb.append("💰 Баланс: ").append(String.format("%.2f", demoTradingBot.getBalance())).append(" USDT\n");
        sb.append("🔄 В обороте: ").append(String.format("%.2f", inTrade)).append(" USDT\n");
        sb.append("📈 Рост: ").append(tradeService.calculateProfitPercent()).append("%\n\n");

        if (openTrades.isEmpty()) {
            sb.append("🔎 Открытых сделок нет");
        } else {
            sb.append("🚀 ОТКРЫТЫЕ ПОЗИЦИИ:\n\n");
            for (Trade dataTrade : openTrades) {
                double currentPrice = binanceAPI.getCurrentPrice(dataTrade.getAsset());

                // Расчет PnL
                double pnl = ((currentPrice - dataTrade.getEntryPrice()) / dataTrade.getEntryPrice()) * 100;
                if ("SHORT".equals(dataTrade.getType())) pnl *= -1;

                // Расчет расстояния до Стоп-Лосса
                double distToSL = ((currentPrice - dataTrade.getStopLoss()) / currentPrice) * 100;
                if ("SHORT".equals(dataTrade.getType())) distToSL *= -1;

                String pnlIcon = (pnl > 0) ? "🟢" : "🔴";

                sb.append("🔸 ").append(Formatter.formatSymbol(dataTrade.getAsset())).append(" | ").append(dataTrade.getType()).append("\n");
                sb.append("   📥 Вход: ").append(String.format("%.8f", dataTrade.getEntryPrice())).append("\n");
                sb.append("   🕒 Цена: ").append(String.format("%.8f", currentPrice)).append("\n");
                sb.append("   🛡️ SL: ").append(String.format("%.8f", dataTrade.getStopLoss())).append(" (").append(String.format("%.2f", Math.abs(distToSL))).append("%)\n");
                sb.append("   ").append(pnlIcon).append(" PnL: ").append(String.format("%.2f", pnl)).append("% (").append(String.format("%.2f", dataTrade.getVolume())).append(" USDT)\n\n");
            }
        }
        telegramAPI.sendMessage(sb.toString());
    }

    private void clearHistory() {
        List<Trade> closed = tradeRepository.findAll().stream()
                .filter(t -> "CLOSED".equals(t.getStatus()))
                .collect(Collectors.toList());
        tradeRepository.deleteAll(closed);
        telegramAPI.sendMessage("🧹 История очищена.");
    }

    private void sendCloseTradeSelection() {
        List<Trade> openTrades = tradeService.getActiveTrades();
        if (openTrades.isEmpty()) {
            telegramAPI.sendMessage("Нет активных сделок для закрытия.");
            return;
        }

        for (Trade t : openTrades) {
            double currentPrice = binanceAPI.getCurrentPrice(t.getAsset());
            double pnl = tradeService.calculateNetProfitPercent(t, currentPrice);
            String pnlIcon = pnl >= 0 ? "🟢" : "🔴";

            String text = String.format(
                    "📝 Сделка: %s (%s)\n" +
                            "💰 Объем: %.2f USDT\n" +
                            "📊 PnL: %s %.2f%%",
                    Formatter.formatSymbol(t.getAsset()), t.getType(), t.getVolume(), pnlIcon, pnl
            );

            telegramAPI.sendMessageWithInlineButton(text, "Завершить " + t.getAsset(), "confirm_close:" + t.getAsset());
        }
    }

    private void handleCallback(String data) {
        if (data.startsWith("confirm_close:")) {
            String symbol = data.split(":")[1];
            Trade trade = tradeRepository.findAll().stream()
                    .filter(t -> t.getAsset().equals(symbol) && "OPEN".equals(t.getStatus()))
                    .findFirst().orElse(null);

            if (trade != null) {
                telegramAPI.clearActiveMenus();

                double price = binanceAPI.getCurrentPrice(symbol);
                double netProfitPercent = tradeService.calculateNetProfitPercent(trade, price);
                double profitUsdt = trade.getVolume() * (netProfitPercent / 100);

                String text = String.format(
                        "Вы уверены?\n\n" +
                                "Сделка: %s\n" +
                                "Итого с учетом комиссии: %.2f USDT",
                        symbol, (trade.getVolume() + profitUsdt)
                );

                telegramAPI.sendConfirmationButtons(text, "✅ Подтвердить", "execute_close:" + symbol, "❌ Оставить", "cancel");
            }
        } else if (data.startsWith("execute_close:")) {
            String symbol = data.split(":")[1];
            telegramAPI.deleteLastMessage();
            tradeService.closeSpecificTradeManually(symbol);
        } else if (data.equals("cancel")) {
            telegramAPI.deleteLastMessage();
            telegramAPI.sendMessage("Действие отменено.");
        }
    }
}
