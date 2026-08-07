import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

/** Repo-wide docs. Per-module docs live in sidebarsModules.ts. */
const sidebars: SidebarsConfig = {
  generalSidebar: [
    'index',
    {
      type: 'category',
      label: 'Contributing',
      collapsed: false,
      items: [
        'contributing/repo-layout',
        'contributing/releasing',
        'contributing/adding-a-module',
      ],
    },
  ],
};

export default sidebars;
