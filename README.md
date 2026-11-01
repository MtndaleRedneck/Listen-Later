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

## Individual Videos Feed cover art
This is the cover picture for the PigeonPod feed that Listen Later sends your videos to. PigeonPod calls that feed "Individual Videos" unless you renamed it.

[Download the cover art](https://mtndaleredneck.github.io/Listen-Later/podcast-cover-3000.png)

1. Download the cover art from the link above.
2. Open PigeonPod in your browser and sign in.
3. On the home page, click the Individual Videos feed (or the name you gave it).
4. At the top, click the pencil icon ("Edit title and cover") next to the feed's name. A box called "Customize Feed Info" opens.
5. Under "Custom Cover", click "Upload Image" and choose the picture you saved.
6. Click "Confirm".

Your podcast app shows the new cover the next time it refreshes the feed. How long that takes depends on the app.
