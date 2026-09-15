/* Chess Vault — UI offline. Bridge Android + análise via chess.js (PGN do cofre). */
(function () {
  'use strict';
  var A = window.Android || null;
  var PAGE = 20, pgOffset = 0;
  var board = null, chess = null, histSans = [], plyIdx = 0, errPlyIdx = -1, flipState = false;
  var touchTimer = null;
  var activeFilter = null, activeFilterLabel = '';
  var analyzeMode = false, analyzeChess = null, analyzeSans = [];

  function $(id) { return document.getElementById(id); }
  function toast(m) { try { A && A.showToast(m); } catch (e) {} }

  function tabs() {
    var btns = document.querySelectorAll('#tabs button');
    for (var i = 0; i < btns.length; i++) {
      btns[i].addEventListener('click', function () {
        for (var j = 0; j < btns.length; j++) btns[j].classList.remove('active');
        this.classList.add('active');
        var t = this.getAttribute('data-tab');
        var secs = document.querySelectorAll('main .tab');
        for (var k = 0; k < secs.length; k++) secs[k].classList.remove('active');
        $('tab-' + t).classList.add('active');
        if (t === 'games') loadGames(false);
      });
    }
  }
  window.openTab = function (t) {
    var b = document.querySelector('#tabs button[data-tab="' + t + '"]');
    if (b) b.click();
  };

  function loadCfg() {
    try {
      var cfg = JSON.parse(A.getAllConfig());
      if (cfg.username) {
        $('cfgUser').value = cfg.username;
      } else {
        $('cfgUser').value = 'LuckGaspar';
      }
      $('cfgUser2').value = cfg.secondary_username || '';
      renderOwnerTabs(cfg);
      var isFullDone = cfg.full_sync_completed === 'true';
      if (isFullDone) {
        $('btnFullSync').classList.add('hidden');
        $('fullSyncBadge').classList.remove('hidden');
        $('fullSyncBadge').textContent = '✓ Histórico completo sincronizado' + (cfg.full_sync_date ? ' (' + cfg.full_sync_date + ')' : '');
      } else {
        $('btnFullSync').classList.remove('hidden');
        $('fullSyncBadge').classList.add('hidden');
      }
      var auto = cfg.auto_sync_enabled !== 'false';
      $('syncInfo').textContent = 'Total no cofre: ' + (cfg.total_games || 0) +
        ' • último sync: ' + (cfg.last_sync_human || 'nunca') +
        ' • próximo: ' + (cfg.next_alarm_human || '—') +
        ' • auto: ' + (auto ? 'ON' : 'OFF');
      $('hdrSub').textContent = (cfg.active_owner || cfg.username || 'LuckGaspar') + ' • ' + (cfg.total_games || 0) + ' partidas';
    } catch (e) {}
  }

  function renderOwnerTabs(cfg) {
    var box = $('ownerTabs');
    if (!box) return;
    var u1 = (cfg.username || 'LuckGaspar').trim();
    var u2 = (cfg.secondary_username || '').trim();
    var active = (cfg.active_owner || u1).trim().toLowerCase();
    var h = '';
    h += '<button class="sec owner-tab' + (active === u1.toLowerCase() ? ' active' : '') + '" data-owner="' + esc(u1) + '">' + esc(u1) + '</button>';
    if (u2) {
      h += '<button class="sec owner-tab' + (active === u2.toLowerCase() ? ' active' : '') + '" data-owner="' + esc(u2) + '">' + esc(u2) + '</button>';
    } else {
      h += '<span class="hint">adicione o 2º username abaixo p/ alternar</span>';
    }
    box.innerHTML = h;
    var tabs = box.querySelectorAll('.owner-tab');
    for (var i = 0; i < tabs.length; i++) {
      (function (el) {
        el.addEventListener('click', function () {
          var o = '';
          try { o = A.switchOwner(el.getAttribute('data-owner')); } catch (e) {}
          clearFilter();
          refresh();
        });
      })(tabs[i]);
    }
  }

  function loadStats() {
    var s = {};
    try { s = JSON.parse(A.getStats()); } catch (e) {}
    var cards = '';
    cards += card(s.total || 0, 'partidas no cofre');
    cards += linkCard(s.timeout_losses || 0, 'derrotas por tempo', { loss_kind: 'timeout' }, 'derrotas por tempo');
    cards += linkCard(s.mate_losses || 0, 'mates recebidos', { loss_kind: 'mate' }, 'mates recebidos');
    cards += card(s.avg_accuracy != null ? s.avg_accuracy : '—', 'accuracy média');
    $('dashCards').innerHTML = cards;
    var tc = '';
    (s.by_time_class || []).forEach(function (t) {
      var tot = t.games || 1;
      var w = Math.round((t.wins / tot) * 100), d = Math.round((t.draws / tot) * 100), l = 100 - w - d;
      tc += '<div class="card linkable" data-f=\'' + esc(JSON.stringify({ time_class: t.time_class })) + '\' data-l="ritmo ' + esc(t.time_class) + '">' +
        '<b>' + esc(t.time_class) + '</b> <span class="hint">' + t.games + ' jogos • rating médio ' + t.avg_rating +
        (t.avg_accuracy != null ? ' • acc ' + t.avg_accuracy : '') + '</b><div class="bar"><div class="w" style="width:' + w + '%"></div></div>' +
        '<div class="hint">' + t.wins + 'V / ' + t.losses + 'D / ' + t.draws + 'E</div></div>';
    });
    $('byTc').innerHTML = tc || '<div class="card dim">Sem dados — sincronize.</div>';
    wireFilterCards($('byTc'));
    var cc = '';
    (s.by_color || []).forEach(function (t) {
      cc += '<div class="card linkable" data-f=\'' + esc(JSON.stringify({ color: t.color })) + '\' data-l="de ' + (t.color === 'white' ? 'brancas' : 'pretas') + '">' +
        '<b>' + (t.color === 'white' ? '♔ Brancas' : '♚ Pretas') + '</b><div class="hint">' +
        t.games + ' jogos • ' + t.wins + 'V / ' + t.losses + 'D / ' + t.draws + 'E</div></div>';
    });
    $('byColor').innerHTML = cc || '';
    wireFilterCards($('byColor'));
    var lt = '<div class="cards">' + linkCard(s.mate_losses || 0, 'xeque-mate', { loss_kind: 'mate' }, 'xeque-mate') +
      linkCard(s.resigns || 0, 'desistências', { loss_kind: 'resign' }, 'desistências') +
      linkCard(s.timeout_losses || 0, 'tempo esgotado', { loss_kind: 'timeout' }, 'tempo esgotado') + '</div>';
    $('lossTypes').innerHTML = lt;
    wireFilterCards($('lossTypes'));
    wireFilterCards($('dashCards'));
    var ol = '';
    (s.openings || []).forEach(function (o) {
      var tot = o.games || 1;
      var wr = Math.round((o.wins / tot) * 100);
      var crisis = (o.games >= 5 && wr < 35) ? ' <span class="err-mark">em crise</span>' : '';
      ol += '<tr class="linkable" data-f=\'' + esc(JSON.stringify({ eco: o.eco })) + '\' data-l="abertura ' + esc(shortEco(o.eco)) + '">' +
        '<td>' + esc(shortEco(o.eco)) + crisis + '</td><td>' + o.games + '</td><td>' +
        o.wins + '/' + o.losses + '/' + o.draws + '</td><td>' + wr + '%</td></tr>';
    });
    $('openList').innerHTML = '<table class="tbl"><tr><th>Abertura</th><th>J</th><th>V/D/E</th><th>WR</th></tr>' + ol + '</table>';
    wireFilterCards($('openList'));
    var ep = s.err_by_phase || {};
    var epCards = linkCard(ep.abertura || 0, 'erros na abertura (1–12)', { err_phase: 'abertura' }, 'erros na abertura') +
      linkCard(ep.meio || 0, 'erros no meio-jogo (13–30)', { err_phase: 'meio' }, 'erros no meio-jogo') +
      linkCard(ep.final || 0, 'erros no final (31+)', { err_phase: 'final' }, 'erros no final');
    $('errPhase').innerHTML = epCards;
    wireFilterCards($('errPhase'));
    var h = s.err_hist || [], mx = 1;
    h.forEach(function (e) { if (e.count > mx) mx = e.count; });
    var hh = '';
    h.forEach(function (e) {
      hh += '<div class="linkable" data-f=\'' + esc(JSON.stringify({ err_move: e.move })) + '\' data-l="erro no lance ' + e.move + '"' +
        ' style="display:flex;align-items:center;gap:8px;font-size:12px;margin:3px 0;">' +
        '<span style="width:34px;color:var(--dim)">L' + e.move + '</span>' +
        '<div class="bar" style="flex:1"><div class="l" style="width:' + Math.round((e.count / mx) * 100) + '%"></div></div>' +
        '<span style="width:30px;text-align:right">' + e.count + '</span></div>';
    });
    $('errHist').innerHTML = hh || '<div class="card dim">Sem mates registrados.</div>';
    wireFilterCards($('errHist'));
  }

  function card(v, l) { return '<div class="card"><div class="big">' + v + '</div><div class="lbl">' + l + '</div></div>'; }
  function linkCard(v, l, f, fl) {
    if (!v) return card(v, l);
    return '<div class="card linkable" data-f=\'' + esc(JSON.stringify(f)) + '\' data-l="' + esc(fl) + '">' +
      '<div class="big">' + v + '</div><div class="lbl">' + l + ' →</div></div>';
  }
  function wireFilterCards(root) {
    if (!root) return;
    var els = root.querySelectorAll ? root.querySelectorAll('.linkable') : [];
    for (var i = 0; i < els.length; i++) {
      (function (el) {
        el.addEventListener('click', function () {
          var f = null, l = '';
          try { f = JSON.parse(el.getAttribute('data-f')); } catch (e) {}
          l = el.getAttribute('data-l') || '';
          if (f) showFiltered(f, l);
        });
      })(els[i]);
    }
  }
  function showFiltered(f, label) {
    activeFilter = f;
    activeFilterLabel = label;
    pgOffset = 0;
    window.openTab('games');
    loadGames(true);
  }
  function clearFilter() {
    activeFilter = null;
    activeFilterLabel = '';
    pgOffset = 0;
    loadGames(true);
  }
  function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/'/g, '&#39;').replace(/"/g, '&quot;'); }
  function shortEco(e) {
    e = String(e || '');
    return e.length > 42 ? e.slice(0, 42) + '…' : e;
  }
  function resPill(g) {
    if (g.is_win) return '<span class="pill win">vitória</span>';
    if (g.is_loss) return '<span class="pill loss">' + esc(g.my_result) + '</span>';
    return '<span class="pill draw">empate</span>';
  }

  function loadGames(reset, keepDetail) {
    if (reset) {
      pgOffset = 0;
      if (!keepDetail) closeGameDetail();
    }
    var arr = [];
    var filterStr = activeFilter ? JSON.stringify(activeFilter) : null;
    try {
      if (filterStr) { arr = JSON.parse(A.queryGames(filterStr, PAGE, pgOffset)); }
      else { arr = JSON.parse(A.getRecentGames(PAGE, pgOffset)); }
    } catch (e) {}
    var banner = '';
    if (filterStr) {
      var total = 0;
      try { total = A.countFilteredGames(filterStr); } catch (e) {}
      banner = '<div class="card filter-banner">🔎 ' + esc(activeFilterLabel || 'filtrado') +
        (total ? ' • ' + total + ' partidas' : '') +
        ' <a href="#" id="clearFilter" style="color:var(--accent)">limpar ✕</a></div>';
    }
    var h = banner;
    arr.forEach(function (g) {
      var d = new Date(g.end_time * 1000);
      var ds = d.toLocaleDateString('pt-BR') + ' ' + d.toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
      h += '<div class="gitem" data-uuid="' + g.uuid + '">' +
        '<div class="t">' + resPill(g) + ' vs ' + esc(g.opp_username) + ' (' + g.opp_rating + ')</div>' +
        '<div class="s">' + esc(g.time_class) + ' • ' + esc(shortEco(g.eco_name) || 'abertura?') +
        ' • ' + (g.my_color === 'white' ? 'brancas' : 'pretas') + ' • ' + g.move_count + ' lances' +
        (g.accuracy != null ? ' • acc ' + g.accuracy : '') + '<br>' + ds + '</div></div>';
    });
    $('gameList').innerHTML = h || '<div class="card dim">Nada aqui ainda.</div>';
    $('pgInfo').textContent = (pgOffset + 1) + '–' + (pgOffset + arr.length);
    var cf = $('clearFilter');
    if (cf) cf.addEventListener('click', function (ev) { ev.preventDefault(); clearFilter(); });
    var items = document.querySelectorAll('#gameList .gitem');
    for (var i = 0; i < items.length; i++) {
      items[i].addEventListener('click', function () { openGame(this.getAttribute('data-uuid')); });
    }
    bindGameDetailButtons();
  }

  var detailBound = false;
  function bindGameDetailButtons() {
    if (detailBound) return;
    detailBound = true;
  }
  function closeGameDetail() {
    exitAnalyze();
    var gd = $('gameDetail');
    if (gd) gd.classList.add('hidden');
    var gl = $('gameList');
    if (gl) gl.style.display = '';
    var p = document.querySelector('#tab-games .pager');
    if (p) p.style.display = '';
  }

  function chessNew() {
    try {
      if (typeof Chess === 'function') return new Chess();
    } catch (e) {}
    try {
      if (window.Chess && typeof window.Chess === 'function') return new window.Chess();
    } catch (e) {}
    return null;
  }

  function loadPgnCompat(chess, pgn) {
    try {
      if (chess.load_pgn) return chess.load_pgn(pgn);
      if (chess.loadPgn) return chess.loadPgn(pgn);
      if (chess.load) return chess.load(pgn);
    } catch (e) {}
    return false;
  }

  function movesFromGame(g) {
    try {
      var m = JSON.parse(g.moves_san || '[]');
      if (m && m.length) return m;
    } catch (e) {}
    try {
      var chess = chessNew();
      if (chess && loadPgnCompat(chess, g.pgn || '')) return chess.history();
    } catch (e) {}
    return [];
  }

  function openGame(uuid) {
    var g = {};
    try { g = JSON.parse(A.getGame(uuid)); } catch (e) { toast('Falha ao abrir partida'); return; }
    if (!g || !g.uuid) { toast('Partida não encontrada neste perfil'); return; }
    $('gameList').style.display = 'none';
    document.querySelector('.pager').style.display = 'none';
    $('gameDetail').classList.remove('hidden');
    var title = (g.is_win ? '✅ ' : g.is_loss ? '❌ ' : '➖ ') + 'vs ' + (g.opp_username || '?') +
      ' (' + (g.opp_rating || '?') + ') • ' + (g.end_date || '');
    $('gdTitle').textContent = title;
    var meta = (g.my_color === 'white' ? 'Você de brancas' : 'Você de pretas') +
      ' • rating ' + g.my_rating + ' • ' + g.time_class + ' ' + (g.time_control || '') +
      '<br>' + esc(g.eco_name || '') + ' • resultado: ' + esc(g.my_result || '') +
      (g.accuracy != null && g.accuracy !== 'null' ? ' • sua accuracy ' + g.accuracy : '') +
      '<br><a href="#" id="openChessCom" style="color:var(--accent)">Abrir no chess.com ↗</a>';
    $('gdMeta').innerHTML = meta;
    var oc = $('openChessCom');
    if (oc) oc.addEventListener('click', function (ev) {
      ev.preventDefault();
      try { A.openExternal(g.url); } catch (e) {}
    });
    try {
      chess = chessNew();
      var ok = chess ? loadPgnCompat(chess, g.pgn || '') : false;
      if (!chess) toast('Motor de xadrez não carregou');
      histSans = ok ? chess.history() : movesFromGame(g);
    } catch (e) { histSans = []; try { chess = chessNew(); } catch (e2) {} }
    if (!histSans.length) histSans = movesFromGame(g);
    if (!histSans.length) { toast('PGN vazio — não dá p/ montar o tabuleiro'); }
    plyIdx = 0; flipState = (g.my_color === 'black');
    errPlyIdx = (g.err_move_num != null && g.err_move_num !== 'null')
      ? Math.max(0, (parseInt(g.err_move_num, 10) - 1) * 2 + (g.my_color === 'white' ? 0 : 1)) : -1;
    if (!board) { try { board = new ChessBoard('board'); } catch (e) { toast('Tabuleiro não iniciou'); return; } }
    board.flipped = flipState;
    exitAnalyze();
    renderPly(g);
    window.__boardSwipe = function (dir) { stepPly(dir, g); };
  }

  function chessAtPly(n) {
    var c = chessNew();
    if (!c) return null;
    for (var i = 0; i < n && i < histSans.length; i++) {
      try { c.move(histSans[i]); } catch (e) { break; }
    }
    return c;
  }

  function renderPly(g) {
    if (!board) { try { board = new ChessBoard('board'); } catch (e) { toast('Tabuleiro não iniciou'); return; } }
    var c = chessAtPly(plyIdx);
    if (!c) { toast('Motor de xadrez não carregou'); return; }
    var lastMove = null;
    if (plyIdx > 0) {
      try {
        var verbose = c.history({ verbose: true });
        var v = verbose[verbose.length - 1];
        if (v) lastMove = { from: v.from, to: v.to };
      } catch (e) {}
    }
    var errSq = null;
    if (errPlyIdx >= 0 && plyIdx === errPlyIdx + 1) {
      try {
        var vv = c.history({ verbose: true });
        var last = vv[vv.length - 1];
        if (last) errSq = last.to;
      } catch (e) {}
    }
    board.flipped = flipState;
    board.setFromChess(c, lastMove, errSq);
    var mv = plyIdx === 0 ? 'início' : ('lance ' + (Math.floor((plyIdx - 1) / 2) + 1) + (plyIdx % 2 === 0 ? ' (pretas)' : ' (brancas)'));
    if (errPlyIdx >= 0 && plyIdx === errPlyIdx + 1) mv += ' ⚠️ provável erro';
    $('mvInfo').textContent = mv + ' • ' + plyIdx + '/' + histSans.length;
  }

  function stepPly(d, g) {
    if (analyzeMode) { analyzeRedo(d); return; }
    plyIdx = Math.max(0, Math.min(histSans.length, plyIdx + d));
    renderPly(g || {});
  }

  function enterAnalyze() {
    var base = chessAtPly(plyIdx);
    if (!base) { toast('Motor de xadrez não carregou'); return; }
    analyzeMode = true;
    analyzeChess = base;
    analyzeSans = [];
    $('analyzePanel').classList.remove('hidden');
    $('btnAnalyzeExit').classList.remove('hidden');
    $('btnAnalyze').classList.add('hidden');
    renderAnalyze();
  }
  function exitAnalyze() {
    analyzeMode = false;
    analyzeChess = null;
    analyzeSans = [];
    var p = $('analyzePanel');
    if (p) p.classList.add('hidden');
    var e = $('btnAnalyzeExit');
    if (e) e.classList.add('hidden');
    var b = $('btnAnalyze');
    if (b) b.classList.remove('hidden');
  }
  function renderAnalyze() {
    if (!analyzeChess || !board) return;
    var lastMove = null;
    try {
      var verbose = analyzeChess.history({ verbose: true });
      var v = verbose[verbose.length - 1];
      if (v) lastMove = { from: v.from, to: v.to };
    } catch (e) {}
    board.setFromChess(analyzeChess, lastMove, null);
    var turn = 'brancas';
    try { turn = analyzeChess.turn() === 'w' ? 'brancas' : 'pretas'; } catch (e) {}
    var fen = '';
    try { fen = analyzeChess.fen(); } catch (e) {}
    $('analyzeFen').textContent = '🔬 análise • jogam ' + turn + (fen ? ' • ' + fen : '') +
      (analyzeSans.length ? ' • ' + analyzeSans.join(' ') : '');
    var moves = [];
    try { moves = analyzeChess.moves(); } catch (e) {}
    var h = '';
    var seen = {};
    for (var i = 0; i < moves.length && h.length < 3000; i++) {
      var san = moves[i];
      if (seen[san]) continue;
      seen[san] = 1;
      h += '<button class="sec an-mv" data-san="' + esc(san) + '">' + esc(san) + '</button>';
    }
    $('analyzeMoves').innerHTML = h || '<span class="hint">sem lances legais (fim de jogo?)</span>';
    var btns = document.querySelectorAll('.an-mv');
    for (var j = 0; j < btns.length; j++) {
      (function (el) {
        el.addEventListener('click', function () { analyzePlay(el.getAttribute('data-san')); });
      })(btns[j]);
    }
    $('mvInfo').textContent = 'análise • ' + analyzeSans.length + ' lances livres';
  }
  function analyzePlay(san) {
    if (!analyzeChess) return;
    try {
      var mv = analyzeChess.move(san);
      if (!mv) { toast('Lance ilegal'); return; }
      analyzeSans.push(mv.san || san);
      renderAnalyze();
    } catch (e) { toast('Lance ilegal'); }
  }
  function analyzeRedo(d) {
    if (!analyzeChess) return;
    if (d < 0) {
      try {
        var u = analyzeChess.undo();
        if (u) analyzeSans.pop();
      } catch (e) {}
    }
    renderAnalyze();
  }

  function wireBoard(g) { return g; }

  function wire() {
    $('prevPg').addEventListener('click', function () { pgOffset = Math.max(0, pgOffset - PAGE); loadGames(false, true); window.scrollTo(0, 0); });
    $('nextPg').addEventListener('click', function () { pgOffset += PAGE; loadGames(false, true); window.scrollTo(0, 0); });
    $('backList').addEventListener('click', function () { closeGameDetail(); });
    $('mvFirst').addEventListener('click', function () { if (analyzeMode) return; plyIdx = 0; renderPly({}); });
    $('mvPrev').addEventListener('click', function () { if (analyzeMode) { analyzeRedo(-1); return; } plyIdx = Math.max(0, plyIdx - 1); renderPly({}); });
    $('mvNext').addEventListener('click', function () { plyIdx = Math.min(histSans.length, plyIdx + 1); renderPly({}); });
    $('mvLast').addEventListener('click', function () { if (analyzeMode) return; plyIdx = histSans.length; renderPly({}); });
    $('mvFlip').addEventListener('click', function () { flipState = !flipState; if (analyzeMode) renderAnalyze(); else renderPly({}); });
    $('btnAnalyze').addEventListener('click', function () { enterAnalyze(); });
    $('btnAnalyzeExit').addEventListener('click', function () { exitAnalyze(); renderPly({}); });
    $('btnUndoAn').addEventListener('click', function () { analyzeRedo(-1); });
    $('btnResetAn').addEventListener('click', function () { enterAnalyze(); });
    $('btnSaveUser').addEventListener('click', function () {
      var u = ($('cfgUser').value || '').trim();
      if (!u) { toast('Digite o username'); return; }
      A.saveConfig('username', u);
      var u2 = ($('cfgUser2').value || '').trim();
      A.saveConfig('secondary_username', u2);
      try { A.switchOwner(u); } catch (e) {}
      toast('Salvo: ' + u + (u2 ? ' + ' + u2 : ''));
      clearFilter();
      refresh();
    });
    $('btnFullSync').addEventListener('click', function () {
      A.triggerFullSync();
      toast('Iniciando histórico completo…');
      startSyncProgressPoll();
    });
    $('btnSync').addEventListener('click', function () {
      A.triggerIncrementalSync();
      toast('Sincronizando recentes…');
      startSyncProgressPoll();
    });
    $('btnAutoOn').addEventListener('click', function () { A.scheduleSync(); setTimeout(loadCfg, 800); });
    $('btnAutoOff').addEventListener('click', function () { A.cancelSync(); setTimeout(loadCfg, 800); });
    $('btnExport').addEventListener('click', function () {
      var j = '';
      try { j = A.exportJson(); } catch (e) {}
      toast('Exportado ' + j.length + ' chars (copie via log)');
      try { console.log(j); } catch (e) {}
    });
    $('btnWipe').addEventListener('click', function () {
      if (confirm('Apagar todas as partidas do cofre?')) { A.clearAll(); toast('Cofre apagado'); refresh(); }
    });
    $('btnCrashLog').addEventListener('click', function () {
      var t = '';
      try { t = A.getCrashLog(); } catch (e) { t = 'erro: ' + e; }
      var el = $('crashLog');
      el.textContent = t || '(vazio)';
      el.classList.remove('hidden');
    });
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) { loadCfg(); loadStats(); checkActiveSync(); }
    });
  }

  var syncIv = null;
  function startSyncProgressPoll() {
    if (syncIv) clearInterval(syncIv);
    $('syncProgressWrap').classList.remove('hidden');
    syncIv = setInterval(function () {
      var p = {};
      try { p = JSON.parse(A.getSyncProgress()); } catch (e) {}
      if (p && p.is_syncing) {
        var pct = p.total_months > 0 ? Math.round((p.current_month / p.total_months) * 100) : 0;
        $('syncProgressBar').style.width = pct + '%';
        $('syncStatus').textContent = (p.mode === 'full' ? '📥 Histórico: ' : '🔄 Recentes: ') +
          (p.status_message || (p.current_month + '/' + p.total_months));
        $('syncDetail').textContent = (p.games_inserted || 0) + ' partidas importadas • ' + pct + '%';
      } else {
        clearInterval(syncIv);
        syncIv = null;
        setTimeout(function () {
          $('syncProgressWrap').classList.add('hidden');
        }, 2500);
        if (p && p.last_error) {
          toast('Erro no sync: ' + p.last_error);
        } else {
          toast('Sync concluído: +' + (p && p.games_inserted ? p.games_inserted : 0) + ' partidas');
        }
        refresh();
      }
    }, 800);
  }

  function checkActiveSync() {
    try {
      var p = JSON.parse(A.getSyncProgress());
      if (p && p.is_syncing) startSyncProgressPoll();
    } catch (e) {}
  }

  function refresh() { loadCfg(); loadStats(); loadGames(true); }

  document.addEventListener('DOMContentLoaded', function () {
    tabs(); wire(); refresh(); checkActiveSync();
  });
})();
