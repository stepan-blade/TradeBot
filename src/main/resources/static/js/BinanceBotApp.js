class BinanceBotApp {
    constructor() {
        this.state = {
            fullHistory: [],
            currentView: 'OPEN',
            currentPage: 1,
            itemsPerPage: 10,
            filters: {asset: 'ALL', type: 'ALL', result: 'ALL'}
        };

        this.api = new ApiService();
        this.ui = new UIManager(this);
        this.table = new TradingTable(this);
        this.chart = new ChartManager('balanceChart');

        this.init();
    }

    async init() {
        const menuIcon = document.getElementById('menuIcon');
        if (menuIcon) menuIcon.onclick = () => this.ui.toggleMenu();

        const selector = document.getElementById('view-selector');
        if (selector) selector.onchange = (e) => this.changeView(e.target.value);

        await this.updateDashboard();

        setInterval(async () => {
            await this.updateDashboard();
        }, 2000);
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
            const [cooldowns, data] = await Promise.all([
                this.api.fetchCooldowns(),
                this.api.fetchStatus()
            ]);

            this.ui.renderCooldowns(cooldowns);

            const settings = await window.api.getSettings();
            this.ui.updateBotStatus(settings.status);

            this.state.fullHistory = data.history || [];

            this.ui.updateHeaderStats({
                balance: Number(data.balance || 0).toFixed(6),
                inTrade: Number(data.occupiedBalance || 0).toFixed(2),
                todayProfit: Number(data.todayProfitUSDT || 0).toFixed(2),
                todayPercent: Number(data.todayProfitPercent || 0).toFixed(2),
                totalPnl: Number(data.unrealizedPnLUsdt || 0).toFixed(2), // грязный
                totalPnlPercent: data.occupiedBalance > 0
                    ? ((data.unrealizedPnLUsdt / data.occupiedBalance) * 100).toFixed(2)
                    : 0,
                calculatedPercent: Number(data.allProfitPercent || 0).toFixed(2)
            });

            if (data.balanceHistory) this.chart.update(data.balanceHistory);
            this.table.render(this.state.fullHistory);
        } catch (error) {
            console.error("Ошибка:", error);
        }
    }

    async manualClose(symbol) {
        try {
            const data = await this.api.fetchPreviewClose(symbol);

            const profitText = data.profitUsdt >= 0 ? `+${data.profitUsdt}` : `${data.profitUsdt}`;
            const emoji = data.profitUsdt >= 0 ? "✅" : "⚠️";

            const confirmMsg = `Закрыть позицию ${symbol} ?\n\n` +
                `Текущая цена: ${data.currentPrice}\n` +
                `Ожидаемый PNL: ${profitText} $ (${data.percent}%)\n` +
                `С учетом комиссии ${data.feePercent}% ${emoji}`;

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

window.toggleMenu = () => {
    if (window.app && window.app.ui) {
        window.app.ui.toggleMenu();
    }
};