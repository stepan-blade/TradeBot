class UIManager {
    constructor(app) {
        this.menu = document.getElementById('glassMenu');
        this.icon = document.getElementById('menuIcon');
        this.overlay = document.getElementById('saveOverlay');
        this.audioCtx = null;

        // Биндим контекст, чтобы 'this' всегда указывал на UIManager
        this.toggleMenu = this.toggleMenu.bind(this);
        this.closeMenu = this.closeMenu.bind(this);
        this.openMenu = this.openMenu.bind(this);

        // Кэшируем элементы один раз при запуске
        this.balanceEl = document.getElementById('balance-val');
        this.inTradeEl = document.getElementById('in-trade-val');
        this.percentChangeEl = document.getElementById('percent-change');
        this.todayProfitEl = document.getElementById('today-profit-val');
        this.totalPnlEl = document.getElementById('total-pnl-val');
        this.totalPnlContainer = document.getElementById('total-pnl-container');

        this.init();
    }

    init() {
        if (this.menu) {
            this.menu.addEventListener('click', (e) => {
                if (e.target === this.menu) {
                    this.closeMenu();
                }
            });
        }
    }

    // Открытие и закрытие меню NavBar
    toggleMenu() {
        const menu = this.menu || document.getElementById('glassMenu');
        if (!menu) return;

        const isOpen = menu.classList.contains('active');
        if (isOpen) {
            this.closeMenu();
        } else {
            this.openMenu();
        }
    }

    openMenu() {
        const menu = this.menu || document.getElementById('glassMenu');
        const icon = this.icon || document.getElementById('menuIcon');

        if (icon) icon.classList.add('open');
        if (menu) {
            menu.style.display = 'flex';
            setTimeout(() => menu.classList.add('active'), 10);
        }
        document.body.style.overflow = 'hidden';
    }

    closeMenu() {
        const menu = this.menu || document.getElementById('glassMenu');
        const icon = this.icon || document.getElementById('menuIcon');

        if (menu) menu.classList.remove('active');
        if (icon) icon.classList.remove('open'); // ГАРАНТИЯ СНЯТИЯ КРЕСТИКА

        setTimeout(() => {
            if (menu) menu.style.display = 'none';
        }, 300);
        document.body.style.overflow = 'auto';
    }


    // Основной метод обновления всей статистики
    updateHeaderStats(data) {
        if (!data) return;

        // 1. Баланс
        if (this.balanceEl) {
            this.balanceEl.innerText = `${Number(data.balance || 0).toFixed(2)} $`;
        }

        // 2. Общий процент (под балансом)
        if (this.percentChangeEl) {
            const perc = data.calculatedPercent || 0;
            this.percentChangeEl.innerText = `${perc >= 0 ? '+' : ''}${perc.toFixed(2)}%`;
            this.percentChangeEl.className = `fw-bold fs-6 mb-3 ${perc >= 0 ? 'profit-pos' : 'profit-neg'}`;
        }

        // 3. В обороте
        if (this.inTradeEl) {
            this.inTradeEl.innerText = `${Number(data.inTrade || 0).toFixed(2)} $`;
        }

        // 4. Профит сегодня
        if (this.todayProfitEl) {
            const val = data.todayProfit || 0;
            const perc = data.todayPercent || 0;
            this.todayProfitEl.innerText = `${val.toFixed(2)} $ (${perc.toFixed(2)}%)`;
            this.todayProfitEl.className = `fw-bold fs-6 lh-1 ${val >= 0 ? 'profit-pos' : 'profit-neg'}`;
        }

        // 5. PnL активных сделок (левый блок)
        if (this.totalPnlEl) {
            const pnl = data.totalPnl || 0;
            const pnlPerc = data.totalPnlPercent || 0;
            this.totalPnlEl.innerText = `${pnl.toFixed(2)} $ (${pnlPerc.toFixed(2)}%)`;
            this.totalPnlEl.className = `fw-bold fs-6 lh-1 ${pnl >= 0 ? 'profit-pos' : 'profit-neg'}`;

            // Показываем контейнер только если есть активные сделки (в обороте > 0)
            if (this.totalPnlContainer) {
                this.totalPnlContainer.style.display = data.inTrade > 0 ? 'block' : 'none';
            }
        }
    }
    showSuccess() {
        this.playSound(true);
        this.overlay.style.display = 'flex';
        setTimeout(() => this.overlay.classList.add('active'), 10);
        setTimeout(() => {
            this.overlay.classList.remove('active');
            setTimeout(() => { this.overlay.style.display = 'none' }, 400);
        }, 1500);
    }

    playSound(isSuccess) {
        if (!this.audioCtx) this.audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        const osc = this.audioCtx.createOscillator();
        const gain = this.audioCtx.createGain();
        osc.connect(gain); gain.connect(this.audioCtx.destination);
        osc.frequency.setValueAtTime(isSuccess ? 523.25 : 220, this.audioCtx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.01, this.audioCtx.currentTime + 0.4);
        osc.start(); osc.stop(this.audioCtx.currentTime + 0.4);
    }
}
window.ui = new UIManager();
window.toggleMenu = () => {
    if (window.ui) {
        window.ui.toggleMenu();
    }
};