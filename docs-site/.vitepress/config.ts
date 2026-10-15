import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Trove',
  description: 'Self-hostable personal finance platform — AGPL-3.0',
  lang: 'en-US',
  base: '/trove/',
  cleanUrls: true,
  ignoreDeadLinks: 'localhostLinks',

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/logo.svg' }],
    ['meta', { name: 'theme-color', content: '#4a6b4f' }]
  ],

  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'Trove',

    nav: [
      { text: 'Guide', link: '/guide/getting-started' },
      { text: 'Self-Hosting', link: '/self-hosting/docker-compose' },
      { text: 'API', link: '/api/' },
      { text: 'Mobile', link: '/mobile/build' },
      {
        text: 'v1.0.0',
        items: [
          { text: 'Changelog', link: 'https://github.com/Harnoor6693/trove/releases' },
          { text: 'Contributing', link: '/guide/contributing' }
        ]
      }
    ],

    sidebar: {
      '/guide/': [
        {
          text: 'Getting Started',
          items: [
            { text: 'Quick Start', link: '/guide/getting-started' },
            { text: 'Local Development', link: '/guide/local-development' },
            { text: 'Architecture', link: '/guide/architecture' },
            { text: 'Environment Variables', link: '/guide/environment-variables' },
            { text: 'Contributing', link: '/guide/contributing' }
          ]
        }
      ],
      '/self-hosting/': [
        {
          text: 'Self-Hosting',
          items: [
            { text: 'Docker Compose', link: '/self-hosting/docker-compose' },
            { text: 'Traefik + TLS', link: '/self-hosting/traefik-tls' }
          ]
        }
      ],
      '/api/': [
        {
          text: 'API Reference',
          items: [
            { text: 'Overview', link: '/api/' },
            { text: 'Authentication', link: '/api/authentication' },
            { text: 'Transactions', link: '/api/transactions' },
            { text: 'Ingestion', link: '/api/ingestion' },
            { text: 'Categorisation', link: '/api/categorisation' },
            { text: 'Budgets', link: '/api/budgets' },
            { text: 'Tax', link: '/api/tax' },
            { text: 'Receipts', link: '/api/receipts' },
            { text: 'Subscriptions', link: '/api/subscriptions' },
            { text: 'Reports', link: '/api/reports' },
            { text: 'AI Coach', link: '/api/ai-coach' },
            { text: 'Notifications', link: '/api/notifications' }
          ]
        }
      ],
      '/mobile/': [
        {
          text: 'Mobile',
          items: [
            { text: 'EAS Build Setup', link: '/mobile/build' },
            { text: 'Release (sideload APK)', link: '/mobile/app-store-release' }
          ]
        }
      ]
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/Harnoor6693/trove' }
    ],

    editLink: {
      pattern: 'https://github.com/Harnoor6693/trove/edit/canary/docs-site/:path',
      text: 'Edit this page on GitHub'
    },

    footer: {
      message: 'Released under the AGPL-3.0 License.',
      copyright: 'Copyright © 2026 Trove Contributors'
    },

    search: {
      provider: 'local'
    }
  }
})
