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
        if (icon) icon.classList.remove('open');

        setTimeout(() => {
            if (menu) menu.style.display = 'none';
        }, 300);
        document.body.style.overflow = 'auto';
    }

    // Основной метод обновления всей статистики
    updateHeaderStats(data) {
        if (!data) return;

        const formatValue = (val) => {
            const num = Number(val || 0);
            if (num > 0) return `+${num.toFixed(2)}`;
            if (num < 0) return num.toFixed(2);
            return num.toFixed(2);
        };

        const getClass = (num) => {
            if (num > 0) return 'profit-pos';
            if (num < 0) return 'profit-neg';
            return 'text-white';
        };

        // 1. Баланс
        if (this.balanceEl) {
            this.balanceEl.innerText = `${Number(data.balance || 0).toFixed(6)} USDT`;
        }

        // 2. Общий процент роста
        if (this.percentChangeEl) {
            const perc = Number(data.calculatedPercent || 0);
            this.percentChangeEl.innerText = formatValue(perc) + '%';
            this.percentChangeEl.className = `fw-bold fs-6 mb-3 ${getClass(perc)}`;
        }

        // 3. В обороте
        if (this.inTradeEl) {
            this.inTradeEl.innerText = `${Number(data.inTrade || 0).toFixed(2)} $`;
        }

        // 4. PNL сегодня
        if (this.todayProfitEl) {
            const val = Number(data.todayProfit || 0);
            const perc = Number(data.todayPercent || 0);
            this.todayProfitEl.innerText = `${formatValue(val)} $ (${formatValue(perc)}%)`;
            this.todayProfitEl.className = `fw-bold fs-6 lh-1 ${getClass(val)}`;
        }

        // 5. Нереализованный PnL активных сделок
        if (this.totalPnlEl) {
            const pnl = Number(data.totalPnl || 0);
            const pnlPerc = Number(data.totalPnlPercent || 0);
            this.totalPnlEl.innerText = `${formatValue(pnl)} $ (${formatValue(pnlPerc)}%)`;
            this.totalPnlEl.className = `fw-bold fs-6 lh-1 ${getClass(pnlPerc)}`;

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

    renderCooldowns(cooldowns) {
        const section = document.getElementById('cooldown-section');
        const list = document.getElementById('cooldown-list');
        if (!section || !list) return;

        list.innerHTML = '';

        const entries = Object.entries(cooldowns || {});
        if (entries.length === 0) {
            section.style.display = 'none';
            return;
        }

        section.style.display = 'block';

        entries.forEach(([symbol, time]) => {
            const shortName = symbol.replace('USDT', '');

            const badge = document.createElement('div');
            badge.className = 'badge bg-warning text-dark rounded-pill px-4 py-3 d-flex align-items-center gap-3 fs-6';
            badge.innerHTML = `
            <i class="bi bi-clock-fill fs-4"></i>
            <div>
                <strong>${shortName}</strong><br>
                <small>до ${time}</small>
            </div>
        `;
            list.appendChild(badge);
        });
    }

    updateBotStatus(status) {
        // Находим контейнер статуса (правая карточка в верхнем ряду)
        const statusWrapper = document.querySelector('.col-6 .card .mb-2');
        if (!statusWrapper) return;

        const isOnline = status === 'ONLINE';

        // Полностью обновляем содержимое блока статуса
        statusWrapper.innerHTML = `
        <div class="stat-label mb-1">Статус бота</div>
        <div class="d-flex align-items-center bg-dark px-2 py-1 rounded-pill w-fit-content" style="width: fit-content;">
            <div class="spinner-grow ${isOnline ? 'text-success' : 'text-danger'} spinner-grow-sm me-2" 
                 style="width: 8px; height: 8px; animation-duration: ${isOnline ? '0.75s' : '0s'};"></div>
            <span class="${isOnline ? 'text-success' : 'text-danger'} fw-bold small">
                ${isOnline ? 'Online' : 'Offline'}
            </span>
        </div>
    `;

        // Опционально: Обновляем текст в шапке (SYSTEM ACTIVE / SYSTEM PAUSED)
        const systemSubtext = document.querySelector('.navbar .text-info.small');
        if (systemSubtext) {
            if (isOnline) {
                systemSubtext.textContent = 'SYSTEM ACTIVE';
                systemSubtext.classList.replace('text-danger', 'text-info');
            } else {
                systemSubtext.textContent = 'SYSTEM PAUSED';
                systemSubtext.classList.replace('text-info', 'text-danger');
            }
        }
    }
}
window.ui = new UIManager();

window.toggleMenu = () => {
    if (window.ui) window.ui.toggleMenu();
};