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

    async init() {
        // 1. ПОДТЯГИВАЕМ ДАННЫЕ ИЗ БД
        try {
            const settings = await window.api.getSettings();
            if (settings) {
                if (this.assetsInput) this.assetsInput.value = settings.assets || "";
                if (this.percentInput) this.percentInput.value = settings.tradePercent || "";
            }
        } catch (err) {
            console.error("Ошибка загрузки данных из БД:", err);
        }

        // 2. ПЕРЕХВАТЫВАЕМ ОТПРАВКУ (чтобы не открывалась страница с JSON)
        this.form.onsubmit = async (e) => {
            e.preventDefault(); // Это самое важное!

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

    // ЛОГИКА ОЧИСТКИ
    async confirmClear() {
        if (!confirm("Вы уверены? Это действие безвозвратно удалит всю историю сделок.")) return;

        try {
            const response = await window.api.clearHistory();
            if (response.ok) {
                location.reload(); // Перезагружаем, чтобы обновить состояние
            }
        } catch (err) {
            console.error("Ошибка очистки:", err);
        }
    }
}

window.settingsManager = new SettingsManager();