## What's new in 1.12.0

November 25, 2026. You can now delete a video from the list.

New:
- Press and hold a video in the list to delete it. A confirmation asks first. On a server with the optional full-delete add-on, the video is removed from your feed too. Videos still waiting to send are simply taken off the list.

Improved:
- If the server can't do a full delete, or the check fails, the app says so and leaves the list as it is.

## What's new in 1.11.0

November 25, 2026. Settings now remembers where you were, and a few spacing and color details are cleaned up.

Improved:
- Settings keeps its scroll position when you come back from Feedback, and starts at the top each time you open it fresh.
- A little more space above the app header on the home screen.
- The arrow on "Feedback & bugs" is white and larger.
- Accent and the vibrate switch now share one card titled "Accent color".

## What's new in 1.10.0

November 25, 2026. Buttons now feel like BingeMote's, and Settings has an accent color picker.

New:
- Accent color picker in Settings with eight colors. Your pick changes the buttons, highlights and the status dot across the app.
- Every button sinks slightly when pressed and gives a small tick. A new "Vibrate on press" switch in Settings turns the tick off.

Improved:
- The server address in Settings is a clean rounded button, without the square highlight.
- Less empty space under the header on the home and Settings screens, and under the last card in Settings.
- Status labels in the feed list are always right-aligned, so the "Feed list" card and its switch are gone.
- Feedback screen: the back arrow matches BingeMote's, the extra note under "What will be sent" is gone, and the display details (font scale, density, screen size) are included to help explain layout problems.
- Red and the text-field outline now use the same shades as BingeMote.

## What's new in 1.9.0

November 24, 2026. A visual pass on the home and Settings screens to match BingeMote, plus clearer status labels.

Improved:
- Screen colors now follow the same surface shades as BingeMote.
- Feed rows have thin dividers and a fixed status column. "Get again" and "Retry" are now tappable amber and red pills, and "In feed" stays plain green.
- When the server can't be reached the top pill now shows a red dot and "Disconnected".
- In Settings, only the server address is tappable, shown as a pill. The "Server" label and the rest of the row are no longer links.
- Settings has a new switch to center the status labels in the feed list instead of right-aligning them.
- The Feedback entry matches BingeMote: the extra line under it is gone and the arrow is larger.
- The notification icon now fills its space and no longer sits off to the right: the same microphone and clock, centered and slightly larger, with less empty space on its left.
- "You're up to date." now disappears after 5 seconds.

## What's new in 1.8.0

November 24, 2026. A fresh look for the home and Settings screens, and a new notification icon drawn from the app's real logo.

New:
- New notification icon: the Listen Later microphone with a small clock, drawn from the app's real logo.

Improved:
- Refreshed home screen: the connection status sits in a pill in the top bar, list cards have a soft gradient, and rows show full video titles and error details instead of cutting them off.
- Sign out, copy feed link, and the server link moved from the home screen into Settings: the server link is tappable and Copy feed link is a prominent button.
- Clearer wording when the feed link isn't available yet.

## What's new in 1.7.0

November 24, 2026. An under-the-hood update: the app is rebuilt on current Android foundations and is now 87% smaller, with everything you use unchanged.

Improved:
- Much smaller app: the download drops from 22 MB to about 3 MB by stripping code and resources the app never uses.
- Updated everything the app is built on to current versions.

Fixed:
- Corrected six spots in the docs that no longer matched what the app does (share messages, playlist sharing, skipped-share cleanup, update checks, feedback preview).

## What's new in 1.6.4

November 24, 2026. Share-send reliability fixes.

Fixed:
- Rotating the phone or reopening the app no longer interrupts a video that was still being sent to your server. The send keeps going. ([2c2e952](https://github.com/MtndaleRedneck/Listen-Later/commit/2c2e95221b1be15a2e170947233aecb71f3a7c72))
- A cancelled send can no longer be marked as handled by a late answer from the server. ([2c2e952](https://github.com/MtndaleRedneck/Listen-Later/commit/2c2e95221b1be15a2e170947233aecb71f3a7c72))

## What's new in 1.6.3

- Security: the app no longer follows redirects to a different server, so your sign-in and session can never be forwarded anywhere but your own server.

## What's new in 1.6.2

- Updated the app icon and logo with the new microphone design.

## What's new in 1.6.1

- Fixed a crash when sharing a video to Listen Later. Videos shared in 1.6.0 weren't saved, so share them again.
- The server address on the home screen is tappable and opens your server in the browser.

## What's new in 1.6.0

- The list keeps refreshing itself while anything is downloading or waiting, not just for the first 10 seconds, and it updates when you come back to the app.
- If your server is down a while, shares stay queued and keep trying, and they send on their own once it's back. One stuck video no longer blocks the rest.
- Sharing when the server can't be reached shows a short message so you know it will keep trying.
- Re-sharing an older video is recognized properly instead of a wrong "Already in your feed", and a brief server hiccup no longer marks a video "Couldn't get it".
- Waiting shares show the video's title and what they're doing: "Waiting for sign-in", "Retrying at 3:40", or "Sending".
- "Couldn't get it" items keep their Try again and clear themselves after about a day.
- Popups name the video: "Already downloading" for one still on its way, and "Sent" with the title when it lands.
- Enter in the password box signs you in, and the app starts faster.
