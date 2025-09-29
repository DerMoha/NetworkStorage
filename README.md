# NetworkStorage

A Minecraft plugin that allows players to create a centralized storage network.

## Features

*   Create a network of chests to store your items.
*   Access all your items from a single terminal.
*   Trust other players to access your network.
*   Track player deposits and withdrawals 
*   Wireless access to your network with a wireless terminal.
*   **Configurable Network Modes:** Choose between individual player networks or a single, server-wide global network.
*   **Optional Trust System:** Make networks publicly accessible, perfect for cooperative servers.

## Commands

*   `/storage wand`: Get the storage wand.
*   `/storage info`: View your network information.
*   `/storage trust <player>`: Trust a player to your network.
*   `/storage untrust <player>`: Untrust a player from your network.
*   `/storage wireless`: Get a wireless terminal.

## How to Use

1.  Use `/storage wand` to get a storage wand.
2.  Right-click with the wand to create a new network.
3.  Left-click on chests with the wand to add them to your network.
4.  Place a terminal to access all your items in one place.

---

## Important Notes

### Network Modes (`network-mode`)

In the `config.yml`, you can choose between two modes:

*   **`PLAYER` (Default):** Each player has their own private network. This is the classic behavior.
*   **`GLOBAL`:** There is only one, single, massive network for the entire server. All players share this one network. Commands like `/storage trust` or `/network` are disabled in this mode.

### Trust System (`enable-trust-system`)

This option is only relevant in `PLAYER` mode:

*   **`true` (Default):** Only the owner and players they have added via `/storage trust` can access a network.
*   **`false`:** The trust system is completely disabled. **Every player can access every network.** Ideal for small, private servers where everyone works together.

### Language and Item Search

*   **Display:** The names of items in the terminal are always displayed in the language you have set in your Minecraft client (e.g., German, French, etc.).
*   **Search:** The search function works with the internal, English material names (e.g., `diamond`, `stone`, `iron_ingot`) or with custom names you have given an item using an anvil. This means you must search in English, even if the items are displayed in your own language.
