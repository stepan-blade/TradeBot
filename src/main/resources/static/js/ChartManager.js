class ChartManager {
    constructor(canvasId) {
        this.ctx = document.getElementById(canvasId)?.getContext('2d');
        this.chart = null;
    }

    update(balanceData) {
        if (!this.ctx) return;

        const balanceText = document.getElementById('balance')?.innerText || "0";
        const currentBalance = parseFloat(balanceText.replace(' USDT', '')) || 0;

        let formattedData = [];

        if (!balanceData || balanceData.length === 0) {
            formattedData = [{ x: new Date(), y: currentBalance }];
        } else {
            formattedData = balanceData.map(item => ({
                x: new Date(item.timestamp),
                y: Number(item.balance)
            })).sort((a, b) => a.x - b.x);
        }

        if (this.chart) {
            this.chart.data.datasets[0].data = formattedData;
            this.chart.update('none');
        } else {
            this.initChart(formattedData, currentBalance);
        }
    }

    initChart(data, currentBalance) {
        this.chart = new Chart(this.ctx, {
            type: 'line',
            data: {
                datasets: [{
                    label: 'Баланс USDT',
                    data: data,
                    borderColor: '#00ff88',
                    backgroundColor: 'rgba(0, 255, 136, 0.1)',
                    fill: true,
                    tension: 0.3,
                    pointRadius: 4,
                    pointBackgroundColor: '#00ff88'
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                scales: {
                    x: {
                        type: 'time',
                        time: { unit: 'minute', displayFormats: { minute: 'HH:mm' } },
                        grid: { color: 'rgba(255, 255, 255, 0.05)' }
                    },
                    y: {
                        grid: { color: 'rgba(255, 255, 255, 0.05)' },
                        suggestedMin: currentBalance - 10,
                        suggestedMax: currentBalance + 10
                    }
                },
                plugins: {
                    legend: { display: false },
                    zoom: {
                        pan: { enabled: true, mode: 'x', threshold: 10 },
                        zoom: { wheel: { enabled: true }, pinch: { enabled: true }, mode: 'x' }
                    }
                }
            }
        });
    }

    resetZoom() {
        if (this.chart) this.chart.resetZoom();
    }
}