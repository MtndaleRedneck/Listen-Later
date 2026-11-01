<p align="center"><img src="docs/readme-icon.png" alt="Listen Later icon" width="96"></p>

# Listen Later

Share a video to your podcast app. Listen Later is an Android companion
for a self-hosted [PigeonPod](https://github.com/aizhimou/pigeon-pod) server:
you share a video to it, the server's built-in Individual Videos feed picks
it up, and your podcast app plays it like any episode.

Inspired by the original Listenbox app, which shut down in 2025. Not affiliated with Listenbox, PigeonPod or YouTube.

## How it works

1. Sign in once with your server address and login.
2. Share a video to Listen Later from any app. One tap, nothing opens, a
   toast confirms it was sent.
3. The video shows up in your Individual Videos feed. The app's main screen
   has a Copy feed link button for subscribing to that feed in your podcast
   app.

The app never changes feed settings; quality, retention, and the rest stay
where you set them on your server's website.

## Install

Grab the APK from the [latest release](../../releases/latest) and install
it. You need a self-hosted PigeonPod server you can sign in to, and any
podcast app that can subscribe to an RSS feed.

Setup, privacy (including what the update check sends), and screenshots are
in [docs/details.md](docs/details.md).

## Feed cover art
Square 3000x3000 cover for your podcast feed: [Download podcast-cover-3000.png](https://github.com/MtndaleRedneck/Listen-Later/releases/latest/download/podcast-cover-3000.png)
1. Download the PNG.
2. Open your podcast feed's settings on your server or podcast host.
3. Upload it as the cover art. 3000x3000 meets Apple and Spotify specs.