(() => {
  'use strict';

  const host = window.TerminalHost;
  const element = document.getElementById('terminal');
  const terminal = new Terminal({
    cursorBlink: false,
    allowProposedApi: true,
    fontFamily: 'monospace',
    fontSize: 14,
    scrollback: 5000,
    theme: {
      background: '#101010',
      foreground: '#f3f3f3',
      cursor: '#fafafa',
      selectionBackground: '#505050',
    },
  });
  const fit = new FitAddon.FitAddon();
  const search = new SearchAddon.SearchAddon();
  terminal.loadAddon(fit);
  terminal.loadAddon(search);
  terminal.open(element);
  terminal.onResize(({rows, cols}) => host.resize(rows, cols));

  let modifier = '';
  function send(data) {
    if (modifier === 'Ctrl' && data.length === 1) {
      const code = data.toUpperCase().charCodeAt(0);
      if (code >= 64 && code <= 95) data = String.fromCharCode(code - 64);
      else if (data === ' ') data = '\x00';
      else if (data === '?') data = '\x7f';
    } else if (modifier === 'Alt') {
      data = '\x1b' + data;
    }
    modifier = '';
    host.modifier('');
    host.input(data);
  }

  terminal.onData(send);
  new ResizeObserver(() => fit.fit()).observe(element);
  fit.fit();
  host.resize(terminal.rows, terminal.cols);

  window.phoneTerminal = Object.freeze({
    write(encoded) {
      const bytes = Uint8Array.from(atob(encoded), ch => ch.charCodeAt(0));
      terminal.write(bytes);
    },
    reset() {
      terminal.reset();
    },
    focus() {
      terminal.focus();
    },
    key(name) {
      if (name === 'Ctrl' || name === 'Alt') {
        modifier = name === modifier ? '' : name;
        host.modifier(modifier);
      } else if (name === 'Interrupt') {
        modifier = '';
        send('\x03');
      } else {
        const cursor = terminal.modes.applicationCursorKeysMode ? '\x1bO' : '\x1b[';
        const sequences = {
          Esc: '\x1b', Tab: '\t', Home: cursor + 'H', End: cursor + 'F',
          Up: cursor + 'A', Down: cursor + 'B', Right: cursor + 'C', Left: cursor + 'D',
        };
        if (Object.prototype.hasOwnProperty.call(sequences, name)) send(sequences[name]);
      }
      terminal.focus();
    },
    copy() {
      return terminal.getSelection();
    },
    paste(text) {
      terminal.paste(text);
      terminal.focus();
    },
    search(text) {
      return Boolean(text) && search.findNext(text);
    },
    font(size) {
      terminal.options.fontSize = size;
      fit.fit();
    },
  });
  host.ready();
})();
