import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

/**
 * One exported sidebar per module. A second module adds a key here, a folder under `modules/`, and
 * an entry in the navbar's Modules dropdown — nothing else.
 *
 * Ordered by what a reader is trying to do rather than by how the code is organised: get it
 * running, learn what it can do, then look things up. Contributor material sits last, collapsed,
 * so it doesn't compete with the user path.
 *
 * Sidebar structure is independent of file paths, so this grouping costs no URL changes.
 */
const sidebars: SidebarsConfig = {
  mcpSidebar: [
    'mcp/index',
    {
      type: 'category',
      label: 'Get started',
      collapsed: false,
      items: ['mcp/quickstart', 'mcp/credentials', 'mcp/versions', 'mcp/docker'],
    },
    {
      type: 'category',
      label: 'Using it',
      collapsed: false,
      items: [
        'mcp/using',
        {
          type: 'category',
          label: 'Perspective',
          link: {type: 'doc', id: 'mcp/perspective/index'},
          items: [
            'mcp/perspective/editing',
            'mcp/perspective/validation',
            'mcp/perspective/live-diagnostics',
          ],
        },
        'mcp/tags',
        'mcp/performance',
        'mcp/designer-save',
        'mcp/clients/remote-designer',
      ],
    },
    {
      type: 'category',
      label: 'Reference',
      collapsed: false,
      items: [
        {
          type: 'category',
          label: 'Tool reference',
          link: {type: 'doc', id: 'mcp/tools/index'},
          items: ['mcp/tools/gateway', 'mcp/tools/designer'],
        },
        'mcp/endpoints',
        'mcp/troubleshooting',
        'mcp/clients/inspector',
      ],
    },
    {
      type: 'category',
      label: 'Contributing',
      collapsed: true,
      items: [
        'mcp/contributing/building',
        'mcp/contributing/dev-gateway',
        'mcp/contributing/adding-a-tool',
        'mcp/design',
      ],
    },
  ],
};

export default sidebars;
