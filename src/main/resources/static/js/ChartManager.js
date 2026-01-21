class ChartManager {
    constructor(canvasId) {
        this.ctx = document.getElementById(canvasId)?.getContext('2d');
        this.chart = null;
    }

    addCrossingPoints(data, threshold) {
        if (data.length < 2) return data.slice();
        let newData = [data[0]];
        for (let i = 1; i < data.length; i++) {
            let a = newData[newData.length - 1];
            let b = data[i];
            let da = a.y - threshold;
            let db = b.y - threshold;
            if (da * db < 0) {
                let t = -da / (db - da);
                let x = new Date(a.x.getTime() + t * (b.x.getTime() - a.x.getTime()));
                newData.push({ x, y: threshold, isOriginal: false });
            }
            newData.push(b);
        }
        return newData;
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

        const initial = formattedData[0].y;
        let originalShiftedData = formattedData.map(item => ({
            x: item.x,
            y: item.y - initial,
            isOriginal: true
        }));
        let shiftedData = this.addCrossingPoints(originalShiftedData, 0);

        const values = shiftedData.map(d => d.y);
        const minY = Math.min(...values);
        const maxY = Math.max(...values);
        const margin = (maxY - minY) * 0.05 || 5;

        const green = '#00ff88';
        const red = '#ff0000';
        const pointColors = shiftedData.map(d => d.y >= 0 ? green : red);
        const pointRadius = shiftedData.map(d => d.isOriginal ? 3 : 0);

        let minX = shiftedData[0].x;
        let maxX = shiftedData[shiftedData.length - 1].x;
        if (shiftedData.length === 1) {
            minX = new Date(minX.getTime() - 1800000); // 30 minutes before
            maxX = new Date(maxX.getTime() + 1800000); // 30 minutes after
        }
        const horizontalData = [{ x: minX, y: 0 }, { x: maxX, y: 0 }];

        if (this.chart) {
            this.chart.data.datasets[0].data = horizontalData;
            this.chart.data.datasets[1].data = shiftedData;
            this.chart.data.datasets[1].pointBackgroundColor = pointColors;
            this.chart.data.datasets[1].pointRadius = pointRadius;
            this.chart.data.datasets[1].segment = {
                borderColor: ctx => ((ctx.p0.parsed.y + ctx.p1.parsed.y) / 2 >= 0) ? green : red
            };
            this.chart.options.scales.y.min = minY - margin;
            this.chart.options.scales.y.max = maxY + margin;
            this.chart.options.scales.y.ticks.callback = value => (value + initial).toFixed(2) + ' $';
            this.chart.options.plugins.tooltip.callbacks.label = item => `Баланс: ${(item.parsed.y + initial).toFixed(6)} USDT`;
            this.chart.options.plugins.zoom.limits.y.min = minY;
            this.chart.options.plugins.zoom.limits.y.max = maxY;
            this.chart.update('none');
        } else {
            this.initChart(shiftedData, horizontalData, minY - margin, maxY + margin, initial, green, red, pointColors, pointRadius);
        }
    }

    getCurrentBalance() {
        const balanceText = document.getElementById('balance-val')?.innerText || "0 USDT";
        return parseFloat(balanceText.replace(' USDT', '')) || 0;
    }

    initChart(data, horizontalData, minY, maxY, initial, green, red, pointColors, pointRadius) {
        this.chart = new Chart(this.ctx, {
            type: 'line',
            data: {
                datasets: [
                    // Horizontal line dataset
                    {
                        type: 'line',
                        data: horizontalData,
                        borderColor: 'rgba(170, 170, 170, 0.2)',
                        borderDash: [5, 5],
                        borderWidth: 1,
                        pointRadius: 0,
                        fill: false,
                        label: 'Initial Balance',
                        order: 1 // Draw after the main line
                    },
                    // Main balance dataset
                    {
                        label: 'Баланс USDT',
                        data: data,
                        tension: 0.4,
                        pointRadius: pointRadius,
                        pointBackgroundColor: pointColors,
                        pointHoverRadius: 6,
                        borderWidth: 2,
                        segment: {
                            borderColor: ctx => ((ctx.p0.parsed.y + ctx.p1.parsed.y) / 2 >= 0) ? green : red
                        },
                        fill: {
                            target: 'origin',
                            above: (ctx) => {
                                const gradient = ctx.chart.ctx.createLinearGradient(0, ctx.chart.chartArea.top, 0, ctx.chart.chartArea.bottom);
                                gradient.addColorStop(0, 'rgba(0, 255, 136, 0.3)');
                                gradient.addColorStop(1, 'rgba(0, 255, 136, 0)');
                                return gradient;
                            },
                            below: (ctx) => {
                                const gradient = ctx.chart.ctx.createLinearGradient(0, ctx.chart.chartArea.top, 0, ctx.chart.chartArea.bottom);
                                gradient.addColorStop(0, 'rgba(255, 0, 0, 0)');
                                gradient.addColorStop(1, 'rgba(255, 0, 0, 0.3)');
                                return gradient;
                            }
                        },
                        order: 0
                    }
                ]
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
                            callback: value => (value + initial).toFixed(2) + ' $'
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
                            label: item => item.datasetIndex === 1 ? `Баланс: ${(item.parsed.y + initial).toFixed(6)} USDT` : null
                        },
                        filter: item => item.datasetIndex === 1,
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
                            modifierKey: null,
                            threshold: 10
                        },
                        zoom: {
                            wheel: { enabled: true },
                            drag: { enabled: true, backgroundColor: 'rgba(0,255,136,0.2)' },
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