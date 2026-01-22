class SettingsManager {
    constructor() {
        this.form = document.getElementById('settings-form');
        this.assetsInput = document.getElementById('assets-input');
        this.percentInput = document.getElementById('percent-input');
        this.maxOpenTrades = document.getElementById('max_open_trades_input');
        this.gear = document.getElementById('statusGear');

        if (this.form) {
            this.init();
        }
    }

    // 1. НАСТРОЙКИ БОТА
    async init() {
        try {
            const settings = await window.api.getSettings();
            if (settings) {
                if (this.assetsInput) this.assetsInput.value = settings.assets || "";
                if (this.percentInput) this.percentInput.value = settings.tradePercent || "";
                if (this.maxOpenTrades) this.maxOpenTrades.value = settings.maxOpenTrades || "";

                this.updateStatusUI(settings.status);
            }
        } catch (err) {
            console.error("Ошибка загрузки данных из БД:", err);
        }

        this.form.onsubmit = async (e) => {
            e.preventDefault();

            if (this.gear) this.gear.classList.add('gear-rotate');

            try {
                const response = await window.api.saveSettings(new FormData(this.form));
                if (response.ok) {
                    if (window.ui) window.ui.showSuccess();
                } else {
                    alert("Ошибка при сохранении");
                }
            } catch (err) {
                console.error("Ошибка сохранения:", err);
            } finally {
                if (this.gear) this.gear.classList.remove('gear-rotate');
            }
        };
    }

    // 2. ОЧИСТКИ ИСТОРИИ СДЕЛОК
    async confirmClear() {
        if (!confirm("Вы уверены? Это действие безвозвратно удалит всю историю сделок.")) return;

        try {
            const response = await window.api.clearHistory();
            if (response.ok) {
                const historyContainer = document.getElementById('history-table-body');
                if (historyContainer) historyContainer.innerHTML = '';

                if (window.ui) window.ui.showSuccess();
            }
        } catch (err) {
            console.error("Ошибка очистки:", err);
        }
    }

    async toggleBotStatus() {
        const btn = document.getElementById('toggle-bot-btn');
        const isOnline = btn.getAttribute('data-status') === 'ONLINE';
        const newStatus = isOnline ? 'OFFLINE' : 'ONLINE';

        // Формируем данные для отправки (совместимо с контроллером)
        const formData = new FormData();
        formData.append('assets', this.assetsInput.value);
        formData.append('trade_percent', this.percentInput.value);
        formData.append('max_open_trades', this.maxOpenTrades.value);
        formData.append('status', newStatus);

        try {
            const response = await window.api.saveSettings(formData);
            if (response.ok) {
                this.updateStatusUI(newStatus);
                if (window.ui) window.ui.showSuccess();
            }
        } catch (err) {
            console.error("Ошибка смены статуса:", err);
        }
    }

    updateStatusUI(status) {
        const btn = document.getElementById('toggle-bot-btn');
        const text = document.getElementById('bot-status-text');
        const hiddenInput = document.getElementById('status-hidden-input');

        if (hiddenInput) hiddenInput.value = status;
        btn.setAttribute('data-status', status);

        if (status === 'ONLINE') {
            btn.innerHTML = '<i class="bi bi-power"></i>';
            btn.className = 'btn btn-outline-danger-custom';

            text.textContent = 'Бот активен и сканирует рынок';
            text.className = 'text-success-light';
        } else {
            btn.innerHTML = '<i class="bi bi-power"></i>';
            btn.className = 'btn btn-outline-success-custom';

            text.textContent = 'Бот остановлен (пассивный режим)';
            text.className = 'text-danger-light';
        }
    }
}

window.settingsManager = new SettingsManager();