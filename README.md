# NetworkStorage

A Minecraft plugin that allows players to create a centralized storage network with powerful management features.

## Features

*   **Multiple Networks:** Create and manage multiple separate networks per player (configurable)
*   **Centralized Storage:** Store items across multiple chests, access from any terminal
*   **Wireless Terminals:** Access your network remotely with portable wireless terminals
*   **Auto-Transfer Sender Chests:** Items automatically transfer to your network
*   **Trust System:** Share network access with specific players
*   **Protection Integration:** Works with WorldGuard, Towny, GriefPrevention, and Lands
*   **Configurable Network Modes:** Choose between individual player networks or a single, server-wide global network
*   **Performance Optimized:** Smart caching system with configurable resync intervals
*   **Multi-Language Support:** Available in English and German
*   **Statistics Tracking:** Monitor player deposits and withdrawals

## Commands

### Storage Management
*   `/storage wand` - Get the storage wand to build networks
*   `/storage info` - View your current network information
*   `/storage trust <player>` - Grant a player access to your network
*   `/storage untrust <player>` - Remove a player's access to your network
*   `/storage wireless` - Get a wireless network terminal
*   `/storage reset` - Reset your current network
*   `/storage help` - Show help message

### Network Management
*   `/network create <name>` - Create a new network
*   `/network delete <name>` - Delete a network
*   `/network list` - List all your networks
*   `/network switch <name>` - Switch to a different network
*   `/network gui` - Open the network selector GUI
*   `/network rename <old> <new>` - Rename a network
*   `/network help` - Show network commands help

### Admin Commands
*   `/networkstorage reload` - Reload the plugin configuration

## How to Use

### Basic Setup
1.  Use `/storage wand` to get a storage wand
2.  **Left-click** chests with the wand to add them as storage chests
3.  **Shift + Right-click** chests with the wand to make them terminals
4.  **Shift + Left-click** chests with the wand to make them sender chests (auto-transfer items)
5.  Right-click terminals to access your network
6.  Use `/storage wireless` to get a portable terminal

### Managing Multiple Networks
1.  Use `/network create <name>` to create additional networks
2.  Use `/network gui` to see all your networks and switch between them
3.  Each network can have its own chests, terminals, and trusted players
4.  Delete networks you no longer need with `/network delete <name>`

### Storage Types
- **Storage Chests:** Regular storage - items are stored here
- **Terminals:** Access points to view and retrieve items from the network
- **Sender Chests:** Items placed here automatically transfer to the network
- **Wireless Terminals:** Portable devices to access your network from anywhere

## Configuration

### Network Modes (`network-mode`)

*   **`PLAYER` (Default):** Each player can have their own private network(s) (configurable via `max-networks-per-player`)
*   **`GLOBAL`:** One shared network for the entire server. Multi-network and trust commands are disabled in this mode

### Network Limits

Configure maximum limits per network and per player:
- `max-networks-per-player: 1` - Maximum networks each player can create (0 = unlimited, default: 1)
- `max-chests-per-network: 100` - Maximum storage chests per network
- `max-terminals-per-network: 100` - Maximum access terminals per network
- `max-sender-chests-per-network: 100` - Maximum sender chests per network

### Performance Settings

- `sender-chest-transfer-interval-seconds: 5` - How often sender chests transfer items
- `auto-save-interval-minutes: 5` - How often network data is saved to disk
- `cache-resync-interval-minutes: 10` - How often to rebuild the item cache from physical chests

### Permission Settings

- `enable-permissions: true` - Require permissions to use features
- `enable-trust-system: true` - Enable the trust system (PLAYER mode only)
  - **`true` (Default):** Only owners and trusted players can access networks
  - **`false`:** Everyone can access all networks - ideal for cooperative servers

### Protection Integration (`enable-protection-check`)

- **`true` (Default):** Players can only add chests they own or have permission to modify
- **`false`:** No protection checks - players can add any accessible chest
- Supports: WorldGuard, Towny, GriefPrevention, Lands
- Bypass permission: `networkstorage.bypass.protection`

### Wireless Terminals

- `enable-wireless-terminals: true` - Enable/disable wireless terminal feature
- `wireless-terminal-durability: 100` - Number of uses (0 = unlimited)
- Customizable crafting recipe in config.yml

### Language Support

- `language: en` - Set to `en` (English) or `de` (German)
- **Display:** Item names shown in your Minecraft client language
- **Search:** Use English material names (e.g., `diamond`, `stone`) or custom item names

## Permissions

### Basic Permissions
- `networkstorage.use` - Basic usage of storage networks (default: true)
- `networkstorage.wand` - Get and use storage wands (default: true)
- `networkstorage.wireless` - Get and use wireless terminals (default: op)
- `networkstorage.reset` - Reset own storage network (default: true)

### Network Management
- `networkstorage.network.create` - Create new networks (default: true)
- `networkstorage.network.delete` - Delete networks (default: true)
- `networkstorage.network.edit` - Edit networks (default: op)
- `networkstorage.network.rename` - Rename networks (default: op)

### Admin Permissions
- `networkstorage.admin` - All administrative permissions (default: op)
- `networkstorage.access.all` - Access any network (default: op)
- `networkstorage.bypass.protection` - Bypass protection plugin checks (default: op)
- `networkstorage.reload` - Reload plugin configuration (default: op)
- `networkstorage.*` - All permissions (default: op)

## Installation

1. Download NetworkStorage-2.0.0.jar
2. Place in your server's `plugins` folder
3. Restart the server
4. Configure `plugins/NetworkStorage/config.yml` as desired
5. Optional: Install WorldGuard, Towny, GriefPrevention, or Lands for protection integration

## Requirements

- Minecraft Server: Paper 1.21+ (or compatible forks)
- Java: 21+
- Optional: WorldGuard, Towny, GriefPrevention, or Lands for protection features

## Metrics & Analytics

This plugin uses bStats to collect anonymous usage statistics. This helps understand how the plugin is used and guides future development.

**Collected data includes:**
- Server count and player count
- Plugin version distribution
- Network mode (PLAYER/GLOBAL)
- Language preference
- Feature usage (wireless terminals, protection checks)
- Total networks, chests, and terminals

**Privacy:**
- All data is **anonymous** - no IPs, server names, or personal information
- Fully **GDPR compliant**
- Can be **disabled** by server admins in `plugins/bStats/config.yml`
- View collected data: [bStats NetworkStorage Page](https://bstats.org/plugin/bukkit/NetworkStorage)

For more information about bStats, visit: https://bstats.org/

## Support & Issues

Report bugs and request features on our [GitHub Issues](https://github.com/DerMoha/NetworkStorage/issues) page.
