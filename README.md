# CustomTNT

Paper/Spigot plugin for a custom TNT item that keeps vanilla TNT mechanics.

## Commands

- `/customtnt give [player] [amount]`
- `/customtnt reload`

## Permissions

- `customtnt.give` - allows giving custom TNT with `/customtnt give`.
- `customtnt.create` - allows placing custom TNT blocks.
- `customtnt.craft` - allows crafting custom TNT recipes.
- `customtnt.admin` - allows opening and editing TNT settings menus.
- `customtnt.reload` - allows reloading the plugin config.

## ProtectionStones compatibility

The plugin does not manually break blocks. It marks placed custom TNT, lets the
server prime a normal `TNTPrimed` entity, and uses normal explosion events. This
allows ProtectionStones and WorldGuard to cancel or filter explosions like they
do for regular TNT.
