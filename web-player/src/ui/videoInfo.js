/** Renders the current quality and the ladder the manifest advertises. */
export function createVideoInfo(element) {
  return {
    render(player) {
      const levels = player.levels();
      if (levels.length === 0) {
        element.replaceChildren();
        return;
      }

      const current = player.currentLevel();
      const mode =
        current === -1
          ? `Automático${describe(levels[player.loadLevel()])}`
          : `Manual: ${describe(levels[current]).replace(' | Atual: ', '')}`;

      // Built as nodes rather than innerHTML: the level labels come from a manifest the
      // server generated, and string-concatenating that into HTML is a habit worth not having.
      const status = document.createElement('div');
      status.append(strong('Status: '), document.createTextNode(mode));

      const available = document.createElement('div');
      available.append(
        strong('Qualidades disponíveis: '),
        document.createTextNode(levels.map(label).join(', ')),
      );

      element.replaceChildren(status, available);
    },
    clear() {
      element.replaceChildren();
    },
  };
}

function strong(text) {
  const node = document.createElement('strong');
  node.textContent = text;
  return node;
}

function label(level) {
  return `${level.height}p`;
}

function describe(level) {
  return level ? ` | Atual: ${label(level)}` : '';
}
