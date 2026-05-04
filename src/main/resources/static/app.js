const state = {
    workbook: null,
    mappings: [],
    taskId: null,
    pollTimer: null
};

const els = {
    healthStatus: document.querySelector("#healthStatus"),
    questionnaireUrl: document.querySelector("#questionnaireUrl"),
    excelFile: document.querySelector("#excelFile"),
    executionMode: document.querySelector("#executionMode"),
    intervalSeconds: document.querySelector("#intervalSeconds"),
    maxRows: document.querySelector("#maxRows"),
    startRow: document.querySelector("#startRow"),
    endRow: document.querySelector("#endRow"),
    fillDurationMinSeconds: document.querySelector("#fillDurationMinSeconds"),
    fillDurationMaxSeconds: document.querySelector("#fillDurationMaxSeconds"),
    sourceRatioMobile: document.querySelector("#sourceRatioMobile"),
    sourceRatioLink: document.querySelector("#sourceRatioLink"),
    sourceRatioWechat: document.querySelector("#sourceRatioWechat"),
    submitEnabled: document.querySelector("#submitEnabled"),
    uploadButton: document.querySelector("#uploadButton"),
    workbookSummary: document.querySelector("#workbookSummary"),
    previewTable: document.querySelector("#previewTable"),
    mappingTableBody: document.querySelector("#mappingTable tbody"),
    copyHeaderButton: document.querySelector("#copyHeaderButton"),
    startButton: document.querySelector("#startButton"),
    cancelButton: document.querySelector("#cancelButton"),
    statusValue: document.querySelector("#statusValue"),
    completedValue: document.querySelector("#completedValue"),
    failedValue: document.querySelector("#failedValue"),
    logList: document.querySelector("#logList")
};

async function api(path, options = {}) {
    const response = await fetch(path, options);
    if (!response.ok) {
        const text = await response.text();
        let message = text;
        try {
            message = JSON.parse(text).error || text;
        } catch (_) {
            // Keep the raw response.
        }
        throw new Error(message);
    }
    return response.json();
}

async function checkHealth() {
    try {
        await api("/api/health");
        els.healthStatus.textContent = "服务正常";
    } catch (error) {
        els.healthStatus.textContent = "服务异常";
    }
}

async function uploadWorkbook() {
    const file = els.excelFile.files[0];
    if (!file) {
        alert("请选择 Excel 文件。");
        return;
    }

    const form = new FormData();
    form.append("file", file);
    els.uploadButton.disabled = true;
    els.uploadButton.textContent = "上传中";
    try {
        state.workbook = await api("/api/workbooks", {
            method: "POST",
            body: form
        });
        state.mappings = state.workbook.headers.map(header => {
            const questionNumber = questionNumberPrefix(header);
            const isQuestionColumn = questionNumber !== null;
            return {
                excelColumn: header,
                excelColumns: [header],
                questionTitle: isQuestionColumn ? header : "",
                questionType: isQuestionColumn ? "AUTO" : "TEXT",
                valueMode: isQuestionColumn ? "ORDINAL" : "TEXT",
                required: isQuestionColumn,
                offset: isQuestionColumn ? questionNumber : 0
            };
        });
        renderWorkbook();
        renderMappings();
        els.startButton.disabled = false;
    } catch (error) {
        alert(error.message);
    } finally {
        els.uploadButton.disabled = false;
        els.uploadButton.textContent = "上传并预览 Excel";
    }
}

function renderWorkbook() {
    const workbook = state.workbook;
    els.workbookSummary.classList.remove("empty-state");
    els.workbookSummary.textContent = `${workbook.originalFileName}，共 ${workbook.rowCount} 行数据，预览前 ${workbook.rows.length} 行`;

    const headers = workbook.headers;
    const head = `<thead><tr>${headers.map(h => `<th>${escapeHtml(h)}</th>`).join("")}</tr></thead>`;
    const body = workbook.rows.map(row => {
        return `<tr>${headers.map(header => `<td>${escapeHtml(row[header] || "")}</td>`).join("")}</tr>`;
    }).join("");
    els.previewTable.innerHTML = `${head}<tbody>${body}</tbody>`;
}

function renderMappings() {
    els.mappingTableBody.innerHTML = state.mappings.map((mapping, index) => `
        <tr>
            <td>${escapeHtml(mapping.excelColumn)}</td>
            <td><input data-index="${index}" data-field="questionTitle" value="${escapeAttr(mapping.questionTitle)}" placeholder="问卷中的题目文字"></td>
            <td>
                <select data-index="${index}" data-field="questionType">
                    ${questionTypeOption("AUTO", "自动判断", mapping.questionType)}
                    ${questionTypeOption("TEXT", "填空题", mapping.questionType)}
                    ${questionTypeOption("SINGLE_CHOICE", "单选题", mapping.questionType)}
                    ${questionTypeOption("MULTIPLE_CHOICE", "多选题", mapping.questionType)}
                    ${questionTypeOption("SCALE", "量表题", mapping.questionType)}
                    ${questionTypeOption("SELECT", "下拉题", mapping.questionType)}
                </select>
            </td>
            <td>
                <select data-index="${index}" data-field="valueMode">
                    ${valueModeOption("TEXT", "按文本/原值", mapping.valueMode)}
                    ${valueModeOption("ORDINAL", "按序号选第 N 项", mapping.valueMode)}
                    ${valueModeOption("MULTI_BINARY_COLUMNS", "多选拆列 0/1", mapping.valueMode)}
                </select>
            </td>
            <td><input data-index="${index}" data-field="excelColumns" value="${escapeAttr((mapping.excelColumns || [mapping.excelColumn]).join(" | "))}" placeholder="多选拆列时用 | 分隔多个 Excel 列名"></td>
            <td class="checkbox-cell"><input type="checkbox" data-index="${index}" data-field="required" ${mapping.required ? "checked" : ""}></td>
        </tr>
    `).join("");
}

