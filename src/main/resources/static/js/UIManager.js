class UIManager {
    constructor(app) {
        this.menu = document.getElementById('glassMenu');
        this.icon = document.getElementById('menuIcon');
        this.overlay = document.getElementById('saveOverlay');
        this.audioCtx = null;

        // Биндим контекст
        this.toggleMenu = this.toggleMenu.bind(this);
        this.closeMenu = this.closeMenu.bind(this);
        this.openMenu = this.openMenu.bind(this);

        // Кэшируем элементы
        this.balanceEl = document.getElementById('balance-val');
        this.inTradeEl = document.getElementById('in-trade-val');
        this.percentChangeEl = document.getElementById('percent-change'); // это контейнер под балансом
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

    // Открытие и закрытие секции управления системой
    toggleAdminPanel() {
        const content = document.getElementById('adminPanelContent');
        const chevron = document.getElementById('adminChevron');

        content.classList.toggle('show');
        chevron.classList.toggle('rotate');
    }

    // Открытие и закрытие секции параметров торговли
    toggleSettingsPanel() {
        const content = document.getElementById('settingsPanelContent');
        const chevron = document.getElementById('settingsChevron');

        if (content && chevron) {
            const isOpen = content.classList.toggle('show');
            chevron.classList.toggle('rotate', isOpen);
        }
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
            if (num > 0) return 'text-success';
            if (num < 0) return 'text-danger';
            return 'text-white';
        };

        // 1. Баланс
        if (this.balanceEl) {
            this.balanceEl.innerText = `${Number(data.balance || 0).toFixed(6)} USDT`;
        }

        // 2. Общий процент роста / PnL под балансом — ИСПРАВЛЕННЫЙ БЛОК
        if (this.percentChangeEl) {
            const pnl = Number(data.totalPnl || 0);        // сумма PnL в $
            const perc = Number(data.totalPnlPercent || 0); // процент

            const sign = pnl >= 0 ? '+' : '';
            const pnlText = `${sign}${pnl.toFixed(2)} $`;
            const percText = `(${sign}${perc.toFixed(2)}%)`;

            const colorClass = getClass(pnl);

            this.percentChangeEl.innerHTML = `
                <span class="${colorClass}">${pnlText}</span> 
                <span class="${colorClass}">${percText}</span>
            `;
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

        section.style.display = 'flex';
        section.style.flexWrap = 'wrap';
        section.style.gap = '0.5rem';

        entries.forEach(([symbol, time]) => {
            const shortName = symbol.replace('USDT', '');

            const badge = document.createElement('div');
            badge.className = 'd-flex align-items-center gap-2 px-3 py-2 rounded-4 text-light';
            badge.style.background = 'rgba(18, 18, 18, 0.6)';
            badge.style.backdropFilter = 'blur(15px)';
            badge.style.webkitBackdropFilter = 'blur(15px)';
            badge.style.border = '1px solid #333';
            badge.style.boxShadow = '0 4px 12px rgba(0,0,0,0.3)';

            badge.innerHTML = `
            <div class="d-flex align-items-center justify-content-center" 
                 style="width: 20px; height: 20px; opacity: 0.8;">
                <i class="bi bi-clock-fill text-warning" style="font-size: 0.85rem;"></i>
            </div>
            <div class="d-flex flex-column" style="line-height: 1.1;">
                <span class="fw-bold" style="font-size: 0.9rem; letter-spacing: 0.5px;">${shortName}</span>
                <span class="text-muted" style="font-size: 0.65rem; opacity: 0.6;">до ${time}</span>
            </div>
        `;
            list.appendChild(badge);
        });
    }

    // Обновление статуса торгового алгоритма
    updateBotStatus(status) {
        const statusWrapper = document.querySelector('.col-6 .card .mb-2');
        if (!statusWrapper) return;

        const isOnline = status === 'ONLINE';

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
const uiInstance = new UIManager();
window.ui = uiInstance;

window.toggleMenu = function(e) {
    if (e) {
        e.preventDefault();
        e.stopPropagation();
    }

    const menu = document.getElementById('glassMenu');
    const icon = document.getElementById('menuIcon');

    if (!menu || !icon) return;

    // Проверяем реальное состояние по наличию класса active
    const isNowActive = menu.classList.contains('active');

    if (!isNowActive) {
        // ОТКРЫВАЕМ
        icon.classList.add('open');
        menu.style.display = 'flex';
        menu.offsetHeight; // force reflow
        menu.classList.add('active');
        document.body.style.overflow = 'hidden';
    } else {
        // ЗАКРЫВАЕМ
        window.closeGlobalMenu();
    }
};

// Выносим закрытие в отдельную функцию для надежности
window.closeGlobalMenu = function() {
    const menu = document.getElementById('glassMenu');
    const icon = document.getElementById('menuIcon');

    if (icon) icon.classList.remove('open');
    if (menu) {
        menu.classList.remove('active');
        setTimeout(() => {
            if (!menu.classList.contains('active')) {
                menu.style.display = 'none';
                document.body.style.overflow = '';
            }
        }, 300);
    }
};

// Обработка клика по фону glass-menu
document.addEventListener('click', (e) => {
    const menu = document.getElementById('glassMenu');
    const isMenuOpen = menu && menu.classList.contains('active');

    // Если кликнули именно по подложке (glass-menu) или вне контента
    if (isMenuOpen && (e.target.id === 'glassMenu' || e.target.classList.contains('glass-menu'))) {
        window.closeGlobalMenu();
    }
});