import type * as Preset from '@docusaurus/preset-classic';
import type {Config} from '@docusaurus/types';
import type * as DocsPlugin from '@docusaurus/plugin-content-docs';

const config: Config = {
  title: 'co-lens Ignition modules',
  tagline: 'Ignition modules, built small and documented from the code.',
  favicon: 'img/favicon.ico',

  // A project page, not an organisation page: no co-lens.github.io repository exists, so the site
  // is served from a subpath and baseUrl must carry the repo name. If a custom domain is ever
  // added, `url` becomes that domain and `baseUrl` becomes '/', plus a static/CNAME file — which
  // is why nothing inside the site hardcodes '/ignition-modules/'. Use relative links.
  url: 'https://co-lens.github.io',
  baseUrl: '/ignition-modules/',
  organizationName: 'co-lens',
  projectName: 'ignition-modules',

  // Set explicitly because the default (undefined) leaves GitHub Pages to redirect every
  // extensionless URL to its trailing-slash form. `false` emits foo.html, which Pages serves at
  // /foo directly.
  trailingSlash: false,

  // This site is a split of one 454-line README that was full of in-page anchors. `throw` is what
  // catches every anchor that wasn't rewritten as a cross-page link.
  onBrokenLinks: 'throw',
  // 'warn', not 'throw': the tool-reference anchors are emitted at runtime by a React component
  // reading tools.json, and Docusaurus's anchor checker only sees statically authored headings.
  onBrokenAnchors: 'warn',

  markdown: {
    hooks: {
      onBrokenMarkdownLinks: 'throw',
    },
  },

  i18n: {defaultLocale: 'en', locales: ['en']},

  presets: [
    [
      'classic',
      {
        // The 'default' docs instance: repo-wide material, mounted at the root.
        docs: {
          path: 'content',
          routeBasePath: '/',
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/co-lens/ignition-modules/tree/main/docs/',
          showLastUpdateTime: true,
        },
        blog: false,
        theme: {customCss: './src/css/custom.css'},
      } satisfies Preset.Options,
    ],
  ],

  themes: [
    [
      // Offline search, built at compile time — no Algolia account, no external requests, which
      // keeps the site self-contained. It is a third-party theme coupled to Docusaurus internals,
      // so treat a Docusaurus major upgrade as needing this checked.
      require.resolve('@easyops-cn/docusaurus-search-local'),
      {
        hashed: true,
        // Both docs instances, or the module pages — the bulk of the site — wouldn't be indexed.
        docsRouteBasePath: ['/', 'modules'],
        indexBlog: false,
        highlightSearchTermsOnTargetPage: true,
        searchResultLimits: 8,
      },
    ],
  ],

  plugins: [
    [
      '@docusaurus/plugin-content-docs',
      {
        // A second instance, one tree per module under /modules/<module>/. Adding a module is a new
        // folder here plus a sidebar entry and a navbar item — no change to this file.
        id: 'modules',
        path: 'modules',
        routeBasePath: 'modules',
        sidebarPath: './sidebarsModules.ts',
        editUrl: 'https://github.com/co-lens/ignition-modules/tree/main/docs/',
        showLastUpdateTime: true,
      } satisfies DocsPlugin.Options,
    ],
  ],

  themeConfig: {
    image: 'img/docusaurus-social-card.jpg',
    navbar: {
      title: 'co-lens modules',
      logo: {alt: '', src: 'img/logo.svg'},
      items: [
        {type: 'docSidebar', sidebarId: 'generalSidebar', position: 'left', label: 'Docs'},
        {
          type: 'dropdown',
          label: 'Modules',
          position: 'left',
          items: [
            // docSidebar rather than a plain link, so the navbar item stays highlighted while
            // you're anywhere in the module's tree. docsPluginId targets the second instance.
            {
              type: 'docSidebar',
              sidebarId: 'mcpSidebar',
              docsPluginId: 'modules',
              label: 'Ignition MCP',
            },
          ],
        },
        {
          href: 'https://github.com/co-lens/ignition-modules',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {label: 'Ignition MCP', to: '/modules/mcp'},
            {label: 'Quickstart', to: '/modules/mcp/quickstart'},
            {label: 'Tool reference', to: '/modules/mcp/tools'},
          ],
        },
        {
          title: 'More',
          items: [
            {label: 'GitHub', href: 'https://github.com/co-lens/ignition-modules'},
            {label: 'Releases', href: 'https://github.com/co-lens/ignition-modules/releases'},
          ],
        },
      ],
      copyright: `© ${new Date().getFullYear()} co-lens. MIT licensed.`,
    },
    prism: {
      theme: require('prism-react-renderer').themes.github,
      darkTheme: require('prism-react-renderer').themes.dracula,
      additionalLanguages: ['kotlin', 'java', 'bash', 'json', 'groovy', 'yaml'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