function questionTypeOption(value, label, current) {
    return `<option value="${value}" ${value === current ? "selected" : ""}>${label}</option>`;
}

function valueModeOption(value, label, current) {
    return `<option value="${value}" ${value === current ? "selected" : ""}>${label}</option>`;
}

function updateMapping(event) {
    const target = event.target;
    const index = Number(target.dataset.index);
    const field = target.dataset.field;
    if (!Number.isInteger(index) || !field) {
        return;
    }
    if (field === "required") {
        state.mappings[index][field] = target.checked;
        return;
    }
    if (field === "excelColumns") {
        state.mappings[index][field] = splitColumns(target.value);
        return;
    }
    state.mappings[index][field] = target.value;
}

function copyHeadersToQuestions() {
    state.mappings = state.mappings.map(mapping => ({
        ...mapping,
        questionTitle: mapping.questionTitle || mapping.excelColumn
    }));
    renderMappings();
}

async function startTask() {
    if (!state.workbook) {
        alert("请先上传 Excel。");
        return;
    }
    const request = {
        workbookId: state.workbook.workbookId,
        questionnaireUrl: els.questionnaireUrl.value.trim(),
        mappings: state.mappings
            .filter(mapping => mapping.questionTitle.trim())
            .map(mapping => ({
                ...mapping,
                excelColumns: mapping.excelColumns && mapping.excelColumns.length ? mapping.excelColumns : [mapping.excelColumn]
            })),
        mode: els.executionMode.value,
        intervalSeconds: Number(els.intervalSeconds.value || 3),
        maxRows: els.maxRows.value ? Number(els.maxRows.value) : null,
        startRow: Number(els.startRow.value || 1),
        endRow: els.endRow.value ? Number(els.endRow.value) : null,
        fillDurationMinSeconds: Number(els.fillDurationMinSeconds.value || 100),
        fillDurationMaxSeconds: Number(els.fillDurationMaxSeconds.value || 200),
        sourceRatioMobile: Number(els.sourceRatioMobile.value || 0),
        sourceRatioLink: Number(els.sourceRatioLink.value || 0),
        sourceRatioWechat: Number(els.sourceRatioWechat.value || 0),
        changeIp: document.querySelector('input[name="changeIp"]:checked').value === "true",
        submitEnabled: els.submitEnabled.checked
    };
    if (!request.questionnaireUrl) {
        alert("请输入问卷链接。");
        return;
    }
    if (request.mappings.length === 0) {
        alert("至少需要一条字段映射。");
        return;
    }
    if (request.endRow !== null && request.endRow < request.startRow) {
        alert("结束份数必须大于或等于起始份数。");
        return;
    }
    if (request.fillDurationMinSeconds < 1 || request.fillDurationMaxSeconds < request.fillDurationMinSeconds) {
        alert("填写用时最大秒数必须大于或等于最小秒数。");
        return;
    }
    const sourceTotal = request.sourceRatioMobile + request.sourceRatioLink + request.sourceRatioWechat;
    if (sourceTotal !== 100) {
        alert("提交来源比例总和必须为 100%。");
        return;
    }

    els.startButton.disabled = true;
    els.cancelButton.disabled = false;
    try {
        const task = await api("/api/tasks", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(request)
        });
        state.taskId = task.id;
        renderTask(task);
        startPolling();
    } catch (error) {
        alert(error.message);
        els.startButton.disabled = false;
        els.cancelButton.disabled = true;
    }
}

async function cancelTask() {
    if (!state.taskId) {
        return;
    }
    try {
        const task = await api(`/api/tasks/${state.taskId}/cancel`, { method: "POST" });
        renderTask(task);
    } catch (error) {
        alert(error.message);
    }
}

function startPolling() {
    clearInterval(state.pollTimer);
    state.pollTimer = setInterval(async () => {
        if (!state.taskId) {
            return;
        }
        try {
            const task = await api(`/api/tasks/${state.taskId}`);
            renderTask(task);
            if (["COMPLETED", "FAILED", "CANCELLED"].includes(task.status)) {
                clearInterval(state.pollTimer);
                els.startButton.disabled = false;
                els.cancelButton.disabled = true;
            }
        } catch (error) {
            clearInterval(state.pollTimer);
            addLocalLog("error", error.message);
        }
    }, 1000);
}

