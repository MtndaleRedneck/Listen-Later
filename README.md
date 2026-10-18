# Listen Later

Share a video to your podcast app. Listen Later is a tiny Android companion
for a self-hosted [PigeonPod](https://github.com/aizhimou/pigeon-pod) server: it sends the video to the server's
built-in Individual Videos feed, the server downloads it, and your podcast
app picks it up like any episode.

## How it works

1. Sign in once with your server address and login. When the session
   expires, the app asks you to sign in again.
2. Share a video to Listen Later from any app. One tap, nothing opens, a
   toast confirms it was sent.
3. The video appears in your Individual Videos podcast feed. Subscribe to
   that feed once in your podcast app (the app has a Copy button for the
   feed link) and new shares show up by themselves.

The app never changes feed settings. Quality, retention, and everything
else stay wherever you set them on your server's website. Sharing the same
video twice gets you an "already in your feed" toast, not a second download.

## Install

Grab the APK from the latest release and install it. Android 12 or newer.

## Setup

- A self-hosted [PigeonPod](https://github.com/aizhimou/pigeon-pod) server you can sign in to.
- Your server address (https only), username, and password. If your server
  asks for a captcha at login, the app shows the same picture.
- Any podcast app that can subscribe to an RSS feed.

## Privacy

Your server address, login token, and feed link are stored only in the
Android Keystore-backed encrypted preferences on your phone. Your password
is never stored. Nothing is sent anywhere but your own server.

Inspired by the [Listenbox](https://listenbox.app) Listen Later app (RIP).
