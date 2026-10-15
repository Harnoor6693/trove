---
layout: home

hero:
  name: Trove
  text: Self-hostable personal finance platform
  tagline: Your money, fully understood — not just tracked. AGPL-3.0, runs entirely on your own hardware.
  actions:
    - theme: brand
      text: Get Started
      link: /guide/getting-started
    - theme: alt
      text: View on GitHub
      link: https://github.com/Harnoor6693/trove

features:
  - title: One deployment, self-hosted
    details: One Spring Boot module, one Postgres database, behind Traefik. No Kubernetes, no service mesh, no cloud dependency.
  - title: AI, off by default
    details: Optional coaching and categorisation. A household brings its own provider (e.g. a local Ollama) and its own key — nothing is called until configured.
  - title: Import from where your money already is
    details: Manual entry, CSV/OFX import, SimpleFIN bank connections, and receipt OCR — see the Ingestion and Receipts API docs.
  - title: Tax-time export, not a tax engine
    details: CSV/PDF export of your own recorded amounts and category labels, for Canada and the US. No computed figures, no filing, no advice.
  - title: Web and mobile
    details: A React web app plus a React Native (Expo) mobile app, sideloaded — no App Store or Play Store listing.
  - title: Honest AI Coach
    details: Conversational financial coaching that treats you like an adult — no upsells, no dark patterns.
---
