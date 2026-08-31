import type {Config} from '@docusaurus/types';
// ThemeConfig comes from the preset rather than from preset-classic, which this site no longer
// depends on. The preset's types entry also carries the `@theme/*` module declarations that
// ToolReference needs — they used to arrive with preset-classic.
import type {ColensPresetOptions, ThemeConfig} from '@co-lens/docusaurus-preset';

// The one place the subpath is written down. `headTags` hrefs are emitted verbatim — Docusaurus
// does not prefix them the way it does `favicon` — so they have to build their own absolute path,
// and doing it from this constant keeps the "nothing hardcodes '/ignition-modules/'" rule below
// true: moving to a custom domain is still a one-line change.
const baseUrl = '/ignition-modules/';

const config: Config = {
  title: 'co-lens Ignition modules',
  tagline: 'Ignition modules, built small and documented from the code.',
  // The .ico carries six sizes and is what every browser will accept; the SVG below is offered
  // alongside it for the ones that prefer a vector. Deliberately not the co-lens wordmark, which
  // is 726x158 and unreadable at 16px — favicon.svg is the square lambda mark.
  favicon: 'img/favicon.ico',

  headTags: [
    {
      tagName: 'link',
      attributes: {rel: 'icon', type: 'image/svg+xml', href: `${baseUrl}img/favicon.svg`},
    },
  ],

  // A project page, not an organisation page: no co-lens.github.io repository exists, so the site
  // is served from a subpath and baseUrl must carry the repo name. If a custom domain is ever
  // added, `url` becomes that domain and `baseUrl` becomes '/', plus a static/CNAME file — which
  // is why nothing inside the site hardcodes '/ignition-modules/'. Use relative links.
  url: 'https://co-lens.github.io',
  baseUrl,
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
      // The co-lens template. It supplies the theme, the token layer, the search wiring, the MDX
      // components and the doc-health checks; everything below is what this site owns.
      '@co-lens/docusaurus-preset',
      {
        docs: [
          // The default instance: repo-wide material, mounted at the root.
          {
            path: 'content',
            routeBasePath: '/',
            sidebarPath: './sidebars.ts',
            editUrl: 'https://github.com/co-lens/ignition-modules/tree/main/docs/',
            showLastUpdateTime: true,
          },
          // A second instance, one tree per module under /modules/<module>/. Adding a module is a
          // new folder here plus a sidebar entry and a navbar item — no change to this file.
          {
            id: 'modules',
            path: 'modules',
            routeBasePath: 'modules',
            sidebarPath: './sidebarsModules.ts',
            editUrl: 'https://github.com/co-lens/ignition-modules/tree/main/docs/',
            showLastUpdateTime: true,
          },
        ],
        // The preset derives `docsRouteBasePath` from the instances above, so the search index
        // covers both trees without it being restated here. These two are genuinely site-specific.
        search: {
          hashed: true,
          searchResultLimits: 8,
        },
      } satisfies ColensPresetOptions,
    ],
  ],

  themeConfig: {
    // og:image, generated with `npx colens-docs-card`. It has to be a raster format, because
    // Slack, LinkedIn, Discord and X all decline to render an SVG there.
    image: 'img/social-card.png',
    navbar: {
      // The lambda mark comes from the preset and uses currentColor, so there is no srcDark asset
      // to keep in sync any more. The title carries what the mark doesn't.
      title: 'modules',
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
  } satisfies ThemeConfig,
};

export default config;
