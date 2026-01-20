class ChartManager {
    constructor(canvasId) {
        this.ctx = document.getElementById(canvasId)?.getContext('2d');
        this.chart = null;
    }

    update(balanceData) {
        if (!this.ctx) return;

        let formattedData = [];

        if (!balanceData || balanceData.length === 0) {
            const currentBalance = this.getCurrentBalance();
            formattedData = [{ x: new Date(), y: currentBalance }];
        } else {
            formattedData = balanceData.map(item => ({
                x: new Date(item.timestamp),
                y: Number(item.balance)
            })).sort((a, b) => a.x - b.x);
        }

        const values = formattedData.map(d => d.y);
        const minY = Math.min(...values);
        const maxY = Math.max(...values);
        const margin = (maxY - minY) * 0.05 || 5;

        if (this.chart) {
            this.chart.data.datasets[0].data = formattedData;
            this.chart.options.scales.y.min = minY - margin;
            this.chart.options.scales.y.max = maxY + margin;
            this.chart.update('none');
        } else {
            this.initChart(formattedData, minY - margin, maxY + margin);
        }
    }

    getCurrentBalance() {
        const balanceText = document.getElementById('balance-val')?.innerText || "0 USDT";
        return parseFloat(balanceText.replace(' USDT', '')) || 0;
    }

    initChart(data, minY, maxY) {
        const gradient = this.ctx.createLinearGradient(0, 0, 0, this.ctx.canvas.height);
        gradient.addColorStop(0, 'rgba(0, 255, 136, 0.3)');
        gradient.addColorStop(1, 'rgba(0, 255, 136, 0)');

        this.chart = new Chart(this.ctx, {
            type: 'line',
            data: {
                datasets: [{
                    label: 'Баланс USDT',
                    data: data,
                    borderColor: '#00ff88',
                    backgroundColor: gradient,
                    fill: true,
                    tension: 0.4,
                    pointRadius: 3,
                    pointBackgroundColor: '#00ff88',
                    pointHoverRadius: 6,
                    borderWidth: 2
                }]
            },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: {
                    mode: 'index',
                    intersect: false
                },
                scales: {
                    x: {
                        type: 'time',
                        time: {
                            unit: 'minute',
                            stepSize: 30,
                            displayFormats: { minute: 'HH:mm' }
                        },
                        ticks: {
                            maxRotation: 0,
                            minRotation: 0,
                            autoSkip: false,
                            color: '#aaaaaa',
                            font: { size: 11 }
                        },
                        grid: { color: 'rgba(255, 255, 255, 0.05)' }
                    },
                    y: {
                        min: minY,
                        max: maxY,
                        ticks: {
                            color: '#aaaaaa',
                            font: { size: 11 },
                            callback: value => value.toFixed(2) + ' $'
                        },
                        grid: { color: 'rgba(255, 255, 255, 0.05)' }
                    }
                },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        callbacks: {
                            title: items => {
                                const date = new Date(items[0].parsed.x);
                                return date.toLocaleDateString('ru-RU') + ' ' + date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
                            },
                            label: item => `Баланс: ${item.parsed.y.toFixed(6)} USDT`
                        },
                        backgroundColor: 'rgba(0, 0, 0, 0.8)'
                    },
                    zoom: {
                        limits: {
                            x: { min: 'original', max: 'original' },
                            y: { min: minY, max: maxY }
                        },
                        pan: {
                            enabled: true,
                            mode: 'x',
                            modifierKey: null, // Без модификатора — простой drag
                            threshold: 10
                        },
                        zoom: {
                            wheel: { enabled: true },
                            drag: { enabled: true, backgroundColor: 'rgba(0,255,136,0.2)' }, // Drag для зума (зажать и тянуть)
                            pinch: { enabled: true },
                            mode: 'x'
                        }
                    }
                },
                animation: { duration: 750 }
            }
        });
    }

    resetZoom() {
        if (this.chart) this.chart.resetZoom();
    }
}