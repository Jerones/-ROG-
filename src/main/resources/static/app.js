// ============================================================
// 施工安全规范智能问答系统 前端逻辑（原生 JS，无框架）
// 两个菜单：对话（chat） / 知识库（kb），通过 switchMenu 切换
// 对话走 SSE 流式接口：token 事件拼正文，citations 事件带引用
// ============================================================

const $ = (id) => document.getElementById(id);

/** 文档列表缓存：弹层定位、引用跳转都靠它把文档名换成 docId，所以放在最前面。 */
let docsCache = [];

/**
 * 最近若干轮对话（user / assistant 交替），只用于「追问改写」。
 * 后端拿它把「那颜色呢」补全成可独立检索的问句，不参与最终 Prompt 的上下文拼接。
 */
let chatHistory = [];

/** 限定检索的文档 id；空数组 = 全库。与知识库文档列表联动（删文档时自动剔除）。 */
let scopeDocIds = [];

// ---------------- 菜单切换 ----------------
/**
 * @param silent 初始化时用：只切页面，不往地址栏写 view 参数（避免无意义地改 URL）
 */
function switchMenu(name, silent) {
    $('menuChat').classList.toggle('active', name === 'chat');
    $('menuKb').classList.toggle('active', name === 'kb');
    $('viewChat').classList.toggle('hidden', name !== 'chat');
    $('viewKb').classList.toggle('hidden', name !== 'kb');
    if (!silent) {
        // 记住当前所在页：刷新后不会跳回对话，地址也可以直接分享
        const url = new URL(location.href);
        url.searchParams.set('view', name);
        history.replaceState(null, '', url);
    }
    if (name === 'kb') {
        loadStats();
        loadDocuments();
    }
}

function scrollMessages() {
    const box = $('messages');
    box.scrollTop = box.scrollHeight;
}

// ---------------- 轻提示 ----------------
/**
 * 顶部飘一条提示，2.2 秒后自己消失。
 * 只用于"复制成功""删除失败"这类不需要用户确认的反馈；
 * 删除之类的破坏性操作仍然走 confirm，不能用 toast 当确认框。
 */
function toast(text, kind) {
    const host = $('toastHost');
    const el = document.createElement('div');
    el.className = 'toast' + (kind === 'bad' ? ' bad' : '');
    el.textContent = text;
    host.appendChild(el);
    // 下一帧再加 show，否则 opacity 过渡不会触发
    requestAnimationFrame(() => el.classList.add('show'));
    setTimeout(() => {
        el.classList.remove('show');
        setTimeout(() => el.remove(), 220);
    }, 2200);
}

/** 复制纯文本；http 页面拿不到 clipboard API，退回到 execCommand。 */
async function copyText(text) {
    if (!text) return false;
    try {
        if (navigator.clipboard && window.isSecureContext) {
            await navigator.clipboard.writeText(text);
            return true;
        }
    } catch (e) { /* 继续走兜底 */ }
    try {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.select();
        const ok = document.execCommand('copy');
        ta.remove();
        return ok;
    } catch (e) {
        return false;
    }
}

/** 空状态 HTML：首次进入和「新对话」共用一份，免得两处改漏。 */
function emptyStateHtml() {
    return '<h1 class="empty-title">施工安全规范问答</h1>' +
        '<p class="empty-sub">基于 RAG 的规范检索助手，回答只依据知识库原文，并附引用出处。</p>' +
        '<p class="empty-stat" id="emptyStat"></p>' +
        '<div class="example-chips">' +
        '  <button class="chip" onclick="askExample(\'安全帽佩戴有什么要求？\')">安全帽佩戴有什么要求？</button>' +
        '  <button class="chip" onclick="askExample(\'脚手架搭设有哪些规范？\')">脚手架搭设有哪些规范？</button>' +
        '  <button class="chip" onclick="askExample(\'高处作业如何防护？\')">高处作业如何防护？</button>' +
        '  <button class="chip" onclick="askExample(\'施工现场临时用电有什么要求？\')">施工现场临时用电有什么要求？</button>' +
        '</div>';
}

/** 空状态里那行"知识库已收录 N 篇…"。统计还没回来时留空，不写占位文字。 */
let lastStats = null;

function fillEmptyStat() {
    const el = $('emptyStat');
    if (!el) return;
    if (!lastStats) {
        el.textContent = '';
        return;
    }
    el.textContent = lastStats.documentCount > 0
        ? '知识库已收录 ' + lastStats.documentCount + ' 篇规范 · ' + lastStats.chunkCount + ' 个文本块'
        : '知识库还是空的，先到「知识库」上传规范';
}

/** 清空当前对话，回到最初的空状态。 */
function newChat() {
    if (streaming) stopStream();
    // 上下文一起清掉：否则新对话的第一问会莫名其妙被上一轮「改写」
    chatHistory = [];
    $('msgCol').innerHTML = '';
    $('messages').scrollTop = 0;
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.id = 'chatEmpty';
    empty.innerHTML = emptyStateHtml();
    $('msgCol').appendChild(empty);
    fillEmptyStat();
    $('questionInput').focus();
}

// ---------------- 对话 ----------------
$('questionInput').addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendQuestion();
    }
});

// 输入框高度随内容自适应
$('questionInput').addEventListener('input', (e) => {
    e.target.style.height = 'auto';
    e.target.style.height = Math.min(e.target.scrollHeight, 160) + 'px';
});

/** 示例问题胶囊：点击直接发送。 */
function askExample(q) {
    $('questionInput').value = q;
    sendQuestion();
}

/**
 * 追加一条消息。
 * user：右对齐浅蓝气泡；assistant：通栏纯文本 + 左侧小蓝圆点标识，
 * 正文与引用都挂在 .msg-body 里。
 */
function appendMessage(role, text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + role;
    if (role === 'assistant') {
        const dot = document.createElement('span');
        dot.className = 'avatar-dot';
        wrap.appendChild(dot);
        const body = document.createElement('div');
        body.className = 'msg-body';
        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        body.appendChild(bubble);
        wrap.appendChild(body);
    } else {
        const bubble = document.createElement('div');
        bubble.className = 'bubble';
        bubble.textContent = text;
        wrap.appendChild(bubble);
    }
    $('msgCol').appendChild(wrap);
    scrollMessages();
    return wrap;
}

/** 渲染引用溯源：折叠卡片，第一条默认展开，其余点击展开。 */
function renderCitations(msgWrap, citations) {
    if (!citations || citations.length === 0) return;
    const body = msgWrap.querySelector('.msg-body') || msgWrap;
    const box = document.createElement('div');
    box.className = 'citations';
    citations.forEach((c, i) => {
        const card = document.createElement('div');
        card.className = 'citation-card' + (i === 0 ? ' open' : '');
        const pageText = (c.page !== null && c.page !== undefined) ? ' · 第' + c.page + '页' : '';
        const pct = Math.max(0, Math.min(100, Number(c.score) * 100)).toFixed(0);
        card.innerHTML =
            '<div class="citation-head"><span class="citation-title">[' + (i + 1) + '] ' +
            escapeHtml(c.docName) + ' · ' + escapeHtml(c.clause) + pageText + '</span>' +
            '<span class="citation-right">' +
            '  <span class="cite-score-wrap" title="混合检索最终分数">' +
            '    <span class="cite-bar"><i style="width:' + pct + '%"></i></span>' +
            '    <span class="citation-score">' + Number(c.score).toFixed(3) + '</span>' +
            '  </span>' +
            '  <button class="cite-open" title="在这篇文档的块原文里定位该条款">原文</button>' +
            '  <span class="citation-arrow">▾</span></span></div>' +
            '<div class="citation-snippet">' + escapeHtml(c.snippet) + '</div>';
        card.querySelector('.citation-head').addEventListener('click', () => {
            card.classList.toggle('open');
        });
        // 「原文」按钮不参与折叠，单独拦掉冒泡
        card.querySelector('.cite-open').addEventListener('click', (e) => {
            e.stopPropagation();
            openDocAtClause(c.docName, c.clause);
        });
        box.appendChild(card);
    });
    body.appendChild(box);
    scrollMessages();
}

