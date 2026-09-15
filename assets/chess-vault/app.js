/* Chess Vault — UI offline. Bridge Android + análise via chess.js (PGN do cofre). */
(function () {
  'use strict';
  var A = window.Android || null;
  var PAGE = 20, pgOffset = 0;
  var board = null, chess = null, histSans = [], plyIdx = 0, errPlyIdx = -1, flipState = false;
  var touchTimer = null;

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
        if (t === 'games' && pgOffset === 0) loadGames(true);
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
      if (cfg.username) $('cfgUser').value = cfg.username;
      var auto = cfg.auto_sync_enabled !== 'false';
      $('syncInfo').textContent = 'Total no cofre: ' + (cfg.total_games || 0) +
        ' • último sync: ' + (cfg.last_sync_human || 'nunca') +
        ' • próximo: ' + (cfg.next_alarm_human || '—') +
        ' • auto: ' + (auto ? 'ON' : 'OFF');
      $('hdrSub').textContent = cfg.username ? '@' + cfg.username + ' • ' + (cfg.total_games || 0) + ' partidas' : 'sincronize suas partidas';
    } catch (e) {}
  }

  function loadStats() {
    var s = {};
    try { s = JSON.parse(A.getStats()); } catch (e) {}
    var cards = '';
    cards += card(s.total || 0, 'partidas no cofre');
    cards += card(s.timeout_losses || 0, 'derrotas por tempo');
    cards += card(s.mate_losses || 0, 'mates recebidos');
    cards += card(s.avg_accuracy != null ? s.avg_accuracy : '—', 'accuracy média');
    $('dashCards').innerHTML = cards;
    var tc = '';
    (s.by_time_class || []).forEach(function (t) {
      var tot = t.games || 1;
      var w = Math.round((t.wins / tot) * 100), d = Math.round((t.draws / tot) * 100), l = 100 - w - d;
      tc += '<div class="card"><b>' + esc(t.time_class) + '</b> <span class="hint">' + t.games + ' jogos • rating médio ' + t.avg_rating +
        (t.avg_accuracy != null ? ' • acc ' + t.avg_accuracy : '') + '</b><div class="bar"><div class="w" style="width:' + w + '%"></div></div>' +
        '<div class="hint">' + t.wins + 'V / ' + t.losses + 'D / ' + t.draws + 'E</div></div>';
    });
    $('byTc').innerHTML = tc || '<div class="card dim">Sem dados — sincronize.</div>';
    var cc = '';
    (s.by_color || []).forEach(function (t) {
      cc += '<div class="card"><b>' + (t.color === 'white' ? '♔ Brancas' : '♚ Pretas') + '</b><div class="hint">' +
        t.games + ' jogos • ' + t.wins + 'V / ' + t.losses + 'D / ' + t.draws + 'E</div></div>';
    });
    $('byColor').innerHTML = cc || '';
    var lt = '<div class="cards">' + card(s.mate_losses || 0, 'xeque-mate') +
      card(s.resigns || 0, 'desistências') + card(s.timeout_losses || 0, 'tempo esgotado') + '</div>';
    $('lossTypes').innerHTML = lt;
    var ol = '';
    (s.openings || []).forEach(function (o) {
      var tot = o.games || 1;
      var wr = Math.round((o.wins / tot) * 100);
      var crisis = (o.games >= 5 && wr < 35) ? ' <span class="err-mark">em crise</span>' : '';
      ol += '<tr><td>' + esc(shortEco(o.eco)) + crisis + '</td><td>' + o.games + '</td><td>' +
        o.wins + '/' + o.losses + '/' + o.draws + '</td><td>' + wr + '%</td></tr>';
    });
    $('openList').innerHTML = '<table class="tbl"><tr><th>Abertura</th><th>J</th><th>V/D/E</th><th>WR</th></tr>' + ol + '</table>';
    var ep = s.err_by_phase || {};
    $('errPhase').innerHTML = card(ep.abertura || 0, 'erros na abertura (1–12)') +
      card(ep.meio || 0, 'erros no meio-jogo (13–30)') + card(ep.final || 0, 'erros no final (31+)');
    var h = s.err_hist || [], mx = 1;
    h.forEach(function (e) { if (e.count > mx) mx = e.count; });
    var hh = '';
    h.forEach(function (e) {
      hh += '<div style="display:flex;align-items:center;gap:8px;font-size:12px;margin:3px 0;">' +
        '<span style="width:34px;color:var(--dim)">L' + e.move + '</span>' +
        '<div class="bar" style="flex:1"><div class="l" style="width:' + Math.round((e.count / mx) * 100) + '%"></div></div>' +
        '<span style="width:30px;text-align:right">' + e.count + '</span></div>';
    });
    $('errHist').innerHTML = hh || '<div class="card dim">Sem mates registrados.</div>';
  }

  function card(v, l) { return '<div class="card"><div class="big">' + v + '</div><div class="lbl">' + l + '</div></div>'; }
  function esc(s) { return String(s == null ? '' : s).replace(/&/g, '&amp;').replace(/</g, '&lt;'); }
  function shortEco(e) {
    e = String(e || '');
    return e.length > 42 ? e.slice(0, 42) + '…' : e;
  }
  function resPill(g) {
    if (g.is_win) return '<span class="pill win">vitória</span>';
    if (g.is_loss) return '<span class="pill loss">' + esc(g.my_result) + '</span>';
    return '<span class="pill draw">empate</span>';
  }

  function loadGames(reset) {
    if (reset) { pgOffset = 0; $('gameDetail').classList.add('hidden'); }
    var arr = [];
    try { arr = JSON.parse(A.getRecentGames(PAGE, pgOffset)); } catch (e) {}
    var h = '';
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
    var items = document.querySelectorAll('.gitem');
    for (var i = 0; i < items.length; i++) {
      items[i].addEventListener('click', function () { openGame(this.getAttribute('data-uuid')); });
    }
  }

  function movesFromGame(g) {
    try {
      var m = JSON.parse(g.moves_san || '[]');
      if (m && m.length) return m;
    } catch (e) {}
    try {
      var chess = new Chess();
      if (chess.load_pgn(g.pgn || '')) return chess.history();
    } catch (e) {}
    return [];
  }

  function openGame(uuid) {
    var g = {};
    try { g = JSON.parse(A.getGame(uuid)); } catch (e) { toast('Falha ao abrir partida'); return; }
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
    $('openChessCom').addEventListener('click', function (ev) {
      ev.preventDefault();
      try { A.openExternal(g.url); } catch (e) {}
    });
    try {
      chess = new Chess();
      var ok = chess.load_pgn(g.pgn || '');
      histSans = ok ? chess.history() : movesFromGame(g);
    } catch (e) { histSans = []; try { chess = new Chess(); } catch (e2) {} }
    if (!histSans.length) histSans = movesFromGame(g);
    plyIdx = 0; flipState = (g.my_color === 'black');
    errPlyIdx = (g.err_move_num != null && g.err_move_num !== 'null')
      ? Math.max(0, (parseInt(g.err_move_num, 10) - 1) * 2 + (g.my_color === 'white' ? 0 : 1)) : -1;
    if (!board) board = new ChessBoard('board');
    board.flipped = flipState;
    renderPly(g);
    window.__boardSwipe = function (dir) { stepPly(dir, g); };
  }

  function chessAtPly(n) {
    var c = new Chess();
    for (var i = 0; i < n && i < histSans.length; i++) {
      try { c.move(histSans[i]); } catch (e) { break; }
    }
    return c;
  }

  function renderPly(g) {
    var c = chessAtPly(plyIdx);
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
    plyIdx = Math.max(0, Math.min(histSans.length, plyIdx + d));
    renderPly(g || {});
  }

  function wireBoard(g) { return g; }

  function wire() {
    $('prevPg').addEventListener('click', function () { pgOffset = Math.max(0, pgOffset - PAGE); loadGames(false); window.scrollTo(0, 0); });
    $('nextPg').addEventListener('click', function () { pgOffset += PAGE; loadGames(false); window.scrollTo(0, 0); });
    $('backList').addEventListener('click', function () {
      $('gameDetail').classList.add('hidden');
      $('gameList').style.display = '';
      document.querySelector('.pager').style.display = '';
    });
    $('mvFirst').addEventListener('click', function () { plyIdx = 0; renderPly({}); });
    $('mvPrev').addEventListener('click', function () { plyIdx = Math.max(0, plyIdx - 1); renderPly({}); });
    $('mvNext').addEventListener('click', function () { plyIdx = Math.min(histSans.length, plyIdx + 1); renderPly({}); });
    $('mvLast').addEventListener('click', function () { plyIdx = histSans.length; renderPly({}); });
    $('mvFlip').addEventListener('click', function () { flipState = !flipState; renderPly({}); });
    $('btnSaveUser').addEventListener('click', function () {
      var u = ($('cfgUser').value || '').trim().toLowerCase();
      if (!u) { toast('Digite o username'); return; }
      A.saveConfig('username', u);
      toast('Salvo: ' + u);
      loadCfg();
    });
    $('btnSync').addEventListener('click', function () { A.triggerSync(); toast('Sync iniciado…'); pollSync(); });
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
    document.addEventListener('visibilitychange', function () {
      if (!document.hidden) { loadCfg(); loadStats(); }
    });
  }

  function pollSync() {
    var n = 0;
    var iv = setInterval(function () {
      n++;
      loadCfg(); loadStats();
      if (n > 20) clearInterval(iv);
    }, 3000);
  }

  function refresh() { loadCfg(); loadStats(); loadGames(true); }

  document.addEventListener('DOMContentLoaded', function () {
    tabs(); wire(); refresh();
  });
})();
