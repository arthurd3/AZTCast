import { icon } from '../ui/icons.js';

/**
 * Keyboard control for the whole watch page.
 *
 * The bindings are the ones every video site has converged on, so that muscle memory built on
 * YouTube works here. `?` opens the map, because a shortcut nobody can discover is a shortcut
 * nobody uses.
 *
 * Bound on the document rather than the player, so the keys work wherever focus happens to be
 * on the page — except inside a text field, and except on the two sliders, which handle their
 * own arrows and stop the event before it reaches here.
 */
const GROUPS = [
  {
    title: 'Reprodução',
    keys: [
      [['Espaço', 'K'], 'Reproduzir ou pausar'],
      [['J'], 'Voltar 10 segundos'],
      [['L'], 'Avançar 10 segundos'],
      [['←', '→'], 'Voltar ou avançar 5 segundos'],
      [['0', '–', '9'], 'Saltar para 0%…90% do vídeo'],
      [['<', '>'], 'Diminuir ou aumentar a velocidade'],
    ],
  },
  {
    title: 'Som e tela',
    keys: [
      [['↑', '↓'], 'Aumentar ou diminuir o volume'],
      [['M'], 'Silenciar'],
      [['F'], 'Tela cheia'],
      [['P'], 'Picture-in-picture'],
      [['?'], 'Este mapa de atalhos'],
    ],
  },
];

export function createShortcuts(actions) {
  const dialog = buildDialog();
  document.body.appendChild(dialog);

  function handle(event) {
    // A viewer typing in a field is typing, not steering the player.
    const target = event.target;
    if (
      target instanceof HTMLElement &&
      (target.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName))
    ) {
      return;
    }
    // Leave browser and OS shortcuts alone.
    if (event.ctrlKey || event.metaKey || event.altKey) {
      return;
    }

    const key = event.key;

    // Digits seek proportionally: 3 goes to 30% in.
    if (/^[0-9]$/.test(key)) {
      event.preventDefault();
      actions.seekToFraction(Number(key) / 10);
      return;
    }

    switch (key.toLowerCase()) {
      case ' ':
      case 'k':
        event.preventDefault();
        actions.togglePlay();
        break;
      case 'j':
        event.preventDefault();
        actions.seekBy(-10);
        break;
      case 'l':
        event.preventDefault();
        actions.seekBy(10);
        break;
      case 'arrowleft':
        event.preventDefault();
        actions.seekBy(-5);
        break;
      case 'arrowright':
        event.preventDefault();
        actions.seekBy(5);
        break;
      case 'arrowup':
        event.preventDefault();
        actions.adjustVolume(0.05);
        break;
      case 'arrowdown':
        event.preventDefault();
        actions.adjustVolume(-0.05);
        break;
      case 'm':
        event.preventDefault();
        actions.toggleMute();
        break;
      case 'f':
        event.preventDefault();
        actions.toggleFullscreen();
        break;
      case 'p':
        event.preventDefault();
        actions.togglePictureInPicture();
        break;
      case '<':
      case ',':
        event.preventDefault();
        actions.adjustRate(-1);
        break;
      case '>':
      case '.':
        event.preventDefault();
        actions.adjustRate(1);
        break;
      case '?':
        event.preventDefault();
        toggleHelp();
        break;
      default:
        break;
    }
  }

  function toggleHelp() {
    if (dialog.open) {
      dialog.close();
    } else {
      // showModal, not show: it traps focus and wires Escape for us, which is most of what an
      // accessible modal has to do.
      dialog.showModal();
    }
  }

  document.addEventListener('keydown', handle);

  return {
    openHelp: () => {
      if (!dialog.open) {
        dialog.showModal();
      }
    },
    destroy: () => {
      document.removeEventListener('keydown', handle);
      dialog.remove();
    },
  };
}

function buildDialog() {
  const dialog = document.createElement('dialog');
  dialog.className = 'shortcuts';
  dialog.setAttribute('aria-labelledby', 'shortcutsTitle');

  const header = document.createElement('div');
  header.className = 'shortcuts__head';

  const title = document.createElement('h2');
  title.id = 'shortcutsTitle';
  title.textContent = 'Atalhos de teclado';

  const close = document.createElement('button');
  close.type = 'button';
  close.className = 'btn btn--icon';
  close.setAttribute('aria-label', 'Fechar');
  close.appendChild(icon('close'));
  close.addEventListener('click', () => dialog.close());

  header.append(title, close);

  const body = document.createElement('div');
  body.className = 'shortcuts__body';

  for (const section of GROUPS) {
    const column = document.createElement('div');

    const heading = document.createElement('p');
    heading.className = 'menu__title';
    heading.textContent = section.title;
    column.appendChild(heading);

    const list = document.createElement('dl');
    list.className = 'shortcuts__list';

    for (const [keys, description] of section.keys) {
      const term = document.createElement('dt');
      for (const key of keys) {
        const kbd = document.createElement('kbd');
        kbd.textContent = key;
        term.appendChild(kbd);
      }

      const definition = document.createElement('dd');
      definition.textContent = description;

      list.append(term, definition);
    }

    column.appendChild(list);
    body.appendChild(column);
  }

  dialog.append(header, body);

  // Clicking the backdrop closes. The dialog element itself fills the viewport, so a click
  // that lands on it rather than on a child is a click outside the panel.
  dialog.addEventListener('click', (event) => {
    if (event.target === dialog) {
      dialog.close();
    }
  });

  return dialog;
}
