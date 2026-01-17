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
        // 0. Кулдауны
        try {
            const cooldowns = await this.api.fetchCooldowns();
            this.ui.renderCooldowns(cooldowns);
        } catch (e) {}

        try {
            // 1. Статус
            const data = await this.api.fetchStatus();
            this.state.fullHistory = data.history || [];

            // 2. Баланс
            const currentBalance = data.balance || 1000.0;
            const initialDeposit = 1000.0;

            // 3. РАСЧЕТ ПРОФИТА ЗА СЕГОДНЯ
            const todayPrefix = new Date().toLocaleDateString('ru-RU');
            const todayProfit = data.todayProfitUSDT || 0;

            const startBalanceToday = currentBalance - todayProfit;
            const todayPercent = startBalanceToday > 0 ? (todayProfit / startBalanceToday) * 100 : 0;

            // 4. РАСЧЕТ PNL
            const openTrades = this.state.fullHistory.filter(t => t.status === 'OPEN');
            let totalUnrealizedPnL = 0;
            const totalInTrade = openTrades.reduce((sum, t) => sum + (t.volume || 0), 0);

            openTrades.forEach(trade => {
                const currentPrice = trade.exitPrice || trade.entryPrice || 0;
                const entryPrice = trade.entryPrice || 0;
                if (entryPrice > 0) {
                    let pnlPercent = ((currentPrice - entryPrice) / entryPrice) * 100;
                    if (trade.type === 'SHORT') pnlPercent *= -1;
                    totalUnrealizedPnL += trade.volume * ((pnlPercent - 0.2) / 100);
                }
            });

            // 5. ОБЩИЙ ПРИРОСТ
            const diffUsdt = currentBalance - initialDeposit;
            const calculatedPercent = (diffUsdt / initialDeposit) * 100;

            this.ui.updateHeaderStats({
                balance: currentBalance,
                todayProfit: todayProfit,
                todayPercent: todayPercent,
                totalPnl: totalUnrealizedPnL,
                totalPnlPercent: (totalUnrealizedPnL / currentBalance) * 100,
                inTrade: totalInTrade,
                diffUsdt: diffUsdt,
                calculatedPercent: calculatedPercent
            });

            // 6. График
            if (data.balanceHistory && Array.isArray(data.balanceHistory)) {
                this.chart.update(data.balanceHistory);
            }

            // 7. Таблица
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
                `*с учетом комиссии 0.2% ${emoji}`;

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