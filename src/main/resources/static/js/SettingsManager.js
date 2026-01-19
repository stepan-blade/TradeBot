class SettingsManager {
    constructor() {
        this.form = document.getElementById('settings-form');
        this.assetsInput = document.getElementById('assets-input');
        this.percentInput = document.getElementById('percent-input');
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
                    // Показываем ту самую галочку из UIManager
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
}

window.settingsManager = new SettingsManager();