function renderTask(task) {
    els.statusValue.textContent = task.status;
    els.completedValue.textContent = String(task.completedRows);
    els.failedValue.textContent = String(task.failedRows);
    els.logList.innerHTML = task.logs.slice().reverse().map(entry => `
        <div class="log-item ${escapeAttr(entry.level)}">
            <div class="log-meta">${escapeHtml(entry.timestamp)}${entry.rowNumber ? ` · 第 ${entry.rowNumber} 行` : ""}</div>
            <div class="log-message">${escapeHtml(entry.message)}</div>
            ${entry.screenshotUrl ? `<a class="log-link" href="${escapeAttr(entry.screenshotUrl)}" target="_blank">查看截图</a>` : ""}
        </div>
    `).join("");
}

function addLocalLog(level, message) {
    const now = new Date().toISOString();
    els.logList.insertAdjacentHTML("afterbegin", `
        <div class="log-item ${escapeAttr(level)}">
            <div class="log-meta">${escapeHtml(now)}</div>
            <div class="log-message">${escapeHtml(message)}</div>
        </div>
    `);
}

function escapeHtml(value) {
    return String(value)
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function escapeAttr(value) {
    return escapeHtml(value).replaceAll("`", "&#096;");
}

function splitColumns(value) {
    return String(value)
        .split(/\s*(?:\||\n)\s*/)
        .map(item => item.trim())
        .filter(Boolean);
}

function questionNumberPrefix(value) {
    const match = String(value).match(/^\s*(\d+)[.、．]\s*/);
    return match ? Number(match[1]) : null;
}

// ====================== 提交来源比例自动保持100%（已修复） ======================
function linkSourceRatios() {
    const mobile = els.sourceRatioMobile;
    const linkEl = els.sourceRatioLink;
    const wechat = els.sourceRatioWechat;

    const inputs = [mobile, linkEl, wechat];

    function adjustOthers(changed) {
        let m = parseFloat(mobile.value) || 0;
        let l = parseFloat(linkEl.value) || 0;
        let w = parseFloat(wechat.value) || 0;

        const sum = m + l + w;
        if (sum === 0) {
            mobile.value = 33;
            linkEl.value = 33;
            wechat.value = 34;
            return;
        }

        const diff = 100 - sum;
        if (diff === 0) {
            return;
        }

        const adjust = diff / 2;

        if (changed === mobile) {
            linkEl.value = Math.max(0, Math.round(l + adjust));
            wechat.value = Math.max(0, Math.round(w + (diff - adjust)));
        } else if (changed === linkEl) {
            mobile.value = Math.max(0, Math.round(m + adjust));
            wechat.value = Math.max(0, Math.round(w + (diff - adjust)));
        } else {
            mobile.value = Math.max(0, Math.round(m + adjust));
            linkEl.value = Math.max(0, Math.round(l + (diff - adjust)));
        }
    }

    inputs.forEach(input => {
        input.addEventListener("input", () => adjustOthers(input));
    });
}

els.uploadButton.addEventListener("click", uploadWorkbook);
els.mappingTableBody.addEventListener("input", updateMapping);
els.mappingTableBody.addEventListener("change", updateMapping);
els.copyHeaderButton.addEventListener("click", copyHeadersToQuestions);
els.startButton.addEventListener("click", startTask);
els.cancelButton.addEventListener("click", cancelTask);

linkSourceRatios();
checkHealth();

window.addEventListener('load', () => {
    fetch('/api/automation/profiles')
        .then(r => {
            if (!r.ok) throw new Error('HTTP ' + r.status);
            return r.json();
        })
        .then(data => {
            console.log('%c[Stealth] Loaded Automation Profiles:', data);
        })
        .catch(err => {
            console.warn('[Stealth] Failed to load profiles; backend may not be ready yet', err);
        });
});


// ====================== 强制启用 Stealth 模式 ======================
console.log('%c[Debug] 强制启用 local-demo-enhanced 配置', 'color: #f59e0b; font-size: 14px');

// 拦截任务提交，强制添加 automationProfileId
const originalFetch = window.fetch;
window.fetch = async function(url, options = {}) {
    if (typeof url === 'string' && url.includes('/api/tasks') && options.method === 'POST') {
        try {
            if (!options.body || typeof options.body !== 'string') {
                return originalFetch.call(this, url, options);
            }
            const body = JSON.parse(options.body);
            // 强制使用增强配置
            body.automationProfileId = "local-demo-enhanced";
            options.body = JSON.stringify(body);
            
            console.log('%c[Stealth] 已强制启用 local-demo-enhanced 配置', 'color: #22c55e; font-weight: bold');
        } catch(e) {
            console.warn('[Stealth] Error while intercepting fetch', e);
        }
    }
    return originalFetch.call(this, url, options);
};

// 同时在页面加载时输出提示
window.addEventListener('load', () => {
    console.log('%c[Stealth] Stealth 强制注入已启用 - 使用 local-demo-enhanced', 'color: #22c55e; font-size: 13px');
});
