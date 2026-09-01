# Discord Components V2 design

Boocord Music 5 renders every message through Discord Components V2. Traditional
message content and embeds are intentionally not mixed into the new interface.

## Platform rules reflected in the implementation

- Every message request enables `IS_COMPONENTS_V2` (`1 << 15`). Discord does not
  allow this flag to be removed after it has been set on a message.
- Components V2 messages use Text Displays instead of message content and embeds.
- Purple Containers provide the shared brand surface and accent color (`#9B59FF`).
- Sections associate song metadata with its cover Thumbnail.
- Separators establish hierarchy without relying on empty text or embed fields.
- Action Rows remain the interactive layer for playback and pagination controls.
- Layouts stay below Discord's limit of 40 total components and 4,000 text
  characters per message.
- Deferred interaction responses are converted through the original-response edit,
  which is the supported path for Components V2 after a defer.

## Interface system

| Surface | Components V2 layout |
| --- | --- |
| Now playing | Purple Container, title, cover Section, live status, two control rows |
| Lists | Purple Container, context, divider, numbered items, page status |
| Lyrics | Purple Container, track context, monospaced lyric body, pagination |
| Statistics | Purple Container with a prominent listening-time value |
| Feedback | Purple Container with contextual icon, title, body and brand footer |
| Ended player | Persistent inactive Container instead of an empty/invalid message |

## Primary references

- [Discord: Components Overview](https://docs.discord.com/developers/components/overview)
- [Discord: Using Message Components](https://docs.discord.com/developers/components/using-message-components)
- [Discord: Component Reference](https://docs.discord.com/developers/components/reference)
- [Discord: Interaction Responses](https://docs.discord.com/developers/interactions/receiving-and-responding)
- [JDA: TextDisplay API](https://docs.jda.wiki/net/dv8tion/jda/api/components/textdisplay/TextDisplay.html)
