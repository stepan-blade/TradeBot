class ApiService {
    async fetchStatus() {
        // Запрос с nocache
        const response = await fetch('/api/status?nocache=' + Date.now());
        return await response.json();
    }

    async fetchCooldowns() {
        const response = await fetch('/api/cooldowns');
        return await response.json();
    }

    async fetchPreviewClose(symbol) {
        // Запрос для получения данных перед закрытием (превью)
        const response = await fetch(`/api/preview-close?symbol=${symbol}`);
        if (!response.ok) throw new Error("Trade not found");
        return await response.json();
    }

    async postCloseTrade(symbol) {
        return await fetch(`/api/close-trade?symbol=${symbol}`, { method: 'POST' });
    }

    async getSettings() {
        const response = await fetch('/api/settings');
        return response.ok ? await response.json() : null;
    }

    async saveSettings(formData) {
        return await fetch('/api/save-settings', {
            method: 'POST',
            body: formData
        });
    }

    async clearHistory() {
        return await fetch('/api/clear-history', { method: 'POST' });
    }
}

window.api = new ApiService();
