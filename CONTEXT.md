# NetworkStorage

NetworkStorage connects registered Minecraft containers into named storage networks. These terms describe the scanning and terminal behavior shared by gameplay code and tests.

## Language

**Network**:
A named collection of storage chests, sender chests, and terminals with an owner and access rules. Trust grants usage access; management remains restricted to the owner or an admin.

**Network scan**:
An incremental traversal of registered storage chests that produces an item snapshot and diagnostics. Scans run on the primary server thread with a chunk budget; content changes invalidate snapshots and restart unfinished scans before publication.

**Authoritative snapshot**:
A complete scan result accounting for every registered storage chest. An incomplete scan reports diagnostics and may retain the last complete snapshot; ordinary terminal renders do not automatically retry it.

**Terminal session**:
One player's terminal interaction, including inventory view, chat search, and stats navigation. These transitions preserve pending final wireless use; closing the session, search timeout, quitting, or plugin cleanup finishes that use once.

**Final wireless use**:
The last allowed opening of a wireless terminal when break-on-zero is enabled. The first click warns, the second opens the final session, and the item breaks when that session ends, including after search or stats navigation.

**Terminal item selection**:
The filtered, ordered page of network items shown in a terminal. Search matches custom names, English material names, and enchantments; sorting preserves the input order for ties and pagination clamps to a valid page.
