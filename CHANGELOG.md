# Changelog

## 1.5.0 — 2026-10-16

### Added

- Support for Minecraft Paper 26.3, built against Paper API 26.3 build 184.
- Barrel and copper chest support for storage networks.
- SQLite storage with automatic migration from YAML and legacy SQLite data, schema upgrades, and verified rolling backups.
- Hopper interaction with terminals and comparator output based on network capacity.
- Time-limited player trust, editable network descriptions, and network deletion with confirmation.
- In-game configuration editor and administrative network inspection, rescan, storage diagnostics, and update-check commands.
- PlaceholderAPI integration and a public service API for other plugins.
- French and Spanish translations, plus support for custom language files.
- Item search by enchantment name and level, a configurable default sort order, and saved per-player sort preferences.

### Changed

- Unified commands and help under `/ns`. `/storage`, `/network`, and `/networkstorage`, together with their existing aliases, now use the same command surface.
- Grouped administrative commands under `/ns admin <command>`. Use `/ns admin list` to list all networks and `/ns admin info <network>` for administrative network details; `/ns list` and `/ns info` show player information.
- Replaced immediate bulk deposits with an inventory preview and separate confirm/cancel buttons inside the terminal. Confirmation expires after 15 seconds and must be renewed if eligible inventory contents change.
- Bulk deposits now include only the 27 main inventory slots. Hotbar, armor, offhand, storage wands, and wireless terminals are excluded. Items that do not fit remain in the player's inventory.
- Protected storage wands and wireless terminals from individual player deposits as well as bulk deposits.
- Improved large-network scanning with incremental scans and batched persistence. Terminal and comparator output use complete scan results; incomplete scans report unavailable containers instead of presenting partial totals as authoritative.
- Wireless terminals configured to break at zero uses now remain available through the final terminal session and break when that session ends.

### Fixed

- Fixed startup failing when a database upgrade left a valid historical import schema version in migration metadata.
- Fixed migration digest verification rejecting permanent trusted players with no expiry.
- Fixed imports failing when registered containers reference worlds that are not currently loaded. Registrations are preserved and resolve when their worlds become available.
- Fixed stale player selections blocking migration. References to deleted networks are cleared with warnings while valid selections are preserved.
- Preserved existing networks whose names differ only by capitalization, such as `Main` and `main`. Exact-name lookups distinguish them; ambiguous case-insensitive lookups fail safely. New case-insensitive name collisions remain blocked.
- Fixed newly created networks not becoming the player's active network.
- Fixed wireless terminal interactions accidentally placing items from the other hand and improved handling of cancelled interactions.
- Improved terminal search and stats transitions, scan invalidation, save ordering, and cleanup during reload or shutdown.
- Improved update-check concurrency, version comparison, and lifecycle handling.

### Upgrade notes

- Stop the server and back up the complete `plugins/NetworkStorage` folder before upgrading. Replace the old plugin JAR with `NetworkStorage-1.5.0.jar`, then start the server.
- Existing YAML and legacy SQLite data are migrated automatically. Source backups are retained; no manual SQL changes are required for the migration fixes in this release.
- If migration fails, the plugin stays disabled and preserves recovery data. Inspect the error and any `migration.lock` before retrying; do not delete the lock blindly.
- Keep the plugin's SQLite database and backups on local storage rather than an NFS or SMB share.
- If a wireless terminal was deposited with an earlier build, use a physical terminal or obtain a replacement with `/ns wireless` to retrieve the stored items. Issuing a replacement requires permission.
- Existing tools created before metadata verification was introduced may need to be reissued with `/ns wand` or `/ns wireless`.
- This release targets Paper 26.3. Compatibility with older Minecraft versions has not been verified.

### Validation

- All 153 automated tests pass on Java 25, including migration and upgrade coverage for SQLite schemas 1–4, restart persistence, command routing, protected deposits, and partial/full storage behavior.
- The packaged SQLite driver smoke check passes.

Previous published release notes: [1.4.0](https://github.com/DerMoha/NetworkStorage/releases/tag/1.4.0).
