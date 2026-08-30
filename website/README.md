# datapipelines.co — marketing website

Static site. No build step, no framework, no runtime dependencies: plain
HTML, CSS and a small vanilla-JS file (theme toggle + copy buttons). The
site is fully readable with JavaScript disabled.

## Layout

```
index.html                  the whole site, one page
assets/css/site.css         all site styles (token-only)
assets/js/site.js           theme toggle + copy-to-clipboard
assets/img/                 screenshots (referenced by index.html)
assets/vendor/design-system/  vendored design tokens/themes — do not edit
```

## Local preview

Any static file server works, for example:

```bash
cd website
python3 -m http.server 8080
```

Then open `http://localhost:8080`.

## Deploy: S3 + CloudFront

The directory deploys as-is — upload the contents of `website/`, not the
folder itself.

```bash
aws s3 sync website/ s3://YOUR-BUCKET/ --delete
```

- S3: enable static website hosting (or serve through CloudFront with an
  origin access control). Set `index.html` as the index document.
- CloudFront: point the distribution at the bucket, default root object
  `index.html`. After updates, invalidate the cache:

```bash
aws cloudfront create-invalidation --distribution-id YOUR_DIST_ID --paths "/*"
```

- Cache headers: everything is fingerprint-free, so keep TTLs short for
  `index.html` and longer for `assets/vendor/**` (those files change only
  when the design system is re-vendored).

## Deploy: GitHub Pages

The site needs no generator — serve the directory verbatim.

1. Push the `website/` directory to the branch/folder GitHub Pages serves
   (e.g. the root of a `gh-pages` branch, or `/docs` on `main` — the folder
   must contain `index.html` at its top level).
2. In the repository settings, set the Pages source to that branch/folder
   with "no Jekyll" (a plain static deploy; add an empty `.nojekyll` file
   if the folder is served from a branch that GitHub would otherwise run
   through Jekyll).

No build job is required; a plain copy of the directory is the deploy
artifact.
