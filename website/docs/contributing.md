---
title: Contributing
description: OryxOS Contributing — current capabilities and runtime design.
---

# Contributing

OryxOS is an oryx-labs project developed with AI-assisted engineering. Contributions can start with design feedback, documentation, implementation, tests, or real use cases.

## Before changing code

1. Read the [development guide](https://github.com/jianghr-rr/OryxOS/blob/main/CLAUDE.md) and the relevant technical design.
2. Discuss large interface or architecture changes in an [issue](https://github.com/jianghr-rr/OryxOS/issues).
3. Keep a pull request focused and explain the behavior, validation, and limitations.

## Verify your change

For Java changes:

```bash
./mvnw clean package
```

For website changes:

```bash
npm ci
npm run docs:build
npm run docs:preview
```

Check both languages, desktop and mobile layouts, internal navigation, and assets under the `/OryxOS/` base path. Keep generated output and credentials out of Git.

## Website deployment

The repository includes a GitHub Pages workflow. A maintainer must select **GitHub Actions** in repository **Settings → Pages → Build and deployment**. Leave **Custom domain** empty for the default project URL.

After the website files are pushed to `main`, the workflow builds and deploys the site to `https://jianghr-rr.github.io/OryxOS/`. Preparing this workflow locally does not publish the site.

## References

The website layout follows [robustmq/mq9](https://github.com/robustmq/mq9), adapted for OryxOS. The project plans to use Apache License 2.0; a formal repository LICENSE file is still pending.
