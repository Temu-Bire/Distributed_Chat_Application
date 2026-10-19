# Vendored frontend dependency

`stomp.umd.min.js` is the UMD build of **@stomp/stompjs**, the STOMP-over-WebSocket
client used by `../chat.js`.

| | |
|---|---|
| Library | `@stomp/stompjs` |
| Version | 7.1.1 |
| Source | https://unpkg.com/@stomp/stompjs@7.1.1/bundles/stomp.umd.min.js |
| Licence | Apache-2.0 |

It is committed here on purpose: the MVP must run locally with no CDN, no npm and
no build step. To upgrade, download a newer bundle from the same path pattern and
update this file and the version above.
