/* Tabuleiro próprio: desenha FEN num canvas com tema chess.com (verde/creme).
 * Sem dependência de gadget externo: o "embed" do chess.com é só p/ sites
 * (iframe/js) e exige rede; aqui o replay é 100% offline via chess.js + PGN do cofre.
 * Peças Unicode com contorno para legibilidade. Toque: arrastar alterna lance.
 */
(function () {
  'use strict';
  var PIECES = {
    w: { k: '♔', q: '♕', r: '♖', b: '♗', n: '♘', p: '♙' },
    b: { k: '♚', q: '♛', r: '♜', b: '♝', n: '♞', p: '♟' }
  };
  var LIGHT = '#eeeed2', DARK = '#769656';
  var HL_FROM = 'rgba(255,255,0,0.45)', HL_TO = 'rgba(255,255,0,0.30)';

  function Board(canvasId) {
    this.cv = document.getElementById(canvasId);
    this.ctx = this.cv.getContext('2d');
    this.flipped = false;
    this.position = null; // 8x8 array vindo do chess.js
    this.lastMove = null; // {from,to}
    this.errSquare = null;
    this.selSquare = null; // casa selecionada no tap-to-move
    this.legalTargets = null; // mapa to->san[] p/ highlight
    var self = this;
    this._touchX = null;
    this.cv.addEventListener('touchstart', function (e) {
      if (e.touches.length === 1) self._touchX = e.touches[0].clientX;
    }, { passive: true });
    this.cv.addEventListener('touchend', function (e) {
      if (self._touchX === null) return;
      var dx = e.changedTouches[0].clientX - self._touchX;
      self._touchX = null;
      if (Math.abs(dx) < 30) return;
      if (window.__boardSwipe) window.__boardSwipe(dx > 0 ? -1 : 1);
    }, { passive: true });
    this.cv.addEventListener('click', function (e) {
      if (window.__boardTap) {
        var sq = self.squareAt(e);
        if (sq) window.__boardTap(sq);
      }
    });
  }

  Board.prototype.setSelected = function (sqName, legalTargets) {
    this.selSquare = sqName || null;
    this.legalTargets = legalTargets || null;
    this.draw();
  };

  Board.prototype.squareAt = function (e) {
    try {
      var rect = this.cv.getBoundingClientRect();
      var cssX = (e.clientX - rect.left), cssY = (e.clientY - rect.top);
      var scaleX = this.cv.width / Math.max(1, rect.width);
      var scaleY = this.cv.height / Math.max(1, rect.height);
      var px = cssX * scaleX, py = cssY * scaleY;
      var S = this.cv.width, sq = S / 8;
      var c = Math.floor(px / sq), r = Math.floor(py / sq);
      if (c < 0 || c > 7 || r < 0 || r > 7) return null;
      return sqName(r, c, this.flipped);
    } catch (err) { return null; }
  };

  Board.prototype.setFromChess = function (chessObj, lastMove, errSquare) {
    this.position = chessObj.board();
    this.lastMove = lastMove || null;
    this.errSquare = errSquare || null;
    this.selSquare = null;
    this.legalTargets = null;
    this.draw();
  };

  Board.prototype.setFenBoard = function (boardArr, lastMove, errSquare) {
    this.position = boardArr;
    this.lastMove = lastMove || null;
    this.errSquare = errSquare || null;
    this.draw();
  };

  function sqName(r, c, flipped) {
    var file = flipped ? 7 - c : c;
    var rank = flipped ? r : 7 - r;
    return 'abcdefgh'.charAt(file) + (rank + 1);
  }

  Board.prototype.draw = function () {
    var ctx = this.ctx, S = this.cv.width, sq = S / 8;
    ctx.clearRect(0, 0, S, S);
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    for (var r = 0; r < 8; r++) {
      for (var c = 0; c < 8; c++) {
        var name = sqName(r, c, this.flipped);
        var isLight = (r + c) % 2 === 0;
        ctx.fillStyle = isLight ? LIGHT : DARK;
        ctx.fillRect(c * sq, r * sq, sq, sq);
        if (this.lastMove && (name === this.lastMove.from || name === this.lastMove.to)) {
          ctx.fillStyle = name === this.lastMove.from ? HL_FROM : HL_TO;
          ctx.fillRect(c * sq, r * sq, sq, sq);
        }
        if (this.errSquare && name === this.errSquare) {
          ctx.fillStyle = 'rgba(224,92,92,0.60)';
          ctx.fillRect(c * sq, r * sq, sq, sq);
        }
        if (this.selSquare && name === this.selSquare) {
          ctx.fillStyle = 'rgba(129,182,76,0.55)';
          ctx.fillRect(c * sq, r * sq, sq, sq);
        }
        var piece = null;
        if (this.position) {
          var pr = this.flipped ? 7 - r : r;
          var pc = this.flipped ? 7 - c : c;
          var cell = this.position[pr] && this.position[pr][pc];
          if (cell) piece = { type: cell.type.toLowerCase(), color: cell.color };
        }
        if (piece) {
          var isW = piece.color === 'w';
          var glyph = (PIECES[piece.color] && PIECES[piece.color][piece.type]) || piece.type.toUpperCase();
          ctx.font = Math.round(sq * 0.72) + 'px "DejaVu Sans", "Segoe UI Symbol", "Apple Symbols", serif';
          var x = c * sq + sq / 2, y = r * sq + sq / 2 + sq * 0.03;
          if (isW) {
            ctx.lineWidth = Math.max(1.5, sq * 0.05);
            ctx.strokeStyle = '#1b1b1b';
            ctx.fillStyle = '#ffffff';
            ctx.strokeText(glyph, x, y);
            ctx.fillText(glyph, x, y);
          } else {
            ctx.lineWidth = Math.max(1.2, sq * 0.04);
            ctx.strokeStyle = '#ffffff';
            ctx.fillStyle = '#1c1b19';
            ctx.strokeText(glyph, x, y);
            ctx.fillText(glyph, x, y);
          }
        }
        if (this.legalTargets && this.legalTargets[name]) {
          var cx = c * sq + sq / 2, cy = r * sq + sq / 2;
          ctx.fillStyle = 'rgba(20,40,20,0.55)';
          ctx.beginPath();
          ctx.arc(cx, cy, sq * 0.16, 0, Math.PI * 2);
          ctx.fill();
        }
      }
    }
    ctx.fillStyle = 'rgba(0,0,0,0.45)';
    ctx.font = Math.round(sq * 0.22) + 'px sans-serif';
    ctx.textAlign = 'left'; ctx.textBaseline = 'top';
    for (var i = 0; i < 8; i++) {
      var fileCh = 'abcdefgh'.charAt(this.flipped ? 7 - i : i);
      ctx.fillStyle = (i % 2 === 0) ? DARK : LIGHT;
      ctx.fillText(fileCh, i * sq + 3, S - sq * 0.24);
    }
  };

  window.ChessBoard = Board;
})();
