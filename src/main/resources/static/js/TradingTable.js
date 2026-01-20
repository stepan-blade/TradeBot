class TradingTable {
    constructor(app) {
        this.app = app;
        this.container = document.getElementById('trade-history');
        this.paginationRoot = document.getElementById('pagination-controls');
    }

    updateAssetFilterList(fullHistory) {
        const assetList = document.getElementById('filter-asset-list');
        if (!assetList) return;

        const uniqueAssets = [...new Set(fullHistory.map(t => t.asset))];
        let html = `<li><a class="dropdown-item" href="javascript:void(0)" onclick="app.setFilter('asset', 'ALL')">Все пары</a></li>`;
        uniqueAssets.forEach(asset => {
            html += `<li><a class="dropdown-item" href="javascript:void(0)" onclick="app.setFilter('asset', '${asset}')">${asset}</a></li>`;
        });
        assetList.innerHTML = html;
    }

    render(fullHistory) {
        this.updateAssetFilterList(fullHistory);

        // 1. ОБНОВЛЕНИЕ ИНДИКАТОРОВ (ТОЧЕК)
        const filters = this.app.state.filters;
        const start = document.getElementById('filter-date-start').value;
        const end = document.getElementById('filter-date-end').value;

        const setDot = (id, visible) => {
            const el = document.getElementById(id);
            if (el) el.style.display = visible ? 'inline-block' : 'none';
        };

        setDot('dot-date', (start || end));
        setDot('dot-asset', (filters.asset !== 'ALL'));
        setDot('dot-type', (filters.type !== 'ALL'));
        setDot('dot-result', (filters.result !== 'ALL'));

        // 2. ФИЛЬТРАЦИЯ ПО СТАТУСУ (OPEN/CLOSED)
        let data = fullHistory.filter(trade => trade.status === this.app.state.currentView);

        // СОРТИРОВКА (ЛОГИКА С ПАРСИНГОМ ДАТ)
        data.sort((a, b) => {
            try {
                const currentView = this.app.state.currentView;
                const timeA_raw = (currentView === 'CLOSED' && a.exitTime) ? a.exitTime : a.entryTime;
                const timeB_raw = (currentView === 'CLOSED' && b.exitTime) ? b.exitTime : b.entryTime;

                if (!timeA_raw || !timeB_raw) return 0;

                const parseDate = (str) => {
                    const [datePart, timePart] = str.split(' | ');
                    const [d, m, y] = datePart.split('.');
                    const [hh, mm] = timePart ? timePart.split(':') : ['00', '00'];
                    return new Date(y, m - 1, d, hh, mm).getTime();
                };
                return parseDate(timeB_raw) - parseDate(timeA_raw);
            } catch (e) { return 0; }
        });

        // 3. ФИЛЬТР РЕЗУЛЬТАТОВ
        if (filters.asset !== 'ALL') data = data.filter(t => t.asset === filters.asset);
        if (filters.type !== 'ALL') data = data.filter(t => t.type === filters.type);
        if (filters.result !== 'ALL') {
            if (filters.result === 'WIN') data = data.filter(t => t.profit > 0);
            if (filters.result === 'LOSS') data = data.filter(t => t.profit < 0);
        }

        // 4. ФИЛЬТР ДАТ
        if (start || end) {
            data = data.filter(t => {
                const currentView = this.app.state.currentView;
                const timeStr = (currentView === 'CLOSED' && t.exitTime) ? t.exitTime : (t.entryTime || t.time);

                if (!timeStr || !timeStr.includes('.')) return false;

                try {
                    const datePart = timeStr.split(' | ')[0];
                    const [d, m, y] = datePart.split('.').map(Number);
                    const tradeTime = new Date(y, m - 1, d).getTime();

                    if (start) {
                        const [sy, sm, sd] = start.split('-').map(Number);
                        const startTime = new Date(sy, sm - 1, sd).getTime();
                        if (tradeTime < startTime) return false;
                    }
                    if (end) {
                        const [ey, em, ed] = end.split('-').map(Number);
                        const endTime = new Date(ey, em - 1, ed).getTime();
                        if (tradeTime > endTime) return false;
                    }
                    return true;
                } catch (e) { return false; }
            });
        }

        // 5. ОТРИСОВКА ЗАГОЛОВКОВ
        this.container.innerHTML = '';
        const actionHeader = document.getElementById('col-action-header');
        if (actionHeader) {
            actionHeader.style.display = 'table-cell';
            actionHeader.innerText = 'Действие';
        }

        const resultCell = document.getElementById('col-result-cell');
        if (this.app.state.currentView === 'OPEN') {
            if (resultCell) {
                const dotVisible = filters.result !== 'ALL' ? 'inline-block' : 'none';
                resultCell.innerHTML = `Текущий PnL <span id="dot-result" class="filter-dot" style="display: ${dotVisible}"></span>`;
            }
            document.getElementById('col-exit-header').innerText = 'Тек. цена';
        } else {
            if (resultCell) {
                const dotVisible = filters.result !== 'ALL' ? 'inline-block' : 'none';
                resultCell.innerHTML = `
                <div class="dropdown">
                  <span class="dropdown-toggle sortable" data-bs-toggle="dropdown">
                    Результат <span id="dot-result" class="filter-dot" style="display: ${dotVisible}"></span>
                  </span>
                  <ul class="dropdown-menu dropdown-menu-dark">
                    <li><a class="dropdown-item" href="javascript:void(0)" onclick="app.setFilter('result', 'ALL')">Все</a></li>
                    <li><a class="dropdown-item" href="javascript:void(0)" onclick="app.setFilter('result', 'WIN')">Прибыльные</a></li>
                    <li><a class="dropdown-item" href="javascript:void(0)" onclick="app.setFilter('result', 'LOSS')">Убыточные</a></li>
                  </ul>
                </div>`;
            }
            document.getElementById('col-exit-header').innerText = 'Выход';
            if (actionHeader) actionHeader.style.display = 'none';
        }

        if (data.length === 0) {
            this.container.innerHTML = `
                <tr><td colspan="12" class="text-center py-5 text-muted" style="background-color: #1e1e1e !important;">
                <i class="bi bi-search d-block mb-2" style="font-size: 2rem; opacity: 0.3;"></i>Сделок не найдено</td></tr>`;
            this.renderPagination(0);
            return;
        }

        // 6. ПАГИНАЦИЯ И РЕНДЕР СТРОК
        const startIndex = (this.app.state.currentPage - 1) * this.app.state.itemsPerPage;
        const paginatedItems = data.slice(startIndex, startIndex + this.app.state.itemsPerPage);

        paginatedItems.forEach(trade => {
            const row = document.createElement('tr');
            const timeFull = trade.entryTime || "— | —";
            const timeParts = timeFull.split(" | ");

            let timeDisplay = "";
            if (this.app.state.currentView === 'OPEN') {
                timeDisplay = timeParts[1] || "—";
            } else {
                let exitTimeOnly = "—";
                if (trade.exitTime && trade.exitTime.includes(" | ")) {
                    exitTimeOnly = trade.exitTime.split(" | ")[1];
                }
                timeDisplay = `<div class="d-flex align-items-center gap-1"><small class="text-muted">${timeParts[1]}</small><i class="bi bi-arrow-right text-secondary" style="font-size: 0.7rem;"></i><span>${exitTimeOnly}</span></div>`;
            }

            const entryPrice = parseFloat(trade.entryPrice) || 0;
            const displayPrice = parseFloat(trade.exitPrice) || entryPrice;
            const tradeVolume = parseFloat(trade.volume) || 0;
            const quantity = parseFloat(trade.quantity) || 0;

            const rawProfitPercent = entryPrice !== 0 ? ((displayPrice - entryPrice) / entryPrice) * 100 : 0;
            let finalPercent = trade.type === 'SHORT' ? -rawProfitPercent : rawProfitPercent;

            // Единая логика форматирования PnL (USDT и %)
            let profitUsdt = 0;
            let profitClass = "text-white";
            let profitText = "0.00 $";
            let percentText = "0.00%";

            if (this.app.state.currentView === 'OPEN') {
                profitUsdt = tradeVolume * (finalPercent / 100);
                if (profitUsdt > 0) {
                    profitText = `+${profitUsdt.toFixed(2)} $`;
                    profitClass = "profit-pos";
                } else if (profitUsdt < 0) {
                    profitText = `${profitUsdt.toFixed(2)} $`;
                    profitClass = "profit-neg";
                } else {
                    profitText = `${profitUsdt.toFixed(2)} $`;
                }

                percentText = profitUsdt > 0 ? `+${finalPercent.toFixed(2)}%` :
                    profitUsdt < 0 ? `${finalPercent.toFixed(2)}%` :
                        `${finalPercent.toFixed(2)}%`;
            } else {
                profitUsdt = trade.profit || 0;
                finalPercent = (tradeVolume > 0) ? (profitUsdt / tradeVolume) * 100 : 0;

                if (profitUsdt > 0) {
                    profitText = `+${profitUsdt.toFixed(2)} $`;
                    profitClass = "profit-pos";
                } else if (profitUsdt < 0) {
                    profitText = `${profitUsdt.toFixed(2)} $`;
                    profitClass = "profit-neg";
                } else {
                    profitText = `${profitUsdt.toFixed(2)} $`;
                }

                percentText = profitUsdt > 0 ? `+${finalPercent.toFixed(2)}%` :
                    profitUsdt < 0 ? `${finalPercent.toFixed(2)}%` :
                        `${finalPercent.toFixed(2)}%`;
            }

            const actionCell = this.app.state.currentView === 'OPEN'
                ? `<td><button class="btn btn-outline-danger btn-sm w-100" onclick="app.manualClose('${trade.asset}')">Завершить</button></td>`
                : '';

            row.innerHTML = `
                <td class="small text-muted">${timeParts[0]}</td>
                <td style="white-space: nowrap;">${timeDisplay}</td>
                <td><span class="badge bg-secondary">${trade.asset || '—'}</span></td>
                <td class="fw-bold text-info">${tradeVolume.toFixed(2)} $<br><small class="text-muted">${quantity.toFixed(6)}</small></td>
                <td><span class="badge ${trade.type === 'LONG' ? 'bg-success' : 'bg-danger'}">${trade.type || '—'}</span></td>
                <td style="white-space: nowrap;">${entryPrice.toLocaleString('en-US', { minimumFractionDigits: 2 })}</td>
                <td style="white-space: nowrap;">${displayPrice.toLocaleString('en-US', { minimumFractionDigits: 2 })}</td>
                <td class="${profitClass} fw-bold">${profitText}<br><small style="color: inherit;">(${percentText})</small></td>
                ${actionCell}`;
            this.container.appendChild(row);
        });

        this.renderPagination(data.length);
    }

    renderPagination(totalLength) {
        const controls = this.paginationRoot;
        if (!controls) return;

        controls.innerHTML = '';
        const totalPages = Math.ceil(totalLength / this.app.state.itemsPerPage);
        if (totalPages <= 1) return;

        const navGroup = document.createElement('div');
        navGroup.className = 'btn-group';
        const currentPage = this.app.state.currentPage;

        const createBtn = (html, page, disabled = false, active = false) => {
            const btn = document.createElement('button');
            btn.className = `btn btn-sm ${active ? 'btn-primary' : 'btn-outline-secondary'} ${disabled ? 'disabled' : ''}`;
            btn.innerHTML = html;
            if (!disabled) {
                btn.onclick = () => {
                    this.app.state.currentPage = page;
                    this.app.updateDashboard();
                };
            }
            navGroup.appendChild(btn);
        };

        createBtn('<i class="bi bi-chevron-double-left"></i>', 1, currentPage === 1);
        createBtn('<i class="bi bi-chevron-left"></i>', currentPage - 1, currentPage === 1);

        // Показываем ±2 страницы от текущей
        for (let i = Math.max(1, currentPage - 2); i <= Math.min(totalPages, currentPage + 2); i++) {
            createBtn(i, i, false, i === currentPage);
        }

        createBtn('<i class="bi bi-chevron-right"></i>', currentPage + 1, currentPage === totalPages);
        createBtn('<i class="bi bi-chevron-double-right"></i>', totalPages, currentPage === totalPages);

        controls.appendChild(navGroup);
    }
}