/**
 * 从引用卡片跳到该文档的块弹层，并按条款号过滤。
 * 引用里只有文档名，所以要先用文档列表把它换成 docId；
 * 列表还没加载过（用户直接进的对话页）就现拉一次。
 */
async function openDocAtClause(docName, clause) {
    let doc = docsCache.find((d) => d.docName === docName);
    if (!doc) {
        try {
            docsCache = await (await fetch('/api/kb/documents')).json();
            doc = docsCache.find((d) => d.docName === docName);
        } catch (e) { /* 拉不到就走下面的提示 */ }
    }
    if (!doc) {
        toast('知识库里没找到这篇文档，可能已被删除', 'bad');
        return;
    }
    openChunks(doc.docId, doc.docName, clause === '正文片段' ? '' : clause);
}

/** 回答下方的元信息 + 复制按钮。 */
function appendMsgTools(msgWrap, opts) {
    const body = msgWrap.querySelector('.msg-body');
    if (!body) return;
    const box = document.createElement('div');
    box.className = 'msg-tools';
    const secs = (opts.elapsed / 1000).toFixed(1);
    box.innerHTML =
        '<button class="msg-tool" title="复制回答与引用">' +
        '  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">' +
        '    <rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15V5a2 2 0 0 1 2-2h10"/></svg>' +
        '  <span>复制</span></button>' +
        (opts.citations > 0 ? '<span>' + opts.citations + ' 条引用</span>' : '') +
        '<span>用时 ' + secs + ' 秒</span>' +
        (opts.stopped ? '<span class="msg-stopped">已手动停止</span>' : '');
    const btn = box.querySelector('.msg-tool');
    btn.onclick = async () => {
        const ok = await copyText(opts.text());
        toast(ok ? '已复制到剪贴板' : '复制失败，请手动选中复制', ok ? '' : 'bad');
    };
    body.appendChild(box);
    scrollMessages();
}

/**
 * 追问被改写时，在回答上方标一行「结合上下文理解为…」。
 * 这不是装饰：改写出错时用户能立刻看出系统理解偏了，而不是对着一个
 * 答非所问的结果猜哪里出了问题。
 */
function showRewriteNote(msgWrap, rewritten) {
    const body = msgWrap.querySelector('.msg-body');
    if (!body || !rewritten) return;
    const note = document.createElement('div');
    note.className = 'rewrite-note';
    note.innerHTML = '<span class="rewrite-label">结合上下文理解为</span>' +
        '<span class="rewrite-text">' + escapeHtml(rewritten) + '</span>';
    body.insertBefore(note, body.firstChild);
    scrollMessages();
}

/** 生成中的状态；null 表示空闲。发送键据此决定自己是"发送"还是"停止"。 */
let streaming = null;

function onSendClick() {
    if (streaming) stopStream();
    else sendQuestion();
}

function setSending(on) {
    const btn = $('sendBtn');
    btn.classList.toggle('stopping', on);
    btn.title = on ? '停止生成' : '发送';
    $('sendIcon').classList.toggle('hidden', on);
    $('stopIcon').classList.toggle('hidden', !on);
}

function stopStream() {
    if (streaming) streaming.controller.abort();
}

/** 三点跳动等待态：提问后先给个"正在干活"的反馈，首个 token 到达时清掉。 */
function showTyping(bubble, label) {
    bubble.innerHTML = '<span class="typing"><span class="typing-dots"><i></i><i></i><i></i></span>' +
        '<span>' + escapeHtml(label) + '</span></span>';
}

/**
 * 极简 Markdown 渲染：只处理模型实际会输出的三种东西——加粗、行内代码、编号/项目列表。
 * 流式过程中不动它（逐 token 重排会让光标乱跳），等回答收尾时一次性渲染。
 * 先 escapeHtml 再替换，避免正文里的尖括号被当成标签。
 */
