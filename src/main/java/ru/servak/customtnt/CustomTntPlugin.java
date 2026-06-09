package ru.servak.customtnt;

import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ExplosionPrimeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CustomTntPlugin extends JavaPlugin implements Listener, TabExecutor {
    private static final int MENU_SIZE = 54;
    private static final int BLOCKS_PER_PAGE = 45;
    private static final String DEFAULT_TNT_ID = "basic";

    private NamespacedKey itemKey;
    private NamespacedKey entityKey;
    private File dataFile;
    private YamlConfiguration dataConfig;
    private final Map<String, TntType> tntTypes = new LinkedHashMap<>();
    private final Map<BlockKey, String> customBlocks = new HashMap<>();
    private final Map<BlockKey, String> pendingPrimes = new HashMap<>();
    private final Map<UUID, ChatEditSession> chatEdits = new HashMap<>();
    private final Set<NamespacedKey> recipeKeys = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        itemKey = new NamespacedKey(this, "custom_tnt_type");
        entityKey = new NamespacedKey(this, "custom_tnt_entity_type");
        dataFile = new File(getDataFolder(), "data.yml");
        loadTypes();
        loadData();
        registerRecipes();

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("customtnt") != null) {
            getCommand("customtnt").setExecutor(this);
            getCommand("customtnt").setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        clearRecipes();
        saveData();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onBlockPlacePermission(BlockPlaceEvent event) {
        String typeId = getItemType(event.getItemInHand());
        if (event.getBlockPlaced().getType() != Material.TNT || typeId == null) {
            return;
        }

        if (!event.getPlayer().hasPermission("customtnt.create")) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(color("&cУ тебя нет права ставить кастомный динамит."));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        String typeId = getItemType(event.getItemInHand());
        if (event.getBlockPlaced().getType() != Material.TNT || typeId == null || !tntTypes.containsKey(typeId)) {
            return;
        }

        customBlocks.put(BlockKey.from(event.getBlockPlaced()), typeId);
        saveData();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        BlockKey key = BlockKey.from(event.getBlock());
        String typeId = customBlocks.remove(key);
        if (typeId == null) {
            return;
        }

        saveData();
        if (event.getPlayer().getGameMode() == GameMode.CREATIVE) {
            return;
        }

        event.setDropItems(false);
        event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation(), createCustomTnt(typeId, 1));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onTntPrime(TNTPrimeEvent event) {
        BlockKey key = BlockKey.from(event.getBlock());
        String typeId = customBlocks.remove(key);
        if (typeId == null) {
            return;
        }

        pendingPrimes.put(key, typeId);
        saveData();
        getServer().getScheduler().runTaskLater(this, () -> pendingPrimes.remove(key), 2L);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onEntitySpawn(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof TNTPrimed tnt)) {
            return;
        }

        BlockKey key = BlockKey.from(event.getLocation());
        String typeId = pendingPrimes.remove(key);
        TntType type = tntTypes.get(typeId);
        if (type == null) {
            return;
        }

        markCustomTnt(tnt, type.id());
        tnt.setFuseTicks(type.fuseTicks());
        tnt.setYield((float) type.power());
        tnt.setIsIncendiary(type.setFire());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.NORMAL)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        TntType type = getEntityType(event.getEntity());
        if (type == null) {
            return;
        }

        event.setRadius((float) type.power());
        event.setFire(type.setFire());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.LOWEST)
    public void onCustomTntExplode(EntityExplodeEvent event) {
        TntType type = getEntityType(event.getEntity());
        if (type == null) {
            return;
        }

        if (!type.explodeInWater() && isInWater(event.getLocation())) {
            return;
        }

        event.blockList().clear();
        event.blockList().addAll(findForcedExplosionBlocks(event.getLocation(), type));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        boolean changed = false;
        for (Block block : event.blockList()) {
            if (block.getType() == Material.TNT && customBlocks.remove(BlockKey.from(block)) != null) {
                changed = true;
            }
        }

        if (changed) {
            saveData();
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof RecipeMenuHolder holder) {
            handleRecipeClick(event, holder);
            return;
        }

        if (event.getInventory().getHolder() instanceof EditorMenuHolder holder) {
            handleEditorClick(event, holder);
            return;
        }

        if (!(event.getInventory().getHolder() instanceof BlockMenuHolder holder)) {
            return;
        }

        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        TntType type = tntTypes.get(holder.typeId);
        if (type == null) {
            player.closeInventory();
            player.sendMessage(color("&cЭтот тип динамита больше не найден в конфиге."));
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= MENU_SIZE) {
            return;
        }

        if (rawSlot == 45) {
            openBlockMenu(player, type.id(), Math.max(0, holder.page - 1));
            return;
        }
        if (rawSlot == 49) {
            type.breakableBlocks().clear();
            type.breakableBlocks().addAll(menuMaterials());
            saveBreakableBlocks(type);
            player.sendMessage(color("&aДинамит &f" + type.id() + " &aтеперь ломает все блоки, кроме бедрока."));
            openBlockMenu(player, type.id(), holder.page);
            return;
        }
        if (rawSlot == 53) {
            openBlockMenu(player, type.id(), holder.page + 1);
            return;
        }
        if (rawSlot >= BLOCKS_PER_PAGE) {
            return;
        }

        List<Material> blocks = menuMaterials();
        int index = holder.page * BLOCKS_PER_PAGE + rawSlot;
        if (index >= blocks.size()) {
            return;
        }

        Material material = blocks.get(index);
        if (type.breakableBlocks().remove(material)) {
            player.sendMessage(color("&cУбран блок &f" + material.name() + " &cдля динамита &f" + type.id() + "&c."));
        } else {
            type.breakableBlocks().add(material);
            player.sendMessage(color("&aДобавлен блок &f" + material.name() + " &aдля динамита &f" + type.id() + "&a."));
        }

        saveBreakableBlocks(type);
        openBlockMenu(player, type.id(), holder.page);
    }

    private void handleEditorClick(InventoryClickEvent event, EditorMenuHolder holder) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        TntType type = tntTypes.get(holder.typeId);
        if (type == null) {
            player.closeInventory();
            player.sendMessage(color("&cЭтот тип динамита больше не найден."));
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= 27) {
            return;
        }

        switch (rawSlot) {
            case 10 -> startChatEdit(player, type.id(), ChatEdit.NAME);
            case 11 -> startChatEdit(player, type.id(), ChatEdit.LORE);
            case 12 -> changePower(player, type, event.isRightClick() ? -0.5D : 0.5D);
            case 13 -> changeFuse(player, type, event.isRightClick() ? -20 : 20);
            case 14 -> toggleFire(player, type);
            case 15 -> toggleRecipe(player, type);
            case 16 -> openBlockMenu(player, type.id(), 0);
            case 17 -> toggleExplodeInWater(player, type);
            case 21 -> openRecipeMenu(player, type.id());
            case 22 -> {
                player.getInventory().addItem(createCustomTnt(type.id(), 1)).values()
                        .forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
                player.sendMessage(color("&aВыдан тестовый динамит &f" + type.id() + "&a."));
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof RecipeMenuHolder holder)) {
            return;
        }
        saveRecipeFromMenu(holder.typeId, event.getInventory());
        if (event.getPlayer() instanceof Player player) {
            player.sendMessage(color("&aРецепт динамита &f" + holder.typeId + " &aсохранен."));
        }
    }

    @EventHandler
    public void onChatEdit(AsyncPlayerChatEvent event) {
        ChatEditSession session = chatEdits.get(event.getPlayer().getUniqueId());
        if (session == null) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();
        getServer().getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            if (message.equalsIgnoreCase("cancel")) {
                chatEdits.remove(player.getUniqueId());
                player.sendMessage(color("&cРедактирование отменено."));
                openEditorMenu(player, session.typeId());
                return;
            }

            if (session.edit() == ChatEdit.NAME) {
                if (message.equalsIgnoreCase("done")) {
                    String name = session.buffer().toString();
                    if (name.isBlank()) {
                        player.sendMessage(color("&cНазвание пустое. Напиши часть названия или &ccancel&c."));
                        return;
                    }
                    chatEdits.remove(player.getUniqueId());
                    getConfig().set("tnts." + session.typeId() + ".name", name);
                } else {
                    session.buffer().append(message);
                    player.sendMessage(color("&aЧасть добавлена. Длина: &f" + session.buffer().length() + "&a. Напиши еще часть, &fdone&a для сохранения или &ccancel&a."));
                    return;
                }
                player.sendMessage(color("&aНазвание обновлено."));
            } else {
                chatEdits.remove(player.getUniqueId());
                List<String> lore = new ArrayList<>();
                for (String line : message.split("\\|")) {
                    lore.add(line.trim());
                }
                getConfig().set("tnts." + session.typeId() + ".lore", lore);
                player.sendMessage(color("&aОписание обновлено."));
            }

            saveConfig();
            reloadConfig();
            loadTypes();
            registerRecipes();
            openEditorMenu(player, session.typeId());
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (event.getRecipe() == null || event.getRecipe().getResult() == null || getItemType(event.getRecipe().getResult()) == null) {
            return;
        }

        if (!(event.getView().getPlayer() instanceof Player player) || player.hasPermission("customtnt.craft")) {
            return;
        }

        event.getInventory().setResult(null);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&e/customtnt list &7- список типов динамита"));
            sender.sendMessage(color("&e/customtnt create <id> &7- создать новый тип в конфиге"));
            sender.sendMessage(color("&e/customtnt give <id> [игрок] [кол-во] &7- выдать динамит"));
            sender.sendMessage(color("&e/customtnt edit <id> &7- редактировать TNT через меню"));
            sender.sendMessage(color("&e/customtnt menu <id> &7- настроить блоки для типа"));
            sender.sendMessage(color("&e/customtnt reload &7- перезагрузить конфиг и рецепты"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("customtnt.reload")) {
                sender.sendMessage(color("&cНет прав."));
                return true;
            }

            reloadConfig();
            loadTypes();
            registerRecipes();
            sender.sendMessage(color("&aCustomTNT: конфиг и рецепты перезагружены."));
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(color("&eТипы кастомного динамита:"));
            for (TntType type : tntTypes.values()) {
                sender.sendMessage(color("&7- &f" + type.id() + " &7| сила: &f" + type.power() + " &7| рецепт: " + (type.recipeEnabled() ? "&aвкл" : "&cвыкл")));
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("create")) {
            if (!sender.hasPermission("customtnt.admin")) {
                sender.sendMessage(color("&cНет прав."));
                return true;
            }
            if (args.length < 2 || !args[1].matches("[a-z0-9_\\-]+")) {
                sender.sendMessage(color("&cИспользование: /customtnt create <id>"));
                sender.sendMessage(color("&7ID: только латинские буквы, цифры, _ и -."));
                return true;
            }

            String id = args[1].toLowerCase(Locale.ROOT);
            if (tntTypes.containsKey(id)) {
                sender.sendMessage(color("&cТип &f" + id + " &cуже существует."));
                return true;
            }

            createTypeInConfig(id);
            reloadConfig();
            loadTypes();
            registerRecipes();
            sender.sendMessage(color("&aСоздан тип &f" + id + "&a. Настрой его в plugins/CustomTNT/config.yml и сделай /customtnt reload."));
            return true;
        }

        if (args[0].equalsIgnoreCase("edit")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Только игрок может открыть это меню.");
                return true;
            }
            if (!sender.hasPermission("customtnt.admin")) {
                sender.sendMessage(color("&cНет прав."));
                return true;
            }

            String typeId = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : DEFAULT_TNT_ID;
            if (!tntTypes.containsKey(typeId)) {
                sender.sendMessage(color("&cТип динамита &f" + typeId + " &cне найден."));
                return true;
            }

            openEditorMenu(player, typeId);
            return true;
        }

        if (args[0].equalsIgnoreCase("menu")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Только игрок может открыть это меню.");
                return true;
            }
            if (!sender.hasPermission("customtnt.admin")) {
                sender.sendMessage(color("&cНет прав."));
                return true;
            }

            String typeId = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : DEFAULT_TNT_ID;
            if (!tntTypes.containsKey(typeId)) {
                sender.sendMessage(color("&cТип динамита &f" + typeId + " &cне найден."));
                return true;
            }

            openBlockMenu(player, typeId, 0);
            return true;
        }

        if (!args[0].equalsIgnoreCase("give")) {
            sender.sendMessage(color("&cНеизвестная команда."));
            return true;
        }

        if (!sender.hasPermission("customtnt.give")) {
            sender.sendMessage(color("&cНет прав."));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(color("&cИспользование: /customtnt give <id> [игрок] [кол-во]"));
            return true;
        }

        String typeId = args[1].toLowerCase(Locale.ROOT);
        if (!tntTypes.containsKey(typeId)) {
            sender.sendMessage(color("&cТип динамита &f" + typeId + " &cне найден."));
            return true;
        }

        Player target;
        int amount = 1;
        if (args.length >= 3) {
            target = getServer().getPlayerExact(args[2]);
            if (target == null) {
                sender.sendMessage(color("&cИгрок не найден."));
                return true;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            sender.sendMessage(color("&cИспользование: /customtnt give <id> <игрок> [кол-во]"));
            return true;
        }

        if (args.length >= 4) {
            try {
                amount = Math.max(1, Math.min(64, Integer.parseInt(args[3])));
            } catch (NumberFormatException ignored) {
                sender.sendMessage(color("&cКоличество должно быть числом от 1 до 64."));
                return true;
            }
        }

        target.getInventory().addItem(createCustomTnt(typeId, amount)).values()
                .forEach(item -> target.getWorld().dropItemNaturally(target.getLocation(), item));
        sender.sendMessage(color("&aВыдан динамит &f" + typeId + " &aигроку &f" + target.getName() + " &ax" + amount + "."));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return filter(List.of("give", "edit", "menu", "create", "list", "reload", "help"), args[0]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("menu"))) {
            return filter(new ArrayList<>(tntTypes.keySet()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> names = getServer().getOnlinePlayers().stream().map(Player::getName).toList();
            return filter(names, args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            return filter(List.of("1", "8", "16", "32", "64"), args[3]);
        }
        return Collections.emptyList();
    }

    private void openEditorMenu(Player player, String typeId) {
        TntType type = tntTypes.get(typeId);
        if (type == null) {
            return;
        }

        EditorMenuHolder holder = new EditorMenuHolder(typeId);
        Inventory inventory = getServer().createInventory(holder, 27, color("&8Редактор TNT: " + typeId));
        holder.inventory = inventory;

        inventory.setItem(4, createCustomTnt(type.id(), 1));
        inventory.setItem(10, namedItem(Material.NAME_TAG, "&eНазвание", List.of(
                "&7Сейчас: " + type.name(),
                "&7Клик: ввести новое название в чат.",
                "&7Можно использовать &-цвета."
        )));
        inventory.setItem(11, namedItem(Material.WRITABLE_BOOK, "&eОписание", List.of(
                "&7Клик: ввести описание в чат.",
                "&7Разделяй строки символом |",
                "&7Пример: &fСтрока 1 | Строка 2"
        )));
        inventory.setItem(12, namedItem(Material.GUNPOWDER, "&cСила взрыва", List.of(
                "&7Сейчас: &f" + type.power(),
                "&aЛКМ: +0.5",
                "&cПКМ: -0.5"
        )));
        inventory.setItem(13, namedItem(Material.CLOCK, "&eФитиль", List.of(
                "&7Сейчас: &f" + type.fuseTicks() + " тиков",
                "&aЛКМ: +20",
                "&cПКМ: -20"
        )));
        inventory.setItem(14, namedItem(type.setFire() ? Material.FLINT_AND_STEEL : Material.CAMPFIRE, "&6Огонь", List.of(
                "&7Сейчас: " + (type.setFire() ? "&aвключен" : "&cвыключен"),
                "&7Клик: переключить."
        )));
        inventory.setItem(15, namedItem(type.recipeEnabled() ? Material.CRAFTING_TABLE : Material.BARRIER, "&bРецепт", List.of(
                "&7Сейчас: " + (type.recipeEnabled() ? "&aвключен" : "&cвыключен"),
                "&7Клик: переключить."
        )));
        inventory.setItem(16, namedItem(Material.CHEST, "&aБлоки для взрыва", List.of(
                "&7Открыть меню блоков.",
                "&7Выбрано: &f" + type.breakableBlocks().size()
        )));
        inventory.setItem(22, namedItem(Material.TNT, "&dВыдать тестовый TNT", List.of(
                "&7Выдает 1 штуку этого типа."
        )));

        inventory.setItem(17, namedItem(type.explodeInWater() ? Material.WATER_BUCKET : Material.BUCKET, "&bВзрыв в воде", List.of(
                "&7Сейчас: " + (type.explodeInWater() ? "&aвключен" : "&cвыключен"),
                "&7Клик: переключить."
        )));

        inventory.setItem(21, namedItem(Material.CRAFTING_TABLE, "&bРедактор рецепта", List.of(
                "&7Открыть сетку 3x3.",
                "&7Можно класть обычные предметы",
                "&7и кастомные TNT."
        )));
        player.openInventory(inventory);
    }

    private void startChatEdit(Player player, String typeId, ChatEdit edit) {
        chatEdits.put(player.getUniqueId(), new ChatEditSession(typeId, edit, new StringBuilder()));
        player.closeInventory();
        if (edit == ChatEdit.NAME) {
            player.sendMessage(color("&eНапиши новое название TNT в чат. &7Напиши &ccancel&7, чтобы отменить."));
        } else {
            player.sendMessage(color("&eНапиши новое описание TNT в чат. &7Строки разделяй символом &f|&7. Напиши &ccancel&7, чтобы отменить."));
        }
    }

    private void changePower(Player player, TntType type, double delta) {
        double value = Math.max(0.0D, Math.round((type.power() + delta) * 10.0D) / 10.0D);
        getConfig().set("tnts." + type.id() + ".power", value);
        saveAndReloadType(player, type.id(), "&aСила взрыва: &f" + value);
    }

    private void changeFuse(Player player, TntType type, int delta) {
        int value = Math.max(0, type.fuseTicks() + delta);
        getConfig().set("tnts." + type.id() + ".fuse-ticks", value);
        saveAndReloadType(player, type.id(), "&aФитиль: &f" + value + " тиков");
    }

    private void toggleFire(Player player, TntType type) {
        boolean value = !type.setFire();
        getConfig().set("tnts." + type.id() + ".set-fire", value);
        saveAndReloadType(player, type.id(), value ? "&aОгонь включен." : "&cОгонь выключен.");
    }

    private void toggleRecipe(Player player, TntType type) {
        boolean value = !type.recipeEnabled();
        getConfig().set("tnts." + type.id() + ".recipe.enabled", value);
        saveAndReloadType(player, type.id(), value ? "&aРецепт включен." : "&cРецепт выключен.");
    }

    private void toggleExplodeInWater(Player player, TntType type) {
        boolean value = !type.explodeInWater();
        getConfig().set("tnts." + type.id() + ".explode-in-water", value);
        saveAndReloadType(player, type.id(), value ? "&aВзрыв в воде включен." : "&cВзрыв в воде выключен.");
    }

    private void handleRecipeClick(InventoryClickEvent event, RecipeMenuHolder holder) {
        if (event.isShiftClick()) {
            event.setCancelled(true);
            return;
        }

        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < event.getInventory().getSize() && !isRecipeInputSlot(rawSlot)) {
            event.setCancelled(true);
            if (rawSlot == 24 && event.getWhoClicked() instanceof Player player) {
                saveRecipeFromMenu(holder.typeId, event.getInventory());
                player.closeInventory();
                getServer().getScheduler().runTask(this, () -> openEditorMenu(player, holder.typeId));
            }
        }
    }

    @EventHandler
    public void onRecipeDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof RecipeMenuHolder)) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot >= 0 && slot < event.getInventory().getSize() && !isRecipeInputSlot(slot)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void openRecipeMenu(Player player, String typeId) {
        TntType type = tntTypes.get(typeId);
        if (type == null) {
            return;
        }

        RecipeMenuHolder holder = new RecipeMenuHolder(typeId);
        Inventory inventory = getServer().createInventory(holder, 45, color("&8Рецепт TNT: " + typeId));
        holder.inventory = inventory;

        int[] slots = recipeSlots();
        char letter = 'A';
        Map<Character, RecipeIngredient> ingredients = type.recipeIngredients();
        for (int i = 0; i < slots.length; i++) {
            RecipeIngredient ingredient = ingredients.get((char) (letter + i));
            if (ingredient != null) {
                inventory.setItem(slots[i], ingredient.toItem(this));
            }
        }

        inventory.setItem(24, namedItem(Material.EMERALD_BLOCK, "&aСохранить рецепт", List.of(
                "&7Также сохраняется при закрытии меню."
        )));
        inventory.setItem(25, createCustomTnt(type.id(), 1));
        inventory.setItem(40, namedItem(Material.PAPER, "&eКак пользоваться", List.of(
                "&7Положи предметы в сетку 3x3.",
                "&7Можно использовать кастомные TNT.",
                "&7Пустые слоты разрешены."
        )));
        player.openInventory(inventory);
    }

    private void saveRecipeFromMenu(String typeId, Inventory inventory) {
        String path = "tnts." + typeId + ".recipe.";
        int[] slots = recipeSlots();
        List<String> shape = List.of("ABC", "DEF", "GHI");
        getConfig().set(path + "shape", shape);
        getConfig().set(path + "ingredients", null);

        char letter = 'A';
        for (int i = 0; i < slots.length; i++) {
            ItemStack item = inventory.getItem(slots[i]);
            String encoded = encodeRecipeItem(item);
            if (encoded != null) {
                getConfig().set(path + "ingredients." + (char) (letter + i), encoded);
            }
        }

        saveConfig();
        reloadConfig();
        loadTypes();
        registerRecipes();
    }

    private int[] recipeSlots() {
        return new int[]{10, 11, 12, 19, 20, 21, 28, 29, 30};
    }

    private boolean isRecipeInputSlot(int slot) {
        for (int recipeSlot : recipeSlots()) {
            if (slot == recipeSlot) {
                return true;
            }
        }
        return false;
    }

    private String encodeRecipeItem(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return null;
        }
        String customType = getItemType(item);
        if (customType != null) {
            return "CUSTOM_TNT:" + customType;
        }
        return "MATERIAL:" + item.getType().name();
    }

    private RecipeIngredient parseRecipeIngredient(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.startsWith("CUSTOM_TNT:")) {
            String typeId = raw.substring("CUSTOM_TNT:".length()).toLowerCase(Locale.ROOT);
            return RecipeIngredient.customTnt(typeId);
        }
        String materialName = raw.startsWith("MATERIAL:") ? raw.substring("MATERIAL:".length()) : raw;
        Material material = Material.matchMaterial(materialName);
        return material == null ? null : RecipeIngredient.material(material);
    }

    private void saveAndReloadType(Player player, String typeId, String message) {
        saveConfig();
        reloadConfig();
        loadTypes();
        registerRecipes();
        player.sendMessage(color(message));
        openEditorMenu(player, typeId);
    }

    private boolean isInWater(Location location) {
        Material material = location.getBlock().getType();
        return material == Material.WATER || material == Material.KELP || material == Material.KELP_PLANT
                || material == Material.SEAGRASS || material == Material.TALL_SEAGRASS;
    }

    private List<Block> findForcedExplosionBlocks(Location center, TntType type) {
        World world = center.getWorld();
        if (world == null || type.power() <= 0.0D) {
            return Collections.emptyList();
        }

        List<Block> blocks = new ArrayList<>();
        int radius = (int) Math.ceil(type.power());
        double radiusSquared = type.power() * type.power();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;

        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int y = Math.max(minY, centerY - radius); y <= Math.min(maxY, centerY + radius); y++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    double dx = x + 0.5D - center.getX();
                    double dy = y + 0.5D - center.getY();
                    double dz = z + 0.5D - center.getZ();
                    if ((dx * dx) + (dy * dy) + (dz * dz) > radiusSquared) {
                        continue;
                    }

                    Block block = world.getBlockAt(x, y, z);
                    if (type.canBreak(block.getType())) {
                        blocks.add(block);
                    }
                }
            }
        }
        return blocks;
    }

    private void openBlockMenu(Player player, String typeId, int requestedPage) {
        TntType type = tntTypes.get(typeId);
        if (type == null) {
            return;
        }

        List<Material> blocks = menuMaterials();
        int maxPage = Math.max(0, (blocks.size() - 1) / BLOCKS_PER_PAGE);
        int page = Math.max(0, Math.min(requestedPage, maxPage));
        BlockMenuHolder holder = new BlockMenuHolder(typeId, page);
        Inventory inventory = getServer().createInventory(holder, MENU_SIZE, color("&8" + typeId + " блоки " + (page + 1) + "/" + (maxPage + 1)));
        holder.inventory = inventory;

        int start = page * BLOCKS_PER_PAGE;
        for (int slot = 0; slot < BLOCKS_PER_PAGE; slot++) {
            int index = start + slot;
            if (index >= blocks.size()) {
                break;
            }

            Material material = blocks.get(index);
            inventory.setItem(slot, menuBlockItem(type, material));
        }

        inventory.setItem(45, namedItem(Material.ARROW, "&eПредыдущая страница", List.of("&7Нажми, чтобы вернуться назад.")));
        inventory.setItem(49, namedItem(Material.EMERALD_BLOCK, "&aДобавить все кроме бедрока", List.of("&7Добавляет все блоки", "&7кроме бедрока.")));
        inventory.setItem(53, namedItem(Material.ARROW, "&eСледующая страница", List.of("&7Нажми, чтобы продолжить.")));
        player.openInventory(inventory);
    }

    private ItemStack menuBlockItem(TntType type, Material material) {
        boolean enabled = type.breakableBlocks().contains(material);
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color((enabled ? "&a" : "&c") + material.name()));
            meta.setLore(List.of(
                    color(enabled ? "&aВключено" : "&cВыключено"),
                    color("&7Нажми, чтобы переключить.")
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack namedItem(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(name));
            meta.setLore(lore.stream().map(this::color).toList());
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createCustomTnt(String typeId, int amount) {
        TntType type = tntTypes.getOrDefault(typeId, tntTypes.get(DEFAULT_TNT_ID));
        if (type == null) {
            type = fallbackType(typeId);
        }

        ItemStack item = new ItemStack(Material.TNT, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(color(type.name()));
            List<String> lore = new ArrayList<>();
            for (String line : type.lore()) {
                lore.add(color(line));
            }
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, type.id());
            if (type.customModelData() > 0) {
                meta.setCustomModelData(type.customModelData());
            }
            item.setItemMeta(meta);
        }
        return item;
    }

    private String getItemType(ItemStack item) {
        if (item == null || item.getType() != Material.TNT || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private void markCustomTnt(TNTPrimed tnt, String typeId) {
        PersistentDataContainer container = tnt.getPersistentDataContainer();
        container.set(entityKey, PersistentDataType.STRING, typeId);
    }

    private TntType getEntityType(Entity entity) {
        String typeId = entity.getPersistentDataContainer().get(entityKey, PersistentDataType.STRING);
        return typeId == null ? null : tntTypes.get(typeId);
    }

    private void loadTypes() {
        tntTypes.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("tnts");
        if (section == null) {
            tntTypes.put(DEFAULT_TNT_ID, fallbackType(DEFAULT_TNT_ID));
            return;
        }

        for (String id : section.getKeys(false)) {
            String path = "tnts." + id + ".";
            Set<Material> breakable = new HashSet<>();
            for (String raw : getConfig().getStringList(path + "breakable-blocks")) {
                Material material = Material.matchMaterial(raw);
                if (material != null && isAllowedMenuBlock(material)) {
                    breakable.add(material);
                }
            }

            String normalizedId = id.toLowerCase(Locale.ROOT);
            tntTypes.put(normalizedId, new TntType(
                    normalizedId,
                    getConfig().getString(path + "name", "&cКастомный динамит"),
                    getConfig().getStringList(path + "lore"),
                    getConfig().getInt(path + "custom-model-data", 0),
                    getConfig().getInt(path + "fuse-ticks", 80),
                    getConfig().getDouble(path + "power", 4.0D),
                    getConfig().getBoolean(path + "set-fire", false),
                    getConfig().getBoolean(path + "explode-in-water", false),
                    breakable,
                    getConfig().getBoolean(path + "recipe.enabled", true),
                    getConfig().getStringList(path + "recipe.shape"),
                    getRecipeIngredients(path + "recipe.ingredients")
            ));
        }

        if (tntTypes.isEmpty()) {
            tntTypes.put(DEFAULT_TNT_ID, fallbackType(DEFAULT_TNT_ID));
        }
    }

    private Map<Character, RecipeIngredient> getRecipeIngredients(String path) {
        Map<Character, RecipeIngredient> ingredients = new HashMap<>();
        ConfigurationSection section = getConfig().getConfigurationSection(path);
        if (section == null) {
            ingredients.put('G', RecipeIngredient.material(Material.GUNPOWDER));
            ingredients.put('R', RecipeIngredient.material(Material.REDSTONE));
            ingredients.put('T', RecipeIngredient.material(Material.TNT));
            return ingredients;
        }

        for (String key : section.getKeys(false)) {
            if (key.length() != 1) {
                continue;
            }
            RecipeIngredient ingredient = parseRecipeIngredient(section.getString(key, ""));
            if (ingredient != null) {
                ingredients.put(key.charAt(0), ingredient);
            }
        }
        return ingredients;
    }

    private void registerRecipes() {
        clearRecipes();
        for (TntType type : tntTypes.values()) {
            if (!type.recipeEnabled()) {
                continue;
            }

            NamespacedKey key = new NamespacedKey(this, "custom_tnt_" + type.id().replace('-', '_'));
            ShapedRecipe recipe = new ShapedRecipe(key, createCustomTnt(type.id(), 1));
            List<String> shape = type.recipeShape().isEmpty() ? List.of("GRG", "RTR", "GRG") : type.recipeShape();
            shape = normalizeRecipeShape(shape, type.recipeIngredients());
            if (shape.stream().allMatch(String::isBlank)) {
                continue;
            }
            recipe.shape(shape.toArray(String[]::new));
            for (Map.Entry<Character, RecipeIngredient> entry : type.recipeIngredients().entrySet()) {
                RecipeChoice choice = entry.getValue().toChoice(this);
                if (choice != null) {
                    recipe.setIngredient(entry.getKey(), choice);
                }
            }
            getServer().addRecipe(recipe);
            recipeKeys.add(key);
        }
    }

    private List<String> normalizeRecipeShape(List<String> shape, Map<Character, RecipeIngredient> ingredients) {
        List<String> normalized = new ArrayList<>();
        for (String row : shape) {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < row.length(); i++) {
                char key = row.charAt(i);
                builder.append(key == ' ' || ingredients.containsKey(key) ? key : ' ');
            }
            normalized.add(builder.toString());
        }
        return normalized;
    }

    private void clearRecipes() {
        for (NamespacedKey key : recipeKeys) {
            getServer().removeRecipe(key);
        }
        recipeKeys.clear();
    }

    private void saveBreakableBlocks(TntType type) {
        List<String> blocks = type.breakableBlocks().stream()
                .map(Material::name)
                .sorted()
                .toList();
        getConfig().set("tnts." + type.id() + ".breakable-blocks", blocks);
        saveConfig();
    }

    private void createTypeInConfig(String id) {
        String path = "tnts." + id + ".";
        getConfig().set(path + "name", "&cДинамит " + id);
        getConfig().set(path + "lore", List.of("&7Описание можно изменить в config.yml."));
        getConfig().set(path + "custom-model-data", 0);
        getConfig().set(path + "fuse-ticks", 80);
        getConfig().set(path + "power", 4.0D);
        getConfig().set(path + "set-fire", false);
        getConfig().set(path + "explode-in-water", false);
        getConfig().set(path + "breakable-blocks", new ArrayList<String>());
        getConfig().set(path + "recipe.enabled", true);
        getConfig().set(path + "recipe.shape", List.of("GRG", "RTR", "GRG"));
        getConfig().set(path + "recipe.ingredients.G", "GUNPOWDER");
        getConfig().set(path + "recipe.ingredients.R", "REDSTONE");
        getConfig().set(path + "recipe.ingredients.T", "TNT");
        saveConfig();
    }

    private List<Material> menuMaterials() {
        return List.of(Material.values()).stream()
                .filter(this::isAllowedMenuBlock)
                .sorted((first, second) -> first.name().compareTo(second.name()))
                .toList();
    }

    private boolean isAllowedMenuBlock(Material material) {
        return material.isBlock() && material.isItem() && material != Material.AIR && material != Material.CAVE_AIR
                && material != Material.VOID_AIR && material != Material.BEDROCK;
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text == null ? "" : text);
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private void loadData() {
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        customBlocks.clear();

        ConfigurationSection section = dataConfig.getConfigurationSection("blocks");
        if (section == null) {
            for (String raw : dataConfig.getStringList("blocks")) {
                BlockKey key = BlockKey.parse(raw);
                if (key != null) {
                    customBlocks.put(key, DEFAULT_TNT_ID);
                }
            }
            return;
        }

        for (String rawKey : section.getKeys(false)) {
            BlockKey key = BlockKey.parse(rawKey);
            String typeId = section.getString(rawKey, DEFAULT_TNT_ID);
            if (key != null && typeId != null && tntTypes.containsKey(typeId)) {
                customBlocks.put(key, typeId);
            }
        }
    }

    private void saveData() {
        if (dataConfig == null) {
            return;
        }

        dataConfig.set("blocks", null);
        for (Map.Entry<BlockKey, String> entry : customBlocks.entrySet()) {
            dataConfig.set("blocks." + entry.getKey().serialize(), entry.getValue());
        }
        try {
            dataConfig.save(dataFile);
        } catch (IOException exception) {
            getLogger().warning("Не удалось сохранить data.yml: " + exception.getMessage());
        }
    }

    private TntType fallbackType(String id) {
        return new TntType(
                id,
                "&cКастомный динамит",
                List.of("&7Настрой этот тип в config.yml."),
                0,
                80,
                4.0D,
                false,
                false,
                new HashSet<>(),
                true,
                List.of("GRG", "RTR", "GRG"),
                Map.of('G', RecipeIngredient.material(Material.GUNPOWDER), 'R', RecipeIngredient.material(Material.REDSTONE), 'T', RecipeIngredient.material(Material.TNT))
        );
    }

    private static final class BlockMenuHolder implements InventoryHolder {
        private final String typeId;
        private final int page;
        private Inventory inventory;

        private BlockMenuHolder(String typeId, int page) {
            this.typeId = typeId;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class EditorMenuHolder implements InventoryHolder {
        private final String typeId;
        private Inventory inventory;

        private EditorMenuHolder(String typeId) {
            this.typeId = typeId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class RecipeMenuHolder implements InventoryHolder {
        private final String typeId;
        private Inventory inventory;

        private RecipeMenuHolder(String typeId) {
            this.typeId = typeId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private enum ChatEdit {
        NAME,
        LORE
    }

    private record ChatEditSession(String typeId, ChatEdit edit, StringBuilder buffer) {
    }

    private record TntType(
            String id,
            String name,
            List<String> lore,
            int customModelData,
            int fuseTicks,
            double power,
            boolean setFire,
            boolean explodeInWater,
            Set<Material> breakableBlocks,
            boolean recipeEnabled,
            List<String> recipeShape,
            Map<Character, RecipeIngredient> recipeIngredients
    ) {
        boolean canBreak(Material material) {
            return material != Material.BEDROCK && breakableBlocks.contains(material);
        }
    }

    private record RecipeIngredient(Material material, String customTntType) {
        static RecipeIngredient material(Material material) {
            return new RecipeIngredient(material, null);
        }

        static RecipeIngredient customTnt(String typeId) {
            return new RecipeIngredient(Material.TNT, typeId);
        }

        ItemStack toItem(CustomTntPlugin plugin) {
            if (customTntType != null) {
                return plugin.createCustomTnt(customTntType, 1);
            }
            return new ItemStack(material);
        }

        RecipeChoice toChoice(CustomTntPlugin plugin) {
            if (customTntType != null) {
                return new RecipeChoice.ExactChoice(plugin.createCustomTnt(customTntType, 1));
            }
            return new RecipeChoice.MaterialChoice(material);
        }
    }

    private record BlockKey(String world, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
        }

        static BlockKey from(Location location) {
            World world = location.getWorld();
            String worldName = world == null ? "" : world.getName();
            return new BlockKey(worldName, location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }

        String serialize() {
            return world + "," + x + "," + y + "," + z;
        }

        static BlockKey parse(String raw) {
            String[] parts = raw.split(",", 4);
            if (parts.length != 4) {
                return null;
            }
            try {
                return new BlockKey(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}
