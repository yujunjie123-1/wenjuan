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
        state.mappings = state.workbook.headers.map(header => ({
            excelColumn: header,
            questionTitle: "",
            questionType: "TEXT",
            required: true
        }));
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
                    ${questionTypeOption("TEXT", "填空题", mapping.questionType)}
                    ${questionTypeOption("SINGLE_CHOICE", "单选题", mapping.questionType)}
                    ${questionTypeOption("MULTIPLE_CHOICE", "多选题", mapping.questionType)}
                    ${questionTypeOption("SELECT", "下拉题", mapping.questionType)}
                </select>
            </td>
            <td class="checkbox-cell"><input type="checkbox" data-index="${index}" data-field="required" ${mapping.required ? "checked" : ""}></td>
        </tr>
    `).join("");
}

function questionTypeOption(value, label, current) {
    return `<option value="${value}" ${value === current ? "selected" : ""}>${label}</option>`;
}

function updateMapping(event) {
    const target = event.target;
    const index = Number(target.dataset.index);
    const field = target.dataset.field;
    if (!Number.isInteger(index) || !field) {
        return;
    }
    state.mappings[index][field] = field === "required" ? target.checked : target.value;
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
        mappings: state.mappings.filter(mapping => mapping.questionTitle.trim()),
        mode: els.executionMode.value,
        intervalSeconds: Number(els.intervalSeconds.value || 3),
        maxRows: Number(els.maxRows.value || 5),
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

els.uploadButton.addEventListener("click", uploadWorkbook);
els.mappingTableBody.addEventListener("input", updateMapping);
els.mappingTableBody.addEventListener("change", updateMapping);
els.copyHeaderButton.addEventListener("click", copyHeadersToQuestions);
els.startButton.addEventListener("click", startTask);
els.cancelButton.addEventListener("click", cancelTask);

checkHealth();
