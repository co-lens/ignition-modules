import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

/**
 * One exported sidebar per module. A second module adds a key here, a folder under `modules/`, and
 * an entry in the navbar's Modules dropdown — nothing else.
 */
const sidebars: SidebarsConfig = {
  mcpSidebar: [
    'mcp/index',
    'mcp/quickstart',
    'mcp/endpoints',
    {
      type: 'category',
      label: 'Tool reference',
      collapsed: false,
      link: {type: 'doc', id: 'mcp/tools/index'},
      items: ['mcp/tools/gateway', 'mcp/tools/designer'],
    },
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
    {
      type: 'category',
      label: 'Clients',
      items: ['mcp/clients/remote-designer', 'mcp/clients/inspector'],
    },
    {
      type: 'category',
      label: 'Contributing',
      items: [
        'mcp/contributing/building',
        'mcp/contributing/dev-gateway',
        'mcp/contributing/adding-a-tool',
      ],
    },
    'mcp/design',
  ],
};

export default sidebars;
