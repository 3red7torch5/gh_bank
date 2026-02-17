package gh.creditcard;

import org.bukkit.*;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class CreditCardPlugin extends JavaPlugin implements TabCompleter {
    private final NamespacedKey cardIdKey = new NamespacedKey(this, "card_id");
    private FileConfiguration config;
    private Material currencyItem;
    private Material cardMaterial;
    private String cardName;
    private List<String> cardLoreTemplate;
    private int cooldownHours;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private int cardsPerTick;
    private BukkitRunnable autoSaveTask;
    private BukkitScheduler scheduler;

    private CardManager cardManager;
    private DatabaseManager databaseManager;

    @Override
    public void onEnable() {
        getLogger().info("Credit Card Plugin Enabled!");
        saveDefaultConfig();
        config = getConfig();
        loadCurrencySettings();

        cardManager = new CardManager(this);
        databaseManager = new DatabaseManager(this);

        databaseManager.loadCardsDatabase(cardManager);
        databaseManager.loadCooldownsDatabase(cooldowns);
        databaseManager.loadSkinsDatabase();
        setupAutoSave();

        getCommand("карта").setExecutor(this);
        getCommand("карта").setTabCompleter(this);
        getLogger().info("Plugin loaded with " + cardManager.getAllCards().size() + " cards in database");

        scheduler = this.getServer().getScheduler();
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @EventHandler
            public void onPlayerInteract(org.bukkit.event.player.PlayerInteractEvent event) {
                ItemStack item = event.getItem();
                String cardId = getIdFromItem(item);
                if (
                        !event.getAction().name().contains("RIGHT_CLICK") ||
                                item == null || item.getType() == org.bukkit.Material.AIR ||
                                cardId == null ||
                                !cardManager.exists(cardId)
                ) {
                    return;
                }
                ItemStack offhandItem = event.getPlayer().getInventory().getItemInOffHand();
                if (offhandItem != null && offhandItem.getType() == org.bukkit.Material.SHEARS && event.getPlayer().isSneaking()) {
                    if (config.getBoolean("destroy-balance-check") && cardManager.getCard(cardId).getBalance() != 0) {
                        return;
                    }
                    destroyCardInHand(event.getPlayer());
                    event.getPlayer().getLocation().getWorld().playSound(event.getPlayer().getLocation(), Sound.ENTITY_WITHER_SHOOT, SoundCategory.UI, 1.0f, 2f);
                    event.getPlayer().sendMessage(colorize("&7Карта &b" + String.valueOf(cardId) + " &c&lаннулированна"));
                } else {
                    updateCardItem(item, cardId);
                    event.getPlayer().sendActionBar(colorize("&7Баланс: &b" + String.valueOf( // Пробелы для читаемости
                            String.valueOf(cardManager.getCard(cardId).getBalance()).replaceAll("(\\d)(?=(\\d{3})+$)", "$1 ")) + " АЛМ"));
                }
                //event.setCancelled(true);
            }
        }, this);
    }

    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        databaseManager.saveCardsDatabase(cardManager);
        databaseManager.saveCooldownsDatabase(cooldowns);
        databaseManager.saveSkinsDatabase();
        getLogger().info("Credit Card Plugin Disabled!");
    }

    private void loadCurrencySettings() {
        String currencyItemName = config.getString("currency.item", "DIAMOND");
        String cardMaterialName = config.getString("currency.card.material", "WOODEN_SHOVEL");
        try {
            currencyItem = Material.valueOf(currencyItemName.toUpperCase());
            cardMaterial = Material.valueOf(cardMaterialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            getLogger().warning("Конфиг хуйня, карты теперь деревянные лопаты");
            currencyItem = Material.DIAMOND;
            cardMaterial = Material.WOODEN_SHOVEL;
        }
        cardName = colorize(config.getString("currency.card.name", "&6&lCredit Card"));
        cardLoreTemplate = config.getStringList("currency.card.lore");
        cooldownHours = config.getInt("cooldown.hours", 24);
        cardsPerTick = config.getInt("card-creation.cards-per-tick", 20);
    }

    private void setupAutoSave() {
        int autoSaveInterval = config.getInt("database.auto-save", 300);
        if (autoSaveInterval > 0) {
            autoSaveTask = new BukkitRunnable() {
                @Override
                public void run() {
                    databaseManager.saveCardsDatabase(cardManager);
                    getLogger().info("Saved cards database");
                    databaseManager.saveSkinsDatabase();
                    getLogger().info("Saved skins database");
                }
            };
            autoSaveTask.runTaskTimer(this, autoSaveInterval * 20L, autoSaveInterval * 20L);
            getLogger().info("Auto-save enabled every " + autoSaveInterval + " seconds");
        }
    }

    private String getMessage(String key) {
        String prefix = config.getString("messages.prefix", "[Card] ");
        String message = config.getString("messages." + key, "&cMessage not found: " + key);
        return colorize(prefix + message);
    }

    private String getMessage(String key, Map<String, String> replacements) {
        String message = getMessage(key);
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            message = message.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return message;
    }

    public String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    private boolean hasCooldown(Player player) {
        if (player.hasPermission(config.getString("cooldown.bypass-permission", "creditcard.cooldown.bypass"))) {
            return false;
        }
        UUID playerUuid = player.getUniqueId();
        Long cooldownEnd = cooldowns.get(playerUuid);
        if (cooldownEnd == null) {
            return false;
        }
        long currentTime = System.currentTimeMillis();
        return currentTime < cooldownEnd;
    }

    private String getCooldownRemaining(Player player) {
        UUID playerUuid = player.getUniqueId();
        Long cooldownEnd = cooldowns.get(playerUuid);
        if (cooldownEnd == null) {
            return "0ч 0м";
        }
        long remaining = cooldownEnd - System.currentTimeMillis();
        if (remaining <= 0) {
            return "0ч 0м";
        }
        long hours = remaining / (1000 * 60 * 60);
        long minutes = (remaining % (1000 * 60 * 60)) / (1000 * 60);
        long seconds = (remaining % (1000 * 60)) / 1000;
        return hours + "ч " + minutes + "м " + seconds + "с";
    }

    public List<String> getAvailableSkins(Player player) {
        String path = "skins." + player.getUniqueId().toString();
        if (databaseManager.getSkinsConfig().contains(path + ".skins")) {
            return databaseManager.getSkinsConfig().getStringList(path + ".skins");
        }
        return new ArrayList<>();
    }

    public boolean addSkin(Player player, String skinID) {
        try {
            Integer.parseInt(skinID);
        } catch (NumberFormatException e) {
            return false;
        }
        ConfigurationSection skinNamesSection = config.getConfigurationSection("skin-names");
        if (skinNamesSection == null || !skinNamesSection.contains(skinID)) {
            return false;
        }
        String playerPath = "skins." + player.getUniqueId().toString();
        if (!databaseManager.getSkinsConfig().contains(playerPath)) {
            databaseManager.getSkinsConfig().set(playerPath + ".name", player.getName());
            databaseManager.getSkinsConfig().set(playerPath + ".skins", new ArrayList<String>());
        }
        List<String> currentSkins = databaseManager.getSkinsConfig().getStringList(playerPath + ".skins");
        if (currentSkins.contains(skinID)) {
            return false;
        }
        currentSkins.add(skinID);
        databaseManager.getSkinsConfig().set(playerPath + ".skins", currentSkins);
        if (!databaseManager.getSkinsConfig().getString(playerPath + ".name", "").equals(player.getName())) {
            databaseManager.getSkinsConfig().set(playerPath + ".name", player.getName());
        }
        databaseManager.saveSkinsDatabase();
        return true;
    }

    public boolean removeSkin(Player player, String skinID) {
        String playerPath = "skins." + player.getUniqueId().toString();
        if (!databaseManager.getSkinsConfig().contains(playerPath)) {
            return false;
        }
        List<String> currentSkins = databaseManager.getSkinsConfig().getStringList(playerPath + ".skins");
        if (!currentSkins.contains(skinID)) {
            return false;
        }
        currentSkins.remove(skinID);
        databaseManager.getSkinsConfig().set(playerPath + ".skins", currentSkins);
        if (currentSkins.isEmpty()) {
            databaseManager.getSkinsConfig().set(playerPath, null);
        }
        databaseManager.saveSkinsDatabase();
        return true;
    }

    private void setCooldown(Player player) {
        long cooldownEnd = System.currentTimeMillis() + (cooldownHours * 60 * 60 * 1000L);
        cooldowns.put(player.getUniqueId(), cooldownEnd);
        databaseManager.saveCooldownsDatabase(cooldowns);
    }

    private String createCard(Player player) {
        String cardId = cardManager.createCard(player);
        setCooldown(player);
        databaseManager.saveCardsDatabase(cardManager);
        getLogger().info("Created new card " + cardId + " for " + player.getName());
        return cardId;
    }

    private ItemStack makeCard(String cardId) {
        return cardManager.makeCard(cardId, cardMaterial, cardName, cardLoreTemplate, currencyItem);
    }

    private String getIdFromItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        ItemMeta meta = item.getItemMeta();
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.get(cardIdKey, PersistentDataType.STRING);
    }

    private int countPlayerCurrency(Player player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == currencyItem) {
                if (getIdFromItem(item) == null) {
                    count += item.getAmount();
                }
            }
        }
        return count;
    }

    public int countPlayerAir(Player player) {
        int emptySlots = 0;
        ItemStack[] contents = player.getInventory().getContents();
        for (int i = 0; i < 36; i++) {
            if (contents[i] == null || contents[i].getType() == Material.AIR) {
                emptySlots++;
            }
        }
        return emptySlots;
    }

    private boolean takePlayerCurrency(Player player, int amount) {
        if (countPlayerCurrency(player) < amount) return false;
        int remaining = amount;
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == currencyItem) {
                if (getIdFromItem(item) != null) continue;
                int itemamount = item.getAmount();
                if (itemamount <= remaining) {
                    player.getInventory().setItem(i, null);
                    remaining -= itemamount;
                } else {
                    item.setAmount(itemamount - remaining);
                    remaining = 0;
                }
                if (remaining <= 0) break;
            }
        }
        return true;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только игроки могут использовать эту команду");
            return true;
        }
        if (args.length == 0) {
            showHelp(player);
            return true;
        }
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "создать":
                return commandCreate(player, args);
            case "баланс":
                return commandBalance(player);
            case "пополнить":
                return commandDeposit(player, args);
            case "снять":
                return commandWithdraw(player, args);
            case "reload":
                return commandReload(player);
            case "подделать":
                return commandForge(player, args);
            case "сфабриковать":
                return commandFabricate(player, args);
            case "перевести":
                return commandTransfer(player, args);
            case "скин":
                return commandSkins(player, args);
            default:
                showHelp(player);
                return true;
        }
    }

    private void showHelp(Player player) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("currency_item", getCurrencyItemName());
        player.sendMessage(getMessage("help-header", placeholders));
        player.sendMessage(getMessage("help-create", placeholders));
        player.sendMessage(getMessage("help-balance", placeholders));
        player.sendMessage(getMessage("help-deposit", placeholders));
        player.sendMessage(getMessage("help-withdraw", placeholders));
        player.sendMessage(getMessage("help-transfer", placeholders));
        if (player.hasPermission("creditcard.reload")) {
            player.sendMessage(colorize("&e/card reload &7- Reload configuration"));
        }
        if (player.hasPermission("creditcard.forge")) {
            player.sendMessage(getMessage("help-forge", placeholders));
        }
        if (player.hasPermission("creditcard.fabricate")) {
            player.sendMessage(getMessage("help-fabricate", placeholders));
        }
    }

    private boolean commandCreate(Player player, String[] args) {
        if (hasCooldown(player) && !player.hasPermission("creditcard.cooldownbypass")) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("cooldown", getCooldownRemaining(player));
            player.sendMessage(getMessage("cooldown-active", placeholders));
            return true;
        }

        String cardId = createCard(player);
        ItemStack card = makeCard(cardId);

        if (card != null) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(card);
            if (!leftover.isEmpty()) {
                player.getWorld().dropItem(player.getLocation(), card);
                player.sendMessage(getMessage("inventory-full"));
            }
        }
        setCooldown(player);
        databaseManager.saveCardsDatabase(cardManager);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("card_id", cardId);
        player.sendMessage(getMessage("card-created",placeholders));
        return true;
    }

    private boolean commandFabricate(Player player, String[] args) {
        if (!player.hasPermission("creditcard.fabricate")) {
            player.sendMessage(getMessage("no-permission"));
            return true;
        }
        if (args.length < 2) {
            player.sendMessage(getMessage("help-fabricate"));
            return true;
        }

        String customId = args[1].toUpperCase().replace("-", "");
        if (!customId.matches("\\d+")) {
            player.sendMessage(getMessage("numbers-only"));
            return true;
        }
        if (customId.length() != 8) {
            player.sendMessage(getMessage("must-be-eight"));
            return true;
        }
        customId = customId.substring(0, 4) + "-" + customId.substring(4, Math.min(customId.length(), 8));
        if (cardManager.getUsedCardIds().contains(customId) || cardManager.exists(customId)) {
            Map<String, String> placeholders = new HashMap<>();
            player.sendMessage(getMessage("already-exists"));
            return true;
        }
        CardData cardData = new CardData(player, cardManager.generateRandomColor());
        cardManager.addCard(customId, cardData);
        ItemStack card = makeCard(customId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        if (player.getInventory().addItem(card).isEmpty()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("card_id", customId);
            player.sendMessage(getMessage("card-created", placeholders));
        } else {
            player.getWorld().dropItem(player.getLocation(), card);
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("card_id", customId);
            player.sendMessage(getMessage("card-created", placeholders));
            player.sendMessage(getMessage("inventory-full"));
        }
        databaseManager.saveCardsDatabase(cardManager);
        getLogger().info("Fabricated new card " + customId + " for " + player.getName());
        return true;
    }

    private boolean commandForge(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(getMessage("usage-forge"));
        }
        if (args[1].isEmpty()) {
            player.sendMessage(getMessage("usage-forge"));
            return true;
        }
        String cardId = args[1];
        ItemStack card = makeCard(cardId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        if (player.getInventory().addItem(card).isEmpty()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("card_id", cardId);
            player.sendMessage(getMessage("card-created", placeholders));
        } else {
            player.getWorld().dropItem(player.getLocation(), card);
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("card_id", cardId);
            player.sendMessage(getMessage("card-created", placeholders));
            player.sendMessage(getMessage("inventory-full"));
        }
        return true;
    }

    private boolean commandTransfer(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(getMessage("usage-transfer"));
            return true;
        }
        String targetCardId = args[1].toUpperCase().replace("-", "");
        if (!targetCardId.matches("\\d+")) {
            player.sendMessage(getMessage("numbers-only"));
            return true;
        }
        if (targetCardId.length() != 8) {
            player.sendMessage(getMessage("must-be-eight"));
            return true;
        }
        targetCardId = targetCardId.substring(0, 4) + "-" + targetCardId.substring(4, 8);
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
            if (amount <= 0) {
                player.sendMessage(getMessage("invalid-amount"));
                return true;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(getMessage("invalid-amount"));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String senderCardId = getIdFromItem(hand);
        if (senderCardId == null) {
            player.sendMessage(getMessage("no-card-in-hand"));
            return true;
        }
        CardData senderCard = cardManager.getCard(senderCardId);
        if (senderCard == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        CardData targetCard = cardManager.getCard(targetCardId);
        if (targetCard == null) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("card_id", targetCardId);
            player.sendMessage(getMessage("target-card-not-found", placeholders));
            return true;
        }
        if (senderCardId.equals(targetCardId)) {
            player.sendMessage(getMessage("cannot-transfer-to-self"));
            return true;
        }
        if (senderCard.getBalance() < amount) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("balance", String.valueOf(senderCard.getBalance()));
            player.sendMessage(getMessage("insufficient-funds", placeholders));
            return true;
        }
        senderCard.removeBalance(amount);
        targetCard.addBalance(amount);
        senderCard.updateLastUsed();
        targetCard.updateLastUsed();
        updateCardItem(hand, senderCardId);
        player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0f, 2f);

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", String.valueOf(amount));
        placeholders.put("target_card", targetCardId);
        placeholders.put("target_owner", targetCard.ownerName);
        placeholders.put("balance", String.valueOf(senderCard.getBalance()));
        placeholders.put("currency_item", getCurrencyItemName());
        player.sendMessage(getMessage("transfer-success", placeholders));

        Player targetPlayer = Bukkit.getPlayer(UUID.fromString(targetCard.ownerUuid));
        if (targetPlayer != null && targetPlayer.isOnline()) {
            Map<String, String> targetPlaceholders = new HashMap<>();
            targetPlaceholders.put("amount", String.valueOf(amount));
            targetPlaceholders.put("sender_card", senderCardId);
            targetPlaceholders.put("sender_name", senderCard.ownerName);
            targetPlaceholders.put("target_card", targetCardId);
            targetPlaceholders.put("balance", String.valueOf(targetCard.getBalance()));
            targetPlaceholders.put("currency_item", getCurrencyItemName());
            targetPlayer.sendMessage(getMessage("transfer-received", targetPlaceholders));
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 0.1f);
        }
        databaseManager.saveCardsDatabase(cardManager);
        getLogger().info("Transfer " + amount + " from card " + senderCardId + " to card " + targetCardId);
        return true;
    }

    private boolean commandBalance(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        String cardId = getIdFromItem(hand);
        if (cardId == null) {
            player.sendMessage(getMessage("no-card-in-hand"));
            return true;
        }
        CardData card = cardManager.getCard(cardId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        card.updateLastUsed();
        updateCardItem(hand, cardId);
        player.playSound(player.getLocation(), Sound.UI_LOOM_SELECT_PATTERN, 1.0f, 2f);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("balance", String.valueOf( // Пробелы для читаемости
                String.valueOf(card.getBalance()).replaceAll("(\\d)(?=(\\d{3})+$)", "$1 ")));
        placeholders.put("currency_item", getCurrencyItemName());
        player.sendMessage(getMessage("balance-show", placeholders));
        return true;
    }

    private boolean commandDeposit(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(getMessage("usage-deposit"));
            return true;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
            if (amount <= 0) {
                player.sendMessage(getMessage("invalid-amount"));
                return true;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(getMessage("invalid-amount"));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String cardId = getIdFromItem(hand);
        if (cardId == null) {
            player.sendMessage(getMessage("no-card-in-hand"));
            return true;
        }
        CardData card = cardManager.getCard(cardId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        int playerCurrency = countPlayerCurrency(player);
        if (playerCurrency < amount && !player.getGameMode().equals(GameMode.CREATIVE)) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("needed", String.valueOf(amount - playerCurrency));
            placeholders.put("currency_item", getCurrencyItemName());
            player.sendMessage(getMessage("not-enough-items", placeholders));
            return true;
        }
        if (!takePlayerCurrency(player, amount) && !player.getGameMode().equals(GameMode.CREATIVE)) {
            player.sendMessage(getMessage("not-enough-items"));
            return true;
        }
        card.addBalance(amount);
        card.updateLastUsed();
        updateCardItem(hand, cardId);
        player.playSound(player.getLocation(), Sound.ITEM_BOTTLE_FILL_DRAGONBREATH, 1.0f, 1.5f);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", String.valueOf(amount));
        placeholders.put("balance", String.valueOf(card.getBalance()));
        placeholders.put("currency_item", getCurrencyItemName());
        player.sendMessage(getMessage("deposit-success", placeholders));
        databaseManager.saveCardsDatabase(cardManager);
        return true;
    }

    private boolean commandWithdraw(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(getMessage("usage-withdraw"));
            return true;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
            if (amount <= 0) {
                player.sendMessage(getMessage("invalid-amount"));
                return true;
            }
        } catch (NumberFormatException e) {
            player.sendMessage(getMessage("invalid-amount"));
            return true;
        }
        ItemStack hand = player.getInventory().getItemInMainHand();
        String cardId = getIdFromItem(hand);
        if (cardId == null) {
            player.sendMessage(getMessage("no-card-in-hand"));
            return true;
        }
        CardData card = cardManager.getCard(cardId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        if (card.getBalance() < amount) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("balance", String.valueOf(card.getBalance()));
            player.sendMessage(getMessage("insufficient-funds", placeholders));
            return true;
        }
        if (!card.removeBalance(amount)) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("balance", String.valueOf(card.getBalance()));
            player.sendMessage(getMessage("insufficient-funds", placeholders));
            return true;
        }
        card.updateLastUsed();
        ItemStack currency = new ItemStack(currencyItem, amount);
        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(currency);
        if (!leftover.isEmpty()) {
            for (ItemStack leftoverItem : leftover.values()) {
                player.getWorld().dropItem(player.getLocation(), leftoverItem);
            }
        }
        updateCardItem(hand, cardId);
        player.playSound(player.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.0f, 2f);
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("amount", String.valueOf(amount));
        placeholders.put("balance", String.valueOf(card.getBalance()));
        placeholders.put("currency_item", getCurrencyItemName());
        player.sendMessage(getMessage("withdraw-success", placeholders));
        if (!leftover.isEmpty()) {
            player.sendMessage(getMessage("inventory-full"));
        }
        databaseManager.saveCardsDatabase(cardManager);
        return true;
    }

    private boolean commandReload(Player player) {
        if (!player.hasPermission("creditcard.reload")) {
            player.sendMessage(getMessage("no-permission"));
            return true;
        }
        reloadConfig();
        config = getConfig();
        loadCurrencySettings();
        player.sendMessage(colorize("&aConfiguration reloaded!"));
        getLogger().info("Configuration reloaded by " + player.getName());
        return true;
    }

    private boolean commandSkins(Player player, String[] args) {
        if (args.length < 2) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("skins", String.join(", ", getAvailableSkins(player)));
            player.sendMessage(getMessage("skins-show", placeholders));
            return true;
        }

        String subAction = args[1].toLowerCase();

        if (subAction.equals("список")) {
            Player target = player;
            if (args.length >= 3) {
                if (!player.hasPermission("creditcard.skinmanagement")) {
                    player.sendMessage(getMessage("no-permission"));
                    return true;
                }
                target = Bukkit.getPlayer(args[2]);
                if (target == null) {
                    player.sendMessage(colorize("&cИгрок не найден!"));
                    return true;
                }
            }

            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("skins", String.join(", ", getAvailableSkins(target)));
            player.sendMessage(getMessage("skins-show", placeholders));
            return true;
        }

        if (subAction.equals("прогнать")) {
            return commandRunSkinPreview(player);
        }
        if (subAction.equals("дисплей")) {
            return commandDisplay(player);
        }

        if (args.length < 3) {
            player.sendMessage(getMessage("usage-skins"));
            return true;
        }

        String skinId = args[2];

        if (!subAction.equals("поставить")) {
            try {
                Integer.parseInt(skinId);
            } catch (NumberFormatException e) {
                player.sendMessage(getMessage("invalid-skinid"));
                return true;
            }
        }

        if (subAction.equals("добавить")) {
            if (!player.hasPermission("creditcard.skinmanagement")) {
                player.sendMessage(getMessage("no-permission"));
                return true;
            }

            Player target = player;
            if (args.length >= 4) {
                target = Bukkit.getPlayer(args[3]);
                if (target == null) {
                    player.sendMessage(colorize("&cИгрок не найден!"));
                    return true;
                }
            }

            if (skinId.equals("-1")) {
                ConfigurationSection skinNamesSection = config.getConfigurationSection("skin-names");
                if (skinNamesSection != null) {
                    List<String> currentSkins = getAvailableSkins(target);
                    for (String configSkinId : skinNamesSection.getKeys(false)) {
                        if (!currentSkins.contains(configSkinId)) {
                            addSkin(target, configSkinId);
                        }
                    }
                }
                return true;
            }

            ConfigurationSection skinNamesSection = config.getConfigurationSection("skin-names");
            if (skinNamesSection == null || !skinNamesSection.contains(skinId)) {
                player.sendMessage(colorize("&cСкин с ID " + skinId + " не существует!"));
                return true;
            }

            boolean added = addSkin(target, skinId);
            player.sendMessage(colorize(added ? getMessage("skin-add-success") : getMessage("skin-already-has")));
            return true;
        }

        if (subAction.equals("забрать")) {
            if (!player.hasPermission("creditcard.skinmanagement")) {
                player.sendMessage(getMessage("no-permission"));
                return true;
            }

            Player target = player;
            if (args.length >= 4) {
                target = Bukkit.getPlayer(args[3]);
                if (target == null) {
                    player.sendMessage(colorize("&cИгрок не найден!"));
                    return true;
                }
            }

            boolean removed = removeSkin(target, skinId);
            player.sendMessage(colorize(removed ? getMessage("skin-remove-success") : getMessage("does-not-belong")));
            return true;
        }

        if (subAction.equals("поставить")) {
            ItemStack hand = player.getInventory().getItemInMainHand();
            String cardId = getIdFromItem(hand);
            if (cardId == null) {
                player.sendMessage(getMessage("no-card-in-hand"));
                return true;
            }
            if (config.getBoolean("skin-card-owner-check") && !cardManager.isOwner(player,cardId)) {
                player.sendMessage(getMessage("not-yours"));
                return true;
            }

            try {
                Integer.parseInt(skinId);
            } catch (NumberFormatException e) {
                player.sendMessage(getMessage("invalid-skinid"));
                return true;
            }

            if (!getAvailableSkins(player).contains(skinId)) {
                player.sendMessage(getMessage("does-not-belong"));
                return true;
            }

            ConfigurationSection skinNamesSection = config.getConfigurationSection("skin-names");
            if (skinNamesSection == null || !skinNamesSection.contains(skinId)) {
                player.sendMessage(colorize("&cСкин с ID " + skinId + " не существует!"));
                return true;
            }

            ItemMeta meta = hand.getItemMeta();
            meta.setCustomModelData(Integer.parseInt(skinId));

            String skinName = config.getString("skin-names." + skinId);
            if (skinName != null) {
                meta.setDisplayName(skinName.replace('&', '§'));
                meta.setCustomModelData(Integer.valueOf(skinId));
            }

            hand.setItemMeta(meta);
            player.sendMessage(colorize("&aСкин установлен!"));
            return true;
        }

        player.sendMessage(getMessage("usage-skins"));
        return true;
    }

    private boolean commandDisplay(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        ConfigurationSection skinNamesSection = config.getConfigurationSection("skin-names");
        if (skinNamesSection == null || skinNamesSection.getKeys(false).isEmpty()) {
            player.sendMessage(colorize("&cВ конфигурации не найдено скинов!"));
            return true;
        }
        List<String> allSkins = new ArrayList<>(skinNamesSection.getKeys(false));
        allSkins.sort((s1, s2) -> {
            try {
                return Integer.compare(Integer.parseInt(s1), Integer.parseInt(s2));
            } catch (NumberFormatException e) {
                return s1.compareTo(s2);
            }
        });
        final int totalSkins = allSkins.size();
        for (int i = 0; i < totalSkins; i++) {
            final int skinNumber = i + 1;
            final Integer skinId = Integer.valueOf(allSkins.get(i));
            if (player == null || !player.isOnline()) {
                return true;
            }
            ItemStack currentHand = player.getInventory().getItemInMainHand();
            String currentCardId = getIdFromItem(currentHand);
            try {
                String skinName = config.getString("skin-names." + skinId);
                double angle = (2 * Math.PI / totalSkins) * i;
                Location location = new Location(player.getWorld(),Math.cos(angle)*2,i*0.2,Math.sin(angle)*2);
                spawnSkinPreviewDisplay(skinId,player.getEyeLocation().add(location).setDirection(player.getLocation().subtract(location).toVector()));
            } catch (NumberFormatException e) {
                player.sendMessage(colorize("&cОшибка в формате ID скина: " + skinId));
            }
        }
        return true;
    }

    private void spawnSkinPreviewDisplay(Integer skinId, Location location) {
        try {
            ItemStack displayItem = new ItemStack(Material.LEATHER_HORSE_ARMOR);
            ItemMeta meta = displayItem.getItemMeta();
            if (meta == null) {
                return;
            }
            meta.setCustomModelData(skinId);
            displayItem.setItemMeta(meta);

            location.getWorld().spawn(location, org.bukkit.entity.ItemDisplay.class, display -> {
                display.setItemStack(displayItem);
            });

        } catch (NumberFormatException e) {
            getLogger().warning("Invalid skin ID format: " + skinId);
        }
    }

    private void updateCardItem(ItemStack cardItem, String cardId) {
        if (cardItem == null || !cardItem.hasItemMeta()) return;
        CardData card = cardManager.getCard(cardId);
        if (card == null) return;
        ItemMeta meta = cardItem.getItemMeta();
        if (meta == null) return;
        List<String> lore = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("card_id", cardId);
        placeholders.put("balance", String.valueOf( // Пробелы для читаемости
                String.valueOf(card.getBalance()).replaceAll("(\\d)(?=(\\d{3})+$)", "$1 ")));
        placeholders.put("currency_item", getCurrencyItemName());
        placeholders.put("owner_name", card.ownerName);
        for (String line : cardLoreTemplate) {
            String processedLine = line;
            for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                processedLine = processedLine.replace("%" + entry.getKey() + "%", entry.getValue());
            }
            lore.add(colorize(processedLine));
        }
        meta.setLore(lore);
        cardItem.setItemMeta(meta);
    }

    private boolean destroyCardInHand(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        String cardId = getIdFromItem(hand);
        if (cardId == null) {
            player.sendMessage(getMessage("no-card-in-hand"));
            return false;
        }
        CardData card = cardManager.getCard(cardId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return false;
        }
        cardManager.removeCard(cardId);
        ItemMeta meta = hand.getItemMeta();
        if (meta != null) {
            List<String> lore = meta.getLore();
            if (lore == null) {
                lore = new ArrayList<>();
            }
            lore.add(0, colorize("&c&lАннулированная"));
            meta.setLore(lore);
            hand.setItemMeta(meta);
        }
        databaseManager.saveCardsDatabase(cardManager);
        getLogger().info("Card " + cardId + " owned by " + card.ownerName + " was destroyed by " + player.getName());
        return true;
    }

    private String getCurrencyItemName() {
        return currencyItem.name().toLowerCase().replace("_", " ");
    }

    private boolean commandRunSkinPreview(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        String cardId = getIdFromItem(hand);
        if (cardId == null) {
            player.sendMessage(getMessage("no-card-in-hand"));
            return true;
        }
        CardData card = cardManager.getCard(cardId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        ConfigurationSection skinNamesSection = config.getConfigurationSection("skin-names");
        if (skinNamesSection == null || skinNamesSection.getKeys(false).isEmpty()) {
            player.sendMessage(colorize("&cВ конфигурации не найдено скинов!"));
            return true;
        }
        List<String> allSkins = new ArrayList<>(skinNamesSection.getKeys(false));
        allSkins.sort((s1, s2) -> {
            try {
                return Integer.compare(Integer.parseInt(s1), Integer.parseInt(s2));
            } catch (NumberFormatException e) {
                return s1.compareTo(s2);
            }
        });
        final int[] currentIndex = {0};
        final int totalSkins = allSkins.size();
        ItemMeta savedmeta = hand.getItemMeta();
        for (int i = 0; i < totalSkins; i++) {
            final int skinNumber = i + 1;
            final String skinId = allSkins.get(i);
            scheduler.runTaskLater(this, () -> {
                if (player == null || !player.isOnline()) {
                    return;
                }
                ItemStack currentHand = player.getInventory().getItemInMainHand();
                String currentCardId = getIdFromItem(currentHand);
                if (currentCardId != null && currentCardId.equals(cardId)) {
                    try {
                        int skinIdInt = Integer.parseInt(skinId);
                        ItemMeta meta = currentHand.getItemMeta();
                        String skinName = config.getString("skin-names." + skinId);
                        meta.setCustomModelData(skinIdInt);
                        meta.setDisplayName(skinName.replace('&', '§'));
                        meta.setCustomModelData(Integer.valueOf(skinId));
                        currentHand.setItemMeta(meta);
                        String progressText = colorize("Промотка #" + skinId +
                                " " + skinNumber + "/" + totalSkins);
                        player.sendActionBar(progressText);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.0f + (skinNumber * 0.02f));
                    } catch (NumberFormatException e) {
                        player.sendMessage(colorize("&cОшибка в формате ID скина: " + skinId));
                    }
                } else {
                    return;
                }
                currentIndex[0]++;
                if (currentIndex[0] >= totalSkins) {
                    ItemStack finalHand = player.getInventory().getItemInMainHand();
                    String finalCardId = getIdFromItem(finalHand);
                    if (finalCardId != null && finalCardId.equals(cardId)) {
                        updateCardItem(finalHand, cardId);
                    }
                }
            }, i);
        }
        scheduler.runTaskLater(this, () -> {
            hand.setItemMeta(savedmeta);
            updateCardItem(hand, cardId);
        },totalSkins+1);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        try {
            if (args.length == 1) {
                return completeFirstArgument(sender, args[0]);
            } else if (args.length == 2) {
                return completeSecondArgument(sender, args);
            } else if (args.length == 3) {
                return completeThirdArgument(sender, args);
            } else if (args.length == 4) {
                return completeFourthArgument(sender, args);
            } else if (args.length == 5) {
                return completeFifthArgument(sender, args);
            }
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.WARNING, "Error during tab completion for command '/карта'", e);
        }
        return Collections.emptyList();
    }

    private List<String> completeFirstArgument(CommandSender sender, String input) {
        List<String> subCommands = new ArrayList<>(Arrays.asList("создать", "баланс", "пополнить", "снять", "перевести", "скин"));

        if (sender.hasPermission("creditcard.reload")) {
            subCommands.add("reload");
        }
        if (sender.hasPermission("creditcard.forge")) {
            subCommands.add("подделать");
        }
        if (sender.hasPermission("creditcard.fabricate")) {
            subCommands.add("сфабриковать");
        }

        return filterByInput(subCommands, input);
    }

    private List<String> completeSecondArgument(CommandSender sender, String[] args) {
        String subCommand = args[0].toLowerCase();
        String input = args[1].toLowerCase();

        if (subCommand.equals("перевести") || subCommand.equals("подделать") || subCommand.equals("сфабриковать")) {
            return Collections.singletonList("XXXX-XXXX");
        }

        if (subCommand.equals("пополнить") || subCommand.equals("снять")) {
            return completeBalanceOperation(sender, subCommand, input);
        }

        if (subCommand.equals("скин")) {
            List<String> skinfunctions = new ArrayList<>(Arrays.asList("поставить", "дисплей"));

            if (sender.hasPermission("creditcard.skinmanagement")) {
                skinfunctions.addAll(Arrays.asList("добавить", "забрать", "прогнать"));
            }

            return filterByInput(skinfunctions, input);
        }

        return Collections.emptyList();
    }

    private List<String> completeThirdArgument(CommandSender sender, String[] args) {
        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("перевести") && sender instanceof Player) {
            return completeTransferAmount((Player) sender);
        }

        if (subCommand.equals("скин") && sender instanceof Player) {
            String action = args[1].toLowerCase();

            if (action.equals("поставить")) {
                return filterByInput(getAvailableSkins((Player) sender), args[2].toLowerCase());
            }

            if (action.equals("дисплей")) {
                List<String> displayOptions = Arrays.asList("список", "инфо");
                return filterByInput(displayOptions, args[2].toLowerCase());
            }

            if (action.equals("добавить") && sender.hasPermission("creditcard.skinmanagement")) {
                return completeAvailableSkinsToAdd((Player) sender, args[2].toLowerCase());
            }

            if (action.equals("забрать") && sender.hasPermission("creditcard.skinmanagement")) {
                return filterByInput(getAvailableSkins((Player) sender), args[2].toLowerCase());
            }
        }

        return Collections.emptyList();
    }

    private List<String> completeFourthArgument(CommandSender sender, String[] args) {
        String subCommand = args[0].toLowerCase();

        if (subCommand.equals("скин") && args[1].toLowerCase().equals("дисплей") && sender instanceof Player) {
            String displayAction = args[2].toLowerCase();
            String input = args[3].toLowerCase();

            if (displayAction.equals("инфо")) {
                return filterByInput(getAvailableSkins((Player) sender), input);
            }
        }

        return completePlayerNames(args[3]);
    }

    private List<String> completeFifthArgument(CommandSender sender, String[] args) {
        return completePlayerNames(args[4]);
    }

    private List<String> completeBalanceOperation(CommandSender sender, String operation, String input) {
        if (!(sender instanceof Player)) {
            return Collections.emptyList();
        }

        Player player = (Player) sender;
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (cardManager.getAllCards() == null) {
            return Collections.emptyList();
        }

        String cardId = getIdFromItem(hand);
        if (cardId == null || !cardManager.exists(cardId)) {
            return Collections.emptyList();
        }

        CardData card = cardManager.getCard(cardId);
        if (card == null) {
            return Collections.emptyList();
        }

        List<String> suggestions = new ArrayList<>();

        if (operation.equals("пополнить")) {
            suggestions.add(String.valueOf(countPlayerCurrency(player)));
        } else if (operation.equals("снять")) {
            suggestions.add(String.valueOf(card.getBalance()));
            int maxWithdraw = countPlayerAir(player) * 64;
            if (card.getBalance() >= maxWithdraw) {
                suggestions.add(String.valueOf(maxWithdraw));
            }
        }

        return filterByInput(suggestions, input);
    }

    private List<String> completeTransferAmount(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (cardManager.getAllCards() == null) {
            return Collections.emptyList();
        }

        String cardId = getIdFromItem(hand);
        if (cardId != null && cardManager.exists(cardId)) {
            CardData card = cardManager.getCard(cardId);
            if (card != null) {
                return Collections.singletonList(String.valueOf(card.getBalance()));
            }
        }

        return Collections.emptyList();
    }

    private List<String> completeAvailableSkinsToAdd(Player player, String input) {
        List<String> playerSkins = getAvailableSkins(player);
        ConfigurationSection skinNamesSection = config.getConfigurationSection("skin-names");

        if (skinNamesSection != null) {
            List<String> availableToAdd = new ArrayList<>();
            for (String skinId : skinNamesSection.getKeys(false)) {
                if (!playerSkins.contains(skinId)) {
                    availableToAdd.add(skinId);
                }
            }
            return filterByInput(availableToAdd, input);
        }

        return Collections.emptyList();
    }

    private List<String> completePlayerNames(String input) {
        List<String> playerNames = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            playerNames.add(p.getName());
        }
        return filterByInput(playerNames, input);
    }

    private List<String> filterByInput(List<String> suggestions, String input) {
        List<String> filtered = new ArrayList<>();
        for (String suggestion : suggestions) {
            if (suggestion.toLowerCase().startsWith(input)) {
                filtered.add(suggestion);
            }
        }
        return filtered;
    }

    public NamespacedKey getCardIdKey() {
        return cardIdKey;
    }

    public CardManager getCardManager() {
        return cardManager;
    }

    public DatabaseManager getDatabaseManager() {
        return databaseManager;
    }
}