function renderMarkdown(text) {
    const inline = (s) => s
        .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
        .replace(/`([^`]+)`/g, '<code>$1</code>');
    const lines = escapeHtml(text).split('\n');
    let html = '';
    for (const raw of lines) {
        const line = raw.trim();
        if (!line) continue;
        // 编号后可能紧跟星号（模型常写 "2.**加粗**..."），所以空格可有可无；
        // 但 "(?!\d)" 保证不会把行首的条款号 3.13.3 当成编号 3.
        const ol = line.match(/^(\d{1,2})[.、)）](?!\d)\s*(.*)$/);
        const ul = line.match(/^[-*·]\s+(.*)$/);
        if (ol) {
            html += '<div class="md-row"><span class="md-num">' + ol[1] + '.</span><span>' + inline(ol[2]) + '</span></div>';
        } else if (ul) {
            html += '<div class="md-row"><span class="md-dot">•</span><span>' + inline(ul[1]) + '</span></div>';
        } else {
            html += '<p>' + inline(line) + '</p>';
        }
    }
    return html;
}

async function sendQuestion() {
    if (streaming) return; // 已经在生成，连点也不重复发
    const input = $('questionInput');
    const question = input.value.trim();
    if (!question) return;
    input.value = '';
    input.style.height = 'auto';
    const empty = $('chatEmpty');
    if (empty) empty.classList.add('hidden');
    // 历史必须在追加本轮之前取：否则当前问题会被当成"上一轮"，改写时重复一遍
    const historyForRequest = chatHistory.slice(-6);
    appendMessage('user', question);

    const msgWrap = appendMessage('assistant', '');
    const bubble = msgWrap.querySelector('.bubble');
    showTyping(bubble, '正在检索规范…');
    setSending(true);

    const controller = new AbortController();
    const startedAt = Date.now();
    streaming = { controller: controller, bubble: bubble, msgWrap: msgWrap };
    let citations = [];
    let streamText = null; // 首个 token 到了才建（避免光标比正文先出现）
    let stopped = false;

    try {
        // 调用流式接口：逐个接收 token 事件拼接正文，
        // 结束时 citations 事件带回引用列表
        const resp = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                question: question,
                topK: Number($('topkSelect').value),
                history: historyForRequest,
                // 空数组 = 全库；后端只在非空时下推 where 过滤
                docIds: scopeDocIds
            }),
            signal: controller.signal
        });
        if (!resp.ok || !resp.body) {
            // 后端统一返回 {status, message}，把原因原样显示，别只丢一个 500 给用户
            let reason = 'HTTP ' + resp.status;
            try {
                const err = await resp.json();
                if (err && err.message) reason = err.message;
            } catch (e) { /* 不是 JSON 就用状态码 */ }
            throw new Error(reason);
        }

        const reader = resp.body.getReader();
        const decoder = new TextDecoder('utf-8');
        let buffer = '';
        while (true) {
            const { value, done } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            // SSE 以空行分隔事件
            const parts = buffer.split('\n\n');
            buffer = parts.pop();
            for (const part of parts) {
                const ev = parseSse(part);
                if (!ev) continue;
                if (ev.event === 'token') {
                    if (!streamText) {
                        // 第一段正文到达：把等待态换成正文 + 闪烁光标
                        bubble.innerHTML = '<span class="stream-text"></span><span class="stream-caret"></span>';
                        streamText = bubble.querySelector('.stream-text');
                    }
                    streamText.textContent += ev.data;
                    scrollMessages();
                } else if (ev.event === 'rewrite') {
                    showRewriteNote(msgWrap, ev.data);
                } else if (ev.event === 'citations') {
                    try { citations = JSON.parse(ev.data); } catch (e) { /* 忽略解析失败 */ }
                }
            }
        }
    } catch (err) {
        if (err.name === 'AbortError') {
            stopped = true;
        } else {
            bubble.textContent = '请求出错：' + err.message;
            setSending(false);
            streaming = null;
            return;
        }
    }

    // 收尾：去掉光标、兜底空内容、渲染引用与工具条
    const caret = bubble.querySelector('.stream-caret');
    if (caret) caret.remove();
    const answer = streamText ? streamText.textContent : '';

    // 本轮记入上下文，供下一轮追问改写。答错/无召回也记：
    // 否则用户接着问「那这条呢」时历史会断档，改写就失去依据。
    // isConnected 判断防一个边角情况：生成中点了「新对话」，旧请求收尾时
    // 不能再往刚清空的上下文里塞历史，否则新对话第一问会被上一轮污染。
    if (msgWrap.isConnected) {
        chatHistory.push({ role: 'user', content: question });
        chatHistory.push({ role: 'assistant', content: answer || '（未找到相关内容）' });
        if (chatHistory.length > 20) chatHistory = chatHistory.slice(-20);
    }

    if (!answer) {
        bubble.textContent = stopped ? '（已停止）' : '（无回答内容）';
    } else {
        // 流式时是纯文本，收尾这里才渲染成带段落/列表的排版
        bubble.innerHTML = renderMarkdown(answer);
        bubble.classList.add('md');
    }
    renderCitations(msgWrap, citations);
    appendMsgTools(msgWrap, {
        elapsed: Date.now() - startedAt,
        citations: citations.length,
        stopped: stopped,
        text: () => {
            const lines = [answer];
            if (citations.length) {
                lines.push('', '参考资料：');
                citations.forEach((c, i) => {
                    const page = (c.page !== null && c.page !== undefined) ? '，第' + c.page + '页' : '';
                    lines.push('[' + (i + 1) + '] ' + c.docName + ' · ' + c.clause + page);
                    if (c.snippet) lines.push(c.snippet);
                });
            }
            return lines.join('\n');
        }
    });

    streaming = null;
    setSending(false);
}

$('sendBtn').onclick = onSendClick;

/** 解析一个 SSE 事件块（多行 data: 需要拼接）。 */
function parseSse(block) {
    let event = 'message';
    const dataLines = [];
    for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).replace(/^ /, ''));
    }
    if (dataLines.length === 0) return null;
    return { event: event, data: dataLines.join('\n') };
}

// ---------------- 知识库 ----------------
async function loadStats() {
    try {
        const s = await (await fetch('/api/kb/stats')).json();
        // 知识库页统计卡片
        $('statDocs').textContent = s.documentCount;
        $('statChunks').textContent = s.chunkCount;
        $('statDim').textContent = s.vectorDimension > 0 ? s.vectorDimension : '—';
        $('statModel').textContent = s.embeddingModel;
        $('statStore').textContent = s.vectorStore;
        // 边栏小卡片与状态提示
        $('sideStatDocs').textContent = s.documentCount;
        $('sideStatChunks').textContent = s.chunkCount;
        $('demoBadge').classList.toggle('hidden', !s.demoMode);
        $('modeHint').textContent = (s.demoMode ? '演示模式' : '真实模型') + ' · ' + s.vectorStore;
        $('storeFoot').textContent = String(s.vectorStore).replace(/VectorStore$/, '') + ' · ' + (s.vectorDimension > 0 ? s.vectorDimension + ' 维' : '—');
        // 空状态里的"已收录 N 篇"也靠这份统计
        lastStats = s;
        fillEmptyStat();
    } catch (e) { /* 统计加载失败不阻塞页面 */ }
}

/** 过滤与排序都在前端做：几百篇的 JSON 本来就已经下发了，没必要再往返一次。 */
let docKeyword = '';
let docSort = { key: '', dir: 1 }; // key 为空 = 保持后端返回的原顺序

const SORT_VALUE = {
    name: (d) => d.docName || '',
    size: (d) => d.fileSize || 0,
    chunks: (d) => d.chunkCount || 0,
    time: (d) => d.ingestedAt || ''
};

async function loadDocuments() {
    const tbody = $('docTableBody');
    try {
        docsCache = (await (await fetch('/api/kb/documents')).json()) || [];
        // 被删掉的文档要从「检索范围」里剔除：否则会拿一个不存在的 docId 去过滤，
        // 结果就是明明库里有东西、提问却始终无召回，很难排查
        if (scopeDocIds.length) {
            const alive = new Set(docsCache.map((d) => d.docId));
            scopeDocIds = scopeDocIds.filter((id) => alive.has(id));
        }
        refreshScopeBtn();
        renderDocs();
    } catch (e) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty">加载失败：' + escapeHtml(e.message) + '</td></tr>';
        $('docSummary').innerHTML = '';
    }
}

/** 状态文案映射成徽标：现在只有"已入库"，但失败/处理中的样式先备好。 */
function statusPill(status) {
    const s = String(status || '');
    let cls = 'pill-idle';
    if (/已入库|完成|ready|done/i.test(s)) cls = 'pill-ok';
    else if (/失败|错误|fail/i.test(s)) cls = 'pill-bad';
    else if (/处理|排队|中$|pending|running/i.test(s)) cls = 'pill-warn';
    return '<span class="pill ' + cls + '">' + escapeHtml(s || '未知') + '</span>';
}

function renderDocs() {
    const tbody = $('docTableBody');
    const kw = docKeyword.trim().toLowerCase();
    let list = docsCache.filter((d) => !kw || String(d.docName).toLowerCase().includes(kw));
    if (docSort.key) {
        const pick = SORT_VALUE[docSort.key];
        list = list.slice().sort((a, b) => {
            const va = pick(a);
            const vb = pick(b);
            const diff = typeof va === 'number' ? va - vb : String(va).localeCompare(String(vb), 'zh');
            return diff * docSort.dir;
        });
    }

    // 当前排序列的箭头亮起来
    document.querySelectorAll('.doc-table th.sortable').forEach((th) => {
        th.classList.remove('sort-asc', 'sort-desc');
        if (th.dataset.sort === docSort.key) {
            th.classList.add(docSort.dir > 0 ? 'sort-asc' : 'sort-desc');
        }
    });

    if (list.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" class="empty">' +
            (kw ? '没有匹配「' + escapeHtml(kw) + '」的文档' : '暂无文档，请先上传') + '</td></tr>';
    } else {
        tbody.innerHTML = '';
        for (const d of list) {
            const tr = document.createElement('tr');
            tr.innerHTML =
                '<td class="doc-name-cell">' +
                '  <button class="doc-name-link" data-act="view" title="查看入库后的块原文">' +
                escapeHtml(d.docName) + '</button>' +
                '</td>' +
                '<td class="nowrap">' + formatSize(d.fileSize) + '</td>' +
                '<td class="nowrap cell-chunks"><b>' + d.chunkCount + '</b></td>' +
                '<td class="nowrap" title="' + escapeHtml(d.ingestedAt) + '">' + formatTime(d.ingestedAt) + '</td>' +
                '<td class="nowrap">' + statusPill(d.status) + '</td>' +
                '<td class="doc-actions">' +
                '  <button class="btn-view" data-act="view">查看</button>' +
                '  <button class="btn-danger" data-act="del">删除</button>' +
                '</td>';
            tr.querySelectorAll('[data-act="view"]').forEach((btn) => {
                btn.onclick = () => openChunks(d.docId, d.docName);
            });
            tr.querySelector('[data-act="del"]').onclick = () => deleteDoc(d.docId);
            tbody.appendChild(tr);
        }
    }

    const totalChunks = docsCache.reduce((sum, d) => sum + (d.chunkCount || 0), 0);
    $('docSummary').innerHTML = docsCache.length === 0 ? '' :
        '<span>共 <b>' + docsCache.length + '</b> 篇 · <b>' + totalChunks + '</b> 块</span>' +
        (kw ? '<span>筛选出 ' + list.length + ' 篇</span>' : '');
}

document.querySelectorAll('.doc-table th.sortable').forEach((th) => {
    th.onclick = () => {
        const key = th.dataset.sort;
        if (docSort.key === key) {
            docSort.dir = -docSort.dir;
        } else {
            docSort.key = key;
            // 数值列第一次点按"从大到小"，符合"想看最多的"这一直觉
            docSort.dir = key === 'name' ? 1 : -1;
        }
        renderDocs();
    };
});

$('docFilter').addEventListener('input', () => {
    docKeyword = $('docFilter').value;
    renderDocs();
});

// ---------- 导入内容：拖拽 / 文件夹 / 粘贴 → 预检 → 后台任务 ----------
const dropZone = $('dropZone');
const fileInput = $('fileInput');
const dirInput = $('dirInput');
const uploadList = $('uploadList');
/** 待入库队列（用户还没点"确认入库"） */
let pendingFiles = [];
/** 预检结果缓存：key = 文件名|大小 */
const previewCache = new Map();
/** 当前轮询中的任务 */
let pollingJobId = null;

function fileKey(f) {
    return f.name + '|' + f.size;
}

dropZone.addEventListener('click', (e) => {
    if (e.target === fileInput || e.target === dirInput) return;
    fileInput.click();
});
fileInput.addEventListener('change', () => {
    addPending(fileInput.files);
    fileInput.value = '';
});
dirInput.addEventListener('change', () => {
    addPending(dirInput.files);
    dirInput.value = '';
});
dropZone.addEventListener('dragover', (e) => {
    e.preventDefault();
    dropZone.classList.add('dragging');
});
dropZone.addEventListener('dragleave', () => dropZone.classList.remove('dragging'));
dropZone.addEventListener('drop', (e) => {
    e.preventDefault();
    dropZone.classList.remove('dragging');
    if (e.dataTransfer && e.dataTransfer.files) addPending(e.dataTransfer.files);
});

const ACCEPT_EXT = ['pdf', 'doc', 'docx', 'wps', 'txt', 'md', 'markdown', 'ppt', 'pptx', 'xls', 'xlsx', 'html', 'htm'];

function addPending(fileList) {
    const rejected = [];
    for (const f of fileList) {
        const ext = (f.name.split('.').pop() || '').toLowerCase();
        if (!ACCEPT_EXT.includes(ext)) {
            rejected.push(f.name);
            continue;
        }
        if (pendingFiles.some((p) => p.name === f.name && p.size === f.size)) continue;
        pendingFiles.push(f);
    }
    $('uploadTip').textContent = rejected.length
        ? '已跳过不支持的文件：' + rejected.slice(0, 3).join('、') + (rejected.length > 3 ? ' 等' : '')
        : (pendingFiles.length ? '已选 ' + pendingFiles.length + ' 个文件，正在预检…' : '');
    renderPending();
    previewPending();
}

/**
 * 预检：解析一遍但不写库，拿回"会切多少块 / 条款号覆盖率 / 是否重复 / 版本冲突"。
 * 一次请求带一批文件，失败不影响后续入库（预检只是提示，不是闸门）。
 */
async function previewPending() {
    const batch = pendingFiles.filter((f) => !previewCache.has(fileKey(f)));
    if (batch.length === 0) return;
    batch.forEach((f) => previewCache.set(fileKey(f), null)); // 占位，避免重复触发
    const form = new FormData();
    batch.forEach((f) => form.append('files', f));
    try {
        const resp = await fetch('/api/kb/preview', { method: 'POST', body: form });
        if (!resp.ok) throw new Error('HTTP ' + resp.status);
        const list = await resp.json();
        list.forEach((p, i) => {
            if (batch[i]) previewCache.set(fileKey(batch[i]), p);
        });
        const bad = list.filter((p) => p.warnings && p.warnings.length).length;
        $('uploadTip').textContent = bad
            ? '预检完成：' + list.length + ' 个文件，其中 ' + bad + ' 个有需要留意的地方（见下方黄色提示）'
            : '预检完成：' + list.length + ' 个文件，可以入库';
    } catch (e) {
        batch.forEach((f) => previewCache.set(fileKey(f), { error: e.message }));
        $('uploadTip').textContent = '预检失败（不影响入库）：' + e.message;
    }
    renderPending();
}

function warnHtml(p) {
    if (!p || p.error || !p.warnings || p.warnings.length === 0) return '';
    return '<div class="upload-warn">' +
        p.warnings.map((w) => '<div>· ' + escapeHtml(w) + '</div>').join('') +
        '</div>';
}

function sampleHtml(p) {
    if (!p || !p.samples || p.samples.length === 0) return '';
    const s = p.samples[0];
    return '<div class="upload-sample">' + escapeHtml(s.clause) + '：' + escapeHtml(s.text) + '</div>';
}

function renderPending() {
    $('fileName').textContent = pendingFiles.length ? '待入库 ' + pendingFiles.length + ' 个文件' : '';
    uploadList.innerHTML = '';
    let totalChunks = 0;
    let ready = 0;
    pendingFiles.forEach((f, i) => {
        const p = previewCache.get(fileKey(f));
        let state;
        if (p === null || p === undefined) {
            state = formatSize(f.size) + ' · 预检中…';
        } else if (p.error) {
            state = formatSize(f.size) + ' · 预检失败：' + p.error;
        } else if (!p.supported) {
            state = formatSize(f.size) + ' · 类型不支持，入库会被拒绝';
        } else {
            totalChunks += p.chunks;
            ready++;
            state = formatSize(f.size) + ' · 预计 ' + p.chunks + ' 块 · 条款号 ' + p.clausePercent
                + '% · ' + p.embeddingCalls + ' 次向量调用';
        }
        const row = document.createElement('div');
        row.className = 'upload-item';
        row.innerHTML =
            '<div class="upload-main">' +
            '  <div class="upload-name">' + escapeHtml(f.name) + '</div>' +
            '  <div class="upload-state">' + escapeHtml(state) + '</div>' +
            sampleHtml(p) + warnHtml(p) +
            '</div>' +
            '<button class="btn-link">移除</button>';
        row.querySelector('button').onclick = () => {
            pendingFiles.splice(i, 1);
            renderPending();
        };
        uploadList.appendChild(row);
    });
    if (pendingFiles.length && ready === pendingFiles.length) {
        // 只保留一条汇总，避免每次重渲染都在后面重复追加"（合计约 N 块）"
        const raw = $('uploadTip').textContent.split('（合计')[0];
        const hasWarn = raw.indexOf('有需要留意') >= 0;
        $('uploadTip').textContent = (raw.indexOf('预检完成') === 0 ? raw : '预检完成')
            + '，合计约 ' + totalChunks + ' 块'
            + (hasWarn ? '，建议先看下面的黄色提示' : '，可以入库');
    }
}

async function startUpload() {
    if (pollingJobId) {
        $('uploadTip').textContent = '上一批还在入库，等它跑完再传（避免 embedding 接口并发超限）';
        return;
    }
    if (pendingFiles.length === 0) {
        $('uploadTip').textContent = '请先选择文件，或直接用下面的粘贴框';
        return;
    }
    const batch = pendingFiles.slice();
    pendingFiles = [];
    $('fileName').textContent = '';
    const form = new FormData();
    batch.forEach((f) => form.append('files', f));
    $('uploadTip').textContent = '已提交，正在入库…';

    // 先把行渲染出来，等服务端返回 jobId 后再按文件状态刷新
    renderJobRows(batch.map((f) => ({ name: f.name, size: f.size, stage: '排队中', status: 'PENDING', written: 0, total: 0 })));

    try {
        const resp = await fetch('/api/kb/uploads', { method: 'POST', body: form });
        if (!resp.ok) {
            const err = await resp.json().catch(() => ({}));
            throw new Error(err.message || ('HTTP ' + resp.status));
        }
        const job = await resp.json();
        pollingJobId = job.jobId;
        batch.forEach((f) => previewCache.delete(fileKey(f)));
        pollJob(job);
    } catch (e) {
        $('uploadTip').textContent = '提交失败：' + e.message;
        pollingJobId = null;
    }
}

/**
 * 粘贴入库：不挑文件、不存盘，直接把一段条文交给知识库。
 * 服务端会把它落成 txt 再走正常入库链路，所以重建索引不会丢掉它。
 */
async function startPaste() {
    const text = $('pasteText').value.trim();
    if (!text) {
        $('pasteMeta').textContent = '先把条文粘贴到上面的框里';
        return;
    }
    if (pollingJobId) {
        $('pasteMeta').textContent = '上一批还在入库，等它跑完再提交';
        return;
    }
    const title = $('pasteTitle').value.trim();
    $('pasteMeta').textContent = '正在入库…';
    try {
        const resp = await fetch('/api/kb/paste', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: title, text: text })
        });
        if (!resp.ok) {
            const err = await resp.json().catch(() => ({}));
            throw new Error(err.message || ('HTTP ' + resp.status));
        }
        const job = await resp.json();
        pollingJobId = job.jobId;
        renderJobRows(job.files);
        pollJob(job);
        $('pasteText').value = '';
        $('pasteTitle').value = '';
        $('pasteMeta').textContent = '';
    } catch (e) {
        $('pasteMeta').textContent = '入库失败：' + e.message;
    }
}

/** 粘贴框里的实时估算：字数 + 粗数的条款号（纯前端正则，不打扰服务端）。 */
function updatePasteMeta() {
    const t = $('pasteText').value;
    if (!t.trim()) {
        $('pasteMeta').textContent = '';
        return;
    }
    const articles = (t.match(/第[一二三四五六七八九十百零〇\d]+条/g) || []).length;
    const numbers = (t.match(/(^|\n)\s*\d{1,2}(\.\d{1,2}){1,3}(?![\d.])/g) || []).length;
    $('pasteMeta').textContent = t.length + ' 字 · 约 ' + (articles + numbers) + ' 个条款号';
}
$('pasteText').addEventListener('input', updatePasteMeta);

function renderJobRows(files) {
    uploadList.innerHTML = '';
    files.forEach((f) => {
        const row = document.createElement('div');
        row.className = 'upload-item';
        row.dataset.name = f.name;
        row.innerHTML =
            '<div class="upload-main">' +
            '  <div class="upload-name">' + escapeHtml(f.name) + '</div>' +
            '  <div class="upload-state"></div>' +
            '  <div class="upload-bar"><div class="upload-bar-fill" style="width:0%"></div></div>' +
            '</div>';
        uploadList.appendChild(row);
    });
}

function updateJobRows(files) {
    let done = 0;
    let failed = 0;
    for (const f of files) {
        const row = uploadList.querySelector('.upload-item[data-name="' + cssEscape(f.name) + '"]');
        if (!row) continue;
        const state = row.querySelector('.upload-state');
        const fill = row.querySelector('.upload-bar-fill');
        const pct = f.total > 0 ? Math.round((f.written / f.total) * 100) : 0;
        if (f.status === 'DONE') {
            done++;
            fill.style.width = '100%';
            fill.classList.add('ok');
            state.textContent = '已完成 · ' + f.total + ' 块';
        } else if (f.status === 'FAILED') {
            failed++;
            fill.style.width = '100%';
            fill.classList.add('bad');
            const msg = f.message || '未知原因';
            // 行内只放前 100 字，完整原因（含"源文件已保留，可点重建索引补录"）挂 title，
            // 否则一条网络故障说明能把上传区撑高好几行。
            state.textContent = '失败：' + (msg.length > 100 ? msg.slice(0, 100) + '…' : msg);
            state.title = msg;
            state.classList.add('bad-text');
        } else if (f.stage === '向量化') {
            fill.style.width = pct + '%';
            state.textContent = '向量化 ' + f.written + '/' + f.total + ' 块';
        } else {
            state.textContent = f.stage || '排队中';
        }
    }
    $('uploadTip').textContent = '已完成 ' + done + '/' + files.length + (failed ? '，失败 ' + failed : '');
}

async function pollJob(job) {
    updateJobRows(job.files);
    if (job.finished) {
        finishJob(job);
        return;
    }
    setTimeout(async () => {
        try {
            const resp = await fetch('/api/kb/uploads/' + encodeURIComponent(job.jobId));
            if (!resp.ok) throw new Error('任务查询失败 HTTP ' + resp.status);
            const next = await resp.json();
            updateJobRows(next.files);
            if (next.finished) {
                finishJob(next);
            } else {
                pollJob(next);
            }
        } catch (e) {
            $('uploadTip').textContent = '进度查询失败：' + e.message;
            pollingJobId = null;
        }
    }, 700);
}

function finishJob(job) {
    pollingJobId = null;
    const failed = job.files.filter((f) => f.status === 'FAILED').length;
    $('uploadTip').textContent = failed
        ? '入库结束：成功 ' + (job.files.length - failed) + ' 个，失败 ' + failed + ' 个（失败原因见上）'
        : '全部入库完成，共 ' + job.files.length + ' 个文件';
    toast(failed ? '入库结束：失败 ' + failed + ' 个，详见下方提示' : '入库完成，共 ' + job.files.length + ' 个文件',
        failed ? 'bad' : '');
    // 失败行保留在列表里让用户看清原因，别一结束就清空
    loadStats();
    loadDocuments();
}

/** CSS 属性选择器里的名字需要转义（文件名里常有括号、空格） */
function cssEscape(s) {
    return String(s).replace(/["\\]/g, '\\$&');
}

async function deleteDoc(docId) {
    const doc = docsCache.find((d) => d.docId === docId);
    const name = doc ? doc.docName : '该文档';
    // 破坏性操作必须让用户看清删的是哪一篇，不能让 toast 代替确认
    if (!confirm('确定删除「' + name + '」？其全部向量块会一并删除。')) return;
    const resp = await fetch('/api/kb/documents?docId=' + encodeURIComponent(docId), { method: 'DELETE' });
    if (resp.ok) {
        toast('已删除：' + name);
        loadStats();
        loadDocuments();
    } else {
        toast('删除失败，请稍后重试', 'bad');
    }
}

// ---------- 查看入库内容：某篇文档的块原文（弹层 + 关键字过滤 + 分页） ----------
const CHUNK_PAGE_SIZE = 20;
/** 弹层状态；seq 用于丢弃"打字太快导致的乱序响应" */
const chunkState = { docId: null, docName: '', offset: 0, matched: 0, total: 0, query: '', seq: 0 };
let chunkSearchTimer = null;

function openChunks(docId, docName, clause) {
    chunkState.docId = docId;
    chunkState.docName = docName;
    chunkState.offset = 0;
    // 传了条款号就直接过滤到这一条（引用卡片上的「原文」按钮走这条路）
    chunkState.query = clause || '';
    $('chunkSearch').value = clause || '';
    $('chunkDocName').textContent = docName || docId;
    $('chunkMeta').textContent = '加载中…';
    $('chunkList').innerHTML = '<p class="hint">正在读取块原文…</p>';
    $('chunkModal').classList.remove('hidden');
    document.body.classList.add('modal-open');
    loadChunks();
}

function closeChunks() {
    $('chunkModal').classList.add('hidden');
    document.body.classList.remove('modal-open');
    chunkState.docId = null;
}

/** 切换页码：dir = -1 / 1 */
function chunkPage(dir) {
    const next = chunkState.offset + dir * CHUNK_PAGE_SIZE;
    if (next < 0 || next >= chunkState.matched) return;
    chunkState.offset = next;
    loadChunks();
}

async function loadChunks() {
    if (!chunkState.docId) return;
    const seq = ++chunkState.seq;
    $('chunkList').classList.add('loading');
    try {
        // 关键字过滤放在后端：一篇规范可能上千块，全量下发会让首屏 JSON 到几 MB
        const url = '/api/kb/documents/' + encodeURIComponent(chunkState.docId) + '/chunks'
            + '?offset=' + chunkState.offset
            + '&limit=' + CHUNK_PAGE_SIZE
            + '&q=' + encodeURIComponent(chunkState.query);
        const resp = await fetch(url);
        if (!resp.ok) {
            const err = await resp.json().catch(() => ({}));
            throw new Error(err.message || ('HTTP ' + resp.status));
        }
        const data = await resp.json();
        if (seq !== chunkState.seq) return; // 已经有更新的请求了，丢弃这次结果
        chunkState.matched = data.matched;
        chunkState.total = data.total;
        chunkState.offset = data.offset;
        renderChunks(data);
    } catch (e) {
        if (seq !== chunkState.seq) return;
        $('chunkList').innerHTML = '<p class="hint">读取失败：' + escapeHtml(e.message) + '</p>';
        $('chunkMeta').textContent = '读取失败';
    } finally {
        if (seq === chunkState.seq) $('chunkList').classList.remove('loading');
    }
}

function renderChunks(data) {
    const box = $('chunkList');
    const kw = chunkState.query.trim();
    $('chunkMeta').textContent = kw
        ? '共 ' + data.total + ' 块，命中 ' + data.matched + ' 块（关键字「' + kw + '」）'
        : '共 ' + data.total + ' 块';

    if (!data.chunks || data.chunks.length === 0) {
        box.innerHTML = '<p class="hint">' + (kw ? '没有匹配的块，换个关键字试试。' : '这篇文档没有块。') + '</p>';
        renderPager();
        return;
    }

    box.innerHTML = '';
    for (const c of data.chunks) {
        const pageText = (c.page !== null && c.page !== undefined) ? '第 ' + c.page + ' 页 · ' : '';
        const card = document.createElement('div');
        card.className = 'chunk-card';
        card.innerHTML =
            '<div class="chunk-head">' +
            '  <span class="chunk-clause">' + escapeHtml(c.clause) + '</span>' +
            '  <span class="chunk-meta">第 ' + (c.chunkIndex + 1) + ' 块 · ' + pageText + c.chars + ' 字</span>' +
            '</div>' +
            '<div class="chunk-text">' + highlight(c.text, kw) + '</div>';
        box.appendChild(card);
    }
    renderPager();
    $('chunkList').scrollTop = 0;
}

function renderPager() {
    const from = chunkState.matched === 0 ? 0 : chunkState.offset + 1;
    const to = Math.min(chunkState.offset + CHUNK_PAGE_SIZE, chunkState.matched);
    $('chunkPager').textContent = chunkState.matched === 0
        ? '无结果' : (from + '–' + to + ' / ' + chunkState.matched + ' 块');
    $('chunkPrev').disabled = chunkState.offset <= 0;
    $('chunkNext').disabled = chunkState.offset + CHUNK_PAGE_SIZE >= chunkState.matched;
}

/** 把关键字包成 <mark>：先转义再替换，避免正文里的尖括号被当成标签 */
function highlight(text, kw) {
    const safe = escapeHtml(text);
    if (!kw) return safe;
    const needle = escapeHtml(kw);
    // 关键字里可能有正则元字符，用 split/join 做纯文本替换最稳
    return safe.split(needle).join('<mark>' + needle + '</mark>');
}

$('chunkSearch').addEventListener('input', () => {
    clearTimeout(chunkSearchTimer);
    // 每敲一个字就发请求会把彼此挤掉，250ms 停顿后才查
    chunkSearchTimer = setTimeout(() => {
        chunkState.query = $('chunkSearch').value;
        chunkState.offset = 0;
        loadChunks();
    }, 250);
});
$('chunkSearch').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        clearTimeout(chunkSearchTimer);
        chunkState.query = $('chunkSearch').value;
        chunkState.offset = 0;
        loadChunks();
    }
});
document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (!$('chunkModal').classList.contains('hidden')) closeChunks();
    else if (!$('scopeModal').classList.contains('hidden')) closeScope();
});

async function reindex() {
    const btn = $('reindexBtn');
    if (btn.disabled) return;
    if (!confirm('将按 data/docs 中的原文件全量重建索引（几千块时要几分钟），期间无法上传或删除，继续？')) return;

    // 重建是同步长请求：不给反馈的话页面会静默三分钟，看起来就像卡死了
    const startedAt = Date.now();
    btn.disabled = true;
    const timer = setInterval(() => {
        $('reindexTip').textContent = '重建中…已用 ' + Math.round((Date.now() - startedAt) / 1000) + ' 秒';
    }, 1000);
    $('reindexTip').textContent = '重建中…已用 0 秒';

    try {
        const resp = await fetch('/api/kb/reindex', { method: 'POST' });
        const data = await resp.json().catch(() => ({}));
        if (!resp.ok) throw new Error(data.message || ('HTTP ' + resp.status));
        const failed = data.failed || [];
        const parts = ['重新入库 ' + data.reindexedDocuments + ' 篇'];
        if (data.skippedDuplicates) parts.push('跳过重复 ' + data.skippedDuplicates + ' 篇');
        if (failed.length) parts.push('失败 ' + failed.length + ' 篇');
        $('reindexTip').textContent = '重建完成：' + parts.join(' · ');
        if (failed.length) {
            alert('以下文件重建失败（原文件仍在 data/docs，可修复后再次重建）：\n\n'
                + failed.map((f) => f.name + '：' + f.reason).join('\n'));
        }
        loadStats();
        loadDocuments();
    } catch (e) {
        $('reindexTip').textContent = '重建失败：' + e.message;
    } finally {
        clearInterval(timer);
        btn.disabled = false;
    }
}

async function searchTest() {
    const query = $('searchInput').value.trim();
    if (!query) return;
    const box = $('searchResults');
    const t0 = performance.now();
    box.innerHTML = '<p class="hint">检索中…</p>';
    $('searchMeta').textContent = '';
    try {
        const resp = await fetch('/api/kb/search-test', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ query: query, topK: 5 })
        });
        const hits = await resp.json();
        const ms = Math.round(performance.now() - t0);
        if (!hits || hits.length === 0) {
            $('searchMeta').textContent = '0 条 · ' + ms + ' ms';
            box.innerHTML = '<p class="hint">没有召回任何片段（低于相似度阈值会被过滤）。</p>';
            return;
        }
        $('searchMeta').textContent = '命中 ' + hits.length + ' 条 · ' + ms + ' ms';
        box.innerHTML = '';
        hits.forEach((h, i) => {
            const card = document.createElement('div');
            card.className = 'hit-card';
            const pageText = (h.page !== null && h.page !== undefined) ? ' · 第' + h.page + '页' : '';
            const pct = Math.max(0, Math.min(100, Number(h.score) * 100)).toFixed(0);
            card.innerHTML =
                '<div class="hit-head"><span>#' + (i + 1) + ' ' + escapeHtml(h.docName) +
                ' · ' + escapeHtml(h.clause) + pageText + '</span>' +
                '<span class="hit-score-wrap"><span class="hit-score-bar"><span class="hit-score-fill" style="width:' +
                pct + '%"></span></span><span class="hit-score-num">' +
                Number(h.score).toFixed(3) + '</span></span></div>' +
                '<div class="hit-snippet">' + escapeHtml(h.snippet) + '</div>';
            box.appendChild(card);
        });
    } catch (e) {
        box.innerHTML = '<p class="hint">检索失败：' + escapeHtml(e.message) + '</p>';
    }
}

$('searchInput').addEventListener('keydown', (e) => { if (e.key === 'Enter') searchTest(); });

// ---------------- 检索范围（对话页工具栏） ----------------
/** 按钮文案随选择变化：全部 / N 篇。选了部分文档时按钮高亮，提醒当前不是全库。 */
function refreshScopeBtn() {
    const btn = $('scopeBtn');
    if (!btn) return;
    const total = docsCache.length;
    const n = scopeDocIds.length;
    if (n === 0 || n >= total) {
        btn.textContent = '范围：全部';
        btn.classList.remove('on');
    } else {
        btn.textContent = '范围：' + n + ' 篇';
        btn.classList.add('on');
    }
}

async function openScope() {
    // 直接进对话页时文档列表还没拉过，弹层需要它来列文档
    if (!docsCache.length) {
        try {
            docsCache = (await (await fetch('/api/kb/documents')).json()) || [];
        } catch (e) { /* 拉不到就显示空列表 */ }
    }
    renderScopeList();
    $('scopeModal').classList.remove('hidden');
    document.body.classList.add('modal-open');
}

function closeScope() {
    $('scopeModal').classList.add('hidden');
    // 块弹层可能还开着，别把它一起解锁
    if ($('chunkModal').classList.contains('hidden')) {
        document.body.classList.remove('modal-open');
    }
}

function renderScopeList() {
    const box = $('scopeList');
    if (!docsCache.length) {
        box.innerHTML = '<p class="hint">知识库里还没有文档</p>';
        $('scopeTip').textContent = '';
        return;
    }
    box.innerHTML = docsCache.map((d) =>
        '<label class="scope-item">' +
        '<input type="checkbox" value="' + escapeHtml(d.docId) + '"' +
        (scopeDocIds.includes(d.docId) ? ' checked' : '') + '>' +
        '<span class="scope-name">' + escapeHtml(d.docName) + '</span>' +
        '<span class="scope-count">' + d.chunkCount + ' 块</span>' +
        '</label>').join('');
    updateScopeTip();
    box.querySelectorAll('input').forEach((el) => { el.onchange = updateScopeTip; });
}

function countChecked() {
    return document.querySelectorAll('#scopeList input:checked').length;
}

function updateScopeTip() {
    $('scopeTip').textContent = '已选 ' + countChecked() + ' / ' + docsCache.length;
}

function selectAllScope(on) {
    document.querySelectorAll('#scopeList input').forEach((el) => { el.checked = on; });
    updateScopeTip();
}

function applyScope() {
    scopeDocIds = Array.from(document.querySelectorAll('#scopeList input:checked'))
        .map((el) => el.value);
    refreshScopeBtn();
    closeScope();
    toast(scopeDocIds.length === 0
        ? '已改回全库检索'
        : '检索范围限定为 ' + scopeDocIds.length + ' 篇');
}

// ---------------- 检索质量评测 ----------------
async function runEval() {
    const btn = $('evalBtn');
    if (btn.disabled) return;
    btn.disabled = true;
    const box = $('evalResult');
    $('evalTip').textContent = '';
    box.innerHTML = '<p class="hint">正在评测：15 条问题 × 2 种模式，约 5~10 秒…</p>';
    try {
        const resp = await fetch('/api/kb/eval', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                topK: Number($('topkSelect').value) || 5,
                docIds: scopeDocIds
            })
        });
        const data = await resp.json().catch(() => ({}));
        if (!resp.ok) throw new Error(data.message || ('HTTP ' + resp.status));
        renderEval(data);
        $('evalMeta').textContent = data.total + ' 条 · 耗时 ' + (data.totalMs / 1000).toFixed(1) + ' 秒';
    } catch (e) {
        box.innerHTML = '<p class="hint eval-bad">评测失败：' + escapeHtml(e.message) + '</p>';
    } finally {
        btn.disabled = false;
    }
}

function renderEval(data) {
    const box = $('evalResult');
    const summaries = data.summaries || [];
    if (summaries.length < 2) {
        box.innerHTML = '<p class="hint">评测结果为空</p>';
        return;
    }
    const vec = summaries.find((s) => s.mode === 'vector') || summaries[0];
    const hyb = summaries.find((s) => s.mode === 'hybrid') || summaries[1];
    const rate = (a, b) => (b === 0 ? '—' : Math.round((a / b) * 100) + '%');

    const col = (s, main) =>
        '<div class="eval-col' + (main ? ' eval-col-main' : '') + '">' +
        '<div class="eval-col-head">' + escapeHtml(s.label) +
        (main ? '<span class="eval-badge">当前配置</span>' : '') + '</div>' +
        '<div class="eval-kv"><span>Hit@1</span><b>' + s.hitAt1 + '/' + s.positiveTotal +
        ' <em>' + rate(s.hitAt1, s.positiveTotal) + '</em></b></div>' +
        '<div class="eval-kv"><span>Hit@' + data.topK + '</span><b>' + s.hitAtK + '/' + s.positiveTotal +
        ' <em>' + rate(s.hitAtK, s.positiveTotal) + '</em></b></div>' +
        '<div class="eval-kv"><span>MRR</span><b>' + Number(s.mrr).toFixed(3) + '</b></div>' +
        '<div class="eval-kv"><span>拒答正确</span><b>' + s.negativeCorrect + '/' +
        s.negativeTotal + '</b></div>' +
        '</div>';

    const diff = hyb.hitAtK - vec.hitAtK;
    const delta = diff === 0
        ? '混合检索与纯向量的 Hit@' + data.topK + ' 持平（各 ' + vec.hitAtK + '/' + vec.positiveTotal + '）'
        : '混合检索 Hit@' + data.topK + '：' + vec.hitAtK + ' → <b>' + hyb.hitAtK + '</b> / ' +
        vec.positiveTotal + '（' + (diff > 0 ? '+' : '') + diff + ' 条）';

    // 混合比纯向量差时要给机制解释，不能只把数字涂红。当前用的是 RRF
    // （倒数排名融合），只看名次不看分数，已经不存在"BM25 归一化后拿满分 1.0
    // 盖过向量分"的老问题；这时还倒退，说明 BM25 的名次本身在干扰——
    // 字面重合度高但语义不对的块被顶到了前面。
    const regress = diff < 0
        ? '<p class="eval-note eval-bad">混合检索在这批问题上仍然更差。当前融合策略是 RRF' +
        '（只看名次不看分数），所以这不是量纲问题，而是 BM25 的名次本身在干扰：' +
        '某些块字面重合度很高、但语义并不是用户要的那条，被 BM25 顶到了前面。' +
        '可以试着把 app.retrieval.bm25-weight 调低（当前 0.25），' +
        '或把 min-bm25-score 提高（当前 1.5），让 BM25 只在强字面命中时才参与排序。</p>'
        : '';

    const cell = (hit, rank, hits, negative) => {
        if (negative) {
            return hit
                ? '<span class="eval-ok">正确拒答</span>'
                : '<span class="eval-bad">误召回 ' + hits + ' 条</span>';
        }
        return hit
            ? '<span class="eval-ok">第 ' + rank + ' 位</span>'
            : '<span class="eval-bad">未命中</span>';
    };

    // 语义分列：这是向量路 top1 的原始相似度（不受阈值约束），调 min-score 全靠它。
    // 负样本这一列标红是刻意的——分值高说明"阈值根本分不开闲聊和真问题"，
    // 这比在负样本行上染个浅色背景醒目得多。
    const scoreCell = (v, negative) => {
        if (v === null || v === undefined) return '<span class="eval-score">—</span>';
        const hot = (negative && v >= 0.5) || (!negative && v < 0.4);
        return '<span class="eval-score' + (hot ? ' eval-score-hot' : '') + '">' +
            Number(v).toFixed(3) + '</span>';
    };

    const rows = (data.cases || []).map((c) =>
        '<div class="eval-row' + (c.negative ? ' eval-row-neg' : '') + '">' +
        '<span>' + escapeHtml(c.question) + '</span>' +
        '<span class="eval-expect">' + escapeHtml(c.expect) + '</span>' +
        '<span>' + scoreCell(c.topScore, c.negative) + '</span>' +
        '<span>' + cell(c.vectorHit, c.vectorRank, c.vectorHits, c.negative) + '</span>' +
        '<span>' + cell(c.hybridHit, c.hybridRank, c.hybridHits, c.negative) + '</span>' +
        '</div>').join('');

    // 负样本被误召回时给一条明确解释：这是阈值策略的问题，不是评测算错了。
    // 不写死具体阈值数字——阈值在 application.yml 里可调，写死在页面上迟早对不上。
    const missed = hyb.negativeTotal - hyb.negativeCorrect;
    const warn = missed > 0
        ? '<p class="eval-note eval-bad">有 ' + missed + ' 条无关问题仍然召回到了条款。这不是算错了：' +
        '说明当前的相似度下限（app.retrieval.min-score）偏低，语义上完全无关的问句也能拿到不低的向量分。' +
        '评测的价值正是把这种情况量化出来——要不要收紧阈值，看这张表再定。</p>'
        : '';

    box.innerHTML =
        '<div class="eval-grid">' + col(vec, false) + col(hyb, true) + '</div>' +
        '<div class="eval-delta' + (diff < 0 ? ' eval-delta-bad' : '') + '">' + delta + '</div>' +
        regress +
        warn +
        '<div class="eval-rows">' +
        '<div class="eval-row eval-head"><span>问题</span><span>期望命中</span>' +
        '<span>语义分</span><span>纯向量</span><span>混合检索</span></div>' +
        rows + '</div>' +
        '<p class="eval-note">「语义分」是向量路 top1 的原始相似度，不受阈值约束——' +
        '把正负样本这一列并排看，就知道 min-score 定在哪才分得开：' +
        '负样本标红表示分值已经不低，说明光靠绝对阈值挡不住它。' +
        '总耗时 ' + (data.totalMs / 1000).toFixed(1) +
        ' 秒。单条耗时受 embedding 缓存影响很大（同一问题第二次检索几乎不耗时），' +
        '所以没有逐项对比耗时——那组数字会误导。</p>';
}

// ---------------- 工具函数 ----------------
function escapeHtml(s) {
    if (s === null || s === undefined) return '';
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;');
}

function formatSize(bytes) {
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
    return (bytes / 1024 / 1024).toFixed(2) + ' MB';
}

function formatTime(iso) {
    if (!iso) return '-';
    // 只显示到分钟：秒对"这文档什么时候入库的"没有意义，
    // 省下来的宽度留给文件名列，窄窗口下才不至于把"操作"顶出屏幕。完整时间在 title 里。
    return String(iso).replace('T', ' ').slice(5, 16);
}

// 页面打开时先拉一次统计，用于边栏小卡片、演示模式角标与模式提示
loadStats();

// 地址里带 ?view=kb 时直接进知识库：刷新保持所在页，地址也能直接分享
const initialView = new URLSearchParams(location.search).get('view');
if (initialView === 'kb') {
    switchMenu('kb', true);
}

// ?eval=1：进知识库并立刻跑一次评测。
// 存在的意义是"只发一个链接就能让对方看到检索质量"，不用手点；
// 不做成默认行为——它要跑 30 次检索，无谓地消耗 embedding 调用。
if (new URLSearchParams(location.search).get('eval') === '1') {
    switchMenu('kb', true);
    runEval();
}
