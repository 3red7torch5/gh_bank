package gh.creditcard;
import org.bukkit.*;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitScheduler;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

public class CreditCardPlugin extends JavaPlugin implements TabCompleter {
    private final NamespacedKey cardIdKey = new NamespacedKey(this, "card_id");
    private FileConfiguration config;
    private FileConfiguration cardsConfig;
    private File cardsFile;
    private FileConfiguration cooldownsConfig;
    private File cooldownsFile;
    private final Map<String, CardData> cards = new HashMap<>();
    private Material currencyItem;
    private Material cardMaterial;
    private String cardName;
    private List<String> cardLoreTemplate;
    private int cooldownHours;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<String> usedCardIds = new HashSet<>();
    private final Random random = new Random();
    private int cardsPerTick;
    private BukkitRunnable autoSaveTask;
    private BukkitScheduler scheduler;
    private FileConfiguration skinsConfig;
    private File skinsFile;
    private static class CardData {
        String ownerUuid;
        String ownerName;
        int balance;
        String created;
        String lastUsed;
        Color cardColor;
        CardData(Player owner, Color color) {
            this.ownerUuid = owner.getUniqueId().toString();
            this.ownerName = owner.getName();
            this.balance = 0;
            this.cardColor = color;
            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
            this.created = sdf.format(new Date());
            this.lastUsed = this.created;
            Bukkit.getLogger().info("New card created for " + ownerName + " (UUID: " + ownerUuid + ")");
        }
        CardData(String ownerUuid, String ownerName, int balance, String created, String lastUsed, int color) {
            this.ownerUuid = ownerUuid;
            this.ownerName = ownerName;
            this.balance = balance;
            this.created = created;
            this.lastUsed = lastUsed;
            this.cardColor = Color.fromRGB(color);
        }
        void updateLastUsed() {
            SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
            this.lastUsed = sdf.format(new Date());
        }
        int getBalance() {
            return balance;
        }
        void setBalance(int balance) {
            this.balance = balance;
        }
        void addBalance(int amount) {
            this.balance += amount;
        }
        boolean removeBalance(int amount) {
            if (balance >= amount) {
                balance -= amount;
                return true;
            }
            return false;
        }
        Color getCardColor() {
            return cardColor;
        }
        int getColorAsRGB() {
            return cardColor.asRGB();
        }
    }
    @Override
    public void onEnable() {
        getLogger().info("Credit Card Plugin Enabled!");
        saveDefaultConfig();
        config = getConfig();
        loadCurrencySettings();
        loadCardsDatabase();
        loadCooldownsDatabase();
        loadSkinsDatabase();
        setupAutoSave();
        getCommand("карта").setExecutor(this);
        getCommand("карта").setTabCompleter(this);
        getLogger().info("Plugin loaded with " + cards.size() + " cards in database");
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
                    !cards.containsKey(cardId)
                ) {
                    return;
                }
                ItemStack offhandItem = event.getPlayer().getInventory().getItemInOffHand();
                if (offhandItem != null && offhandItem.getType() == org.bukkit.Material.SHEARS && event.getPlayer().isSneaking()) {
                    if (config.getBoolean("destroy-balance-check") && cards.get(cardId).getBalance() != 0) {
                        return;
                    }
                    destroyCardInHand(event.getPlayer());
                    event.getPlayer().getLocation().getWorld().playSound(event.getPlayer().getLocation(), Sound.ENTITY_WITHER_SHOOT, SoundCategory.UI, 1.0f, 2f);
                    event.getPlayer().sendMessage(colorize("&7Карта &b" + String.valueOf(cardId) + " &c&lаннулированна"));
                } else {
                    updateCardItem(item, cardId);
                    event.getPlayer().playSound(event.getPlayer().getLocation(), Sound.BLOCK_DECORATED_POT_SHATTER, 1.0f, 2f);
                    event.getPlayer().sendActionBar(colorize("&7Баланс: &b" + String.valueOf( // Пробелы для читаемости
                            String.valueOf(cards.get(cardId).getBalance()).replaceAll("(\\d)(?=(\\d{3})+$)", "$1 ")) + " АЛМ"));
                }
                event.setCancelled(true);
            }
        }, this);
    }
    @Override
    public void onDisable() {
        if (autoSaveTask != null) {
            autoSaveTask.cancel();
        }
        saveCardsDatabase();
        saveCooldownsDatabase();
        saveSkinsDatabase();
        getLogger().info("Credit Card Plugin Disabled!");
    }
    private void loadCooldownsDatabase() {
        cooldownsFile = new File(getDataFolder(), config.getString("cooldowns.file", "cooldowns.yml"));
        if (!cooldownsFile.exists()) {
            cooldownsConfig = new YamlConfiguration();
            cooldownsConfig.set("total-cooldowns", 0);
            cooldownsConfig.set("cooldowns", new HashMap<String, Object>());
            saveCooldownsDatabase();
        } else {
            cooldownsConfig = YamlConfiguration.loadConfiguration(cooldownsFile);
            ConfigurationSection cooldownsSection = cooldownsConfig.getConfigurationSection("cooldowns");
            if (cooldownsSection != null) {
                for (String playerUuidStr : cooldownsSection.getKeys(false)) {
                    try {
                        UUID playerUuid = UUID.fromString(playerUuidStr);
                        long cooldownEnd = cooldownsSection.getLong(playerUuidStr);
                        cooldowns.put(playerUuid, cooldownEnd);
                    } catch (IllegalArgumentException e) {
                        getLogger().warning("Неверный UUID в базе данных кулдаунов: " + playerUuidStr);
                    }
                }
            }
        }
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
    private void loadCardsDatabase() {
        cardsFile = new File(getDataFolder(), config.getString("database.file", "cards.yml"));
        if (!cardsFile.exists()) {
            cardsConfig = new YamlConfiguration();
            cardsConfig.set("total-cards", 0);
            cardsConfig.set("cards", new HashMap<String, Object>());
            saveCardsDatabase();
        } else {
            cardsConfig = YamlConfiguration.loadConfiguration(cardsFile);
        }
        ConfigurationSection cardsSection = cardsConfig.getConfigurationSection("cards");
        if (cardsSection != null) {
            for (String cardId : cardsSection.getKeys(false)) {
                String ownerUuid = cardsSection.getString(cardId + ".owner");
                String ownerName = cardsSection.getString(cardId + ".owner-name", "неизвестен");
                int balance = cardsSection.getInt(cardId + ".balance", 0);
                String created = cardsSection.getString(cardId + ".created", "");
                String lastUsed = cardsSection.getString(cardId + ".last-used", "");
                int color = cardsSection.getInt(cardId + ".color", Color.fromRGB(255, 255, 255).asRGB());
                cards.put(cardId, new CardData(ownerUuid, ownerName, balance, created, lastUsed, color));
                usedCardIds.add(cardId);
            }
        }
        getLogger().info("Loaded " + cards.size() + " cards from database");
    }
    private void saveCardsDatabase() {
        if (cardsConfig == null) {
            cardsConfig = new YamlConfiguration();
        }
        cardsConfig.set("cards", null);
        for (Map.Entry<String, CardData> entry : cards.entrySet()) {
            String path = "cards." + entry.getKey();
            CardData card = entry.getValue();
            cardsConfig.set(path + ".owner", card.ownerUuid);
            cardsConfig.set(path + ".owner-name", card.ownerName);
            cardsConfig.set(path + ".balance", card.balance);
            cardsConfig.set(path + ".created", card.created);
            cardsConfig.set(path + ".last-used", card.lastUsed);
            cardsConfig.set(path + ".color", card.getColorAsRGB());
        }
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        cardsConfig.set("last-save", sdf.format(new Date()));
        cardsConfig.set("total-cards", cards.size());
        try {
            cardsConfig.save(cardsFile);
            getLogger().info("Saved " + cards.size() + " cards to database");
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save cards database!", e);
        }
    }
    private void saveCooldownsDatabase() {
        if (cooldownsConfig == null) {
            cooldownsConfig = new YamlConfiguration();
        }
        if (cooldownsFile == null) {
            cooldownsFile = new File(getDataFolder(), "cooldowns.yml");
        }
        cooldownsConfig.set("cooldowns", null);
        for (Map.Entry<UUID, Long> entry : cooldowns.entrySet()) {
            String path = "cooldowns." + entry.getKey().toString();
            cooldownsConfig.set(path, entry.getValue());
        }
        SimpleDateFormat sdf = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss");
        cooldownsConfig.set("version", 1);
        cooldownsConfig.set("last-save", sdf.format(new Date()));
        cooldownsConfig.set("total-cooldowns", cooldowns.size());
        try {
            cooldownsConfig.save(cooldownsFile);
            getLogger().info("Saved " + cooldowns.size() + " card creation cooldowns to database");
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save card creation cooldowns database!", e);
        }
    }
    private void loadSkinsDatabase() {
        skinsFile = new File(getDataFolder(), "skins.yml");
        if (!skinsFile.exists()) {
            skinsConfig = new YamlConfiguration();
            skinsConfig.set("skins", new HashMap<String, Object>());
            saveSkinsDatabase();
        } else {
            skinsConfig = YamlConfiguration.loadConfiguration(skinsFile);
        }
        getLogger().info("Loaded skins database");
    }
    private void saveSkinsDatabase() {
        try {
            skinsConfig.save(skinsFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to save skins database!", e);
        }
    }
    private void setupAutoSave() {
        int autoSaveInterval = config.getInt("database.auto-save", 300);
        if (autoSaveInterval > 0) {
            autoSaveTask = new BukkitRunnable() {
                @Override
                public void run() {
                    saveCardsDatabase();
                    getLogger().info("Saved cards database");
                    saveSkinsDatabase();
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
    private String colorize(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }
    private Color generateRandomColor() {
        int r = random.nextInt(256);
        int g = random.nextInt(256);
        int b = random.nextInt(256);
        return Color.fromRGB(r, g, b);
    }
    private String generateUniqueCardId() {
        String id;
        int attempts = 0;
        do {
            if (attempts++ > 100) {
                getLogger().warning("Failed to generate unique ID after 100 attempts!");
                id = String.format("%012d", System.currentTimeMillis() % 10000000000000L);
                if (!usedCardIds.contains(id)) {
                    usedCardIds.add(id);
                    return id;
                }
            }
            long randomId = Math.abs(random.nextLong()) % 1000000000000L;
            id = String.format("%08d", randomId);
            if (id.length() >= 8) {
                id = id.substring(0, 4) + "-" +
                        id.substring(4, 8);
            }
        } while (usedCardIds.contains(id));
        usedCardIds.add(id);
        return id;
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
        if (skinsConfig.contains(path + ".skins")) {
            return skinsConfig.getStringList(path + ".skins");
        }
        return new ArrayList<>();
    }
    public boolean addSkin(Player player, String skinID) {
        String playerPath = "skins." + player.getUniqueId().toString();
        if (!skinsConfig.contains(playerPath)) {
            skinsConfig.set(playerPath + ".name", player.getName());
            skinsConfig.set(playerPath + ".skins", new ArrayList<String>());
        }
        List<String> currentSkins = skinsConfig.getStringList(playerPath + ".skins");
        if (currentSkins.contains(skinID)) {
            return false;
        }
        currentSkins.add(skinID);
        skinsConfig.set(playerPath + ".skins", currentSkins);
        if (!skinsConfig.getString(playerPath + ".name", "").equals(player.getName())) { // Вдруг имя поменяется йоу
            skinsConfig.set(playerPath + ".name", player.getName());
        }
        saveSkinsDatabase();
        return true;
    }
    public boolean removeSkin(Player player, String skinID) {
        String playerPath = "skins." + player.getUniqueId().toString();
        if (!skinsConfig.contains(playerPath)) {
            return false;
        }
        List<String> currentSkins = skinsConfig.getStringList(playerPath + ".skins");
        if (!currentSkins.contains(skinID)) {
            return false;
        }
        currentSkins.remove(skinID);
        skinsConfig.set(playerPath + ".skins", currentSkins);
        if (currentSkins.isEmpty()) {
            skinsConfig.set(playerPath, null);
        }
        saveSkinsDatabase();
        return true;
    }
    private void setCooldown(Player player) {
        long cooldownEnd = System.currentTimeMillis() + (cooldownHours * 60 * 60 * 1000L);
        cooldowns.put(player.getUniqueId(), cooldownEnd);
        saveCooldownsDatabase();
    }
    private String createCard(Player player) {
        String cardId = generateUniqueCardId();
        Color randomColor = generateRandomColor();
        cards.put(cardId, new CardData(player, randomColor));
        setCooldown(player);
        saveCardsDatabase();
        getLogger().info("Created new card " + cardId + " for " + player.getName());
        return cardId;
    }
    private ItemStack makeCard(String cardId) {
        CardData cardData = cards.get(cardId);
        if (cardData == null) {
            getLogger().warning("Tried to create card item for non-existent card: " + cardId);
            return null;
        }
        ItemStack card = new ItemStack(cardMaterial);
        LeatherArmorMeta meta = (LeatherArmorMeta) card.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(cardName);
            meta.setColor(cardData.getCardColor());
            meta.addItemFlags(
                    ItemFlag.HIDE_ATTRIBUTES,
                    ItemFlag.HIDE_ENCHANTS,
                    ItemFlag.HIDE_UNBREAKABLE,
                    ItemFlag.HIDE_DESTROYS,
                    ItemFlag.HIDE_PLACED_ON,
                    ItemFlag.HIDE_DYE
            );
            List<String> lore = new ArrayList<>();
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("card_id", cardId);
            placeholders.put("balance", String.valueOf(cardData.getBalance()));
            placeholders.put("currency_item", currencyItem.name().toLowerCase().replace("_", " "));
            placeholders.put("owner_name", cardData.ownerName);
            for (String line : cardLoreTemplate) {
                String processedLine = line;
                for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                    processedLine = processedLine.replace("%" + entry.getKey() + "%", entry.getValue());
                }
                lore.add(colorize(processedLine));
            }
            meta.setLore(lore);
            PersistentDataContainer pdc = meta.getPersistentDataContainer();
            pdc.set(cardIdKey, PersistentDataType.STRING, cardId);
            card.setItemMeta(meta);
        }
        return card;
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
        if (hasCooldown(player)) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("cooldown", getCooldownRemaining(player));
            player.sendMessage(getMessage("cooldown-active", placeholders));
            return true;
        }
        int amount = 1;
        if (args.length > 1) {
            try {
                if (player.hasPermission("creditcard.masscreate")){
                    amount = Integer.parseInt(args[1]);
                }
                if (amount <= 0) {
                    player.sendMessage(getMessage("invalid-amount"));
                    return true;
                }
            } catch (NumberFormatException e) {
                player.sendMessage(getMessage("invalid-amount"));
                return true;
            }
        }
        final int finalAmount = amount;
        final boolean[] inventoryFull = {false};
        final int[] currentIndex = {0};
        for (int i = 0; i < finalAmount; i++) {
            final int cardNumber = i + 1;
            scheduler.runTaskLater(this, () -> {
                if (player == null || !player.isOnline()) {
                    return;
                }
                String cardId = createCard(player);
                ItemStack card = makeCard(cardId);
                if (card != null) {
                    Map<Integer, ItemStack> leftover = player.getInventory().addItem(card);
                    if (!leftover.isEmpty()) {
                        player.getWorld().dropItem(player.getLocation(), card);
                        inventoryFull[0] = true;
                    }
                }
                currentIndex[0]++;
                int progressPercent = (cardNumber * 100 / finalAmount);
                String progressText = colorize("&7Создание карт: &f" + cardNumber + "&7/&f" + finalAmount + " &7(&a" + progressPercent + "%&7)");
                player.sendActionBar(progressText);
                if (currentIndex[0] >= finalAmount) {
                    setCooldown(player);
                    saveCardsDatabase();
                    Map<String, String> placeholders = new HashMap<>();
                    placeholders.put("card_count", String.valueOf(finalAmount));
                    player.sendMessage(getMessage("cards-created-multiple", placeholders));
                    if (inventoryFull[0]) {
                        player.sendMessage(getMessage("inventory-full"));
                    }
                    player.sendActionBar("");
                }
            }, i);
        }
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("card_count", String.valueOf(amount));
        player.sendMessage(colorize("&aНачато создание " + amount + " карт. Подождите..."));
        player.sendActionBar(colorize("&7Создание карт: &f0&7/&f" + amount + " &7(&a0%&7)"));
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
        if (usedCardIds.contains(customId) || cards.containsKey(customId)) {
            Map<String, String> placeholders = new HashMap<>();
            player.sendMessage(getMessage("already-exists"));
            return true;
        }
        Color randomColor = generateRandomColor();
        cards.put(customId, new CardData(player, randomColor));
        usedCardIds.add(customId);
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
        saveCardsDatabase();
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
        CardData senderCard = cards.get(senderCardId);
        if (senderCard == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        CardData targetCard = cards.get(targetCardId);
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
        saveCardsDatabase();
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
        CardData card = cards.get(cardId);
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
        CardData card = cards.get(cardId);
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
        saveCardsDatabase();
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
        CardData card = cards.get(cardId);
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
        saveCardsDatabase();
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
        ItemStack hand = player.getInventory().getItemInMainHand();
        String cardId = getIdFromItem(hand);
        if (cardId == null) {
            player.sendMessage(getMessage("no-card-in-hand"));
            return true;
        }
        CardData card = cards.get(cardId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return true;
        }
        if (args.length > 3) {
            player.sendMessage(getMessage("usage-skins"));
            return true;
        }
        if (args.length == 3) {
            int id;
            try {
                id = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage(getMessage("invalid-skinid"));
                return true;
            }
            if (args[1].equals("добавить")) {
                addSkin(player,String.valueOf(id));
            }
            if (args[1].equals("забрать")) {
                removeSkin(player,String.valueOf(id));
            }
            if (args[1].equals("поставить")) {
                if (getAvailableSkins(player).contains(String.valueOf(id))) {
                    ItemMeta meta = hand.getItemMeta();
                    meta.setCustomModelData(id);
                    hand.setItemMeta(meta);
                } else {
                    player.sendMessage(getMessage("does-not-belong"));
                    return true;
                }
            }
        } else {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("skins", String.valueOf(getAvailableSkins(player)));
            player.sendMessage(getMessage("skins-show", placeholders));
        }
        return true;
    }
    private void updateCardItem(ItemStack cardItem, String cardId) {
        if (cardItem == null || !cardItem.hasItemMeta()) return;
        CardData card = cards.get(cardId);
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
        CardData card = cards.get(cardId);
        if (card == null) {
            player.sendMessage(getMessage("invalid-card"));
            return false;
        }
        cards.remove(cardId);
        usedCardIds.remove(cardId);
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
        saveCardsDatabase();
        getLogger().info("Card " + cardId + " owned by " + card.ownerName + " was destroyed by " + player.getName());
        return true;
    }
    private String getCurrencyItemName() {
        return currencyItem.name().toLowerCase().replace("_", " ");
    }
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> completions = new ArrayList<>();
        try {
            if (args.length == 1) {
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
                String input = args[0].toLowerCase();
                for (String subCommand : subCommands) {
                    if (subCommand.toLowerCase().startsWith(input)) {
                        completions.add(subCommand);
                    }
                }
            } else if (args.length == 2) {
                String subCommand = args[0].toLowerCase();
                if (subCommand.equals("перевести") || subCommand.equals("подделать") || subCommand.equals("сфабриковать")) {
                    completions.add("XXXX-XXXX");
                } else if (subCommand.equals("пополнить") || subCommand.equals("снять")) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        ItemStack hand = player.getInventory().getItemInMainHand();
                        if (cards == null) {
                            return completions;
                        }
                        String cardId = getIdFromItem(hand);
                        if (cardId != null && cards.containsKey(cardId)) {
                            CardData card = cards.get(cardId);
                            if (card != null) {
                                if (subCommand.equals("пополнить")) {
                                    completions.add(String.valueOf(countPlayerCurrency(player)));
                                } else if (subCommand.equals("снять")) {
                                    completions.add(String.valueOf(card.getBalance()));
                                    if (card.getBalance() >= countPlayerAir(player)*64) {
                                        completions.add(String.valueOf(countPlayerAir(player)*64));
                                    }
                                }
                            }
                        }
                    }
                } else if (subCommand.equals("скин")) {
                    List<String> skinfunctions = new ArrayList<>(Arrays.asList("поставить", "добавить", "забрать"));
                    String input = args[1].toLowerCase();
                    for (String completion : skinfunctions) {
                        if (completion.toLowerCase().startsWith(input)) {
                            completions.add(completion);
                        }
                    }
                }
            } else if (args.length == 3) {
                String subCommand = args[0].toLowerCase();
                if (subCommand.equals("перевести")) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        ItemStack hand = player.getInventory().getItemInMainHand();
                        if (cards == null) {
                            return completions;
                        }
                        String cardId = getIdFromItem(hand);
                        if (cardId != null && cards.containsKey(cardId)) {
                            CardData card = cards.get(cardId);
                            if (card != null) {
                                completions.add(String.valueOf(card.getBalance()));
                            }
                        }
                    }
                } else if (subCommand.equals("скин")) {
                    completions.add("0");
                }
            }
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error during tab completion for command '/карта'", e);
            return Collections.emptyList();
        }
        return completions;
    }
}