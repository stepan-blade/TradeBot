class ChartManager {
    constructor(canvasId) {
        this.ctx = document.getElementById(canvasId)?.getContext('2d');
        this.chart = null;
        this.selectedDate = new Date();
        this.initialBalance = 0;
        this.balanceData = [];
        this.threshold = 1;
        this.lastRenderedLength = 0;
        this.lastCurrentBalance = null;
    }

    async init() {
        await this.fetchInitialBalance();
        await this.fetchBalanceData();
        this.update();

        const todayStr = new Date().toISOString().split('T')[0];
        const dateInput = document.getElementById('chart-date-input');
        if (dateInput) dateInput.value = todayStr;
        this.updateLabel('Сегодня');
    }

    // Вспомогательный метод для обновления текста кнопки
    updateLabel(text) {
        const label = document.getElementById('chart-selected-date');
        if (label) label.innerText = text;
    }

    // Вспомогательный метод для закрытия дропдауна
    closeDropdown() {
        const toggle = document.getElementById('chart-date-toggle');
        if (toggle) {
            const dropdown = bootstrap.Dropdown.getInstance(toggle);
            if (dropdown) dropdown.hide();
        }
    }

    applyDate() {
        const inputVal = document.getElementById('chart-date-input').value;
        if (inputVal) {
            this.changeDate(inputVal);

            const selected = new Date(inputVal);
            const isToday = selected.toDateString() === new Date().toDateString();
            this.updateLabel(isToday ? 'Сегодня' : selected.toLocaleDateString('ru-RU'));
            this.closeDropdown();
        }
    }

    resetDate() {
        const todayStr = new Date().toISOString().split('T')[0];
        const dateInput = document.getElementById('chart-date-input');
        if(dateInput) dateInput.value = todayStr;

        this.changeDate(todayStr);
        this.updateLabel('Сегодня');
        this.closeDropdown();
    }

    viewAllTime() {
        this.selectedDate = null; // null означает "За всё время"
        this.lastRenderedLength = 0; // Сброс кеша рендера
        this.update();

        this.updateLabel('За всё время');
        this.closeDropdown();
    }

    async fetchInitialBalance() {
        try {
            const res = await fetch('/api/settings');
            const settings = await res.json();
            this.initialBalance = settings.balance || 0;
        } catch (err) {
            console.error('Не удалось загрузить initial balance:', err);
            this.initialBalance = 0;
        }
    }

    async fetchBalanceData() {
        try {
            const res = await fetch('/api/status');
            const data = await res.json();
            const history = data.balanceHistory || [];
            history.sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
            this.balanceData = history;
            return history;
        } catch (err) {
            console.error('Ошибка загрузки истории баланса:', err);
            return this.balanceData;
        }
    }

    getOpeningBalance(date) {
        if (!this.balanceData.length) return this.initialBalance;

        const startOfTargetDay = new Date(date);
        startOfTargetDay.setHours(0, 0, 0, 0);

        const pastRecords = this.balanceData.filter(d => new Date(d.timestamp) < startOfTargetDay);

        if (pastRecords.length > 0) {
            return pastRecords[pastRecords.length - 1].balance;
        }

        // Если записей ДО этого дня нет, берем баланс ПЕРВОЙ существующей записи в истории
        // Это предотвратит сброс в 0, если initialBalance не подтянулся
        return this.balanceData[0].balance || this.initialBalance;
    }

    filterDataByDate(date) {
        if (!date) return this.balanceData; // Если дата не выбрана, возвращаем всё

        const start = new Date(date);
        start.setHours(0, 0, 0, 0);
        const end = new Date(date);
        end.setHours(23, 59, 59, 999);

        return this.balanceData.filter(item => {
            const ts = new Date(item.timestamp);
            return ts >= start && ts <= end;
        });
    }

    async groupByDayForBars(currentBalance) {
        const daily = {};
        // Заполняем последние известные балансы по дням
        this.balanceData.forEach(item => {
            const dt = new Date(item.timestamp);
            const dayKey = dt.toISOString().split('T')[0];
            daily[dayKey] = item.balance;
        });

        const sortedDays = Object.keys(daily).sort();

        // Безопасное определение диапазона дат
        let firstDay = sortedDays.length > 0 ? sortedDays[0] : new Date().toISOString().split('T')[0];
        const lastDay = new Date().toISOString().split('T')[0];

        const allDays = [];
        let current = new Date(firstDay);
        const end = new Date(lastDay);

        // Защита от бесконечного цикла, если даты некорректны
        if (current > end) current = new Date(lastDay);

        while (current <= end) {
            const key = current.toISOString().split('T')[0];
            allDays.push(key);
            current.setDate(current.getDate() + 1);
        }

        let lastBalance = this.initialBalance;
        const balances = [];
        const barColors = [];

        allDays.forEach(day => {
            if (daily[day] !== undefined) {
                lastBalance = daily[day];
            }
            // Для сегодняшнего дня принудительно берем текущий баланс
            if (day === lastDay) {
                lastBalance = currentBalance;
            }
            balances.push(lastBalance);

            // Логика цвета (зеленый если рост, красный если падение)
            if (balances.length === 1) {
                barColors.push('#888888'); // Первый бар серый
            } else {
                const prev = balances[balances.length - 2];
                if (Math.abs(lastBalance - prev) < 0.0001) {
                    barColors.push('#888888');
                } else {
                    barColors.push(lastBalance > prev ? '#00ff88' : '#ff0000');
                }
            }
        });

        return {
            days: allDays,
            balances,
            barColors
        };
    }

    // Метод для добавления точек пересечения (для заливки зеленым/красным)
    addCrossingPoints(data, threshold) {
        if (data.length < 2) return data.slice();
        let newData = [data[0]];
        for (let i = 1; i < data.length; i++) {
            let a = newData[newData.length - 1];
            let b = data[i];
            let da = a.y - threshold;
            let db = b.y - threshold;
            // Если точки по разные стороны от threshold (0), ищем пересечение
            if (da * db < 0) {
                let t = -da / (db - da);
                let x = new Date(a.x.getTime() + t * (b.x.getTime() - a.x.getTime()));
                newData.push({ x, y: threshold, isOriginal: false });
            }
            newData.push(b);
        }
        return newData;
    }

    async update(fullHistory = null) {
        if (!this.ctx) return;

        if (fullHistory) {
            fullHistory.sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp));
            this.balanceData = fullHistory;
        }

        const isAllTime = this.selectedDate === null;
        const isToday = !isAllTime && this.selectedDate.toDateString() === new Date().toDateString();
        const currentBalance = await this.getCurrentBalance();

        // Оптимизация: не перерисовывать, если данные не изменились
        if (isToday) {
            if (this.lastCurrentBalance !== null && Math.abs(this.lastCurrentBalance - currentBalance) < 0.01) {
                return;
            }
            this.lastCurrentBalance = currentBalance;
        } else {
            if (this.lastRenderedLength === this.balanceData.length && this.lastRenderedLength > 0) {
                return;
            }
        }
        this.lastRenderedLength = this.balanceData.length;

        const noDataOverlay = document.getElementById('no-data-overlay');
        if (noDataOverlay) noDataOverlay.style.display = 'none';

        let datasets = [];
        let dayInitial = 0;
        let annotations = [];
        let minY = 0;
        let maxY = 10;
        let margin = 10;
        let isAbsoluteY = false;
        let tooltipMode = 'normal';

        if (this.chart) {
            this.chart.destroy();
            this.chart = null;
        }

        // ================= ЛОГИКА "ЗА ВСЁ ВРЕМЯ" (BAR CHART) =================
        if (isAllTime) {
            const { days, balances, barColors } = await this.groupByDayForBars(currentBalance);
            const formattedData = balances.map((y, i) => ({
                x: days[i], // Передаем строку, Chart.js сам распарсит
                y: y
            }));

            if (balances.length === 0) {
                minY = currentBalance - 10;
                maxY = currentBalance + 10;
            } else {
                minY = Math.min(...balances);
                maxY = Math.max(...balances);
                margin = (maxY - minY) * 0.1 || 10;
            }

            datasets = [{
                type: 'bar',
                label: 'Баланс по дням',
                data: formattedData,
                backgroundColor: barColors,
                borderWidth: 1,
                borderColor: 'rgba(0,0,0,0.5)', // Полупрозрачная граница
                barThickness: 'flex',
                maxBarThickness: 50,
                minBarLength: 2 // Чтобы даже пустые дни были чуть видны
            }];

            isAbsoluteY = true;
            tooltipMode = 'allTime';
        }
        // ================= ЛОГИКА КОНКРЕТНОГО ДНЯ (LINE CHART) =================
        else {
            const startOfDay = new Date(this.selectedDate); startOfDay.setHours(0, 0, 0, 0);
            const endOfDay = new Date(this.selectedDate); endOfDay.setHours(23, 59, 59, 999);

            dayInitial = this.getOpeningBalance(this.selectedDate);

            let filtered = this.filterDataByDate(this.selectedDate);

            // ПРОВЕРКА: Если реальных сделок в массиве filtered нет
            if (filtered.length === 0) {
                if (noDataOverlay) {
                    noDataOverlay.innerHTML = 'Сделок за<br>текущий период<br>не было';
                    noDataOverlay.style.display = 'block';
                }

                datasets = [{
                    label: 'Нет сделок',
                    data: [
                        { x: startOfDay, y: 0 },
                        { x: isToday ? new Date() : endOfDay, y: 0 }
                    ],
                    borderColor: '#888888',
                    borderDash: [6, 4],
                    borderWidth: 2,
                    pointRadius: 0,
                    fill: 'origin',
                    backgroundColor: 'rgba(136, 136, 136, 0.1)',
                    tension: 0
                }];

                minY = -10;
                maxY = 10;
                margin = 0;
                isAbsoluteY = false;
                tooltipMode = 'normal';
            }
            // Если сделки ЕСТЬ
            else {
                let points = [];
                points.push({ x: startOfDay, y: dayInitial });

                let formattedData = filtered.map(item => ({
                    x: new Date(item.timestamp),
                    y: Number(item.balance)
                })).sort((a, b) => a.x - b.x);

                formattedData.forEach(p => points.push(p));

                if (isToday) {
                    points.push({ x: new Date(), y: currentBalance });
                } else {
                    points.push({ x: endOfDay, y: points[points.length - 1].y });
                }

                let segments = [];
                let currentSegment = [];
                let runningInitial = dayInitial;

                for (let i = 0; i < points.length; i++) {
                    currentSegment.push({ x: points[i].x, y: points[i].y - runningInitial, isOriginal: true });
                    if (i < points.length - 1) {
                        const diff = points[i+1].y - points[i].y;
                        if (Math.abs(diff) > this.threshold * 5) {
                            segments.push(currentSegment);
                            currentSegment = [];
                            annotations.push({
                                type: 'line', xMin: points[i+1].x, xMax: points[i+1].x,
                                borderColor: '#aaa', borderWidth: 1, borderDash: [2, 2]
                            });
                            runningInitial += diff;
                        }
                    }
                }
                if (currentSegment.length > 0) segments.push(currentSegment);

                let allShiftedData = [];
                segments.forEach(seg => {
                    allShiftedData = allShiftedData.concat(this.addCrossingPoints(seg, 0));
                });

                const values = allShiftedData.map(d => d.y);
                minY = Math.min(...values);
                maxY = Math.max(...values);
                margin = (maxY - minY) * 0.1 || 5;

                datasets = [
                    {
                        type: 'line',
                        data: [{x: startOfDay, y: 0}, {x: isToday ? new Date() : endOfDay, y: 0}],
                        borderColor: 'rgba(170,170,170,0.2)', borderDash: [5, 5], borderWidth: 1, pointRadius: 0
                    },
                    {
                        label: 'PNL',
                        data: allShiftedData,
                        tension: 0.3,
                        pointRadius: ctx => ctx.raw?.isOriginal ? 3 : 0,
                        pointBackgroundColor: ctx => (ctx.raw?.y >= 0 ? '#00ff88' : '#ff0000'),
                        borderColor: '#888',
                        borderWidth: 2,
                        segment: {
                            borderColor: ctx => {
                                const y0 = ctx.p0.parsed.y;
                                const y1 = ctx.p1.parsed.y;
                                return (y0 >= 0 && y1 >= 0) ? '#00ff88' : '#ff0000';
                            }
                        },
                        fill: { target: 'origin', above: 'rgba(0,255,136,0.1)', below: 'rgba(255,0,0,0.1)' }
                    }
                ];
                isAbsoluteY = false;
                tooltipMode = 'normal';
            }
        }

        // ================= СОЗДАНИЕ ГРАФИКА =================
        this.chart = new Chart(this.ctx, {
            type: isAllTime ? 'bar' : 'line',
            data: { datasets },
            options: {
                responsive: true,
                maintainAspectRatio: false,
                interaction: { mode: 'index', intersect: false },
                scales: {
                    x: {
                        type: 'time',
                        // Добавь эти две строки:
                        min: isAllTime ? undefined : new Date(this.selectedDate).setHours(0,0,0,0),
                        max: isAllTime ? undefined : (isToday ? new Date() : new Date(this.selectedDate).setHours(23,59,59,999)),

                        time: {
                            unit: isAllTime ? 'day' : 'hour',
                            displayFormats: { hour: 'HH:mm', day: 'dd MMM' },
                            tooltipFormat: 'dd MMM HH:mm'
                        },
                        offset: isAllTime,
                        ticks: { color: '#aaa', font: { size: 10 }, maxRotation: 0, autoSkip: true },
                        grid: { color: 'rgba(255,255,255,0.05)' }
                    },
                    y: {
                        min: minY - margin,
                        max: maxY + margin,
                        ticks: {
                            color: '#aaa',
                            font: { size: 10 },
                            callback: v => isAbsoluteY ? v.toFixed(2) + ' $' : (v + dayInitial).toFixed(2) + ' $'
                        },
                        grid: { color: 'rgba(255,255,255,0.05)' }
                    }
                },
                plugins: {
                    legend: { display: false },
                    tooltip: {
                        backgroundColor: 'rgba(20,20,30,0.95)',
                        callbacks: {
                            title: items => {
                                const d = new Date(items[0].parsed.x);
                                return d.toLocaleString('ru-RU', {
                                    day: 'numeric', month: 'short',
                                    hour: isAllTime ? undefined : '2-digit',
                                    minute: isAllTime ? undefined : '2-digit'
                                });
                            },
                            label: item => {
                                const val = item.parsed.y;
                                if (tooltipMode === 'allTime') {
                                    return `Баланс: ${val.toFixed(2)} $`;
                                }
                                if (tooltipMode === 'absolute') {
                                    return `Баланс: ${val.toFixed(2)} $`;
                                }
                                const absBal = val + dayInitial;
                                return [`PnL: ${val > 0 ? '+' : ''}${val.toFixed(2)} $`, `Баланс: ${absBal.toFixed(2)} $`];
                            }
                        }
                    },
                    annotation: {
                        annotations: annotations
                    },
                    zoom: {
                        pan: { enabled: isAllTime, mode: 'x' },
                        zoom: { wheel: { enabled: isAllTime }, pinch: { enabled: isAllTime }, mode: 'x' }
                    }
                },
                animation: { duration: 400 }
            }
        });
    }

    changeDate(dateStr) {
        if (!dateStr) return;
        this.selectedDate = new Date(dateStr);
        this.lastRenderedLength = 0;
        this.lastCurrentBalance = null;
        this.update();
    }

    async getCurrentBalance() {
        try {
            const res = await fetch('/api/status');
            const data = await res.json();
            return data.balance || 0;
        } catch {
            const text = document.getElementById('balance-val')?.innerText || '0';
            return parseFloat(text.replace(/[^0-9.]/g, '')) || 0;
        }
    }
}