class BinanceBotApp {
    constructor() {
        this.state = {
            fullHistory: [],
            currentView: 'OPEN',
            currentPage: 1,
            itemsPerPage: 10,
            filters: { asset: 'ALL', type: 'ALL', result: 'ALL' }
        };

        this.api = new ApiService();
        this.ui = new UIManager(this);
        this.table = new TradingTable(this);
        this.chart = new ChartManager('balanceChart');

        this.init();
    }

    async init() {
        // Слушатель для меню-бургера
        const menuIcon = document.getElementById('menuIcon');
        if (menuIcon) menuIcon.onclick = () => this.ui.toggleMenu();

        // Слушатель для переключения видов (OPEN/CLOSED)
        const selector = document.getElementById('view-selector');
        if (selector) selector.onchange = (e) => this.changeView(e.target.value);

        await this.updateDashboard();
        setInterval(() => this.updateDashboard(), 2000);
    }

    changeView(val) {
        this.state.currentView = val;
        this.state.filters.asset = 'ALL';
        this.state.filters.type = 'ALL';
        this.state.filters.result = 'ALL';
        document.getElementById('filter-date-start').value = '';
        document.getElementById('filter-date-end').value = '';
        this.state.currentPage = 1;
        this.updateDashboard();
    }

    setFilter(key, value) {
        this.state.filters[key] = value;
        this.state.currentPage = 1;
        this.table.render(this.state.fullHistory);
    }

    // Метод для кнопки "Применить" в календаре
    applyDateFilter() {
        this.state.currentPage = 1;
        this.table.render(this.state.fullHistory);

        const dateDropdownEl = document.getElementById('dateDropdown');
        if (dateDropdownEl) {
            const instance = bootstrap.Dropdown.getInstance(dateDropdownEl.querySelector('[data-bs-toggle="dropdown"]'));
            if (instance) instance.hide();
        }
    }

    resetDateFilter() {
        document.getElementById('filter-date-start').value = '';
        document.getElementById('filter-date-end').value = '';
        this.state.currentPage = 1;
        this.table.render(this.state.fullHistory);
    }

    // ГЛАВНЫЙ МЕТОД UPDATE DASHBOARD
    async updateDashboard() {
        try {
            // 1. Cooldowns
            const cooldowns = await this.api.fetchCooldowns();
            this.ui.renderCooldowns(cooldowns);

            // 2. Основные данные статуса (баланс, профит, история)
            const data = await this.api.fetchStatus();
            this.state.fullHistory = data.history || [];

            // 3. Реальный баланс и метрики
            const currentBalance = Number(data.balance || 0);
            const inTrade = this.state.fullHistory
                .filter(t => t.status === 'OPEN')
                .reduce((sum, t) => sum + (Number(t.volume) || 0), 0).toFixed(2);

            // 4. Профит за сегодня
            const todayProfit = Number(data.todayProfitUSDT || 0).toFixed(2);
            const todayPercent = todayProfit > 0 ? (todayProfit / currentBalance * 100).toFixed(2) : 0;

            // 5. Общий прирост
            const initialDeposit = data.balance;
            const calculatedPercent = ((currentBalance - initialDeposit) / initialDeposit * 100).toFixed(2);

            // 6. PnL активных сделок (расчёт нереализованной прибыли)
            let totalUnrealizedPnL = 0;
            this.state.fullHistory
                .filter(t => t.status === 'OPEN')
                .forEach(trade => {
                    const currentPrice = trade.exitPrice || trade.entryPrice || 0;
                    if (currentPrice > 0 && trade.entryPrice > 0) {
                        let pnlPercent = ((currentPrice - trade.entryPrice) / trade.entryPrice) * 100;
                        if (trade.type === 'SHORT') pnlPercent *= -1;
                        totalUnrealizedPnL += trade.volume * ((pnlPercent - 0.2) / 100); // -0.2% комиссия
                    }
                });

            // 7. Обновляем UI (передаём все нужные данные)
            this.ui.updateHeaderStats({
                balance: currentBalance,
                inTrade: inTrade,
                todayProfit: todayProfit,
                todayPercent: todayPercent,
                totalPnl: totalUnrealizedPnL.toFixed(2),
                totalPnlPercent: currentBalance > 0 ? (totalUnrealizedPnL / currentBalance * 100).toFixed(2) : 0,
                calculatedPercent: calculatedPercent
            });

            // 8. График доходности (если balanceHistory приходит)
            if (data.balanceHistory && Array.isArray(data.balanceHistory)) {
                this.chart.update(data.balanceHistory);
            }

            // 9. Таблица сделок
            this.table.render(this.state.fullHistory);

        } catch (error) {
            console.error("Ошибка обновления дашборда:", error);
        }
    }

    async manualClose(symbol) {
        try {
            const data = await this.api.fetchPreviewClose(symbol);

            const profitText = data.profitUsdt >= 0 ? `+${data.profitUsdt}` : `${data.profitUsdt}`;
            const emoji = data.profitUsdt >= 0 ? "✅" : "⚠️";

            const confirmMsg = `Закрыть позицию ${symbol}?\n\n` +
                `Текущая цена: ${data.currentPrice}\n` +
                `Ожидаемый профит: ${profitText} $ (${data.percent}%)\n` +
                `С учетом комиссии 0.2% ${emoji}`;

            if (confirm(confirmMsg)) {
                await this.api.postCloseTrade(symbol);
                this.updateDashboard();
            }
        } catch (error) {
            console.error("Ошибка превью:", error);

            if (confirm(`Вы уверены, что хотите закрыть ${symbol} вручную?`)) {
                await this.api.postCloseTrade(symbol);
                this.updateDashboard();
            }
        }
    }
}

window.app = new BinanceBotApp();